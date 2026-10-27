package com.maxplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * Служба переднего плана: держит процесс живым, пока играет музыка с выключенным экраном,
 * и показывает уведомление с кнопками. Сам звук играет страница в WebView.
 */
public class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;

    static final String ACTION_TOGGLE = "com.maxplayer.app.TOGGLE";
    static final String ACTION_NEXT = "com.maxplayer.app.NEXT";
    static final String ACTION_PREV = "com.maxplayer.app.PREV";
    static final String ACTION_QUIT = "com.maxplayer.app.QUIT";

    private static PlaybackService instance;
    private static String sTitle = "Max Player";
    private static String sArtist = "";
    private static boolean sPlaying;
    private static long sPosMs;
    private static long sDurMs;

    private NotificationManager nm;
    private MediaSession session;

    /** Вызывается из главного потока, когда страница сообщает о смене трека или состояния. */
    static void update(Context ctx, boolean playing, String title, String artist, long posMs, long durMs) {
        sTitle = (title == null || title.isEmpty()) ? "Max Player" : title;
        sArtist = artist == null ? "" : artist;
        sPlaying = playing;
        sPosMs = posMs;
        sDurMs = durMs;
        PlaybackService s = instance;
        if (s != null) {
            s.refresh();
            return;
        }
        if (!playing) return;
        try {
            ctx.startForegroundService(new Intent(ctx, PlaybackService.class));
        } catch (Exception e) {
            // система не разрешила запуск из фона; музыка продолжит играть, пока жив процесс
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW));

        session = new MediaSession(this, "MaxPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.command("play"); }
            @Override public void onPause() { MainActivity.command("pause"); }
            @Override public void onStop() { MainActivity.command("pause"); }
            @Override public void onSkipToNext() { MainActivity.command("next"); }
            @Override public void onSkipToPrevious() { MainActivity.command("prev"); }
            @Override public void onSeekTo(long pos) { MainActivity.command("seek:" + (pos / 1000.0)); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_QUIT.equals(action)) {
            MainActivity.quit();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) MainActivity.command("toggle");
        else if (ACTION_NEXT.equals(action)) MainActivity.command("next");
        else if (ACTION_PREV.equals(action)) MainActivity.command("prev");

        try {
            updateSession();
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void refresh() {
        updateSession();
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void updateSession() {
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, sTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sArtist);
        if (sDurMs > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, sDurMs);
        session.setMetadata(meta.build());

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(sPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        sPosMs, sPlaying ? 1f : 0f)
                .build());
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(sTitle)
                .setContentText(sArtist.isEmpty() ? getString(R.string.app_name) : sArtist)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_prev, "Назад", ACTION_PREV, 1))
                .addAction(action(sPlaying ? R.drawable.ic_pause : R.drawable.ic_play,
                        sPlaying ? "Пауза" : "Играть", ACTION_TOGGLE, 2))
                .addAction(action(R.drawable.ic_next, "Вперёд", ACTION_NEXT, 3))
                .addAction(action(R.drawable.ic_close, "Закрыть", ACTION_QUIT, 4))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private Notification.Action action(int icon, String label, String action, int requestCode) {
        PendingIntent pi = PendingIntent.getService(this, requestCode,
                new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (session != null) session.release();
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
