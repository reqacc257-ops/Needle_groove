package com.needlegroove.player;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagOptionSingleton;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

final class Lyrics {
    private static final long MAX_AUDIO_BYTES = 256L * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 1_000_000;
    private final Context context;

    Lyrics(Context context) {
        this.context = context.getApplicationContext();
        TagOptionSingleton.getInstance().setAndroid(true);
    }

    static String basename(String name) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    static boolean sidecar(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".lrc");
    }

    static Uri pairedSidecar(String name, Map<String, List<Uri>> sidecars, Map<String, Integer> audioCounts) {
        String key = basename(name);
        List<Uri> matches = sidecars.getOrDefault(key, List.of());
        return matches.size() == 1 && audioCounts.getOrDefault(key, 0) == 1 ? matches.get(0) : null;
    }

    String name(Uri uri) throws IOException {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        }
        throw new IOException("Could not read the selected file's name.");
    }

    List<Uri> folderFiles(Uri tree) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        List<Uri> files = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot read the selected folder.");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0), name = cursor.getString(1), type = cursor.getString(2);
                if (id == null || name == null) throw new IOException("A selected folder item has no name or document ID.");
                if (folderItem(name, type)) files.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        } catch (IllegalArgumentException | SecurityException error) {
            throw new IOException("Cannot read the selected folder: " + error.getMessage(), error);
        }
        return files;
    }

    static boolean folderItem(String name, String type) {
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) return false;
        if (sidecar(name) || type != null && type.startsWith("audio/")) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        return List.of(".mp3", ".flac", ".ogg", ".oga", ".m4a", ".mp4", ".wav", ".aac", ".opus")
                .stream().anyMatch(lower::endsWith);
    }

    String text(Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot open the selected lyrics file.");
            return readText(input);
        }
    }

    String embedded(Library.Track track) throws IOException {
        String name = track.filename == null ? "" : track.filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot);
        if (!List.of(".mp3", ".flac", ".ogg", ".oga", ".m4a", ".mp4").contains(extension)) return "";
        if (track.size > MAX_AUDIO_BYTES) throw new IOException("Embedded lyrics skipped: audio exceeds 256 MB.");
        File temp = File.createTempFile("lyrics-", extension, context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(Uri.parse(track.uri));
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (input == null) throw new IOException("Cannot open audio for embedded lyrics.");
                byte[] buffer = new byte[65536];
                int count;
                long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_AUDIO_BYTES) throw new IOException("Embedded lyrics skipped: audio exceeds 256 MB.");
                    output.write(buffer, 0, count);
                }
            }
            Tag tag = AudioFileIO.read(temp).getTag();
            if (tag == null) return "";
            String result = tag.getFirst(FieldKey.LYRICS);
            if (result == null || result.isBlank()) return "";
            if (result.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) throw new IOException("Embedded lyrics exceed the size limit.");
            return result;
        } catch (org.jaudiotagger.audio.exceptions.CannotReadException |
                 org.jaudiotagger.audio.exceptions.ReadOnlyFileException |
                 org.jaudiotagger.audio.exceptions.InvalidAudioFrameException |
                 org.jaudiotagger.tag.TagException error) {
            throw new IOException("Could not read embedded lyrics: " + error.getMessage(), error);
        } finally {
            if (!temp.delete()) android.util.Log.w("NeedleGroove", "Could not remove temporary lyrics file.");
        }
    }

    private static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_TEXT_BYTES) throw new IOException("Lyrics file or response is too large.");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
