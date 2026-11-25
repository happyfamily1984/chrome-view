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
        Diag.log(this, "앱 프로세스 시작 (이전에 완전히 꺼졌었음)" + lastExitReason());
        // 앱 오류로 꺼질 때 어디서 났는지 남긴다.
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try {
                StackTraceElement[] st = ex.getStackTrace();
                Diag.logNow(this, "앱 오류로 꺼짐: " + ex + (st.length > 0 ? " @ " + st[0] : ""));
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(th, ex);
        });
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

    /** 안드로이드가 기록해 둔, 지난번에 크롬뷰가 꺼진 이유. */
    private String lastExitReason() {
        if (android.os.Build.VERSION.SDK_INT < 30) return "";
        try {
            java.util.List<android.app.ApplicationExitInfo> list = getSystemService(android.app.ActivityManager.class)
                    .getHistoricalProcessExitReasons(getPackageName(), 0, 1);
            if (list.isEmpty()) return "";
            android.app.ApplicationExitInfo e = list.get(0);
            String why;
            switch (e.getReason()) {
                case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: why = "메모리 부족"; break;
                case android.app.ApplicationExitInfo.REASON_CRASH: why = "앱 오류"; break;
                case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: why = "앱 오류(네이티브)"; break;
                case android.app.ApplicationExitInfo.REASON_ANR: why = "응답 없음"; break;
                case android.app.ApplicationExitInfo.REASON_USER_REQUESTED: why = "사용자·시스템이 강제 종료"; break;
                case android.app.ApplicationExitInfo.REASON_USER_STOPPED: why = "사용자가 중지"; break;
                case android.app.ApplicationExitInfo.REASON_SIGNALED: why = "시스템이 종료 신호"; break;
                case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: why = "자원 과다 사용"; break;
                case android.app.ApplicationExitInfo.REASON_EXIT_SELF: why = "스스로 종료"; break;
                case android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED: why = "연결된 앱이 꺼짐"; break;
                case android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE: why = "권한 변경"; break;
                case android.app.ApplicationExitInfo.REASON_FREEZER: why = "시스템이 얼림"; break;
                default: why = "기타(" + e.getReason() + ")";
            }
            String desc = e.getDescription() == null ? "" : " " + e.getDescription();
            if (desc.length() > 60) desc = desc.substring(0, 60) + "…";
            return " · 이유: " + why + desc + " · 메모리 " + (e.getPss() / 1024) + "MB";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // 휴대폰 메모리가 모자라다는 경고: 크롬뷰가 통째로 꺼지기 전에 오래 안 본 탭부터 내린다.
        if (tabs == null) return;
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW
                || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            Diag.log(this, "휴대폰 메모리 부족 경고(" + level + ")");
            MainActivity.trimTabs(this, level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_MODERATE ? 0 : 1);
        }
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
