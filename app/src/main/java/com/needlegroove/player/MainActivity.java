package com.needlegroove.player;

import android.Manifest;
import android.app.AlertDialog;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.media3.common.util.UnstableApi
public final class MainActivity extends ComponentActivity {
    private Library library;
    private Lyrics lyrics;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root, navigation, content, miniPlayer;
    private FrameLayout page;
    private TextView title, artist, time, status, sleep, lyricText, miniTitle, miniArtist;
    private Button play, favorite, shuffle, repeat, miniPlay;
    private SeekBar progress;
    private VinylView vinyl;
    private Library.Track current;
    private String tab = "Listening", category = "Songs", group, search = "", artId = "";
    private int ink, muted, gold, surface, background, songGlow = 0xff202020;
    private ValueAnimator backgroundAnimator;
    private boolean scanning, scrubbing;
    private List<Library.Track> shown = List.of();
    private ListView list;
    private final List<TextView> lyricLines = new ArrayList<>();
    private List<QueueRules.Line> timedLyrics = List.of();
    private AlertDialog lyricsDialog;
    private int activeLyric = -2;
    private String backupText;
    private Library.Track lyricTarget;
    private Library.Track clipTarget, coverTarget;
    private double clipStart, clipEnd;
    private boolean observing;
    private final Set<String> lyricsAttempted = java.util.Collections.synchronizedSet(new HashSet<>());
    private final Runnable rescan = this::scan;
    private final ContentObserver musicObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            handler.removeCallbacks(rescan);
            handler.postDelayed(rescan, 1500);
        }
    };
    private final ActivityResultLauncher<String[]> pickCover = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                Library.Track track = coverTarget;
                if (uri == null || track == null) return;
                runWork(() -> {
                    library.saveCover(track, uri);
                    return "Cover saved for " + track.title;
                }, () -> {
                    artId = "";
                    if (controller != null) for (int i = 0; i < controller.getMediaItemCount(); i++)
                        if (controller.getMediaItemAt(i).mediaId.equals(track.id)) controller.replaceMediaItem(i, track.mediaItem());
                    renderPlayback();
                });
            });
    private final ActivityResultLauncher<String> createClip = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("audio/wav"), uri -> {
                Library.Track track = clipTarget;
                if (uri == null || track == null) return;
                double start = clipStart, end = clipEnd;
                runWork(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IOException("Cannot write the clip.");
                        AudioClip.export(this, Uri.parse(track.uri), out, start, end);
                    }
                    return "WAV clip exported. You can choose it as a ringtone in your device settings.";
                }, null);
            });

    private final ActivityResultLauncher<String> mediaPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) scan();
                else {
                    message("Music access was not granted. You can still import selected files without storage permission.", false);
                    scan();
                }
            });
    private final ActivityResultLauncher<String[]> pickMusic = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris.isEmpty() || library == null) return;
                runWork(() -> importSelected(uris), () -> { if (tab.equals("Library")) showTab(tab); });
            });
    private final ActivityResultLauncher<Uri> pickMusicFolder = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null || library == null) return;
                runWork(() -> importSelected(lyrics.folderFiles(uri)), () -> { if (tab.equals("Library")) showTab(tab); });
            });
    private final ActivityResultLauncher<String> createBackup = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri == null || backupText == null) return;
                String content = backupText;
                runWork(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IOException("Cannot write the backup.");
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    return "Backup saved. Audio files stay on this device.";
                }, null);
            });
    private final ActivityResultLauncher<String[]> restoreBackup = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || library == null) return;
                runWork(() -> {
                    int missing = library.restore(readText(uri, 5_000_000));
                    return "Playlists and metadata restored." + (missing == 0 ? "" : "\nSome songs could not be uniquely matched and were skipped: " + missing + ". Reimport the originals and restore again.");
                }, () -> { applyTheme(); showTab(tab); scan(); });
            });
    private final ActivityResultLauncher<String[]> pickLyrics = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                Library.Track track = lyricTarget;
                if (uri == null || track == null) return;
                runWork(() -> {
                    library.saveLyrics(track, readText(uri, 1_000_000));
                    return "Lyrics saved for " + track.title;
                }, null);
            });
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            renderPlayback();
            updateLyrics();
            handler.postDelayed(this, 400);
        }
    };

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        try { library = new Library(this); }
        catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("Library could not open").setMessage(error.getMessage())
                    .setPositiveButton("Close", (dialog, which) -> finish()).setCancelable(false).show();
            return;
        }
        lyrics = new Lyrics(this);
        applyTheme();
        buildShell();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!tab.equals("Listening")) showTab("Listening");
                else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(new Player.Listener() {
                    @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) { syncCurrent(); }
                    @Override public void onIsPlayingChanged(boolean playing) { renderPlayback(); }
                    @Override public void onPlayerError(androidx.media3.common.PlaybackException error) { message("Couldn't play this file: " + error.getMessage(), true); }
                });
                syncCurrent();
                showTab(tab);
            } catch (Exception error) { message("Playback service could not connect: " + error.getMessage(), true); }
        }, androidx.core.content.ContextCompat.getMainExecutor(this));
        scan();
    }

    @Override protected void onStart() {
        super.onStart(); handler.post(ticker);
        if (hasMusicPermission()) observeMusic();
    }
    @Override protected void onStop() {
        handler.removeCallbacks(ticker); handler.removeCallbacks(rescan);
        if (observing) { getContentResolver().unregisterContentObserver(musicObserver); observing = false; }
        if (vinyl != null) vinyl.playing(false);
        super.onStop();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        super.onDestroy();
    }

    private void applyTheme() {
        String theme = library.prefs.getString("theme", "Spotify");
        if (theme.equals("Daylight")) {
            ink = Color.parseColor("#191919"); muted = Color.parseColor("#686868");
            gold = Color.parseColor("#16883B"); background = Color.parseColor("#F4F4F4");
            surface = Color.parseColor("#FFFFFF");
        } else if (theme.equals("Midnight")) {
            ink = Color.parseColor("#F7F7F7"); muted = Color.parseColor("#A7A7A7");
            gold = Color.parseColor("#1ED760"); background = Color.parseColor("#10131A");
            surface = Color.parseColor("#20242D");
        } else if (theme.equals("True black")) {
            ink = Color.parseColor("#FFFFFF"); muted = Color.parseColor("#A7A7A7");
            gold = Color.parseColor("#1ED760"); background = Color.BLACK;
            surface = Color.parseColor("#121212");
        } else {
            ink = Color.parseColor("#FFFFFF"); muted = Color.parseColor("#B3B3B3");
            gold = Color.parseColor("#1ED760"); background = Color.parseColor("#121212");
            surface = Color.parseColor("#242424");
        }
        songGlow = background;
        if (root != null) root.setBackgroundColor(background);
    }

    private void buildShell() {
        root = column();
        root.setBackgroundColor(background);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            root.setPadding(0, system.top, 0, Math.max(system.bottom, keyboard.bottom));
            return insets;
        });
        LinearLayout header = row();
        header.setPadding(dp(22), dp(12), dp(14), dp(6));
        TextView brand = text("NEEDLE & GROOVE", 13, ink);
        brand.setLetterSpacing(.12f);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button importButton = button("＋", this::importMusic); importButton.setContentDescription("Import music"); header.addView(importButton);
        root.addView(header);
        page = new FrameLayout(this);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        buildMiniPlayer();
        root.addView(miniPlayer);
        status = text("Your music stays on this device.", 11, muted);
        status.setPadding(dp(22), dp(6), dp(22), dp(6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        navigation = row();
        navigation.setPadding(dp(12), dp(6), dp(12), dp(10));
        root.addView(navigation);
        setContentView(root);
        showTab(tab);
    }

    private void buildMiniPlayer() {
        miniPlayer = row();
        miniPlayer.setVisibility(View.GONE);
        miniPlayer.setPadding(dp(12), dp(7), dp(8), dp(7));
        miniPlayer.setBackground(rounded(Color.parseColor("#282828"), 12));
        miniPlayer.setElevation(dp(5));
        TextView disc = text("◉", 25, gold);
        disc.setGravity(Gravity.CENTER);
        miniPlayer.addView(disc, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout labels = column();
        miniTitle = text("Nothing playing", 13, ink);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);
        miniArtist = text("Choose a song", 10, muted);
        labels.addView(miniTitle);
        labels.addView(miniArtist);
        miniPlayer.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        miniPlay = button("▶", this::togglePlay);
        miniPlay.setTextColor(ink);
        miniPlay.setBackgroundColor(Color.TRANSPARENT);
        miniPlayer.addView(miniPlay, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button next = button("»", () -> { if (controller != null) controller.seekToNextMediaItem(); });
        next.setTextSize(22);
        next.setBackgroundColor(Color.TRANSPARENT);
        miniPlayer.addView(next, new LinearLayout.LayoutParams(dp(44), dp(44)));
        miniPlayer.setOnClickListener(view -> showTab("Listening"));
    }

    private void updateMiniPlayer() {
        if (miniPlayer == null) return;
        boolean hasTrack = current != null && !tab.equals("Listening");
        miniPlayer.setVisibility(hasTrack ? View.VISIBLE : View.GONE);
        if (!hasTrack) return;
        miniTitle.setText(current.title);
        miniArtist.setText(current.artist);
        miniPlay.setText(controller != null && controller.isPlaying() ? "Ⅱ" : "▶");
        miniPlay.setContentDescription(controller != null && controller.isPlaying() ? "Pause" : "Play");
    }

    private void updateSongBackground(Bitmap artwork) {
        if (page == null || artwork == null || artwork.isRecycled()) return;
        int sample = artwork.getPixel(artwork.getWidth() / 2, artwork.getHeight() / 2);
        int target = Color.rgb(
                (int) (Color.red(sample) * .52f + 18),
                (int) (Color.green(sample) * .52f + 18),
                (int) (Color.blue(sample) * .52f + 18));
        if (backgroundAnimator != null) backgroundAnimator.cancel();
        int from = songGlow;
        backgroundAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, target);
        backgroundAnimator.setDuration(850);
        backgroundAnimator.addUpdateListener(animation -> {
            songGlow = (int) animation.getAnimatedValue();
            if (page != null) {
                GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{songGlow, background, background});
                page.setBackground(gradient);
            }
        });
        backgroundAnimator.start();
    }

    private void populateQueue(LinearLayout queue, AlertDialog dialog) {
        queue.removeAllViews();
        if (controller == null || controller.getMediaItemCount() == 0) {
            queue.addView(text("Queue is empty.", 14, muted));
            return;
        }
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            final int index = i;
            MediaItem item = controller.getMediaItemAt(i);
            String song = item.mediaMetadata.title == null ? "Unknown song" : item.mediaMetadata.title.toString();
            String by = item.mediaMetadata.artist == null ? "Unknown artist" : item.mediaMetadata.artist.toString();
            LinearLayout line = row();
            line.setPadding(dp(8), dp(5), dp(4), dp(5));
            LinearLayout details = column();
            details.addView(text((i == controller.getCurrentMediaItemIndex() ? "♫  " : "") + song, 13,
                    i == controller.getCurrentMediaItemIndex() ? gold : ink));
            details.addView(text(by, 11, muted));
            line.addView(details, new LinearLayout.LayoutParams(0, dp(54), 1));
            Button remove = button("×", () -> {
                if (controller != null && index < controller.getMediaItemCount()) {
                    controller.removeMediaItem(index);
                    if (controller.getMediaItemCount() == 0) dialog.dismiss();
                    else populateQueue(queue, dialog);
                }
            });
            remove.setTextSize(20);
            line.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(44)));
            line.setOnClickListener(view -> {
                if (controller != null && index < controller.getMediaItemCount()) {
                    controller.seekToDefaultPosition(index);
                    controller.play();
                    dialog.dismiss();
                }
            });
            queue.addView(line);
        }
    }

    private void showQueue() {
        if (controller == null || controller.getMediaItemCount() == 0) {
            message("Your queue is empty.", false);
            return;
        }
        LinearLayout queue = column();
        queue.setPadding(dp(16), dp(8), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(queue);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Playing queue")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .create();
        populateQueue(queue, dialog);
        dialog.show();
    }

    private void showTab(String name) {
        if (library == null || page == null) return;
        tab = name;
        title = artist = time = sleep = null;
        play = favorite = shuffle = repeat = null;
        vinyl = null;
        progress = null;
        artId = "";
        list = null;
        page.removeAllViews();
        content = column();
        content.setPadding(dp(22), dp(10), dp(22), dp(16));
        if (name.equals("Library")) page.addView(content, new FrameLayout.LayoutParams(-1, -1));
        else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(content);
            page.addView(scroll);
        }
        navigation.removeAllViews();
        for (String destination : List.of("Listening", "Search", "Library", "Settings")) {
            Button button = button(switch (destination) { case "Listening" -> "⌂  Home"; case "Search" -> "⌕  Search"; case "Library" -> "▤  Your Library"; default -> "⚙  Settings"; }, () -> showTab(destination));
            button.setTextSize(11);
            button.setTextColor(destination.equals(name) ? gold : muted);
            button.setBackground(rounded(destination.equals(name) ? surface : background, 16));
            button.setContentDescription(destination + (destination.equals(name) ? ", selected" : ""));
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        switch (name) {
            case "Library" -> libraryPage();
            case "Search" -> { category = "Songs"; group = null; libraryPage(); }
            case "Sound" -> soundPage();
            case "Settings" -> settingsPage();
            default -> listeningPage();
        }
    }

    private void listeningPage() {
        TextView kicker = text("GOOD EVENING", 11, muted);
        kicker.setLetterSpacing(.18f); kicker.setGravity(Gravity.CENTER);
        content.addView(kicker);
        TextView heading = serif("Now playing", 25);
        heading.setGravity(Gravity.CENTER); content.addView(heading);
        vinyl = new VinylView(this);
        content.addView(vinyl, new LinearLayout.LayoutParams(-1, dp(230)));
        vinyl.setBlack(library.prefs.getString("theme", "").equals("True black"));
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(surface, 24));
        title = text("Nothing playing yet", 22, ink); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        artist = text("Scan your phone or import files.", 13, muted); artist.setGravity(Gravity.CENTER);
        card.addView(title); card.addView(artist);
        time = text("0:00                                      0:00", 11, muted);
        time.setPadding(0, dp(15), 0, 0); card.addView(time);
        progress = new SeekBar(this);
        progress.setMax(1000); progress.setProgressTintList(ColorStateList.valueOf(gold));
        progress.setThumbTintList(ColorStateList.valueOf(gold));
        progress.setContentDescription("Track position");
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar seekBar) { scrubbing = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (controller != null && controller.getDuration() > 0) controller.seekTo(controller.getDuration() * seekBar.getProgress() / 1000);
                scrubbing = false;
            }
        });
        card.addView(progress);
        LinearLayout transport = row();
        shuffle = button("\u21C4", () -> {
            if (controller != null) { controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled()); renderPlayback(); }
        });
        shuffle.setContentDescription("Shuffle");
        Button previous = button("\u23EE", () -> { if (controller != null) controller.seekToPrevious(); });
        previous.setContentDescription("Previous track");
        play = button("\u25B6", this::togglePlay);
        play.setTextSize(25); play.setTextColor(Color.BLACK); play.setBackground(rounded(gold, 30));
        Button next = button("\u23ED", () -> { if (controller != null) controller.seekToNextMediaItem(); });
        next.setContentDescription("Next track");
        repeat = button("\u21BB", () -> {
            if (controller != null) {
                controller.setRepeatMode((controller.getRepeatMode() + 1) % 3);
                renderPlayback();
            }
        });
        for (Button button : List.of(shuffle, previous, play, next, repeat)) transport.addView(button, new LinearLayout.LayoutParams(0, dp(60), 1));
        card.addView(transport);
        LinearLayout extras = row();
        favorite = button("\u2661 Favorite", () -> {
            if (current != null) try { library.favorite(current); renderPlayback(); }
            catch (Exception error) { message(error.getMessage(), true); }
        });
        extras.addView(favorite, new LinearLayout.LayoutParams(0, dp(48), 1));
        extras.addView(button("Lyrics", this::showLyrics), new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(extras);
        LinearLayout extraActions = row();
        extraActions.addView(button("Open library  →", () -> showTab("Library")), new LinearLayout.LayoutParams(0, dp(48), 1));
        extraActions.addView(button("Queue  ☷", this::showQueue), new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(extraActions);
        content.addView(card);
        renderPlayback();
    }

    private void togglePlay() {
        if (controller == null) { message("Connecting to audio...", false); return; }
        if (controller.isPlaying()) controller.pause();
        else if (controller.getMediaItemCount() > 0) { controller.prepare(); controller.play(); }
        else showTab("Library");
    }

    private void syncCurrent() {
        if (controller == null) return;
        MediaItem item = controller.getCurrentMediaItem();
        Library.Track previous = current;
        current = item == null ? null : library.tracks.stream().filter(track -> track.id.equals(item.mediaId)).findFirst().orElse(null);
        if (previous != current && lyricsDialog != null) lyricsDialog.dismiss();
        if (current != null && current.lyrics.isEmpty() && lyricsAttempted.add(current.id)) {
            Library.Track track = current;
            worker.execute(() -> {
                List<String> warnings = new ArrayList<>();
                resolveLyrics(track, null, warnings);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (!warnings.isEmpty()) message(String.join("\n", warnings), true);
                    if (current != null && current.id.equals(track.id) && lyricsDialog != null && !current.lyrics.isEmpty()) {
                        lyricsDialog.dismiss();
                        showLyrics();
                    }
                });
            });
        }
        renderPlayback();
    }

    private void renderPlayback() {
        if (controller == null || library == null) return;
        if (title != null) {
            title.setText(current == null ? "Nothing playing yet" : current.title);
            artist.setText(current == null ? "Scan your phone or import files." : current.artist + " \u00B7 " + current.album);
            play.setText(controller.isPlaying() ? "\u2161" : "\u25B6");
            play.setContentDescription(controller.isPlaying() ? "Pause" : "Play");
            favorite.setEnabled(current != null);
            favorite.setText(current != null && current.favorite ? "\u2665 Favorited" : "\u2661 Favorite");
            favorite.setTextColor(current != null && current.favorite ? gold : muted);
            shuffle.setTextColor(controller.getShuffleModeEnabled() ? gold : muted);
            repeat.setText(controller.getRepeatMode() == Player.REPEAT_MODE_ONE ? "\u21BB1" : "\u21BB");
            repeat.setTextColor(controller.getRepeatMode() == Player.REPEAT_MODE_OFF ? muted : gold);
            repeat.setContentDescription("Repeat: " + switch (controller.getRepeatMode()) {
                case Player.REPEAT_MODE_ONE -> "one";
                case Player.REPEAT_MODE_ALL -> "all";
                case Player.REPEAT_MODE_OFF -> "off, in order";
                default -> throw new IllegalStateException("Unknown playback mode.");
            });
            long duration = Math.max(0, controller.getDuration()), position = Math.max(0, controller.getCurrentPosition());
            time.setText(formatTime(position) + "   /   " + formatTime(duration));
            progress.setEnabled(current != null && duration > 0);
            if (!scrubbing) progress.setProgress(duration > 0 ? (int) (position * 1000 / duration) : 0);
            vinyl.playing(controller.isPlaying());
            if (current != null && !current.id.equals(artId)) {
                artId = current.id;
                Library.Track track = current;
                vinyl.setArtwork(null);
                worker.execute(() -> {
                    try {
                        library.loadArtwork(track);
                        Bitmap bitmap = decodeArtwork(track.art);
                        runOnUiThread(() -> {
                            if (!isDestroyed() && current == track) {
                                if (vinyl != null) vinyl.setArtwork(bitmap);
                                updateSongBackground(bitmap);
                                int index = controller == null ? C.INDEX_UNSET : controller.getCurrentMediaItemIndex();
                                if (index != C.INDEX_UNSET) controller.replaceMediaItem(index, track.mediaItem());
                            }
                        });
                    } catch (Exception error) { runOnUiThread(() -> message("Artwork could not load: " + error.getMessage(), true)); }
                });
            }
        }
        updateMiniPlayer();
        if (sleep != null) {
            long deadline = library.prefs.getLong("sleepDeadline", 0);
            sleep.setText(deadline > 0 ? "Stops in " + formatTime(Math.max(0, deadline - System.currentTimeMillis())) : "Sleep timer off");
        }
    }

    private void libraryPage() {
        heading(tab.equals("Search") ? "Search" : "Your Library", "Find music in your collection.");
        LinearLayout actions = row();
        actions.addView(button(scanning ? "Scanning..." : "Scan this phone", this::requestScan), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("+ Import files", this::importMusic), new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(actions);
        content.addView(button("Import folder (with matching lyrics)", () -> {
            if (scanning) { message("Wait for the library to finish loading.", false); return; }
            pickMusicFolder.launch(null);
        }));
        content.addView(text("Copies audio from the selected folder and pairs matching .lrc files. Subfolders are not included.", 12, muted));
        EditText query = input("What do you want to play?", search);
        query.setSingleLine(true);
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { search = s.toString(); renderLibraryRows(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        content.addView(query);
        HorizontalScrollView tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = row();
        for (String name : List.of("Songs", "Albums", "Artists", "Genres", "Folders", "Playlists")) {
            Button button = button(name, () -> { category = name; group = null; showTab("Library"); });
            button.setTextColor(category.equals(name) ? gold : muted); tabRow.addView(button);
        }
        tabs.addView(tabRow); content.addView(tabs);
        LinearLayout context = row();
        if (group != null) context.addView(button("\u2190", () -> { group = null; showTab("Library"); }));
        TextView count = text(group == null ? category : group, 13, muted);
        context.addView(count, new LinearLayout.LayoutParams(0, dp(44), 1));
        if (category.equals("Playlists") && group == null) context.addView(button("+ New", () -> playlistName(null)));
        else if (group == null && category.equals("Songs") || group != null) context.addView(button("Play these", () -> playTracks(shown, 0)));
        if (group != null && library.playlists.containsKey(group)) context.addView(button("\u22EF", this::playlistMenu));
        content.addView(context);
        list = new ListView(this);
        list.setDivider(null); list.setBackgroundColor(Color.TRANSPARENT);
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        renderLibraryRows();
    }

    private void renderLibraryRows() {
        if (list == null) return;
        boolean groups = !category.equals("Songs") && group == null;
        shown = library.view(category, group, search);
        List<String> groupNames = new ArrayList<>();
        if (groups) {
            if (category.equals("Playlists")) {
                groupNames.addAll(List.of("Favorites", "Recently added", "Most played"));
                groupNames.addAll(library.playlists.keySet());
                groupNames.removeIf(name -> !name.toLowerCase(java.util.Locale.ROOT).contains(search.toLowerCase(java.util.Locale.ROOT)));
            } else for (Library.Track track : shown) {
                String name = switch (category) { case "Albums" -> track.album; case "Artists" -> track.artist; case "Genres" -> track.genre; default -> track.folder; };
                if (!groupNames.contains(name)) groupNames.add(name);
            }
        }
        List<String> names = groupNames;
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return groups ? Math.max(1, names.size()) : Math.max(1, shown.size()); }
            @Override public Object getItem(int position) { return groups ? names.isEmpty() ? null : names.get(position) : shown.isEmpty() ? null : shown.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                if (getItem(position) == null) {
                    TextView empty = text("No songs found.\nScan your phone or import files.", 14, muted);
                    empty.setPadding(dp(14), dp(28), dp(14), dp(28)); return empty;
                }
                LinearLayout row = row(); row.setPadding(dp(8), dp(6), dp(4), dp(6));
                row.setBackground(rounded(surface, 12));
                if (groups) {
                    String name = names.get(position);
                    TextView label = text(name + "\nSongs: " + library.view(category, name, "").size(), 14, ink);
                    row.addView(label, new LinearLayout.LayoutParams(-1, dp(64)));
                    row.setOnClickListener(view -> { group = name; search = ""; showTab("Library"); });
                } else {
                    Library.Track track = shown.get(position);
                    LinearLayout copy = column();
                    copy.addView(text(track.title, 14, ink));
                    copy.addView(text(track.artist + " \u00B7 " + formatTime(track.duration), 11, muted));
                    copy.setMinimumHeight(dp(58));
                    copy.setOnClickListener(view -> playTracks(shown, shown.indexOf(track)));
                    copy.setContentDescription("Play " + track.title + " by " + track.artist);
                    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                    Button heart = button(track.favorite ? "\u2665" : "\u2661", () -> {
                        try { library.favorite(track); renderLibraryRows(); }
                        catch (Exception error) { message(error.getMessage(), true); }
                    });
                    heart.setTextColor(track.favorite ? gold : muted);
                    heart.setContentDescription((track.favorite ? "Unfavorite " : "Favorite ") + track.title);
                    row.addView(heart, new LinearLayout.LayoutParams(dp(48), dp(48)));
                    Button more = button("\u22EF", () -> trackMenu(track));
                    more.setContentDescription("Options for " + track.title);
                    row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
                }
                return row;
            }
        });
    }

    private void playTracks(List<Library.Track> tracks, int start) {
        if (controller == null) { message("Playback is still connecting.", false); return; }
        if (tracks.isEmpty()) { message("Add songs to this collection first.", false); return; }
        List<MediaItem> queue = new ArrayList<>();
        for (Library.Track track : tracks) queue.add(track.mediaItem());
        controller.setMediaItems(queue, start, 0);
        controller.prepare(); controller.play();
        current = tracks.get(start);
        showTab("Listening");
    }

    private Map<String, List<String>> copyPlaylists() {
        Map<String, List<String>> next = new LinkedHashMap<>();
        library.playlists.forEach((name, ids) -> next.put(name, new ArrayList<>(ids)));
        return next;
    }
    private void playlistName(@Nullable String existing) {
        EditText name = input("Playlist name", existing == null ? "" : existing);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "New playlist" : "Rename playlist")
                .setView(name).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String value = name.getText().toString().trim();
                Library.validatePlaylistName(value);
                if (!value.equals(existing) && library.playlists.containsKey(value)) throw new IOException("That playlist already exists.");
                Map<String, List<String>> next = copyPlaylists();
                List<String> ids = existing == null ? new ArrayList<>() : next.remove(existing);
                next.put(value, ids);
                library.savePlaylists(next);
                group = value; category = "Playlists"; dialog.dismiss(); showTab("Library");
            } catch (Exception error) { name.setError(error.getMessage()); }
        }));
        dialog.show();
    }
    private void playlistMenu() {
        String name = group;
        new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Rename", "Delete playlist"}, (dialog, which) -> {
            if (which == 0) playlistName(name);
            else new AlertDialog.Builder(this).setTitle("Delete " + name + "?").setMessage("Your audio files will not be deleted.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (confirmation, ignored) -> {
                        try {
                            Map<String, List<String>> next = copyPlaylists(); next.remove(name); library.savePlaylists(next);
                            group = null; showTab("Library");
                        } catch (Exception error) { message(error.getMessage(), true); }
                    }).show();
        }).show();
    }
    private void trackMenu(Library.Track track) {
        List<String> options = new ArrayList<>(List.of("Edit library details", "Add to playlist", "Import lyrics (.lrc / .txt)", "Choose cover artwork", "Export ringtone clip (WAV)"));
        boolean custom = group != null && library.playlists.containsKey(group);
        if (custom) options.addAll(List.of("Move up", "Move down", "Remove from playlist"));
        new AlertDialog.Builder(this).setTitle(track.title).setItems(options.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) editTrack(track);
            else if (which == 1) addToPlaylist(track);
            else if (which == 2) { lyricTarget = track; pickLyrics.launch(new String[]{"text/*", "application/octet-stream"}); }
            else if (which == 3) { coverTarget = track; pickCover.launch(new String[]{"image/*"}); }
            else if (which == 4) clipDialog(track);
            else {
                try {
                    Map<String, List<String>> next = copyPlaylists();
                    List<String> ids = next.get(group);
                    int index = ids.indexOf(track.id);
                    if (which == 7) ids.remove(track.id);
                    else {
                        int destination = index + (which == 5 ? -1 : 1);
                        if (destination < 0 || destination >= ids.size()) { message("This song is already first or last in the playlist.", false); return; }
                        java.util.Collections.swap(ids, index, destination);
                    }
                    library.savePlaylists(next); renderLibraryRows();
                } catch (Exception error) { message(error.getMessage(), true); }
            }
        }).show();
    }
    private void clipDialog(Library.Track track) {
        LinearLayout form = column(); form.setPadding(dp(24), 0, dp(24), 0);
        EditText start = labeledInput(form, "Start (seconds)", "0");
        EditText end = labeledInput(form, "End (seconds)", String.valueOf(Math.min(30, track.duration / 1000d)));
        start.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        end.setInputType(start.getInputType());
        form.addView(text("Exports up to 60 seconds as WAV. Select the saved file in device ringtone settings. MP3 encoding and automatic ringtone assignment are not included.", 12, muted));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Make a clip").setView(form)
                .setPositiveButton("Export WAV", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                double from = Double.parseDouble(start.getText().toString()), to = Double.parseDouble(end.getText().toString());
                if (!Double.isFinite(from) || !Double.isFinite(to) || from < 0 || to <= from || to - from > 60 || to * 1000 > track.duration) throw new IllegalArgumentException("Choose a valid range within the song, up to 60 seconds long.");
                clipTarget = track; clipStart = from; clipEnd = to;
                dialog.dismiss(); createClip.launch("needle-groove-clip.wav");
            } catch (IllegalArgumentException error) { end.setError(error.getMessage()); }
        }));
        dialog.show();
    }
    private void addToPlaylist(Library.Track track) {
        if (library.playlists.isEmpty()) { message("Create a custom playlist first.", false); category = "Playlists"; group = null; showTab("Library"); return; }
        List<String> names = new ArrayList<>(library.playlists.keySet());
        new AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names.toArray(new String[0]), (dialog, which) -> {
            try {
                Map<String, List<String>> next = copyPlaylists(); List<String> ids = next.get(names.get(which));
                if (!ids.contains(track.id)) ids.add(track.id);
                library.savePlaylists(next); message("Added to " + names.get(which), false);
            } catch (Exception error) { message(error.getMessage(), true); }
        }).show();
    }
    private void editTrack(Library.Track track) {
        LinearLayout form = column(); form.setPadding(dp(22), dp(8), dp(22), 0);
        EditText title = labeledInput(form, "Title", track.title), artist = labeledInput(form, "Artist", track.artist);
        EditText album = labeledInput(form, "Album", track.album), genre = labeledInput(form, "Genre", track.genre);
        form.addView(text("Library metadata only. Original audio file tags are not overwritten.", 11, muted));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Song details").setView(scroll).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String song = title.getText().toString().trim(), performer = artist.getText().toString().trim();
            if (song.isEmpty() || performer.isEmpty()) { title.setError("Title and artist are required."); return; }
            try {
                library.saveEdit(track, song, performer, Library.value(album.getText().toString(), "Unknown album"),
                        Library.value(genre.getText().toString(), "Unknown genre"), track.lyrics);
                if (controller != null) for (int i = 0; i < controller.getMediaItemCount(); i++)
                    if (controller.getMediaItemAt(i).mediaId.equals(track.id)) controller.replaceMediaItem(i, track.mediaItem());
                dialog.dismiss(); renderLibraryRows(); renderPlayback();
            } catch (Exception error) { title.setError(error.getMessage()); }
        }));
        dialog.show();
    }

    private void soundPage() {
        heading("Equalizer", null);
        CheckBox enabled = new CheckBox(this); enabled.setText("Equalizer"); enabled.setTextColor(ink);
        enabled.setChecked(library.prefs.getBoolean("eq", false));
        enabled.setOnCheckedChangeListener((button, checked) -> setting("eq", checked));
        content.addView(enabled);
        Button preset = button("Preset: " + library.prefs.getString("preset", "Flat"), () -> {
            String[] names = {"Flat", "Rock", "Jazz", "Classical", "Bass boost"};
            new AlertDialog.Builder(this).setTitle("Equalizer preset").setItems(names, (dialog, which) -> {
                android.content.SharedPreferences.Editor editor = library.prefs.edit().putString("preset", names[which]).putBoolean("eq", true);
                int count = PlaybackService.active == null ? 5 : PlaybackService.active.bandCount();
                for (short i = 0; i < count; i++) {
                    int frequency = PlaybackService.active == null ? new int[]{60, 230, 910, 3600, 14000}[i] : PlaybackService.active.bandFrequency(i);
                    int level = switch (which) { case 1 -> frequency < 300 || frequency > 3000 ? 400 : -200; case 2 -> frequency < 300 ? 300 : frequency > 3000 ? 200 : 0; case 3 -> frequency > 3000 ? 250 : 0; case 4 -> frequency < 300 ? 600 : 0; default -> 0; };
                    editor.putInt("band" + i, level);
                }
                if (!editor.commit()) message("Could not save equalizer preset.", true);
                refreshSound(); showTab("Sound");
            }).show();
        });
        content.addView(preset);
        int bands = PlaybackService.active == null ? 5 : PlaybackService.active.bandCount();
        short[] range = PlaybackService.active == null ? new short[]{-1500, 1500} : PlaybackService.active.bandRange();
        for (short band = 0; band < bands; band++) {
            int frequency = PlaybackService.active == null ? new int[]{60, 230, 910, 3600, 14000}[band] : PlaybackService.active.bandFrequency(band);
            String key = "band" + band;
            slider(frequency >= 1000 ? frequency / 1000f + " kHz" : frequency + " Hz", range[0], range[1],
                    library.prefs.getInt(key, 0), value -> {
                        library.prefs.edit().putString("preset", "Custom").apply(); setting(key, value);
                    }, value -> String.format(java.util.Locale.ROOT, "%+.1f dB", value / 100f));
        }
        slider("Bass boost", 0, 1000, library.prefs.getInt("bass", 0), value -> setting("bass", value), value -> value / 10 + "%");
        slider("Spatial / virtualizer", 0, 1000, library.prefs.getInt("spatial", 0), value -> setting("spatial", value), value -> value / 10 + "%");
        choice("Reverb", new String[]{"Dry / off", "Small room", "Concert hall"}, library.prefs.getInt("reverb", 0), value -> setting("reverb", value));
        slider("Crossfade", 0, 10, library.prefs.getInt("crossfade", 0), value -> setting("crossfade", value), value -> value == 0 ? "Off" : value + " seconds");
        content.addView(text("Crossfade blends tracks. With it off, Media3 advances without artificial delays; exact gapless behavior depends on the audio format. Effects depend on your device and audio route.", 12, muted));
        content.addView(button("Sleep timer", () -> {
            String[] options = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour"};
            new AlertDialog.Builder(this).setTitle("Sleep timer").setItems(options, (dialog, which) -> {
                long minutes = new long[]{0, 15, 30, 45, 60}[which];
                if (!library.prefs.edit().putLong("sleepDeadline", minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000).commit()) message("Could not save sleep timer.", true);
                renderPlayback();
            }).show();
        }));
        sleep = text("", 12, muted); content.addView(sleep);
        renderPlayback();
        if (PlaybackService.active != null && !PlaybackService.active.soundWarning.isEmpty()) content.addView(text(PlaybackService.active.soundWarning, 12, 0xffffaa95));
    }
    private interface IntAction { void run(int value); }
    private interface Label { String text(int value); }
    private void slider(String name, int min, int max, int selected, IntAction action, Label formatter) {
        TextView label = text(name + "   " + formatter.text(selected), 12, muted);
        label.setPadding(0, dp(12), 0, 0); content.addView(label);
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min); seek.setProgress(Math.max(0, Math.min(max - min, selected - min)));
        seek.setContentDescription(name);
        seek.setProgressTintList(ColorStateList.valueOf(gold)); seek.setThumbTintList(ColorStateList.valueOf(gold));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                label.setText(name + "   " + formatter.text(value + min));
                if (user) action.run(value + min);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        content.addView(seek, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    private void choice(String name, String[] options, int selected, IntAction action) {
        content.addView(button(name + ": " + options[selected], () ->
                new AlertDialog.Builder(this).setTitle(name).setItems(options, (dialog, which) -> { action.run(which); showTab(tab); }).show()));
    }
    private void setting(String key, int value) {
        library.prefs.edit().putInt(key, value).apply(); refreshSound();
    }
    private void setting(String key, boolean value) {
        library.prefs.edit().putBoolean(key, value).apply(); refreshSound();
    }
    private void refreshSound() {
        if (PlaybackService.active != null) {
            PlaybackService.active.refreshSound();
            if (!PlaybackService.active.soundWarning.isEmpty()) message(PlaybackService.active.soundWarning, false);
        }
    }

    private void settingsPage() {
        heading("Settings", "App data stays on your phone.");
        content.addView(button("Theme: " + library.prefs.getString("theme", "Spotify"), () -> {
            String[] themes = {"Spotify", "Midnight", "True black", "Daylight"};
            new AlertDialog.Builder(this).setTitle("Theme").setItems(themes, (dialog, which) -> {
                if (!library.prefs.edit().putString("theme", themes[which]).commit()) { message("Could not save theme.", true); return; }
                applyTheme(); buildShell();
            }).show();
        }));
        content.addView(button("Sound & equalizer", () -> showTab("Sound")));
        heading("Scan filters", "Filters apply on the next scan or import.");
        slider("Minimum duration", 0, 60, library.prefs.getInt("minDuration", 10), value -> library.prefs.edit().putInt("minDuration", value).apply(), value -> value + " seconds");
        slider("Minimum size", 0, 100, Math.round(library.prefs.getFloat("minSize", 0) * 10), value -> library.prefs.edit().putFloat("minSize", value / 10f).apply(), value -> value / 10f + " MB");
        content.addView(button("Rescan device", this::requestScan));
        heading("Backup", "Backups contain metadata and playlists, not audio files.");
        content.addView(button("Export backup", () -> {
            try { backupText = library.backup(); createBackup.launch("needle-groove-backup.json"); }
            catch (Exception error) { message(error.getMessage(), true); }
        }));
        content.addView(button("Restore backup", () -> restoreBackup.launch(new String[]{"application/json", "text/*", "application/octet-stream"})));
        content.addView(text("On another device, scan or import the original audio before restoring. Songs are matched by original title, artist, duration and size; unmatched or ambiguous songs are reported. Custom cover images and audio are not included. Imports live in private app storage; uninstalling removes those copies.", 12, muted));
        heading("Lyrics", "Embedded and selected matching .lrc files work offline.");
        heading("Privacy", "No ads. No accounts. No analytics. No internet permission.");
        content.addView(button("Privacy policy", () -> new AlertDialog.Builder(this).setTitle("Privacy")
                .setMessage("Music and lyrics stay on your device. The app has no internet permission. Music permission is used only to read your audio library. Imported audio, playlists, favorites and settings remain on your device. Backups are saved only where you choose. No advertising or analytics SDKs are included.")
                .setPositiveButton("Done", null).show()));
        content.addView(button("Open-source notices", () -> new AlertDialog.Builder(this).setTitle("Open-source notices")
                .setMessage("Embedded metadata uses jaudiotagger 3.0.0, LGPL 2.1 or later. Source: https://github.com/ijabz/jaudiotagger. License: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html. Publisher: complete the license and relinking review before distributing a release.")
                .setPositiveButton("Done", null).show()));
        content.addView(text("Needle & Groove 1.0.0\nLock-screen, Bluetooth and headset commands are handled by Android Media Session. Audio format support and effects depend on your device.", 12, muted));
        content.addView(button("App permissions", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
    }

    private void requestScan() {
        if (hasMusicPermission()) scan();
        else mediaPermission.launch(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE);
    }
    private boolean hasMusicPermission() {
        return checkSelfPermission(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private void observeMusic() {
        if (!observing) {
            getContentResolver().registerContentObserver(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, musicObserver);
            observing = true;
        }
    }
    private void scan() {
        if (scanning || library == null) return;
        scanning = true;
        boolean permission = hasMusicPermission();
        if (permission && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) observeMusic();
        message(permission ? "Scanning for songs..." : "Loading imported songs. Tap Scan this phone to grant music access.", false);
        runWork(() -> {
            library.scan(permission);
            return "Songs in your library: " + library.tracks.size();
        }, () -> { scanning = false; syncCurrent(); if (tab.equals("Library")) showTab(tab); });
    }
    private void importMusic() {
        if (library == null || scanning) { message("Wait for the library to finish loading.", false); return; }
        pickMusic.launch(new String[]{"*/*"});
    }
    private String importSelected(List<Uri> uris) throws Exception {
        int imported = 0;
        List<String> failures = new ArrayList<>();
        Map<String, List<Uri>> sidecars = new HashMap<>();
        Map<String, Integer> audioCounts = new HashMap<>();
        Map<Uri, String> names = new LinkedHashMap<>();
        for (Uri uri : uris) {
            try {
                String name = lyrics.name(uri);
                names.put(uri, name);
                if (Lyrics.sidecar(name)) sidecars.computeIfAbsent(Lyrics.basename(name), key -> new ArrayList<>()).add(uri);
                else audioCounts.merge(Lyrics.basename(name), 1, Integer::sum);
            } catch (IOException error) { failures.add(error.getMessage()); }
        }
        for (Map.Entry<Uri, String> entry : names.entrySet()) {
            if (Lyrics.sidecar(entry.getValue())) continue;
            try {
                Library.Track track = library.importFile(entry.getKey());
                imported++;
                String key = Lyrics.basename(entry.getValue());
                List<Uri> matches = sidecars.getOrDefault(key, List.of());
                Uri sidecar = Lyrics.pairedSidecar(entry.getValue(), sidecars, audioCounts);
                if (!matches.isEmpty() && sidecar == null) failures.add("Ambiguous lyrics for " + entry.getValue() + "; import them manually.");
                resolveLyrics(track, sidecar, failures);
            } catch (Exception error) { failures.add(error.getMessage()); }
        }
        if (imported == 0 && failures.isEmpty()) throw new IOException("Select at least one audio file with your lyrics.");
        if (imported == 0 && !failures.isEmpty()) throw new IOException(String.join("\n", failures));
        return "Songs imported: " + imported + (failures.isEmpty() ? "" : "\n" + String.join("\n", failures));
    }
    private interface Work { String run() throws Exception; }
    private void runWork(Work work, @Nullable Runnable finish) {
        worker.execute(() -> {
            String result; boolean error;
            try { result = work.run(); error = false; }
            catch (Exception exception) { result = exception.getMessage(); error = true; android.util.Log.e("NeedleGroove", "Operation failed", exception); }
            String message = result; boolean failed = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                message(message, failed);
                if (finish != null) finish.run();
            });
        });
    }

    private void resolveLyrics(Library.Track track, @Nullable Uri sidecar, List<String> warnings) {
        lyricsAttempted.add(track.id);
        if (!track.lyrics.isEmpty()) return;
        try {
            String embedded = lyrics.embedded(track);
            if (!embedded.isBlank() && library.saveLyricsIfUnchanged(track, "", embedded)) return;
        } catch (Exception error) {
            android.util.Log.w("NeedleGroove", "Embedded lyrics failed for " + track.title, error);
            warnings.add("Embedded lyrics for " + track.title + ": " + error.getMessage());
        }
        if (!track.lyrics.isEmpty()) return;
        if (sidecar != null) {
            try {
                String text = lyrics.text(sidecar);
                if (!text.isBlank() && library.saveLyricsIfUnchanged(track, "", text)) return;
                if (text.isBlank()) warnings.add("The .lrc file for " + track.title + " is empty.");
            } catch (Exception error) {
                android.util.Log.w("NeedleGroove", "Sidecar lyrics failed for " + track.title, error);
                warnings.add("Lyrics for " + track.title + ": " + error.getMessage());
            }
        }
    }

    private void showLyrics() {
        if (current == null) { message("Choose a song first.", false); return; }
        Library.Track track = current;
        LinearLayout words = column(); words.setPadding(dp(24), dp(12), dp(24), dp(24));
        words.setBackgroundColor(surface);
        timedLyrics = QueueRules.lyrics(track.lyrics);
        lyricLines.clear(); activeLyric = -2;
        if (track.lyrics.isEmpty()) {
            words.addView(text("No lyrics found yet. Import an .lrc or .txt file.", 16, muted));
        } else if (timedLyrics.isEmpty()) {
            lyricText = serif(track.lyrics, 23); words.addView(lyricText);
        } else for (QueueRules.Line line : timedLyrics) {
            TextView text = serif(line.text(), 23); text.setTextColor(muted);
            text.setPadding(0, dp(12), 0, dp(12)); lyricLines.add(text); words.addView(text);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(words);
        lyricsDialog = new AlertDialog.Builder(this).setTitle(track.title).setView(scroll)
                .setPositiveButton("Close", null)
                .setNeutralButton("Import", (dialog, which) -> { lyricTarget = track; pickLyrics.launch(new String[]{"text/*", "application/octet-stream"}); })
                .create();
        lyricsDialog.setOnDismissListener(dialog -> { lyricsDialog = null; timedLyrics = List.of(); lyricLines.clear(); });
        lyricsDialog.show(); updateLyrics();
    }
    private void updateLyrics() {
        if (lyricsDialog == null || controller == null || timedLyrics.isEmpty()) return;
        int index = QueueRules.activeLyric(timedLyrics, controller.getCurrentPosition());
        if (index == activeLyric) return;
        activeLyric = index;
        for (int i = 0; i < lyricLines.size(); i++) lyricLines.get(i).setTextColor(i == index ? gold : muted);
        if (index >= 0) lyricLines.get(index).getParent().requestChildFocus(lyricLines.get(index), lyricLines.get(index));
    }

    private String readText(Uri uri, int limit) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Cannot open the selected file.");
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new IOException("That file is too large.");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private Bitmap decodeArtwork(@Nullable byte[] art) {
        if (art == null) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(art, 0, art.length, options);
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 768 || options.outHeight / options.inSampleSize > 768) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(art, 0, art.length, options);
    }
    private void heading(String heading, @Nullable String subtitle) {
        TextView title = serif(heading, 28); title.setPadding(0, dp(8), 0, dp(subtitle == null ? 16 : 4)); content.addView(title);
        if (subtitle != null) {
            TextView copy = text(subtitle, 12, muted); copy.setPadding(0, 0, 0, dp(16)); content.addView(copy);
        }
    }
    private EditText labeledInput(LinearLayout parent, String label, String value) {
        parent.addView(text(label, 12, muted)); EditText input = input(label, value); parent.addView(input); return input;
    }
    private EditText input(String hint, String value) {
        EditText input = new EditText(this); input.setText(value); input.setHint(hint);
        input.setTextSize(14); input.setTextColor(ink); input.setHintTextColor(muted);
        input.setPadding(dp(12), dp(8), dp(12), dp(8)); input.setMinHeight(dp(48));
        input.setBackground(rounded(surface, 10)); return input;
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL); return text;
    }
    private TextView serif(String value, int size) { TextView text = text(value, size, ink); text.setTypeface(Typeface.create("serif", Typeface.NORMAL)); return text; }
    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setTextSize(12); button.setTextColor(ink); button.setMinWidth(0);
        button.setMinimumWidth(0); button.setMinHeight(dp(48)); button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setBackgroundTintList(null); button.setBackground(rounded(surface, 12));
        button.setOnClickListener(view -> {
            view.animate().scaleX(.96f).scaleY(.96f).setDuration(65).withEndAction(() ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(95).start()).start();
            action.run();
        }); return button;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void message(@Nullable String message, boolean error) {
        if (status == null) return;
        status.setText(message == null ? "An operation failed. Please try again." : message);
        status.setTextColor(error ? Color.rgb(235, 111, 95) : muted);
    }
    private static String formatTime(long millis) { long seconds = Math.max(0, millis) / 1000; return seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60); }
}
