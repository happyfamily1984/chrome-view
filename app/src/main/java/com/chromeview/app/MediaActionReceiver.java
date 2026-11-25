package com.chromeview.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 미디어 알림의 버튼을 받는다. */
public class MediaActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        MediaHub.command(context, intent.getStringExtra(MediaHub.EXTRA_CMD));
    }
}
