package com.chromeview.app;

import android.content.MutableContextWrapper;
import android.os.Bundle;
import android.webkit.WebView;

class Tab {
    long id;
    /** 이 탭을 연 탭(로그인 팝업 등). 없으면 -1. */
    long openerId = -1;
    String title = "";
    String url = "";
    /** 아직 화면에 띄운 적 없는 복원 탭은 web 이 null 이고 saved 에 이전 기록이 들어 있다. */
    WebView web;
    MutableContextWrapper ctx;
    Bundle saved;
}
