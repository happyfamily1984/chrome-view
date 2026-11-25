package com.chromeview.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

public class App extends Application {
    static final String CHANNEL = "keepalive";
    static final String MEDIA_CHANNEL = "media";
    private static TabManager tabs;

    @Override
    public void onCreate() {
        super.onCreate();
        Diag.log(this, "앱 프로세스 시작 (이전에 완전히 꺼졌었음)");
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "백그라운드 실행 유지", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("다른 앱을 쓰는 동안에도 열린 페이지와 로그인 상태를 유지합니다");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        NotificationChannel media = new NotificationChannel(
                MEDIA_CHANNEL, "미디어 재생", NotificationManager.IMPORTANCE_LOW);
        media.setDescription("페이지에서 재생 중인 영상·음악을 알림창에서 조절합니다");
        media.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(media);
        // 앱이 꺼졌다 켜졌으면 예전 재생 알림은 더 이상 조절할 수 없으니 내린다.
        getSystemService(NotificationManager.class).cancel(MediaHub.NOTIFICATION_ID);
    }

    /** 탭은 화면(Activity)이 아니라 프로세스에 묶여 있어, 화면이 닫혀도 페이지가 살아 있다. */
    static TabManager tabs(Context c) {
        if (tabs == null) {
            tabs = new TabManager(c.getApplicationContext());
            tabs.load();
        }
        return tabs;
    }
}
