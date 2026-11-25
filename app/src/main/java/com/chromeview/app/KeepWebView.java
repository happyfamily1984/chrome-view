package com.chromeview.app;

import android.content.Context;
import android.webkit.WebView;

/**
 * 크롬뷰가 뒤로 가거나 화면이 꺼지면 WebView 는 '창이 가려졌다'는 알림을 받고, 그러면 Chromium 이
 * 영상이 든 재생을 멈춰 버린다(페이지 코드와 상관없이). 이 탭에서 영상이 재생 중이거나 알림창에 재생기가
 * 떠 있는 동안에는 그 알림을 '보이는 중'으로 바꿔 전달해 재생이 이어지게 한다.
 */
class KeepWebView extends WebView {
    private final Tab tab;
    private int realVisibility = VISIBLE;
    private boolean pretending;

    KeepWebView(Context context, Tab tab) {
        super(context);
        this.tab = tab;
    }

    private boolean shouldKeep() {
        return getContext().getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("bgPlay", true)
                && MediaHub.isActiveTab(tab.id);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        realVisibility = visibility;
        if (visibility != VISIBLE && shouldKeep()) {
            if (!pretending) Diag.log(getContext(), "백그라운드로 감 · 재생 중인 탭은 계속 재생");
            pretending = true;
            super.onWindowVisibilityChanged(VISIBLE);
            return;
        }
        pretending = false;
        super.onWindowVisibilityChanged(visibility);
    }

    /** 이미 가려진 뒤에 알림창에서 재생을 누른 경우: 다시 보이는 상태로 돌려 재생이 막히지 않게 한다. */
    void wake() {
        if (realVisibility != VISIBLE && !pretending && shouldKeep()) {
            pretending = true;
            super.onWindowVisibilityChanged(VISIBLE);
        }
    }

    /** 재생기를 닫았으면 원래대로 가려진 상태로 돌린다. */
    void release() {
        if (pretending && realVisibility != VISIBLE) {
            pretending = false;
            super.onWindowVisibilityChanged(realVisibility);
        }
    }
}
