package com.chromeview.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.PowerManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 크롬뷰 고정 창. 페이지를 크롬뷰 안에서 직접 열고, 탭을 화면이 아닌 프로세스에 묶어 둔다.
 * 유지 서비스가 프로세스를 살려 두므로 인증 앱·문자를 다녀와도 페이지가 다시 불러와지지 않는다.
 * 홈(첫 화면)은 열린 탭과 즐겨찾기 목록이다.
 */
public class MainActivity extends Activity {
    static final String ACTION_OPEN = "com.chromeview.app.OPEN";
    static final String ACTION_SETTINGS = "com.chromeview.app.SETTINGS";
    static final String DEFAULT_HOMEPAGE = "https://m.naver.com";
    static final String EXTRA_TAB = "tabId";

    /**
     * 알림창 재생 조절: 영상·음악이 재생/일시정지될 때 앱에 알리고, 알림 버튼 명령을 받아 실행한다.
     * 소리가 없는 자동 재생 미리보기는 무시한다. 다음/이전은 사이트가 지원하면 다음 곡, 아니면 10초 이동.
     */
    private static final String MEDIA_JS = "(function(){"
            + "if(window.__cvMedia)return;window.__cvMedia=true;"
            + "var cur=null,hs={},b=null;"
            + "try{var ms=navigator.mediaSession;if(ms&&ms.setActionHandler){var o=ms.setActionHandler.bind(ms);"
            + "ms.setActionHandler=function(a,h){hs[a]=h;try{o(a,h);}catch(e){}};}}catch(e){}"
            + "function cmd(ev){var c=String(ev.data);var m=cur||document.querySelector('video,audio');"
            + "if(c==='next'){if(hs.nexttrack)hs.nexttrack({action:'nexttrack'});else if(m)m.currentTime+=10;return;}"
            + "if(c==='prev'){if(hs.previoustrack)hs.previoustrack({action:'previoustrack'});"
            + "else if(m)m.currentTime=Math.max(0,m.currentTime-10);return;}"
            + "if(!m)return;if(c==='play'){var r=m.play();if(r&&r.catch)r.catch(function(){});}"
            + "else if(c==='pause')m.pause();else if(c.indexOf('seek:')===0)m.currentTime=Number(c.slice(5))/1000;}"
            + "function send(s,m){if(!b){b=window.cvMedia;if(!b)return;b.onmessage=cmd;}"
            + "try{var md=navigator.mediaSession&&navigator.mediaSession.metadata;"
            + "b.postMessage(JSON.stringify({s:s,t:(md&&md.title)||document.title||'',"
            + "a:(md&&md.artist)||location.hostname,d:m.duration,p:m.currentTime}));}catch(e){}}"
            + "function on(t,s){document.addEventListener(t,function(e){var m=e.target;"
            + "if(!(m instanceof HTMLMediaElement))return;"
            + "if(s==='play'){if(m.muted||m.volume===0)return;cur=m;}else if(m!==cur)return;send(s,m);},true);}"
            + "on('playing','play');on('pause','pause');on('ended','pause');"
            + "on('seeked','meta');on('durationchange','meta');on('volumechange','meta');"
            + "})();";
    private static final String INCOGNITO_PROFILE = "incognito";

    /**
     * 백그라운드 재생: 페이지가 '화면에서 가려졌다'는 신호를 받지 못하게 한다. 유튜브 등은 이 신호를 받으면
     * 영상을 멈추는데, 신호를 막으면 크롬뷰가 뒤로 가거나 화면이 꺼져도 소리가 계속 나온다.
     */
    private static final String BG_PLAY_JS = "(function(){"
            + "if(window.__cvBg)return;window.__cvBg=true;"
            + "var P=Document.prototype;"
            + "function def(n,v){try{Object.defineProperty(P,n,{configurable:true,get:function(){return v;}});}catch(e){}}"
            + "def('hidden',false);def('webkitHidden',false);"
            + "def('visibilityState','visible');def('webkitVisibilityState','visible');"
            + "try{P.hasFocus=function(){return true;};}catch(e){}"
            + "var stop=function(e){e.stopImmediatePropagation();};"
            + "['visibilitychange','webkitvisibilitychange'].forEach(function(t){"
            + "window.addEventListener(t,stop,true);document.addEventListener(t,stop,true);});"
            + "})();";

    private static final int REQ_FILE = 1;
    private static final int REQ_MEDIA_PERM = 2;
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private TabManager tm;
    private Favorites favorites;
    private FrameLayout webContainer, fullscreen;
    private View home, root;
    private AutoCompleteTextView urlBox;
    private History history;
    private ProgressBar progress;
    private LinearLayout tabList, favList;
    private TextView tabsHeader, status, tabCount;

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMedia;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean exiting;
    /** 방금 외부 앱(인증 앱 등)을 부른 탭. 그 앱이 링크로 되돌아올 때 이 탭과 이어 준다. */
    static long waitingTabId = -1;
    static long waitingSince;
    /** 지금 보는 탭 말고 메모리에 살려 둘 탭 수. 46개를 다 살려 두면 휴대폰이 크롬뷰를 통째로 꺼 버린다. */
    static final int KEEP_ALIVE_TABS = 4;
    /** '최근 탭만 남기기'로 남길 탭 수. */
    static final int KEEP_RECENT_TABS = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("app", MODE_PRIVATE);
        Intent intent = getIntent();
        boolean fresh = savedInstanceState == null
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
        boolean firstRun = !prefs.getBoolean("setupShown", false);

