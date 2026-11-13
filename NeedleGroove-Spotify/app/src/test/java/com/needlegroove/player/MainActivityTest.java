package com.needlegroove.player;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36})
public class MainActivityTest {
    @Test public void nativeShellShowsListeningRoomAndNavigatesToLibraryAndSettings() {
        // Session binding is an Android-device concern, not part of this UI navigation test.
        RuntimeEnvironment.getApplication().getSharedPreferences("library", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new android.content.ComponentName(RuntimeEnvironment.getApplication(), PlaybackService.class));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = activity.get().getWindow().getDecorView();
            assertNotNull(find(root, "Now playing", TextView.class));
            assertNotNull(find(root, "Works offline. No account.", TextView.class));
            Button library = (Button) find(root, "Library", Button.class);
            assertNotNull(library); library.performClick();
            assertTrue(find(root, "Scan this phone", Button.class) != null || find(root, "Scanning...", Button.class) != null);
            assertNotNull(find(root, "+ Import files", Button.class));
            assertNotNull(find(root, "Import folder (with matching lyrics)", Button.class));
            Button settings = (Button) find(root, "Settings", Button.class);
            assertNotNull(settings); settings.performClick();
            assertNotNull(find(root, "Privacy policy", Button.class));
            assertNotNull(find(root, "App data stays on your phone.", TextView.class));
            assertNotNull(find(root, "Backup", TextView.class));
            assertNotNull(find(root, "Theme: Warm vinyl", Button.class));
            Button sound = (Button) find(root, "Sound", Button.class);
            assertNotNull(sound); sound.performClick();
            assertNotNull(find(root, "Equalizer", TextView.class));
        }
    }
    @Test public void appDoesNotRequestInternetPermission() throws Exception {
        android.content.pm.PackageInfo info = RuntimeEnvironment.getApplication().getPackageManager().getPackageInfo(
                RuntimeEnvironment.getApplication().getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
        if (info.requestedPermissions != null) {
            for (String permission : info.requestedPermissions) assertNotEquals(android.Manifest.permission.INTERNET, permission);
        }
    }
    @Test @Config(sdk = 36) public void importingSongAndSidecarSavesMatchingLyrics() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear()
                .putInt("minDuration", 0).commit();
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(new android.content.ComponentName(context, PlaybackService.class));
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("import-test", new ImportProvider());
        android.net.Uri audio = android.net.Uri.parse("content://import-test/Record.MP3");
        android.net.Uri sidecar = android.net.Uri.parse("content://import-test/record.lrc");
        try (java.io.InputStream input = getClass().getResourceAsStream("/audio/fixture.mp3")) {
            assertNotNull(input);
            byte[] tagged = input.readAllBytes();
            int size = (tagged[6] & 127) << 21 | (tagged[7] & 127) << 14 | (tagged[8] & 127) << 7 | tagged[9] & 127;
            byte[] withoutTag = java.util.Arrays.copyOfRange(tagged, 10 + size, tagged.length);
            shadowOf(context.getContentResolver()).registerInputStream(audio, new java.io.ByteArrayInputStream(withoutTag));
        }
        shadowOf(context.getContentResolver()).registerInputStream(sidecar,
                new java.io.ByteArrayInputStream("[00:01.00]Synthetic sidecar".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("importSelected", java.util.List.class);
            method.setAccessible(true);
            method.invoke(activity, java.util.List.of(audio, sidecar));
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("library");
            field.setAccessible(true);
            Library library = (Library) field.get(activity);
            assertEquals(1, library.tracks.size());
            assertEquals("[00:01.00]Synthetic sidecar", library.tracks.get(0).lyrics);
            assertTrue(library.prefs.getString("edits", "").contains("Synthetic sidecar"));
        }
    }

    private static final class ImportProvider extends android.content.ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection, String[] args, String order) {
            android.database.MatrixCursor cursor = new android.database.MatrixCursor(projection);
            cursor.addRow(new Object[]{uri.getLastPathSegment()});
            return cursor;
        }
        @Override public String getType(android.net.Uri uri) { return null; }
        @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(android.net.Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(android.net.Uri uri, android.content.ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
    private View find(View view, String text, Class<? extends View> type) {
        if (type.isInstance(view) && view instanceof TextView label && text.contentEquals(label.getText())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View match = find(group.getChildAt(i), text, type);
            if (match != null) return match;
        }
        return null;
    }
}
