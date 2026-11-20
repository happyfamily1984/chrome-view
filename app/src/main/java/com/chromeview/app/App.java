package com.chromeview.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class App extends Application {
    static final String CHANNEL = "keepalive";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "백그라운드 실행 유지", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("다른 앱을 쓰는 동안에도 Chrome의 탭과 로그인 상태를 유지합니다");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
}
