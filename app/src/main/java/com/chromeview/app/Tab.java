package com.chromeview.app;

import android.content.MutableContextWrapper;
import android.os.Bundle;
import android.webkit.WebView;

class Tab {
    long id;
    /** 이 탭을 연 탭(로그인 팝업 등). 없으면 -1. */
    long openerId = -1;
    /** 시크릿 탭: 방문 기록·탭 저장을 남기지 않고, 로그인 정보도 일반 탭과 따로 쓴다. */
    boolean incognito;
    /** 백그라운드 재생용으로 넣어 둔 스크립트(androidx.webkit.ScriptHandler). 없으면 null. */
    Object bgScript;
    /** 재생 조절 스크립트를 페이지 시작 전에 넣어 두었는지. */
    boolean mediaScript;
    /** 다른 앱이 보낸 링크로 열린 탭. 뒤로가기로 끝까지 나가면 그 앱으로 돌아간다. */
    boolean external;
    /** external 탭을 닫았을 때 크롬뷰에서 다시 보여 줄, 그 전에 보던 탭. 없으면 -1(홈). */
    long backToId = -1;
    String title = "";
    String url = "";
    /** 아직 화면에 띄운 적 없는 복원 탭은 web 이 null 이고 saved 에 이전 기록이 들어 있다. */
    WebView web;
    MutableContextWrapper ctx;
    Bundle saved;
}
