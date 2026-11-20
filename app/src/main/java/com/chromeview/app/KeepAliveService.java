package com.chromeview.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 알림을 띄운 포그라운드 서비스.
 * Chrome 이 외부 앱에 열어 둔 서비스(Custom Tabs)에 연결해 둔다. 안드로이드는 연결된 쪽의
 * 중요도를 연결한 쪽만큼 올려 주므로, 이 서비스가 떠 있는 동안 Chrome 프로세스가
 * '화면에 없는 앱'으로 분류되어 정리되는 일을 막는다.
 */
public class KeepAliveService extends Service {
    static final String CHROME = "com.android.chrome";
    /** Chrome 과 연결되어 붙잡고 있는 중인지. */
    static boolean holdingChrome;

    private boolean bound;
    private final ServiceConnection chrome = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            holdingChrome = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Chrome 이 죽은 경우. BIND_AUTO_CREATE 라 시스템이 다시 띄우면 재연결된다.
            holdingChrome = false;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            bound = bindService(
                    new Intent("android.support.customtabs.action.CustomTabsService").setPackage(CHROME),
                    chrome, BIND_AUTO_CREATE | BIND_IMPORTANT);
        } catch (RuntimeException e) {
            bound = false;
        }
    }

    @Override
    public void onDestroy() {
        if (bound) unbindService(chrome);
        bound = false;
        holdingChrome = false;
        super.onDestroy();
    }

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, KeepAliveService.class));
        } catch (RuntimeException ignored) {
            // 백그라운드에서 시작이 거부된 경우: 다음에 화면이 뜰 때 다시 시도된다.
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, App.CHANNEL);
        Intent launch = getPackageManager().getLaunchIntentForPackage(CHROME);
        if (launch != null) {
            b.addAction(new Notification.Action.Builder(null, "Chrome 열기",
                    PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_IMMUTABLE)).build());
        }
        Notification n = b
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("크롬뷰가 Chrome을 유지하는 중")
                .setContentText("탭과 로그인·인증 상태가 닫히지 않게 붙잡고 있습니다")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
