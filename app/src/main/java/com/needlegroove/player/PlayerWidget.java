package com.needlegroove.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import androidx.media3.common.Player;

@androidx.media3.common.util.UnstableApi
public final class PlayerWidget extends AppWidgetProvider {
    static final String TOGGLE = "com.needlegroove.player.TOGGLE";
    static final String PREVIOUS = "com.needlegroove.player.PREVIOUS";
    static final String NEXT = "com.needlegroove.player.NEXT";
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { update(context, null); }
    static void update(Context context, Player player) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, PlayerWidget.class));
        for (int id : ids) {
            RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.player_widget);
            CharSequence title = player == null ? context.getString(R.string.app_name) : player.getMediaMetadata().title;
            view.setTextViewText(R.id.widget_title, title == null ? context.getString(R.string.app_name) : title);
            view.setTextViewText(R.id.widget_play, player != null && player.isPlaying() ? "\u2161" : "\u25B6");
            PendingIntent open = PendingIntent.getActivity(context, 4, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            view.setOnClickPendingIntent(R.id.widget_title, open);
            String[] actions = {PREVIOUS, TOGGLE, NEXT};
            int[] buttons = {R.id.widget_previous, R.id.widget_play, R.id.widget_next};
            for (int i = 0; i < actions.length; i++) {
                Intent action = new Intent(context, PlaybackService.class).setAction(actions[i])
                        .putExtra("widgetToken", PlaybackService.widgetToken(context));
                PendingIntent intent = PendingIntent.getForegroundService(context, i, action, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                view.setOnClickPendingIntent(buttons[i], player == null ? open : intent);
            }
            manager.updateAppWidget(id, view);
        }
    }
}
