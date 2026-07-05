package com.mineradio.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

public class MineradioPlaybackService extends Service {
    public static final String ACTION_UPDATE = "com.mineradio.android.action.PLAYBACK_UPDATE";
    public static final String ACTION_CONTROL = "com.mineradio.android.action.MEDIA_CONTROL";
    public static final String ACTION_STOP = "com.mineradio.android.action.STOP_PLAYBACK_SERVICE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_CONTROL = "control";
    public static final String CONTROL_TOGGLE = "toggle";
    public static final String CONTROL_PLAY = "play";
    public static final String CONTROL_PAUSE = "pause";
    public static final String CONTROL_NEXT = "next";
    public static final String CONTROL_PREVIOUS = "previous";

    private static final String CHANNEL_ID = "mineradio_playback";
    private static final int NOTIFICATION_ID = 1207;

    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private PlaybackSnapshot snapshot = new PlaybackSnapshot();
    private boolean foreground;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        mediaSession = new MediaSession(this, "Mineradio");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { sendControl(CONTROL_PLAY); }
            @Override public void onPause() { sendControl(CONTROL_PAUSE); }
            @Override public void onSkipToNext() { sendControl(CONTROL_NEXT); }
            @Override public void onSkipToPrevious() { sendControl(CONTROL_PREVIOUS); }
            @Override public void onStop() { sendControl(CONTROL_PAUSE); }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPlaybackService();
            return START_NOT_STICKY;
        }
        if (ACTION_CONTROL.equals(action)) {
            sendControl(intent.getStringExtra(EXTRA_CONTROL));
            return START_NOT_STICKY;
        }
        if (ACTION_UPDATE.equals(action)) {
            snapshot = PlaybackSnapshot.fromJson(intent.getStringExtra(EXTRA_STATE));
            if (snapshot.isEmpty()) {
                stopPlaybackService();
                return START_NOT_STICKY;
            }
            updateMediaSession();
            updateNotification();
            return START_STICKY;
        }
        updateNotification();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    private void stopPlaybackService() {
        if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        foreground = false;
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26 || notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Mineradio playback",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mineradio media controls");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, emptyToDefault(snapshot.title, "Mineradio"))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, snapshot.album);
        if (snapshot.durationMs > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs);
        mediaSession.setMetadata(metadata.build());

        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP;
        int state = snapshot.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, snapshot.positionMs, snapshot.playing ? 1.0f : 0.0f)
                .build());
    }

    private void updateNotification() {
        if (snapshot.isEmpty() && !foreground) return;
        Notification notification = buildNotification();
        if (snapshot.playing) {
            startForeground(NOTIFICATION_ID, notification);
            foreground = true;
        } else {
            if (foreground) {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
                else stopForeground(false);
                foreground = false;
            }
            if (notificationManager != null) notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setPackage(getPackageName());
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 1, launchIntent, pendingIntentFlags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(com.mineradio.android.R.drawable.ic_notification)
                .setContentTitle(emptyToDefault(snapshot.title, "Mineradio"))
                .setContentText(emptyToDefault(snapshot.artist, snapshot.playing ? "正在播放" : "已暂停"))
                .setSubText(snapshot.provider)
                .setContentIntent(contentIntent)
                .setOngoing(snapshot.playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "上一首", controlIntent(CONTROL_PREVIOUS, 2))
                .addAction(snapshot.playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        snapshot.playing ? "暂停" : "播放",
                        controlIntent(CONTROL_TOGGLE, 3))
                .addAction(android.R.drawable.ic_media_next, "下一首", controlIntent(CONTROL_NEXT, 4));
        if (Build.VERSION.SDK_INT >= 21 && mediaSession != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    private PendingIntent controlIntent(String control, int requestCode) {
        Intent intent = new Intent(this, MineradioPlaybackService.class);
        intent.setAction(ACTION_CONTROL);
        intent.putExtra(EXTRA_CONTROL, control);
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags());
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private void sendControl(String control) {
        if (control == null || control.trim().isEmpty()) return;
        Intent intent = new Intent(ACTION_CONTROL);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_CONTROL, control);
        sendBroadcast(intent);
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static class PlaybackSnapshot {
        String title = "";
        String artist = "";
        String album = "";
        String provider = "";
        boolean playing;
        long durationMs;
        long positionMs;

        static PlaybackSnapshot fromJson(String json) {
            PlaybackSnapshot out = new PlaybackSnapshot();
            try {
                JSONObject root = new JSONObject(json == null || json.trim().isEmpty() ? "{}" : json);
                out.title = root.optString("title", "");
                out.artist = root.optString("artist", "");
                out.album = root.optString("album", "");
                out.provider = root.optString("provider", "");
                out.playing = root.optBoolean("playing", false);
                out.durationMs = secondsToMs(root.optDouble("duration", 0));
                out.positionMs = secondsToMs(root.optDouble("position", 0));
            } catch (Exception ignored) {
            }
            return out;
        }

        boolean isEmpty() {
            return title.trim().isEmpty() && artist.trim().isEmpty() && !playing;
        }

        private static long secondsToMs(double seconds) {
            if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0) return 0;
            return Math.max(0, Math.round(seconds * 1000.0));
        }
    }
}
