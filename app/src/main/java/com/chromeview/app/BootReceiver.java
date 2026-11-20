package com.chromeview.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 폰을 켜거나 크롬뷰를 업데이트한 직후에도 유지 서비스를 자동으로 다시 올린다. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("autoStart", true)) {
            KeepAliveService.start(context);
        }
    }
}
