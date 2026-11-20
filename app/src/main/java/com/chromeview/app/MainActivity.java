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
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
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

    private static final int REQ_FILE = 1;
    private static final int REQ_MEDIA_PERM = 2;
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private TabManager tm;
    private Favorites favorites;
    private FrameLayout webContainer, fullscreen;
    private View home, root;
    private EditText urlBox;
    private ProgressBar progress;
    private LinearLayout tabList, favList;
    private TextView tabsHeader, status, tabCount;

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMedia;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean exiting;
    /** 방금 외부 앱(인증 앱 등)을 부른 탭. 그 앱이 링크로 되돌아올 때 이 탭과 이어 준다. */
    private static long waitingTabId = -1;
    private static long waitingSince;

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
        favorites = new Favorites(this);

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

        findViewById(R.id.btnHome).setOnClickListener(v -> goHome());
        findViewById(R.id.btnTabs).setOnClickListener(v -> goHome());
        findViewById(R.id.btnMenu).setOnClickListener(this::showMenu);
        findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btnChrome).setOnClickListener(v -> confirmOpenChrome());
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
        if (tm != null && tm.atHome) rebuildHome();
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
        Tab t = tm.atHome ? null : tm.current();
        tabCount.setText(String.valueOf(tm.tabs.size()));
        if (t == null) {
            tm.atHome = true;
            home.setVisibility(View.VISIBLE);
            webContainer.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            urlBox.setText("");
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
        urlBox.setText(t.url);
        int p = t.web.getProgress();
        progress.setProgress(p);
        progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
    }

    private void goHome() {
        leaveUrlBox();
        tm.atHome = true;
        render();
    }

    private void show(Tab t) {
        tm.select(t);
        render();
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
        show(tm.create(url, waiting != null ? waiting.id : -1));
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
        if (cur != null && stripFragment(cur.url).equals(want)) return cur;
        for (int i = tm.tabs.size() - 1; i >= 0; i--) {
            if (stripFragment(tm.tabs.get(i).url).equals(want)) return tm.tabs.get(i);
        }
        return null;
    }

    private static String stripFragment(String url) {
        int i = url.indexOf('#');
        url = i < 0 ? url : url.substring(0, i);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void closeTab(Tab t) {
        if (t.web != null) webContainer.removeView(t.web);
        tm.remove(t);
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
        if (tm.tabs.isEmpty()) {
            tabList.addView(note("열린 탭이 없습니다. 위 주소창에 검색어나 주소를 입력하세요."));
        }
        for (Tab t : new ArrayList<>(tm.tabs)) {
            View row = row(inf, tabList, t.title.isEmpty() ? "새 탭" : t.title, t.url);
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
            urlBox.setText(t == null ? "" : t.url);
            return;
        }
        if (tm.atHome) {
            new AlertDialog.Builder(this)
                    .setMessage("앱을 종료하겠습니까?")
                    .setPositiveButton("예", (d, w) -> exitApp())
                    .setNegativeButton("아니오", null)
                    .show();
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
        goHome();
    }

    /** 종료해도 탭과 로그인 쿠키는 저장되어 다음 실행 때 되살아난다. */
    private void exitApp() {
        exiting = true;
        tm.save();
        webContainer.removeAllViews();
        tm.destroyAll();
        tm.atHome = true;
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
        m.add(0, 8, 0, "설정");
        m.add(0, 9, 0, "로그인 정보 모두 삭제");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    goHome();
                    urlBox.requestFocus();
                    getSystemService(InputMethodManager.class).showSoftInput(urlBox, 0);
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
                    goHome();
                    break;
                case 8:
                    openSettings();
                    break;
                case 9:
                    confirmClearLogins();
                    break;
            }
            return true;
        });
        pm.show();
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
        WebView w = new WebView(t.ctx);
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
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        // 화면에 안 보여도 페이지 프로세스를 정리 대상에서 뺀다.
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
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
                download(url, userAgent, disposition, mime));
    }

    private boolean isShowing(Tab t) {
        return !tm.atHome && tm.current() == t;
    }

    private void onTabChanged(Tab t) {
        if (isShowing(t)) {
            if (!urlBox.hasFocus()) urlBox.setText(t.url);
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
            onTabChanged(tab);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // 메모리 부족으로 페이지 프로세스가 죽은 경우: 앱은 살리고 그 탭만 다시 연다.
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
                else goHome();
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

    private void download(String url, String userAgent, String disposition, String mime) {
        if (!url.matches("(?i)^https?://.*")) {
            toast("이 형식의 파일은 내려받을 수 없습니다. 메뉴의 'Chrome으로 열기'를 이용해 주세요");
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) req.setMimeType(mime);
            String cookies = CookieManager.getInstance().getCookie(url);
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
