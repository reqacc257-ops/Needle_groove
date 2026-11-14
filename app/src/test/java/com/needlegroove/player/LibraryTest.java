package com.needlegroove.player;

import android.content.Context;
import org.robolectric.RuntimeEnvironment;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36})
public class LibraryTest {
    private Context context;
    private Library library;
    @Before public void createLibrary() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit();
        library = new Library(context);
        library.tracks = List.of(track("one", "First"), track("two", "Second"));
    }
    private Library.Track track(String id, String title) {
        Library.Track track = new Library.Track();
        track.id = id; track.title = title; track.artist = "Artist";
        track.album = "Album"; track.genre = "Jazz"; track.folder = "Music";
        track.duration = 100_000; track.size = 500_000; track.added = id.equals("one") ? 1 : 2;
        track.fingerprint = QueueRules.fingerprint(title, track.artist, track.duration, track.size);
        return track;
    }
    @Test public void favoriteSurvivesLibraryRecreation() throws Exception {
        library.favorite(library.tracks.get(0));
        assertEquals(1, library.view("Playlists", "Favorites", "").size());
        Library restored = new Library(context);
        assertTrue(restored.prefs.getStringSet("favorites", java.util.Set.of()).contains("one"));
        library.favorite(library.tracks.get(0));
        assertTrue(library.view("Playlists", "Favorites", "").isEmpty());
    }
    @Test public void customOrderAndSmartPlaylistsUseSavedData() throws Exception {
        LinkedHashMap<String, List<String>> playlists = new LinkedHashMap<>();
        playlists.put("Night drive", List.of("two", "one"));
        library.savePlaylists(playlists);
        assertEquals("two", library.view("Playlists", "Night drive", "").get(0).id);
        assertEquals("two", library.view("Playlists", "Recently added", "").get(0).id);
        library.prefs.edit().putString("plays", "{\"one\":2,\"two\":7}").commit();
        assertEquals("two", library.view("Playlists", "Most played", "").get(0).id);
        assertEquals(2, library.view("Genres", "Jazz", "").size());
        assertEquals(1, library.view("Songs", null, "first").size());
    }
    @Test public void backupMapsFavoritesPlaylistsHistoryAndEditsToNewDeviceIds() throws Exception {
        Library.Track first = library.tracks.get(0);
        library.favorite(first);
        library.saveEdit(first, "Edited title", "Artist", "Album", "Jazz", "[00:01]Hello");
        LinkedHashMap<String, List<String>> playlists = new LinkedHashMap<>();
        playlists.put("Night drive", List.of("two", "one"));
        library.savePlaylists(playlists);
        library.prefs.edit().putString("plays", "{\"one\":3}").commit();
        String backup = library.backup();
        library.tracks = List.of(track("new-one", "First"), track("new-two", "Second"));
        assertEquals(0, library.restore(backup));
        assertEquals("new-two", library.playlists.get("Night drive").get(0));
        assertTrue(library.tracks.get(0).favorite);
        assertEquals("Edited title", library.tracks.get(0).title);
        assertEquals("[00:01]Hello", library.tracks.get(0).lyrics);
        assertEquals(3, library.view("Playlists", "Most played", "").get(0).plays);
    }
    @Test public void invalidBackupDoesNotOverwriteExistingSettings() throws Exception {
        String backup = library.backup();
        JSONObject data = new JSONObject(backup);
        data.getJSONObject("settings").put("minSize", -1);
        try { library.restore(data.toString()); fail("Invalid filters accepted"); }
        catch (IOException expected) { assertEquals("Backup settings are invalid.", expected.getMessage()); }
        assertEquals(0f, library.prefs.getFloat("minSize", 0), 0f);
    }
    @Test public void automaticLyricsNeverReplaceManualEditsOrLoseMetadata() throws Exception {
        Library.Track first = library.tracks.get(0);
        library.saveEdit(first, "Edited title", "Artist", "Album", "Jazz", "Manual words");
        assertFalse(library.saveLyricsIfUnchanged(first, "", "Automatic words"));
        assertEquals("Manual words", first.lyrics);
        assertTrue(library.saveLyricsIfUnchanged(first, "Manual words", "Chosen words"));
        assertEquals("Edited title", first.title);
        assertEquals("Chosen words", first.lyrics);
        Library.Track stale = track("one", "Original title");
        library.saveLyrics(stale, "New manual words");
        assertEquals("Edited title", first.title);
        assertEquals("New manual words", first.lyrics);
        Library recreated = new Library(context);
        assertTrue(recreated.prefs.getString("edits", "").contains("New manual words"));
    }
    @Test public void ambiguousPortableMatchesFailRatherThanGuess() throws Exception {
        String backup = library.backup();
        library.tracks = List.of(track("copy-one", "First"), track("copy-two", "First"));
        try { library.restore(backup); fail("Ambiguous records accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("No matching audio")); }
    }
}
