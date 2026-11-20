package com.chromeview.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

public class App extends Application {
    static final String CHANNEL = "keepalive";
    private static TabManager tabs;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "백그라운드 실행 유지", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("다른 앱을 쓰는 동안에도 열린 페이지와 로그인 상태를 유지합니다");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
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
