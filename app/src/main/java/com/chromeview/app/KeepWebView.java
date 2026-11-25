package com.chromeview.app;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebView;

/**
 * 영상이 재생 중인 탭이 백그라운드로 갈 때의 처리.
 *
 * 1) 먼저 '절약 방식': WebView 를 실제로 가려진 상태로 둔다. 그러면 Chromium 이 화면 그리기와 영상 디코딩을
 *    멈추고 소리만 이어 가서 메모리를 훨씬 적게 쓴다(메모리가 모자라면 휴대폰이 크롬뷰를 통째로 끄기 때문).
 * 2) 그 상태에서 재생이 멈추거나 진행되지 않으면 '화면 유지 방식'으로 바꾼다: 가려졌다는 알림을 '보이는 중'으로
 *    바꿔 전달하고 다시 재생한다. 한 번 그렇게 된 탭은 다음부터 바로 화면 유지 방식을 쓴다.
 */
class KeepWebView extends WebView {
    private final Tab tab;
    private int realVisibility = VISIBLE;
    private boolean pretending;
    private long hiddenAt;

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
            if (tab.bgNeedsVisible) {
                if (!pretending) Diag.log(getContext(), "백그라운드로 감 · 화면 유지 방식으로 계속 재생");
                pretending = true;
                super.onWindowVisibilityChanged(VISIBLE);
                return;
            }
            if (!pretending) {
                Diag.log(getContext(), "백그라운드로 감 · 절약 방식(소리만)으로 계속 재생");
                hiddenAt = SystemClock.elapsedRealtime();
                MediaHub.watchForStall(getContext(), this);
            }
            super.onWindowVisibilityChanged(visibility);
            return;
        }
        pretending = false;
        super.onWindowVisibilityChanged(visibility);
    }

    /** 절약 방식으로 가려진 지 얼마 안 됐는지. 이때 멈춘 것은 사용자가 아니라 가려져서 멈춘 것으로 본다. */
    boolean recentlyHidden() {
        return realVisibility != VISIBLE && !pretending && SystemClock.elapsedRealtime() - hiddenAt < 8000;
    }

    /** 이미 가려진 뒤에 재생이 필요해진 경우: 보이는 상태로 돌려 재생이 막히지 않게 한다. */
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
