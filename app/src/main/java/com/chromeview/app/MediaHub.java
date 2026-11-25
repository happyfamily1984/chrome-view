package com.chromeview.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONObject;

/**
 * 알림창·잠금화면의 재생 조절. 페이지 안의 영상(iframe 안 포함)이 재생/일시정지될 때 보내는 신호를 받아
 * 미디어 알림을 띄우고, 알림의 버튼을 누르면 그 영상이 있는 프레임에 명령을 돌려보낸다.
 */
final class MediaHub {
    static final int NOTIFICATION_ID = 2;
    static final String EXTRA_CMD = "cmd";

    private static MediaSession session;
    private static JavaScriptReplyProxy proxy;
    private static long tabId = -1;
    private static boolean playing, incognito;
    private static String title = "", artist = "";
    private static long durationMs = -1, positionMs = -1;
    private static Bitmap art;

    private MediaHub() {
    }

    /** 페이지가 보낸 재생 상태. {s: play|pause|meta, t: 제목, a: 부제, d: 길이(초), p: 위치(초)} */
    static void onReport(Context c, Tab tab, JavaScriptReplyProxy from, String json) {
        try {
            JSONObject o = new JSONObject(json);
            String s = o.optString("s");
            boolean same = tab.id == tabId && from == proxy;
            if ("play".equals(s)) {
                tabId = tab.id;
                proxy = from;
                playing = true;
            } else if (!same) {
                return; // 다른 영상이 멈춘 것은 상관없다
            } else if ("pause".equals(s)) {
                playing = false;
            }
            incognito = tab.incognito;
            title = o.optString("t").trim();
            if (title.isEmpty()) title = tab.title;
            artist = o.optString("a");
            double d = o.optDouble("d"), p = o.optDouble("p");
            durationMs = Double.isNaN(d) || Double.isInfinite(d) || d <= 0 ? -1 : (long) (d * 1000);
            positionMs = Double.isNaN(p) || p < 0 ? -1 : (long) (p * 1000);
            publish(c.getApplicationContext());
        } catch (Exception ignored) {
        }
    }

    /** 알림 버튼·이어폰 버튼 명령: play, pause, next, prev, seek:밀리초, close */
    static void command(Context c, String cmd) {
        if (cmd == null) return;
        if ("close".equals(cmd)) {
            clear(c, true);
            return;
        }
        if (proxy == null) {
            clear(c, false); // 앱이 다시 시작돼 대상 영상이 없다
            return;
        }
        try {
            proxy.postMessage(cmd);
        } catch (RuntimeException e) {
            clear(c, false);
            return;
        }
        // 페이지의 응답을 기다리는 동안 버튼 모양부터 바꿔 둔다.
        if ("play".equals(cmd) || "pause".equals(cmd)) {
            playing = "play".equals(cmd);
            publish(c.getApplicationContext());
        }
    }

    /** 탭이 닫히거나 다른 페이지로 넘어가면 그 탭의 알림을 내린다. */
    static void forgetTab(Context c, long id) {
        if (id == tabId) clear(c, false);
    }

    static void clear(Context c, boolean pausePage) {
        if (pausePage && proxy != null) {
            try {
                proxy.postMessage("pause");
            } catch (RuntimeException ignored) {
            }
        }
        proxy = null;
        tabId = -1;
        playing = false;
        if (session != null) session.setActive(false);
        c.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    private static MediaSession session(Context c) {
        if (session == null) {
            session = new MediaSession(c, "크롬뷰");
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    command(c, "play");
                }

                @Override
                public void onPause() {
                    command(c, "pause");
                }

                @Override
                public void onSkipToNext() {
                    command(c, "next");
                }

                @Override
                public void onSkipToPrevious() {
                    command(c, "prev");
                }

                @Override
                public void onSeekTo(long pos) {
                    command(c, "seek:" + pos);
                }

                @Override
                public void onStop() {
                    command(c, "close");
                }

                @Override
                public void onCustomAction(String action, android.os.Bundle extras) {
                    if ("close".equals(action)) command(c, "close");
                }
            });
            Intent open = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_OPEN);
            session.setSessionActivity(PendingIntent.getActivity(c, 20, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return session;
    }

    private static void publish(Context c) {
        MediaSession ms = session(c);
        if (art == null) art = BitmapFactory.decodeResource(c.getResources(), R.drawable.logo);
        String shownTitle = incognito ? "시크릿 탭에서 재생 중" : (title.isEmpty() ? "재생 중" : title);
        String shownArtist = incognito ? "크롬뷰" : artist;

        ms.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                        | (durationMs > 0 ? PlaybackState.ACTION_SEEK_TO : 0))
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        positionMs >= 0 ? positionMs : PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        "close", "닫기", R.drawable.ic_n_close).build())
                .build());
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, shownTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, shownArtist)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        if (durationMs > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
        ms.setMetadata(meta.build());
        ms.setActive(true);

        Intent open = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_OPEN)
                .putExtra(MainActivity.EXTRA_TAB, tabId);
        Notification n = new Notification.Builder(c, App.MEDIA_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setLargeIcon(art)
                .setContentTitle(shownTitle)
                .setContentText(shownArtist)
                .setContentIntent(PendingIntent.getActivity(c, 21, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(action(c, "close", 0))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(incognito ? Notification.VISIBILITY_PRIVATE : Notification.VISIBILITY_PUBLIC)
                .addAction(button(c, R.drawable.ic_n_prev, "이전", "prev", 1))
                .addAction(playing ? button(c, R.drawable.ic_n_pause, "일시정지", "pause", 2)
                        : button(c, R.drawable.ic_n_play, "재생", "play", 3))
                .addAction(button(c, R.drawable.ic_n_next, "다음", "next", 4))
                .addAction(button(c, R.drawable.ic_n_close, "닫기", "close", 5))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(ms.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, n);
        } catch (RuntimeException ignored) {
            // 알림 권한이 꺼져 있으면 표시되지 않는다
        }
    }

    private static PendingIntent action(Context c, String cmd, int code) {
        Intent i = new Intent(c, MediaActionReceiver.class).putExtra(EXTRA_CMD, cmd);
        return PendingIntent.getBroadcast(c, 30 + code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification.Action button(Context c, int icon, String label, String cmd, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(c, icon), label,
                action(c, cmd, code)).build();
    }
}
