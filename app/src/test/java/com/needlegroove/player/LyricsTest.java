package com.needlegroove.player;

import android.content.Context;
import android.net.Uri;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.DocumentsContract;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36})
public class LyricsTest {
    private Library.Track track() {
        Library.Track track = new Library.Track();
        track.title = "Test song";
        track.artist = "Test artist";
        track.album = "Test album";
        track.duration = 100_000;
        return track;
    }

    @Test public void extractsSyntheticLyricsFromAllSupportedFormats() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Lyrics lyrics = new Lyrics(context);
        for (String extension : new String[]{"mp3", "flac", "ogg", "m4a"}) {
            File audio = File.createTempFile("fixture-", "." + extension, context.getCacheDir());
            try (InputStream input = getClass().getResourceAsStream("/audio/fixture." + extension);
                 FileOutputStream output = new FileOutputStream(audio)) {
                assertNotNull(input);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            Library.Track track = track();
            Uri uri = Uri.parse("content://lyrics-test/" + extension);
            org.robolectric.Shadows.shadowOf(context.getContentResolver()).registerInputStream(uri, new java.io.FileInputStream(audio));
            track.uri = uri.toString();
            track.filename = audio.getName();
            track.size = audio.length();
            assertEquals("Format " + extension, "[00:00.10]Synthetic test words", lyrics.embedded(track));
            assertTrue(audio.delete());
        }
    }

    @Test public void sidecarNamesMatchCaseInsensitivelyWithoutFuzzyMatching() {
        assertTrue(Lyrics.sidecar("Record.LRC"));
        assertEquals(Lyrics.basename("Record.MP3"), Lyrics.basename("record.lrc"));
        assertNotEquals(Lyrics.basename("Record live.mp3"), Lyrics.basename("record.lrc"));
        Uri first = Uri.parse("content://lyrics-test/first");
        Uri second = Uri.parse("content://lyrics-test/second");
        assertEquals(first, Lyrics.pairedSidecar("Record.MP3",
                java.util.Map.of("record", java.util.List.of(first)), java.util.Map.of("record", 1)));
        assertNull(Lyrics.pairedSidecar("Record.MP3",
                java.util.Map.of("record", java.util.List.of(first, second)), java.util.Map.of("record", 1)));
        assertNull(Lyrics.pairedSidecar("Record.MP3",
                java.util.Map.of("record", java.util.List.of(first)), java.util.Map.of("record", 2)));
    }

    @Test public void folderImportEnumeratesOnlyAudioAndSidecarsInSelectedFolder() throws Exception {
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("folder-test", new FolderProvider());
        Lyrics lyrics = new Lyrics(RuntimeEnvironment.getApplication());
        java.util.List<Uri> files = lyrics.folderFiles(Uri.parse("content://folder-test/tree/root"));
        assertEquals(2, files.size());
        assertEquals("song.mp3", files.get(0).getLastPathSegment());
        assertEquals("song.LRC", files.get(1).getLastPathSegment());
        assertEquals("song.mp3", lyrics.name(files.get(0)));
        assertEquals("song.LRC", lyrics.name(files.get(1)));
    }

    private static final class FolderProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            if (uri.getLastPathSegment().equals("children")) {
                MatrixCursor cursor = new MatrixCursor(projection);
                cursor.addRow(new Object[]{"song.mp3", "song.mp3", "audio/mpeg"});
                cursor.addRow(new Object[]{"song.LRC", "song.LRC", "text/plain"});
                cursor.addRow(new Object[]{"notes.txt", "notes.txt", "text/plain"});
                cursor.addRow(new Object[]{"subfolder", "subfolder", DocumentsContract.Document.MIME_TYPE_DIR});
                return cursor;
            }
            MatrixCursor cursor = new MatrixCursor(projection);
            cursor.addRow(new Object[]{uri.getLastPathSegment()});
            return cursor;
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
