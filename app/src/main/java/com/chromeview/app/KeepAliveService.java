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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

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
            // Chrome 이 죽은 경우. 시스템이 다시 띄우면 재연결되지만, 확실히 하려고 직접 다시 건다.
            holdingChrome = false;
            handler.postDelayed(KeepAliveService.this::rebind, 1000);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Chrome 이 업데이트되는 등 연결 자체가 무효가 된 경우: 새로 연결해야 한다.
            holdingChrome = false;
            handler.postDelayed(KeepAliveService.this::rebind, 1000);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            holdingChrome = false;
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean destroyed;
    /** 연결이 조용히 끊긴 채 방치되지 않도록 주기적으로 확인한다. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (!holdingChrome) rebind();
            handler.postDelayed(this, 60_000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        bind();
        handler.postDelayed(watchdog, 60_000);
    }

    private void bind() {
        try {
            // IMPORTANT: Chrome 을 크롬뷰(알림 떠 있는 서비스)와 같은 등급으로 끌어올린다.
            // ABOVE_CLIENT: 메모리가 모자라면 Chrome 보다 크롬뷰가 먼저 정리 대상이 되게 한다.
            bound = bindService(
                    new Intent("android.support.customtabs.action.CustomTabsService").setPackage(CHROME),
                    chrome, BIND_AUTO_CREATE | BIND_IMPORTANT | BIND_ABOVE_CLIENT);
        } catch (RuntimeException e) {
            bound = false;
        }
        if (!bound) {
            // bindService 가 false 여도 연결 객체는 등록된 상태라 반드시 풀어 줘야 한다.
            try {
                unbindService(chrome);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void rebind() {
        if (destroyed || holdingChrome) return;
        if (bound) {
            try {
                unbindService(chrome);
            } catch (RuntimeException ignored) {
            }
            bound = false;
        }
        bind();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            try {
                unbindService(chrome);
            } catch (RuntimeException ignored) {
            }
        }
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