        // 'Chrome 으로 열기' 방식을 골라 둔 경우: 아이콘을 누르면 유지를 켜고 Chrome 으로 넘긴다.
        if (fresh && !firstRun && Intent.ACTION_MAIN.equals(intent.getAction())
                && !prefs.getBoolean("usePinned", true)) {
            KeepAliveService.start(this);
            if (launchChrome()) {
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        webContainer = findViewById(R.id.webContainer);
        fullscreen = findViewById(R.id.fullscreen);
        home = findViewById(R.id.home);
        urlBox = findViewById(R.id.url);
        progress = findViewById(R.id.progress);
        tabList = findViewById(R.id.tabList);
        favList = findViewById(R.id.favList);
        tabsHeader = findViewById(R.id.tabsHeader);
        status = findViewById(R.id.status);
        tabCount = findViewById(R.id.tabCount);

        tm = App.tabs(this);
        // 탭 목록(메뉴) 화면은 버튼을 눌렀을 때만 보인다. 시작할 때는 항상 페이지를 보여 준다.
        tm.atHome = false;
        boolean anyIncognito = false;
        for (Tab t : tm.tabs) anyIncognito |= t.incognito;
        if (!anyIncognito) deleteIncognitoData(); // 지난번에 앱이 갑자기 꺼졌으면 남아 있을 수 있다
        favorites = new Favorites(this);
        history = new History(this);
        SuggestAdapter suggest = new SuggestAdapter();
        urlBox.setAdapter(suggest);
        urlBox.setOnItemClickListener((parent, view, pos, id) -> {
            String url = (String) suggest.getItem(pos);
            leaveUrlBox();
            navigate(url);
        });

        // 화면만 새로 만들어진 경우: 살아 있던 페이지들을 그대로 다시 붙인다.
        int alive = 0;
        for (Tab t : tm.tabs) if (t.web != null) alive++;
        Diag.log(this, "화면 생성 · 탭 " + tm.tabs.size() + "개 중 살아 있는 페이지 " + alive + "개");
        for (Tab t : tm.tabs) {
            if (t.web != null) {
                t.ctx.setBaseContext(this);
                bind(t);
                attach(t);
            }
        }

        findViewById(R.id.btnHome).setOnClickListener(v -> goHomepage());
        findViewById(R.id.btnTabs).setOnClickListener(v -> openTabList());
        findViewById(R.id.btnMenu).setOnClickListener(this::showMenu);
        findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btnChrome).setOnClickListener(v -> confirmOpenChrome());
        findViewById(R.id.btnKeepRecent).setOnClickListener(v -> confirmKeepRecent());
        urlBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                String text = urlBox.getText().toString().trim();
                leaveUrlBox();
                if (!text.isEmpty()) navigate(toUrl(text));
                return true;
            }
            return false;
        });

        if (!fresh || !handleIntent(intent)) render();
        if (firstRun) openSettings(); // 설정 화면이 처음 안내를 띄운다
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (tm != null) handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        KeepAliveService.start(this);
        if (tm != null) {
            favorites = new Favorites(this); // 설정에서 북마크를 가져왔을 수 있다
            for (Tab t : tm.tabs) if (t.web != null) applyBackgroundPlay(t); // 설정에서 바꿨을 수 있다
            if (tm.atHome) rebuildHome();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Tab t = tm == null || tm.atHome ? null : tm.current();
        Diag.log(this, "크롬뷰로 돌아옴 · " + (t == null ? "홈"
                : (t.web != null ? "페이지 유지됨 " : "페이지 없음 ") + Diag.brief(t.url)));
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 페이지는 멈추지 않는다(WebView.onPause 를 부르지 않음). 만일에 대비해 기록만 저장.
        if (tm != null && !exiting) tm.save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tm == null) return;
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        webContainer.removeAllViews();
        // 화면이 사라져도 페이지는 프로세스에 남겨 둔다.
        for (Tab t : tm.tabs) {
            if (t.web != null) {
                t.web.setWebChromeClient(null);
                t.web.setWebViewClient(new WebViewClient());
                t.web.setDownloadListener(null);
                t.ctx.setBaseContext(getApplicationContext());
            }
        }
    }

    // ───────────────────────── 화면 전환 ─────────────────────────

    private void render() {
        tabCount.setText(String.valueOf(tm.tabs.size()));
        Tab t = null;
        if (!tm.atHome) {
            t = tm.current();
            if (t == null) {
                // 보던 화면이 없으면 메뉴가 아니라 마지막 탭, 그것도 없으면 시작 페이지를 연다.
                t = tm.tabs.isEmpty() ? tm.create(homepage(this), -1) : tm.tabs.get(tm.tabs.size() - 1);
                tm.select(t);
                tabCount.setText(String.valueOf(tm.tabs.size()));
            }
        }
        applyIncognitoLook(t != null && t.incognito);
        if (t == null) {
            home.setVisibility(View.VISIBLE);
            webContainer.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            urlBox.setText("", false);
            rebuildHome();
            return;
        }
        ensureWeb(t);
        for (int i = 0; i < webContainer.getChildCount(); i++) {
            View c = webContainer.getChildAt(i);
            c.setVisibility(c == t.web ? View.VISIBLE : View.GONE);
        }
        home.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
        urlBox.setText(t.url, false);
        int p = t.web.getProgress();
        progress.setProgress(p);
        progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
    }

    /** 탭 목록·즐겨찾기(메뉴) 화면. 탭 개수 버튼이나 메뉴에서 눌렀을 때만 연다. */
    private void openTabList() {
        leaveUrlBox();
        tm.atHome = true;
        render();
    }

    /** 홈 버튼: Chrome 처럼 지금 탭에서 시작 페이지를 연다. */
    private void goHomepage() {
        leaveUrlBox();
        tm.atHome = false;
        Tab t = tm.current();
        if (t == null) {
            show(tm.create(homepage(this), -1));
            return;
        }
        show(t);
        t.url = homepage(this);
        t.web.loadUrl(t.url);
    }

    static String homepage(Context c) {
        return c.getSharedPreferences("app", MODE_PRIVATE).getString("homepage", DEFAULT_HOMEPAGE);
    }

    private void openNewTab(boolean incognito) {
        Tab t = tm.create(homepage(this), -1);
        t.incognito = incognito;
        if (incognito && !incognitoSupported() && !getSharedPreferences("app", MODE_PRIVATE)
                .getBoolean("incognitoWarned", false)) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("incognitoWarned", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("시크릿 탭")
                    .setMessage("이 폰의 'Android 시스템 WebView'가 오래되어 시크릿 탭의 로그인 정보를 일반 탭과 "
                            + "분리하지 못합니다. 방문 기록과 탭은 남지 않지만, 로그인은 일반 탭과 함께 쓰입니다.\n\n"
                            + "Play 스토어에서 'Android 시스템 WebView'를 업데이트하면 완전히 분리됩니다.")
                    .setPositiveButton("확인", null)
                    .show();
        }
        show(t);
        urlBox.requestFocus();
        getSystemService(InputMethodManager.class).showSoftInput(urlBox, 0);
    }

    /** 오래 안 본 탭을 내려 메모리를 확보한다. 앱(App)의 메모리 경고에서도 부른다. */
    static void trimTabs(Context c, int keep) {
        TabManager tabs = App.tabs(c);
        long waiting = System.currentTimeMillis() - waitingSince < 15 * 60_000 ? waitingTabId : -1;
        int n = tabs.discardExtra(keep, MediaHub.activeTabId(), waiting);
        if (n > 0) Diag.log(c, "메모리 절약: 오래 안 본 탭 " + n + "개를 내림 (다시 열면 새로 불러옴)");
    }

    // ───────────────────────── 백그라운드 재생 ─────────────────────────

    private boolean backgroundPlayOn() {
        return getSharedPreferences("app", MODE_PRIVATE).getBoolean("bgPlay", true);
    }

    /** 설정에 맞춰 탭에 백그라운드 재생 스크립트를 넣거나 뺀다. 바뀐 내용은 다음 페이지부터 적용된다. */
    private void applyBackgroundPlay(Tab t) {
        boolean on = backgroundPlayOn();
        if (!on && t.bgScript != null) {
            ((androidx.webkit.ScriptHandler) t.bgScript).remove();
            t.bgScript = null;
        } else if (on && t.bgScript == null
                && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                // 모든 사이트, 영상이 들어 있는 iframe 안까지 페이지 코드보다 먼저 실행된다.
                t.bgScript = WebViewCompat.addDocumentStartJavaScript(t.web, BG_PLAY_JS,
                        java.util.Collections.singleton("*"));
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** 페이지(모든 프레임)가 재생 상태를 알려 올 통로와 그 스크립트를 탭에 붙인다. 페이지를 불러오기 전에 해야 한다. */
    private void attachMediaControls(Tab t) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        try {
            Context app = getApplicationContext();
            WebViewCompat.addWebMessageListener(t.web, "cvMedia", java.util.Collections.singleton("*"),
                    (view, message, origin, isMainFrame, reply) ->
                            MediaHub.onReport(app, t, reply, message.getData()));
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(t.web, MEDIA_JS, java.util.Collections.singleton("*"));
                t.mediaScript = true;
            }
        } catch (RuntimeException ignored) {
        }
    }

    // ───────────────────────── 시크릿 모드 ─────────────────────────

    private static boolean incognitoSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static CookieManager cookiesFor(Tab t) {
        if (t != null && t.incognito && incognitoSupported()) {
            try {
                return ProfileStore.getInstance().getOrCreateProfile(INCOGNITO_PROFILE).getCookieManager();
            } catch (RuntimeException ignored) {
            }
        }
        return CookieManager.getInstance();
    }

    /** 시크릿 탭에서 생긴 쿠키·사이트 데이터를 통째로 지운다. 시크릿 탭이 하나도 없을 때만 지워진다. */
    private void deleteIncognitoData() {
        if (!incognitoSupported()) return;
        try {
            ProfileStore store = ProfileStore.getInstance();
            if (store.getAllProfileNames().contains(INCOGNITO_PROFILE)) store.deleteProfile(INCOGNITO_PROFILE);
        } catch (RuntimeException e) {
            // 페이지가 아직 완전히 내려가지 않았으면 잠시 뒤 한 번 더 시도한다.
            root.postDelayed(() -> {
                try {
                    ProfileStore.getInstance().deleteProfile(INCOGNITO_PROFILE);
                } catch (RuntimeException ignored) {
                }
            }, 1500);
        }
    }

    private boolean hasIncognitoTabs() {
        for (Tab t : tm.tabs) if (t.incognito) return true;
        return false;
    }

    private void closeAllIncognito() {
        for (Tab t : new ArrayList<>(tm.tabs)) if (t.incognito) closeTab(t);
    }

    /** 시크릿 탭을 보고 있을 때는 Chrome 처럼 위쪽 막대를 어둡게 바꾼다. */
    private void applyIncognitoLook(boolean on) {
        int bar = on ? 0xFF3C4043 : getColor(R.color.bg_top);
        int fg = on ? 0xFFFFFFFF : getColor(R.color.text);
        findViewById(R.id.toolbar).setBackgroundColor(bar);
        urlBox.setBackgroundResource(on ? R.drawable.bg_field_incognito : R.drawable.bg_field);
        urlBox.setTextColor(fg);
        urlBox.setHintTextColor(on ? 0xFFBDC1C6 : getColor(R.color.sub));
        urlBox.setHint(on ? "시크릿 모드 · 검색어 또는 주소 입력" : "검색어 또는 주소 입력");
        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(fg);
        ((android.widget.ImageButton) findViewById(R.id.btnHome)).setImageTintList(tint);
        ((android.widget.ImageButton) findViewById(R.id.btnMenu)).setImageTintList(tint);
        tabCount.setTextColor(fg);
        tabCount.setBackgroundResource(on ? R.drawable.bg_tabcount_incognito : R.drawable.bg_tabcount);
        getWindow().setStatusBarColor(bar);
        boolean nightUi = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (on || nightUi) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decor.setSystemUiVisibility(flags);
    }

    private void show(Tab t) {
        tm.select(t);
        render();
        trimTabs(this, KEEP_ALIVE_TABS);
    }

    /** 주소창에서 이동: 홈에서는 새 탭, 탭을 보고 있으면 그 탭에서 연다. */
    private void navigate(String url) {
        Tab t = tm.atHome ? null : tm.current();
        if (t == null) {
            show(tm.create(url, -1));
        } else {
            t.url = url;
            ensureWeb(t);
            t.web.loadUrl(url);
        }
    }

    /** 링크·공유·설정 요청을 처리한다. 화면을 바꿨으면 true. */
    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        if (ACTION_OPEN.equals(intent.getAction()) && intent.hasExtra(EXTRA_TAB)) {
            Tab t = tm.byId(intent.getLongExtra(EXTRA_TAB, -1));
            if (t == null) return false;
            leaveUrlBox();
            show(t);
            return true;
        }
        if (ACTION_SETTINGS.equals(intent.getAction())) {
            render();
            openSettings();
            return true;
        }
        String url = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            url = intent.getData().toString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.trim().isEmpty()) {
                Matcher m = URL_IN_TEXT.matcher(text);
                url = m.find() ? m.group() : toUrl(text.trim());
            }
        }
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            if (!Intent.ACTION_MAIN.equals(intent.getAction()) && !ACTION_OPEN.equals(intent.getAction())) {
                Diag.log(this, "열 수 없는 요청을 받음: " + intent.getAction()
                        + " · " + (url == null ? "주소 없음" : Diag.brief(url)));
            }
            return false;
        }
        leaveUrlBox();
        // 인증 앱이 '원래 페이지로 돌아가기'로 같은 주소를 다시 보내는 경우가 있다. 새로 열면
        // 인증 전 상태의 새 페이지가 떠 버리므로, 이미 그 주소를 연 탭이 있으면 그 탭을 그대로 보여 준다.
        Tab same = findByUrl(url);
        if (same != null) {
            Diag.log(this, "외부에서 링크 받음 → 이미 열린 탭으로 복귀: " + Diag.brief(url));
            show(same);
            return true;
        }
        Tab waiting = System.currentTimeMillis() - waitingSince < 15 * 60_000 ? tm.byId(waitingTabId) : null;
        Diag.log(this, "외부에서 링크 받음 → 새 탭" + (waiting != null ? "(인증 대기 탭과 연결)" : "")
                + ": " + Diag.brief(url));
        Tab before = tm.atHome ? null : tm.current();
        Tab opened = tm.create(url, waiting != null ? waiting.id : -1);
        opened.external = waiting == null;
        opened.backToId = before == null ? -1 : before.id;
        show(opened);
        return true;
    }

    private static String toUrl(String text) {
        if (text.matches("(?i)^(https?|about|file):.*")) return text;
        if (!text.contains(" ") && text.matches("^[^/]+\\.[^/.]{2,}(/.*)?$")) return "https://" + text;
        return "https://www.google.com/search?q=" + Uri.encode(text);
    }

    private Tab findByUrl(String url) {
        String want = stripFragment(url);
        Tab cur = tm.current();
        if (cur != null && want.equals(shownUrl(cur))) return cur;
        for (int i = tm.tabs.size() - 1; i >= 0; i--) {
            if (want.equals(shownUrl(tm.tabs.get(i)))) return tm.tabs.get(i);
        }
        return null;
    }

    /** 그 탭에 실제로 떠 있는 페이지의 주소. 아무 페이지도 뜨지 않은 탭(파일 링크 등)은 null. */
    private static String shownUrl(Tab t) {
        if (t.web == null) return t.saved != null ? stripFragment(t.url) : null;
        String u = t.web.getUrl();
        return u == null ? null : stripFragment(u);
    }

    private static String stripFragment(String url) {
        int i = url.indexOf('#');
        url = i < 0 ? url : url.substring(0, i);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void closeTab(Tab t) {
        MediaHub.forgetTab(this, t.id);
        if (t.web != null) webContainer.removeView(t.web);
        tm.remove(t);
        if (t.incognito && !hasIncognitoTabs()) deleteIncognitoData();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** Chrome 에서 연 페이지는 크롬뷰가 붙잡지 못한다는 점을 알리고 나서 Chrome 을 연다. */
    private void confirmOpenChrome() {
        SharedPreferences prefs = getSharedPreferences("app", MODE_PRIVATE);
        if (!prefs.getBoolean("usePinned", true) || prefs.getBoolean("chromeWarnOff", false)) {
            launchChrome();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Chrome으로 나가기")
                .setMessage("Chrome에서 연 페이지는 크롬뷰가 붙잡지 못합니다. 또 인증 앱은 인증을 마치면 "
                        + "기본 브라우저인 크롬뷰로 돌아오는데, 그 페이지는 Chrome에 있어서 오류가 납니다.\n\n"
                        + "인증이 필요한 사이트는 크롬뷰 홈의 주소창에 입력해서 열어 주세요.")
                .setPositiveButton("Chrome 열기", (d, w) -> launchChrome())
                .setNegativeButton("취소", null)
                .setNeutralButton("다시 묻지 않기", (d, w) -> {
                    prefs.edit().putBoolean("chromeWarnOff", true).apply();
                    launchChrome();
                })
                .show();
    }

    private boolean launchChrome() {
        Intent i = getPackageManager().getLaunchIntentForPackage(KeepAliveService.CHROME);
        try {
            if (i != null) {
                startActivity(i);
                return true;
            }
        } catch (ActivityNotFoundException ignored) {
        }
        toast("Chrome 앱을 실행하지 못했습니다");
        return false;
    }

    /** 주소창 자동완성: 즐겨찾기와 크롬뷰 방문 기록에서 제목·주소가 맞는 것을 보여 준다. */
    private class SuggestAdapter extends BaseAdapter implements Filterable {
        private List<String[]> shown = new ArrayList<>();

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i)[1];
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_suggest, parent, false);
            String[] it = shown.get(i);
            ((TextView) v.findViewById(R.id.title)).setText(it[0].isEmpty() ? it[1] : it[0]);
            ((TextView) v.findViewById(R.id.sub)).setText(it[1]);
            return v;
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    List<String[]> out = new ArrayList<>();
                    String q = constraint == null ? "" : constraint.toString().trim().toLowerCase();
                    if (!q.isEmpty()) {
                        List<String[]> all = new ArrayList<>();
                        try {
                            // 즐겨찾기는 방문 3번으로 쳐서 조금 앞세운다.
                            for (Favorites.Item f : new ArrayList<>(favorites.items)) {
                                all.add(new String[]{f.title, f.url, "3"});
                            }
                        } catch (RuntimeException ignored) {
                        }
                        all.addAll(history.snapshot());

                        // 1) 입력한 글자로 시작하는 사이트를 자주 간 순서로 맨 위에 둔다.
                        java.util.Map<String, Integer> visits = new java.util.HashMap<>();
                        java.util.Map<String, String> rootOf = new java.util.HashMap<>();
                        for (String[] it : all) {
                            Uri u = Uri.parse(it[1]);
                            if (u.getHost() == null) continue;
                            String bare = u.getHost().toLowerCase().replaceFirst("^(www|m)\\.", "");
                            if (!bare.startsWith(q)) continue;
                            visits.merge(bare, Integer.parseInt(it[2]), Integer::sum);
                            if (!rootOf.containsKey(bare)) rootOf.put(bare, u.getScheme() + "://" + u.getHost() + "/");
                        }
                        List<String> sites = new ArrayList<>(visits.keySet());
                        sites.sort((x, y) -> visits.get(y) - visits.get(x));
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        for (String site : sites) {
                            if (out.size() >= 3) break;
                            out.add(new String[]{site, rootOf.get(site)});
                            seen.add(rootOf.get(site));
                        }

                        // 2) 주소나 제목이 그 글자로 시작하는 페이지, 3) 중간에 들어 있는 페이지 순.
                        for (int pass = 0; pass < 2; pass++) {
                            for (String[] it : all) {
                                if (out.size() >= 8) break;
                                String bare = it[1].replaceFirst("(?i)^https?://(www\\.|m\\.)?", "").toLowerCase();
                                String title = it[0].toLowerCase();
                                boolean hit = pass == 0 ? bare.startsWith(q) || title.startsWith(q)
                                        : bare.contains(q) || title.contains(q);
                                if (hit && seen.add(it[1])) out.add(it);
                            }
                        }
                    }
                    FilterResults r = new FilterResults();
                    r.values = out;
                    r.count = out.size();
                    return r;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    shown = results.values == null ? new ArrayList<>() : (List<String[]>) results.values;
                    if (shown.isEmpty()) notifyDataSetInvalidated();
                    else notifyDataSetChanged();
                }
            };
        }
    }

    // ───────────────────────── 홈 화면 ─────────────────────────

    private void rebuildHome() {
        tabCount.setText(String.valueOf(tm.tabs.size()));
        boolean unrestricted = getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName());
        status.setText(unrestricted
                ? "유지 켜짐 · 인증할 사이트는 위 주소창에 입력해서 여세요. 여기서 연 페이지만 다른 앱을 다녀와도 그대로 남습니다."
                : "배터리 절전이 걸려 있어 페이지가 닫힐 수 있습니다. 여기를 눌러 설정에서 '제한 없음'으로 바꿔 주세요.");
        status.setOnClickListener(unrestricted ? null : v -> openSettings());
        status.setClickable(!unrestricted);

        LayoutInflater inf = getLayoutInflater();
        tabList.removeAllViews();
        tabsHeader.setText("열린 탭 " + tm.tabs.size() + "개");
        findViewById(R.id.btnKeepRecent).setVisibility(
                tm.tabs.size() > KEEP_RECENT_TABS ? View.VISIBLE : View.GONE);
        if (tm.tabs.isEmpty()) {
            tabList.addView(note("열린 탭이 없습니다. 위 주소창에 검색어나 주소를 입력하세요."));
        }
        for (Tab t : new ArrayList<>(tm.tabs)) {
            String title = t.title.isEmpty() ? "새 탭" : t.title;
            View row = row(inf, tabList, t.incognito ? "[시크릿] " + title : title, t.url);
            row.setOnClickListener(v -> show(t));
            View x = row.findViewById(R.id.remove);
            x.setContentDescription("탭 닫기");
            x.setOnClickListener(v -> {
                closeTab(t);
                rebuildHome();
            });
        }

        favList.removeAllViews();
        if (favorites.items.isEmpty()) {
            favList.addView(note("페이지를 연 뒤 메뉴에서 '즐겨찾기에 추가'를 누르세요."));
        }
        for (Favorites.Item f : new ArrayList<>(favorites.items)) {
            View row = row(inf, favList, f.title, f.url);
            row.setOnClickListener(v -> show(tm.create(f.url, -1)));
            View x = row.findViewById(R.id.remove);
            x.setContentDescription("즐겨찾기 삭제");
            x.setOnClickListener(v -> {
                favorites.remove(f);
                rebuildHome();
            });
        }
    }

    private View row(LayoutInflater inf, ViewGroup parent, String title, String url) {
        if (parent.getChildCount() > 0) {
            float dp = getResources().getDisplayMetrics().density;
            View line = new View(this);
            line.setBackgroundColor(getColor(R.color.line));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) dp));
            lp.setMarginStart((int) (76 * dp));
            parent.addView(line, lp);
        }
        View row = inf.inflate(R.layout.item_row, parent, false);
        String host = Uri.parse(url).getHost();
        if (host == null) host = "";
        host = host.replaceFirst("^(www|m)\\.", "");
        String letterSource = title.isEmpty() ? host : title;
        ((TextView) row.findViewById(R.id.badge)).setText(
                letterSource.isEmpty() ? "·" : letterSource.substring(0, 1).toUpperCase());
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.sub)).setText(host.isEmpty() ? url : host);
        parent.addView(row);
        return row;
    }

    private TextView note(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(14);
        v.setTextColor(getColor(R.color.sub));
        v.setLineSpacing(0, 1.2f);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        v.setPadding(pad, pad / 2, pad, pad / 2);
        return v;
    }

    // ───────────────────────── 뒤로가기 ─────────────────────────

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (urlBox.hasFocus()) {
            leaveUrlBox();
            Tab t = tm.atHome ? null : tm.current();
            urlBox.setText(t == null ? "" : t.url, false);
            return;
        }
        if (tm.atHome) {
            // 탭 목록(메뉴)은 열린 창처럼 닫고 보던 페이지로 돌아간다.
            tm.atHome = false;
            render();
            return;
        }
        Tab t = tm.current();
        if (t.web != null && t.web.canGoBack()) {
            t.web.goBack();
            return;
        }
        Tab opener = tm.byId(t.openerId);
        if (opener != null) {
            closeTab(t);
            show(opener);
            return;
        }
        if (t.external) {
            // 다른 앱에서 넘어온 탭: 탭을 닫고 그 앱으로 돌아간다. 크롬뷰에는 그 전에 보던 화면을 남겨 둔다.
            Tab before = tm.byId(t.backToId);
            closeTab(t);
            if (before != null) tm.select(before);
            render(); // 그 전에 보던 탭이 없으면 마지막 탭이나 시작 페이지가 대신 남는다
            moveTaskToBack(true);
            return;
        }
        // 더 돌아갈 곳이 없는 탭: 홈 화면으로 보내지 않고 종료 여부를 묻는다. 홈은 홈 버튼으로만 간다.
        confirmExit();
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setMessage("앱을 종료하겠습니까?")
                .setPositiveButton("예", (d, w) -> exitApp())
                .setNegativeButton("아니오", null)
                .show();
    }

    /** 종료해도 탭과 로그인 쿠키는 저장되어 다음 실행 때 되살아난다. */
    private void exitApp() {
        exiting = true;
        MediaHub.clear(this, true);
        tm.save();
        webContainer.removeAllViews();
        closeAllIncognito();
        tm.destroyAll();
        tm.atHome = false;
        KeepAliveService.stop(this);
        finishAndRemoveTask();
    }

    private void leaveUrlBox() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
        urlBox.clearFocus();
        root.requestFocus();
    }

    // ───────────────────────── 메뉴 ─────────────────────────

    private void showMenu(View anchor) {
        Tab t = tm.atHome ? null : tm.current();
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "새 탭");
        m.add(0, 11, 0, "새 시크릿 탭");
        m.add(0, 13, 0, "탭 목록·즐겨찾기");
        if (tm.tabs.size() > KEEP_RECENT_TABS) m.add(0, 15, 0, "최근 탭 " + KEEP_RECENT_TABS + "개만 남기기");
        if (tm.tabs.size() > 1) m.add(0, 14, 0, "다른 탭 모두 닫기");
        if (t != null && t.web != null) {
            m.add(0, 2, 0, "새로고침");
            if (t.web.canGoForward()) m.add(0, 3, 0, "앞으로");
            if (!t.url.isEmpty()) {
                m.add(0, 4, 0, favorites.contains(t.url) ? "즐겨찾기에 추가됨" : "즐겨찾기에 추가")
                        .setEnabled(!favorites.contains(t.url));
                m.add(0, 5, 0, "Chrome으로 열기");
                m.add(0, 6, 0, "주소 공유");
            }
            m.add(0, 7, 0, "이 탭 닫기");
        }
        if (hasIncognitoTabs()) m.add(0, 12, 0, "시크릿 탭 모두 닫기");
        m.add(0, 8, 0, "설정");
        m.add(0, 10, 0, "방문 기록 삭제");
        m.add(0, 9, 0, "로그인 정보 모두 삭제");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    openNewTab(false);
                    break;
                case 11:
                    openNewTab(true);
                    break;
                case 12:
                    closeAllIncognito();
                    render();
                    toast("시크릿 탭을 모두 닫고 기록을 지웠습니다");
                    break;
                case 13:
                    openTabList();
                    break;
                case 14:
                    confirmCloseOthers();
                    break;
                case 15:
                    confirmKeepRecent();
                    break;
                case 2:
                    t.web.reload();
                    break;
                case 3:
                    t.web.goForward();
                    break;
                case 4:
                    favorites.add(t.title.isEmpty() ? t.url : t.title, t.url);
                    toast("즐겨찾기에 추가했습니다");
                    break;
                case 5:
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(t.url))
                                .setPackage(KeepAliveService.CHROME));
                    } catch (ActivityNotFoundException e) {
                        toast("Chrome 앱을 찾을 수 없습니다");
                    }
                    break;
                case 6:
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url), null));
                    break;
                case 7:
                    closeTab(t);
                    render();
                    break;
                case 8:
                    openSettings();
                    break;
                case 9:
                    confirmClearLogins();
                    break;
                case 10:
                    history.clear();
                    toast("방문 기록을 삭제했습니다");
                    break;
            }
            return true;
        });
        pm.show();
    }

    /**
     * 최근에 본 탭 5개만 남기고 나머지를 닫는다. 지금 보는 탭(과 그 탭을 연 탭), 재생 중인 탭,
     * 인증 앱을 기다리는 탭은 5개에 들지 못해도 닫지 않는다.
     */
    private void confirmKeepRecent() {
        java.util.Set<Long> keep = new java.util.HashSet<>();
        Tab cur = tm.atHome ? null : tm.current();
        long waiting = System.currentTimeMillis() - waitingSince < 15 * 60_000 ? waitingTabId : -1;
        for (long id : new long[]{cur == null ? -1 : cur.id, MediaHub.activeTabId(), waiting}) {
            for (Tab t = tm.byId(id); t != null && keep.add(t.id); t = tm.byId(t.openerId)) { }
        }
        List<Tab> recent = new ArrayList<>(tm.tabs);
        recent.sort((a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        for (Tab t : recent) {
            if (keep.size() >= KEEP_RECENT_TABS) break;
            keep.add(t.id);
        }
        List<Tab> close = new ArrayList<>();
        for (Tab t : tm.tabs) if (!keep.contains(t.id)) close.add(t);
        if (close.isEmpty()) {
            toast("정리할 탭이 없습니다");
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("최근에 본 탭 " + keep.size() + "개만 남기고 " + close.size() + "개를 닫을까요?")
                .setPositiveButton("정리", (d, w) -> {
                    for (Tab t : close) closeTab(t);
                    tm.save();
                    render();
                    toast(close.size() + "개 탭을 닫았습니다");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void confirmCloseOthers() {
        Tab keep = tm.atHome ? null : tm.current();
        int n = tm.tabs.size() - (keep == null ? 0 : 1);
        new AlertDialog.Builder(this)
                .setMessage((keep == null ? "열린 탭 " : "지금 보는 탭을 빼고 ") + n + "개를 모두 닫을까요?")
                .setPositiveButton("닫기", (d, w) -> {
                    for (Tab t : new ArrayList<>(tm.tabs)) if (t != keep) closeTab(t);
                    tm.save();
                    render();
                    toast(n + "개 탭을 닫았습니다");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void confirmClearLogins() {
        new AlertDialog.Builder(this)
                .setTitle("로그인 정보 모두 삭제")
                .setMessage("크롬뷰에서 로그인한 모든 사이트에서 로그아웃되고 저장된 사이트 데이터가 지워집니다. "
                        + "되돌릴 수 없습니다. (Chrome 앱의 로그인은 그대로입니다.)")
                .setPositiveButton("삭제", (d, w) -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteAllData();
                    toast("삭제했습니다");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ───────────────────────── WebView ─────────────────────────

    private void ensureWeb(Tab t) {
        if (t.web != null) return;
        t.ctx = new MutableContextWrapper(this);
        WebView w = new KeepWebView(t.ctx, t);
        // 시크릿 탭은 따로 된 저장소를 쓴다. 다른 어떤 설정보다 먼저 지정해야 한다.
        if (t.incognito && incognitoSupported()) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(INCOGNITO_PROFILE);
                WebViewCompat.setProfile(w, INCOGNITO_PROFILE);
            } catch (RuntimeException ignored) {
            }
        }
        t.web = w;
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 일부 사이트(구글 로그인 등)는 '앱 내 브라우저' 표시가 있으면 로그인을 막는다.
        s.setUserAgentString(WebSettings.getDefaultUserAgent(this)
                .replace("; wv", "").replace("Version/4.0 ", ""));
        if (t.incognito) s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager cm = cookiesFor(t);
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        // 화면에 안 보여도 페이지 프로세스를 정리 대상에서 뺀다.
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        applyBackgroundPlay(t);
        attachMediaControls(t);
        bind(t);
        attach(t);

        Bundle saved = t.saved;
        t.saved = null;
        if (saved != null) Diag.log(this, "저장해 둔 탭을 다시 불러옴: " + Diag.brief(t.url));
        if (saved != null && w.restoreState(saved) != null) return;
        if (!t.url.isEmpty()) w.loadUrl(t.url);
    }

    private void attach(Tab t) {
        if (t.web.getParent() != null) ((ViewGroup) t.web.getParent()).removeView(t.web);
        t.web.setVisibility(View.GONE);
        webContainer.addView(t.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void bind(Tab t) {
        t.web.setWebViewClient(new TabClient(t));
        t.web.setWebChromeClient(new TabChrome(t));
        t.web.setDownloadListener((url, userAgent, disposition, mime, length) ->
                download(t, url, userAgent, disposition, mime));
    }

    private boolean isShowing(Tab t) {
        return !tm.atHome && tm.current() == t;
    }

    private void onTabChanged(Tab t) {
        if (isShowing(t)) {
            if (!urlBox.hasFocus()) urlBox.setText(t.url, false);
        } else if (tm.atHome) {
            rebuildHome();
        }
    }

    private class TabClient extends WebViewClient {
        final Tab tab;

        TabClient(Tab tab) {
            this.tab = tab;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.matches("(?i)^(https?|about|blob|data|javascript|file):.*")) return false;
            openExternal(tab, url);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            tab.url = url;
            // 페이지 시작 전에 넣는 방식을 지원하지 않는 폰: 페이지가 열리는 대로 넣는다.
            if (tab.bgScript == null && backgroundPlayOn()) view.evaluateJavascript(BG_PLAY_JS, null);
            if (!tab.mediaScript) view.evaluateJavascript(MEDIA_JS, null);
            MediaHub.forgetTab(MainActivity.this, tab.id); // 다른 페이지로 넘어가면 이전 영상 알림은 내린다
            onTabChanged(tab);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            tab.url = url;
            onTabChanged(tab);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            tab.url = url;
            if (view.getTitle() != null) tab.title = view.getTitle();
            CookieManager.getInstance().flush(); // 로그인 직후 쿠키를 바로 디스크에 남긴다
            if (!tab.incognito) history.add(tab.title, url);
            if (tab.bgScript == null && backgroundPlayOn()) view.evaluateJavascript(BG_PLAY_JS, null);
            if (!tab.mediaScript) view.evaluateJavascript(MEDIA_JS, null);
            onTabChanged(tab);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // 메모리 부족으로 페이지 프로세스가 죽은 경우: 앱은 살리고 그 탭만 다시 연다.
            MediaHub.forgetTab(MainActivity.this, tab.id);
            Diag.log(MainActivity.this, "페이지 프로세스가 " + (detail.didCrash() ? "오류로 죽음" : "시스템에 의해 정리됨")
                    + ": " + Diag.brief(tab.url));
            webContainer.removeView(view);
            view.destroy();
            if (tab.web == view) {
                tab.web = null;
                tab.saved = null;
                if (isShowing(tab)) {
                    toast("페이지를 다시 불러옵니다");
                    render();
                }
            }
            return true;
        }
    }

    private class TabChrome extends WebChromeClient {
        final Tab tab;

        TabChrome(Tab tab) {
            this.tab = tab;
        }

        @Override
        public void onProgressChanged(WebView view, int p) {
            if (!isShowing(tab)) return;
            progress.setProgress(p);
            progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            tab.title = title == null ? "" : title;
            onTabChanged(tab);
        }

        /** 로그인·인증 팝업: 원래 페이지를 그대로 둔 채 새 탭으로 연다. */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            Tab popup = tm.create("", tab.id);
            popup.incognito = tab.incognito; // 로그인 팝업은 원래 탭과 같은 저장소를 써야 한다
            ensureWeb(popup);
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup.web);
            resultMsg.sendToTarget();
            show(popup);
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            boolean wasShowing = isShowing(tab);
            Tab opener = tm.byId(tab.openerId);
            closeTab(tab);
            if (wasShowing) {
                if (opener != null) show(opener);
                else render();
            } else {
                render();
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast("파일을 선택할 앱이 없습니다");
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            List<String> need = new ArrayList<>();
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) need.add(Manifest.permission.CAMERA);
                else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) need.add(Manifest.permission.RECORD_AUDIO);
            }
            if (need.isEmpty()) {
                request.deny();
                return;
            }
            String what = need.size() == 2 ? "카메라와 마이크"
                    : need.get(0).equals(Manifest.permission.CAMERA) ? "카메라" : "마이크";
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(request.getOrigin().getHost() + " 에서 " + what + " 사용을 요청합니다.")
                    .setPositiveButton("허용", (d, w) -> {
                        if (pendingMedia != null) pendingMedia.deny();
                        pendingMedia = request;
                        requestPermissions(need.toArray(new String[0]), REQ_MEDIA_PERM);
                    })
                    .setNegativeButton("거부", (d, w) -> request.deny())
                    .setOnCancelListener(d -> request.deny())
                    .show();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            fullscreen.addView(view);
            fullscreen.setVisibility(View.VISIBLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        fullscreen.removeAllViews();
        fullscreen.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        customView = null;
        WebChromeClient.CustomViewCallback cb = customCallback;
        customCallback = null;
        if (cb != null) cb.onCustomViewHidden();
    }

    /** 본인인증·은행·간편결제 앱 등 외부 앱 호출(intent://, 앱 전용 주소). 페이지는 그대로 남는다. */
    private void openExternal(Tab tab, String url) {
        Intent intent;
        try {
            if (url.startsWith("intent:")) {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
        } catch (Exception e) {
            toast("열 수 없는 링크입니다");
            return;
        }
        try {
            startActivity(intent);
            waitingTabId = tab.id;
            waitingSince = System.currentTimeMillis();
            Diag.log(this, "외부 앱 호출: " + intent.getScheme()
                    + (intent.getPackage() != null ? " (" + intent.getPackage() + ")" : "")
                    + " · 부른 페이지 " + Diag.brief(tab.url));
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Diag.log(this, "외부 앱 호출 실패: " + intent.getScheme()
                    + (intent.getPackage() != null ? " (" + intent.getPackage() + ")" : ""));
        }
        String fallback = intent.getStringExtra("browser_fallback_url");
        if (fallback != null && fallback.matches("(?i)^https?://.*")) {
            if (tab.web != null) tab.web.loadUrl(fallback);
            return;
        }
        if (intent.getPackage() != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + intent.getPackage())));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        toast("이 링크를 열 수 있는 앱이 설치되어 있지 않습니다");
    }

    /** 서버가 알려 준 이름 → 주소의 마지막 부분 순으로 파일 이름을 정한다. 확장자는 건드리지 않는다. */
    private static String fileName(String url, String disposition, String mime) {
        String name = null;
        if (disposition != null) {
            Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
                    Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (m.find()) name = Uri.decode(m.group(1).trim());
        }
        if (name == null || name.isEmpty()) name = Uri.parse(url).getLastPathSegment();
        if (name == null || name.isEmpty()) name = "download";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.contains(".")) {
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) name += "." + ext;
        }
        return name;
    }

    private void download(Tab tab, String url, String userAgent, String disposition, String mime) {
        // 파일 링크만 받고 아무 페이지도 뜨지 않은 빈 탭은 닫고 원래 화면으로 돌아간다.
        if (tab.web != null && tab.web.copyBackForwardList().getSize() == 0) {
            boolean wasShowing = isShowing(tab);
            Tab opener = tm.byId(tab.openerId);
            closeTab(tab);
            if (wasShowing && opener != null) show(opener);
            else render();
        }
        if (!url.matches("(?i)^https?://.*")) {
            toast("이 형식의 파일은 내려받을 수 없습니다. 메뉴의 'Chrome으로 열기'를 이용해 주세요");
            return;
        }
        try {
            String name = fileName(url, disposition, mime);
            // 서버가 형식을 뭉뚱그려 보내면(octet-stream 등) 확장자로 알아낸다. 그래야 받은 APK 를 눌러 설치할 수 있다.
            int dot = name.lastIndexOf('.');
            String byExt = dot < 0 ? null : android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (byExt != null && (mime == null || mime.isEmpty() || mime.startsWith("application/octet-stream")
                    || mime.startsWith("text/plain") || mime.startsWith("binary/"))) {
                mime = byExt;
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) req.setMimeType(mime);
            String cookies = cookiesFor(tab).getCookie(url);
            if (cookies != null) req.addRequestHeader("Cookie", cookies);
            req.addRequestHeader("User-Agent", userAgent);
            req.setTitle(name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            toast("다운로드를 시작합니다: " + name);
        } catch (Exception e) {
            toast("다운로드를 시작하지 못했습니다");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MEDIA_PERM || pendingMedia == null) return;
        PermissionRequest req = pendingMedia;
        pendingMedia = null;
        boolean granted = results.length > 0;
        for (int r : results) granted &= r == PackageManager.PERMISSION_GRANTED;
        if (granted) req.grant(req.getResources());
        else req.deny();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
