package com.needlegroove.player;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

@androidx.media3.common.util.UnstableApi
public final class PlaybackService extends MediaSessionService {
    static PlaybackService active;
    private ExoPlayer player;
    private ExoPlayer fading;
    private MediaSession session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Effects effects, fadeEffects;
    private long fadeStart, fadeLength;
    private String countedId = "";
    String soundWarning = "";
    private SharedPreferences prefs;
    private long lastSave;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, player.isPlaying() || fading != null ? 100 : 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        prefs = getSharedPreferences("library", MODE_PRIVATE);
        player = createPlayer();
        effects = new Effects();
        PendingIntent intent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, player).setSessionActivity(intent)
                .setCallback(new MediaSession.Callback() {
                    @Override public MediaSession.ConnectionResult onConnect(MediaSession mediaSession, MediaSession.ControllerInfo info) {
                        if (info.isTrusted() || info.getUid() == getApplicationInfo().uid) return MediaSession.Callback.super.onConnect(mediaSession, info);
                        return MediaSession.ConnectionResult.reject();
                    }
                }).build();
        restoreQueue();
        handler.post(ticker);
    }

    private ExoPlayer createPlayer() {
        ExoPlayer result = new ExoPlayer.Builder(this).build();
        result.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        result.setHandleAudioBecomingNoisy(true);
        result.setWakeMode(C.WAKE_MODE_LOCAL);
        result.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onAudioSessionIdChanged(EventTime eventTime, int id) {
                if (result == player && effects != null) effects.attach(id, result);
                else if (result == fading && fadeEffects != null) fadeEffects.attach(id, result);
            }
        });
        result.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                if (result != player) return;
                if (!playing && fading != null) cancelFade();
                if (playing) countPlay(result.getCurrentMediaItem());
                if (!playing) savePosition();
                PlayerWidget.update(PlaybackService.this, player);
            }
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                if (result == player) {
                    cancelFade();
                    countedId = "";
                    if (player.isPlaying()) countPlay(item);
                    PlayerWidget.update(PlaybackService.this, player);
                }
            }
            @Override public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) {
                if (result == player && session != null) saveQueue();
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (result == fading) cancelFade();
                android.util.Log.e("NeedleGroove", "Audio playback failed", error);
            }
        });
        return result;
    }

    private void countPlay(@Nullable MediaItem item) {
        if (item == null || item.mediaId.equals(countedId)) return;
        try {
            JSONObject plays = new JSONObject(prefs.getString("plays", "{}"));
            plays.put(item.mediaId, plays.optInt(item.mediaId) + 1);
            if (!prefs.edit().putString("plays", plays.toString()).commit()) throw new java.io.IOException("Could not save play count.");
            countedId = item.mediaId;
        } catch (Exception error) {
            android.util.Log.e("NeedleGroove", "Could not save listening history", error);
            soundWarning = "Listening history could not be saved.";
        }
    }

    private void tick() {
        if (player.isPlaying() && android.os.SystemClock.elapsedRealtime() - lastSave > 5000) {
            savePosition();
            lastSave = android.os.SystemClock.elapsedRealtime();
        }
        long deadline = prefs.getLong("sleepDeadline", 0);
        if (deadline > 0 && System.currentTimeMillis() >= deadline) {
            cancelFade();
            player.pause();
            prefs.edit().remove("sleepDeadline").apply();
            return;
        }

        if (fading != null) {
            if (!player.isPlaying()) { cancelFade(); return; }
            if (!fading.isPlaying()) return;
            if (fadeStart == 0) fadeStart = android.os.SystemClock.elapsedRealtime();
            float progress = Math.min(1, (android.os.SystemClock.elapsedRealtime() - fadeStart) / (float) fadeLength);
            player.setVolume(1 - progress);
            fading.setVolume(progress);
            if (progress >= 1) {
                ExoPlayer old = player;
                Effects oldEffects = effects;
                player = fading;
                effects = fadeEffects;
                fading = null;
                fadeEffects = null;
                player.setVolume(1);
                countedId = "";
                session.setPlayer(player);
                player.setAudioAttributes(player.getAudioAttributes(), true);
                countPlay(player.getCurrentMediaItem());
                PlayerWidget.update(this, player);
                old.release();
                oldEffects.release();
            }
            return;
        }
        int seconds = prefs.getInt("crossfade", 0);
        long duration = player.getDuration(), position = player.getCurrentPosition();
        int next = player.getNextMediaItemIndex();
        if (seconds > 0 && player.isPlaying() && player.getRepeatMode() != Player.REPEAT_MODE_ONE
                && duration != C.TIME_UNSET && duration > seconds * 2000L && position > 0
                && duration - position <= seconds * 1000L && next != C.INDEX_UNSET) {
            fadeLength = Math.max(100, duration - position);
            fadeStart = 0;
            fading = createPlayer();
            fadeEffects = new Effects();
            // The outgoing player owns focus until the session hands over to the incoming one.
            fading.setAudioAttributes(player.getAudioAttributes(), false);
            List<MediaItem> queue = new ArrayList<>();
            for (int i = 0; i < player.getMediaItemCount(); i++) queue.add(player.getMediaItemAt(i));
            fading.setMediaItems(queue, next, 0);
            fading.setRepeatMode(player.getRepeatMode());
            fading.setShuffleModeEnabled(player.getShuffleModeEnabled());
            fading.setVolume(0);
            fading.prepare();
            fading.play();
        }
    }

    private void saveQueue() {
        try {
            org.json.JSONArray items = new org.json.JSONArray();
            for (int i = 0; i < player.getMediaItemCount(); i++) {
                MediaItem item = player.getMediaItemAt(i);
                if (item.localConfiguration != null) items.put(new JSONObject().put("id", item.mediaId)
                        .put("uri", item.localConfiguration.uri.toString()).put("title", String.valueOf(item.mediaMetadata.title))
                        .put("artist", String.valueOf(item.mediaMetadata.artist)).put("album", String.valueOf(item.mediaMetadata.albumTitle)));
            }
            prefs.edit().putString("queue", items.toString()).apply();
            savePosition();
        } catch (org.json.JSONException error) {
            android.util.Log.e("NeedleGroove", "Queue could not be saved", error);
            soundWarning = "Playback position could not be saved.";
        }
    }

    private void savePosition() {
        if (prefs != null) prefs.edit().putInt("queueIndex", player.getCurrentMediaItemIndex())
                .putLong("queuePosition", player.getCurrentPosition()).putInt("repeatMode", player.getRepeatMode())
                .putBoolean("shuffleMode", player.getShuffleModeEnabled()).apply();
    }

    private void restoreQueue() {
        try {
            org.json.JSONArray items = new org.json.JSONArray(prefs.getString("queue", "[]"));
            List<MediaItem> queue = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                queue.add(new MediaItem.Builder().setMediaId(item.getString("id")).setUri(item.getString("uri"))
                        .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder().setTitle(item.getString("title"))
                                .setArtist(item.getString("artist")).setAlbumTitle(item.getString("album")).build()).build());
            }
            int mode = prefs.getInt("repeatMode", Player.REPEAT_MODE_OFF);
            boolean shuffle = prefs.getBoolean("shuffleMode", false);
            if (!queue.isEmpty()) player.setMediaItems(queue, Math.max(0, Math.min(queue.size() - 1, prefs.getInt("queueIndex", 0))),
                    Math.max(0, prefs.getLong("queuePosition", 0)));
            player.setRepeatMode(Math.max(Player.REPEAT_MODE_OFF, Math.min(Player.REPEAT_MODE_ALL, mode)));
            player.setShuffleModeEnabled(shuffle);
        } catch (org.json.JSONException error) {
            android.util.Log.e("NeedleGroove", "Saved queue is invalid", error);
            soundWarning = "Saved queue could not be restored. Select music from your library.";
        }
    }

    private void cancelFade() {
        if (fading != null) { fading.release(); fading = null; }
        if (fadeEffects != null) { fadeEffects.release(); fadeEffects = null; }
        if (player != null) player.setVolume(1);
        fadeStart = 0;
    }

    void refreshSound() {
        soundWarning = "";
        if (prefs.getInt("crossfade", 0) == 0) cancelFade();
        effects.apply();
        if (fadeEffects != null) fadeEffects.apply();
    }

    short[] bandRange() { return effects.eq == null ? new short[]{-1500, 1500} : effects.eq.getBandLevelRange(); }
    int bandCount() { return effects.eq == null ? 5 : effects.eq.getNumberOfBands(); }
    int bandFrequency(short band) {
        return effects.eq == null ? new int[]{60, 230, 910, 3600, 14000}[band] : effects.eq.getCenterFreq(band) / 1000;
    }

    @Override @Nullable public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        int result = super.onStartCommand(intent, flags, startId);
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (List.of(PlayerWidget.TOGGLE, PlayerWidget.NEXT, PlayerWidget.PREVIOUS).contains(action)
                    && widgetToken(this).equals(intent.getStringExtra("widgetToken"))) {
                if (player.getMediaItemCount() == 0) {
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    stopSelf();
                } else {
                    if (action.equals(PlayerWidget.TOGGLE)) {
                        if (player.isPlaying()) player.pause(); else { player.prepare(); player.play(); }
                    } else if (action.equals(PlayerWidget.NEXT)) player.seekToNextMediaItem();
                    else player.seekToPrevious();
                }
            }
        }
        return result;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveQueue();
        cancelFade();
        session.release();
        player.release();
        effects.release();
        PlayerWidget.update(this, null);
        active = null;
        super.onDestroy();
    }

    static String widgetToken(android.content.Context context) {
        SharedPreferences preferences = context.getSharedPreferences("widget", MODE_PRIVATE);
        String token = preferences.getString("token", null);
        if (token == null) {
            token = java.util.UUID.randomUUID().toString();
            if (!preferences.edit().putString("token", token).commit()) throw new IllegalStateException("Could not save widget authorization.");
        }
        return token;
    }

    private final class Effects {
        Equalizer eq;
        BassBoost bass;
        Virtualizer virtualizer;
        PresetReverb reverb;
        ExoPlayer target;
        void attach(int audioSession, ExoPlayer target) {
            release();
            this.target = target;
            try { eq = new Equalizer(0, audioSession); }
            catch (RuntimeException error) { warn("Equalizer", error); }
            try { bass = new BassBoost(0, audioSession); }
            catch (RuntimeException error) { warn("Bass boost", error); }
            try { virtualizer = new Virtualizer(0, audioSession); }
            catch (RuntimeException error) { warn("Spatial effect", error); }
            try {
                reverb = new PresetReverb(0, 0);
                target.setAuxEffectInfo(new AuxEffectInfo(reverb.getId(), .25f));
            } catch (RuntimeException error) { warn("Reverb", error); }
            apply();
        }
        void warn(String feature, RuntimeException error) {
            android.util.Log.w("NeedleGroove", feature + " unavailable", error);
            soundWarning = feature + " is unavailable on this device/audio route.";
        }
        void apply() {
            try {
                if (eq != null) {
                    eq.setEnabled(prefs.getBoolean("eq", false));
                    short[] range = eq.getBandLevelRange();
                    for (short band = 0; band < eq.getNumberOfBands(); band++) {
                        int level = prefs.getInt("band" + band, 0);
                        eq.setBandLevel(band, (short) Math.max(range[0], Math.min(range[1], level)));
                    }
                }
                if (bass != null) {
                    int strength = prefs.getInt("bass", 0);
                    if (bass.getStrengthSupported()) bass.setStrength((short) strength);
                    else if (strength > 0) soundWarning = "Adjustable bass boost is unavailable on this device.";
                    bass.setEnabled(strength > 0);
                }
                if (virtualizer != null) {
                    int strength = prefs.getInt("spatial", 0);
                    if (virtualizer.getStrengthSupported()) virtualizer.setStrength((short) strength);
                    else if (strength > 0) soundWarning = "Adjustable spatial effect is unavailable on this device.";
                    virtualizer.setEnabled(strength > 0);
                }
                if (reverb != null) {
                    int mode = prefs.getInt("reverb", 0);
                    reverb.setPreset(mode == 1 ? PresetReverb.PRESET_SMALLROOM : mode == 2 ? PresetReverb.PRESET_LARGEHALL : PresetReverb.PRESET_NONE);
                    reverb.setEnabled(mode > 0);
                }
            } catch (RuntimeException error) { warn("Sound processing", error); }
        }
        void release() {
            if (eq != null) { eq.release(); eq = null; }
            if (bass != null) { bass.release(); bass = null; }
            if (virtualizer != null) { virtualizer.release(); virtualizer = null; }
            if (reverb != null) { reverb.release(); reverb = null; }
        }
    }
}
