package com.needlegroove.player;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Library {
    static final class Track {
        String id, title, artist, album, genre, folder, uri, fingerprint, filename;
        volatile String lyrics = "";
        long duration, size, added;
        int plays;
        boolean favorite;
        byte[] art;
        MediaItem mediaItem() {
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .setTitle(title).setArtist(artist).setAlbumTitle(album);
            if (art != null) metadata.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
            return new MediaItem.Builder().setMediaId(id).setUri(uri)
                    .setMediaMetadata(metadata.build()).build();
        }
    }

    private final Context context;
    final SharedPreferences prefs;
    volatile List<Track> tracks = List.of();
    volatile Map<String, List<String>> playlists = Map.of();
    private JSONObject edits;
    private JSONArray imports;
    private Set<String> favorites;
    private JSONObject plays;

    Library(Context context) throws JSONException {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        edits = new JSONObject(prefs.getString("edits", "{}"));
        plays = new JSONObject(prefs.getString("plays", "{}"));
        imports = new JSONArray(prefs.getString("imports", "[]"));
        favorites = new HashSet<>(prefs.getStringSet("favorites", new HashSet<>()));
        JSONObject stored = new JSONObject(prefs.getString("playlists", "{}"));
        Map<String, List<String>> loaded = new LinkedHashMap<>();
        for (String key : iterable(stored)) {
            JSONArray ids = stored.getJSONArray(key);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < ids.length(); i++) list.add(ids.getString(i));
            loaded.put(key, list);
        }
        playlists = loaded;
    }

    private static List<String> iterable(JSONObject object) {
        List<String> keys = new ArrayList<>();
        object.keys().forEachRemaining(keys::add);
        return keys;
    }

    synchronized void scan(boolean permission) throws Exception {
        List<Track> found = new ArrayList<>();
        long minDuration = prefs.getInt("minDuration", 10) * 1000L;
        long minSize = Math.round(prefs.getFloat("minSize", 0) * 1_000_000);
        if (permission) {
            Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String folderColumn = Build.VERSION.SDK_INT >= 29 ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA;
            String[] columns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED, folderColumn, MediaStore.Audio.Media.DISPLAY_NAME};
            try (Cursor cursor = context.getContentResolver().query(collection, columns,
                    MediaStore.Audio.Media.DURATION + " >= ? AND " + MediaStore.Audio.Media.SIZE + " >= ?",
                    new String[]{String.valueOf(minDuration), String.valueOf(minSize)},
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
                if (cursor == null) throw new IOException("Device music scan returned no result. Try again.");
                while (cursor.moveToNext()) {
                    Track track = new Track();
                    long id = cursor.getLong(0);
                    track.id = "media:" + id;
                    track.uri = ContentUris.withAppendedId(collection, id).toString();
                    track.title = value(cursor.getString(1), "Untitled");
                    track.artist = value(cursor.getString(2), "Unknown artist");
                    track.album = value(cursor.getString(3), "Unknown album");
                    track.duration = cursor.getLong(4);
                    track.size = cursor.getLong(5);
                    track.added = cursor.getLong(6) * 1000L;
                    String path = value(cursor.getString(7), "Music");
                    track.folder = Build.VERSION.SDK_INT >= 29 ? path : value(new File(path).getParent(), "Music");
                    track.genre = "Unknown genre";
                    track.filename = cursor.getString(8);
                    try {
                        Track metadata = readMetadata(Uri.parse(track.uri), track.title);
                        track.genre = metadata.genre;
                    } catch (IOException error) {
                        android.util.Log.w("NeedleGroove", "Extra metadata unavailable for " + track.title, error);
                    }
                    found.add(track);
                }
            }
        }
        for (int i = 0; i < imports.length(); i++) {
            JSONObject entry = imports.getJSONObject(i);
            File file = new File(context.getFilesDir(), entry.getString("file"));
            if (!file.isFile()) throw new IOException("An imported file is missing: " + entry.getString("title"));
            Track track = readMetadata(Uri.fromFile(file), entry.getString("title"));
            track.filename = entry.getString("title");
            track.id = entry.getString("id");
            track.added = entry.getLong("added");
            track.size = file.length();
            track.folder = "Imported music";
            track.art = null;
            if (QueueRules.passesFilter(track.duration, track.size, minDuration, minSize)) found.add(track);
        }
        for (Track track : found) {
            track.fingerprint = QueueRules.fingerprint(track.title, track.artist, track.duration, track.size);
            applySaved(track);
        }
        tracks = List.copyOf(found);
    }

    private void applySaved(Track track) {
        JSONObject edit = edits.optJSONObject(track.id);
        if (edit != null) {
            track.title = edit.optString("title", track.title);
            track.artist = edit.optString("artist", track.artist);
            track.album = edit.optString("album", track.album);
            track.genre = edit.optString("genre", track.genre);
            track.lyrics = edit.optString("lyrics", "");
        }
        track.favorite = favorites.contains(track.id);
        track.plays = plays.optInt(track.id);
    }

    Track readMetadata(Uri uri, String fallback) throws IOException {
        return readMetadata(uri, fallback, false);
    }

    private Track readMetadata(Uri uri, String fallback, boolean artwork) throws IOException {
        Track track = new Track();
        track.uri = uri.toString();
        track.title = fallback;
        track.artist = "Unknown artist";
        track.album = "Unknown album";
        track.genre = "Unknown genre";
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(context, uri);
            track.title = value(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), fallback);
            track.artist = value(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), track.artist);
            track.album = value(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), track.album);
            track.genre = value(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE), track.genre);
            String duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            track.duration = duration == null ? 0 : Long.parseLong(duration);
            if (artwork) track.art = reader.getEmbeddedPicture();
        } catch (RuntimeException error) {
            throw new IOException("Could not read audio metadata for " + fallback, error);
        } finally {
            reader.release();
        }
        return track;
    }

    synchronized Track importFile(Uri source) throws Exception {
        String name = "Imported audio";
        try (Cursor cursor = context.getContentResolver().query(source, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = value(cursor.getString(0), name);
        }
        String id = "import:" + java.util.UUID.randomUUID();
        File directory = new File(context.getFilesDir(), "music");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private music storage.");
        File target = new File(directory, id.substring(7));
        boolean committed = false;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(source);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) throw new IOException("Cannot open " + name);
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            Track track = readMetadata(Uri.fromFile(target), name.replaceFirst("\\.[^.]+$", ""));
            track.size = target.length();
            if (!QueueRules.passesFilter(track.duration, track.size,
                    prefs.getInt("minDuration", 10) * 1000L, Math.round(prefs.getFloat("minSize", 0) * 1_000_000))) {
                throw new IOException(name + " was skipped by your duration or size filter.");
            }
            track.id = id;
            track.filename = name;
            track.added = System.currentTimeMillis();
            track.folder = "Imported music";
            track.fingerprint = QueueRules.fingerprint(track.title, track.artist, track.duration, track.size);
            JSONObject entry = new JSONObject().put("id", id).put("title", name)
                    .put("file", "music/" + target.getName()).put("added", track.added);
            JSONArray updated = new JSONArray(imports.toString());
            updated.put(entry);
            if (!prefs.edit().putString("imports", updated.toString()).commit()) throw new IOException("Could not save the import.");
            imports = updated;
            List<Track> updatedTracks = new ArrayList<>(tracks);
            updatedTracks.add(track);
            tracks = List.copyOf(updatedTracks);
            committed = true;
            return track;
        } finally {
            if (!committed && target.exists() && !target.delete()) android.util.Log.w("NeedleGroove", "Cannot clean failed import " + target);
        }
    }

    void loadArtwork(Track track) throws IOException {
        if (track.art == null) {
            String cover = prefs.getString("cover:" + track.id, "");
            if (!cover.isEmpty()) {
                File file = new File(context.getFilesDir(), cover);
                try (InputStream in = new java.io.FileInputStream(file)) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    track.art = out.toByteArray();
                }
            } else {
                Track metadata = readMetadata(Uri.parse(track.uri), track.title, true);
                track.art = metadata.art;
                if ("Unknown genre".equals(track.genre)) track.genre = metadata.genre;
            }
        }
    }

    void saveCover(Track track, Uri source) throws IOException {
        android.graphics.Bitmap bitmap;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("Cannot open the selected image.");
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(input, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("The selected file is not a supported image.");
            int sample = 1;
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2;
            bounds.inJustDecodeBounds = false; bounds.inSampleSize = sample;
            try (InputStream pixels = context.getContentResolver().openInputStream(source)) {
                bitmap = android.graphics.BitmapFactory.decodeStream(pixels, null, bounds);
            }
        }
        if (bitmap == null) throw new IOException("Could not decode the artwork.");
        File directory = new File(context.getFilesDir(), "covers");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create artwork storage.");
        File target = new File(directory, java.util.UUID.randomUUID() + ".jpg");
        try (FileOutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)) throw new IOException("Cannot encode artwork.");
        } finally { bitmap.recycle(); }
        String oldCover = prefs.getString("cover:" + track.id, "");
        if (!prefs.edit().putString("cover:" + track.id, "covers/" + target.getName()).commit()) {
            if (!target.delete()) android.util.Log.w("NeedleGroove", "Cannot clean unsaved cover.");
            throw new IOException("Could not save artwork.");
        }
        if (!oldCover.isEmpty() && !new File(context.getFilesDir(), oldCover).delete()) android.util.Log.w("NeedleGroove", "Cannot remove replaced cover.");
        track.art = null;
        loadArtwork(track);
    }

    void favorite(Track track) throws IOException {
        Set<String> next = new HashSet<>(favorites);
        if (!next.add(track.id)) next.remove(track.id);
        if (!prefs.edit().putStringSet("favorites", next).commit()) throw new IOException("Could not save favorites.");
        favorites = next;
        track.favorite = favorites.contains(track.id);
    }

    synchronized void saveEdit(Track track, String title, String artist, String album, String genre, String lyrics) throws Exception {
        JSONObject next = new JSONObject(edits.toString());
        next.put(track.id, new JSONObject().put("title", title).put("artist", artist)
                .put("album", album).put("genre", genre).put("lyrics", lyrics));
        if (!prefs.edit().putString("edits", next.toString()).commit()) throw new IOException("Could not save song details.");
        edits = next;
        applySaved(track);
        for (Track item : tracks) if (item != track && item.id.equals(track.id)) applySaved(item);
    }

    synchronized boolean saveLyricsIfUnchanged(Track track, String expected, String lyrics) throws Exception {
        return updateLyrics(track, expected, lyrics);
    }

    synchronized void saveLyrics(Track track, String lyrics) throws Exception {
        updateLyrics(track, null, lyrics);
    }

    private boolean updateLyrics(Track track, String expected, String lyrics) throws Exception {
        JSONObject existing = edits.optJSONObject(track.id);
        String saved = existing == null ? "" : existing.optString("lyrics", "");
        if (expected != null && !saved.equals(expected)) return false;
        JSONObject next = new JSONObject(edits.toString());
        JSONObject edit = existing == null ? new JSONObject() : new JSONObject(existing.toString());
        edit.put("lyrics", lyrics);
        next.put(track.id, edit);
        if (!prefs.edit().putString("edits", next.toString()).commit()) throw new IOException("Could not save lyrics.");
        edits = next;
        applySaved(track);
        for (Track item : tracks) if (item != track && item.id.equals(track.id)) applySaved(item);
        return true;
    }

    void savePlaylists(Map<String, List<String>> next) throws Exception {
        JSONObject object = new JSONObject();
        for (Map.Entry<String, List<String>> entry : next.entrySet()) object.put(entry.getKey(), new JSONArray(entry.getValue()));
        if (!prefs.edit().putString("playlists", object.toString()).commit()) throw new IOException("Could not save playlists.");
        Map<String, List<String>> saved = new LinkedHashMap<>();
        next.forEach((name, ids) -> saved.put(name, List.copyOf(ids)));
        playlists = saved;
    }

    List<Track> view(String category, String group, String search) {
        List<Track> result = new ArrayList<>();
        JSONObject history;
        try { history = new JSONObject(prefs.getString("plays", "{}")); }
        catch (JSONException error) { throw new IllegalStateException("Listening history is invalid.", error); }
        {
            for (Track track : tracks) {
                track.plays = history.optInt(track.id);
                String key = switch (category) {
                    case "Albums" -> track.album;
                    case "Artists" -> track.artist;
                    case "Genres" -> track.genre;
                    case "Folders" -> track.folder;
                    default -> "";
                };
                boolean matches = group == null || !category.equals("Playlists") && group.equals(key);
                if (category.equals("Playlists") && group != null) {
                    matches = switch (group) {
                        case "Favorites" -> track.favorite;
                        case "Recently added", "Most played" -> true;
                        default -> playlists.getOrDefault(group, List.of()).contains(track.id);
                    };
                }
                if (matches && QueueRules.matchesSearch(track.title, track.artist, track.album, search)) result.add(track);
            }
        }
        if ("Recently added".equals(group)) result.sort(Comparator.comparingLong((Track t) -> t.added).reversed());
        else if ("Most played".equals(group)) {
            result.removeIf(t -> t.plays == 0);
            result.sort(Comparator.comparingInt((Track t) -> t.plays).reversed());
        } else if (category.equals("Playlists") && group != null && playlists.containsKey(group)) {
            List<String> ids = playlists.get(group);
            result.sort(Comparator.comparingInt(t -> ids.indexOf(t.id)));
        } else result.sort(Comparator.comparing(t -> t.title.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    synchronized String backup() throws JSONException {
        JSONArray records = new JSONArray();
        for (Track track : tracks) records.put(new JSONObject().put("id", track.id).put("fingerprint", track.fingerprint));
        return new JSONObject().put("format", "needle-groove").put("version", 1)
                .put("records", records)
                .put("edits", edits).put("playlists", new JSONObject(prefs.getString("playlists", "{}")))
                .put("favorites", new JSONArray(favorites)).put("plays", new JSONObject(prefs.getString("plays", "{}")))
                .put("settings", new JSONObject().put("theme", prefs.getString("theme", "Spotify"))
                        .put("minDuration", prefs.getInt("minDuration", 10)).put("minSize", prefs.getFloat("minSize", 0)))
                .toString(2);
    }

    synchronized int restore(String content) throws Exception {
        JSONObject data = new JSONObject(content);
        if (!"needle-groove".equals(data.optString("format")) || data.optInt("version") != 1) throw new IOException("Not a supported Needle & Groove backup.");
        JSONObject sourceEdits = data.getJSONObject("edits"), sourcePlaylists = data.getJSONObject("playlists"), sourcePlays = data.getJSONObject("plays"), settings = data.getJSONObject("settings");
        JSONArray sourceFavorites = data.getJSONArray("favorites");
        Map<String, String> mapping = new LinkedHashMap<>();
        JSONArray records = data.getJSONArray("records");
        int missing = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            String oldId = record.getString("id"), fingerprint = record.getString("fingerprint");
            List<Track> matches = new ArrayList<>();
            for (Track track : tracks) if (fingerprint.equals(track.fingerprint)) matches.add(track);
            Track same = matches.stream().filter(track -> track.id.equals(oldId)).findFirst().orElse(null);
            if (same != null) mapping.put(oldId, same.id);
            else if (matches.size() == 1) mapping.put(oldId, matches.get(0).id);
            else missing++;
        }
        if (records.length() > 0 && mapping.isEmpty()) throw new IOException("No matching audio found. Scan or import the original files before restoring this backup.");
        JSONObject nextEdits = new JSONObject(), nextPlaylists = new JSONObject(), nextPlays = new JSONObject();
        JSONArray nextFavorites = new JSONArray();
        for (String id : iterable(sourceEdits)) if (mapping.containsKey(id)) nextEdits.put(mapping.get(id), sourceEdits.getJSONObject(id));
        for (String id : iterable(sourcePlays)) {
            int count = sourcePlays.getInt(id);
            if (count < 0) throw new IOException("Backup listening counts are invalid.");
            if (mapping.containsKey(id)) nextPlays.put(mapping.get(id), count);
        }
        for (int i = 0; i < sourceFavorites.length(); i++) if (mapping.containsKey(sourceFavorites.getString(i))) nextFavorites.put(mapping.get(sourceFavorites.getString(i)));
        for (String name : iterable(sourcePlaylists)) {
            JSONArray ids = sourcePlaylists.getJSONArray(name), mapped = new JSONArray();
            for (int i = 0; i < ids.length(); i++) if (mapping.containsKey(ids.getString(i))) mapped.put(mapping.get(ids.getString(i)));
            nextPlaylists.put(name, mapped);
        }
        Set<String> favoriteIds = new HashSet<>();
        for (int i = 0; i < nextFavorites.length(); i++) favoriteIds.add(nextFavorites.getString(i));
        for (String name : iterable(nextPlaylists)) {
            validatePlaylistName(name);
            JSONArray list = nextPlaylists.getJSONArray(name);
            for (int i = 0; i < list.length(); i++) list.getString(i);
        }
        int duration = settings.getInt("minDuration");
        double size = settings.getDouble("minSize");
        String theme = settings.getString("theme");
        if (duration < 0 || duration > 3600 || !Double.isFinite(size) || size < 0 || size > 100
                || !List.of("Spotify", "Warm vinyl", "Midnight", "True black", "Daylight").contains(theme)) throw new IOException("Backup settings are invalid.");
        if (!prefs.edit().putString("edits", nextEdits.toString()).putString("playlists", nextPlaylists.toString())
                .putStringSet("favorites", favoriteIds).putString("plays", nextPlays.toString())
                .putString("theme", theme).putInt("minDuration", duration).putFloat("minSize", (float) size).commit()) throw new IOException("Could not restore backup.");
        edits = nextEdits;
        plays = nextPlays;
        favorites = favoriteIds;
        Map<String, List<String>> restored = new LinkedHashMap<>();
        for (String name : iterable(nextPlaylists)) {
            List<String> ids = new ArrayList<>();
            JSONArray list = nextPlaylists.getJSONArray(name);
            for (int i = 0; i < list.length(); i++) ids.add(list.getString(i));
            restored.put(name, ids);
        }
        playlists = restored;
        for (Track track : tracks) applySaved(track);
        return missing;
    }

    static void validatePlaylistName(String name) throws IOException {
        if (name.isBlank() || name.length() > 80 || List.of("Favorites", "Recently added", "Most played").contains(name)) throw new IOException("Use a unique playlist name, up to 80 characters. Smart playlist names are reserved.");
    }

    static String value(String text, String fallback) {
        return text == null || text.isBlank() || text.equals("<unknown>") ? fallback : text;
    }
}
