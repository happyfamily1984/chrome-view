package com.chromeview.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 설정 화면. 홈(고정 창 목록)에서 열리며, 뒤로가기를 누르면 이전 화면으로 돌아간다. */
public class SettingsActivity extends Activity {
    private TextView status, selfState, defaultState, homepageState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("app", MODE_PRIVATE);
        boolean firstRun = !prefs.getBoolean("setupShown", false);

        setContentView(R.layout.activity_settings);
        status = findViewById(R.id.status);
        selfState = findViewById(R.id.selfState);
        findViewById(R.id.btnChrome).setOnClickListener(v -> confirmOpenChrome());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        defaultState = findViewById(R.id.defaultState);
        findViewById(R.id.rowDefault).setOnClickListener(v -> askDefaultBrowser());
        homepageState = findViewById(R.id.homepageState);
        findViewById(R.id.rowHomepage).setOnClickListener(v -> chooseHomepage());
        findViewById(R.id.rowSelf).setOnClickListener(v -> askBatteryExemption());
        Switch boot = findViewById(R.id.autoStart);
        boot.setChecked(prefs.getBoolean("autoStart", true));
        boot.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("autoStart", on).apply());
        findViewById(R.id.rowChrome).setOnClickListener(v -> openChromeBatterySettings());
        findViewById(R.id.rowDiag).setOnClickListener(v -> showDiag());
        findViewById(R.id.rowAutofill).setOnClickListener(v -> setUpAutofill());
        findViewById(R.id.rowBookmarks).setOnClickListener(v -> pickBookmarkFile());
        Switch pinned = findViewById(R.id.usePinned);
        pinned.setChecked(prefs.getBoolean("usePinned", true));
        pinned.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("usePinned", on).apply());

        if (firstRun) {
            prefs.edit().putBoolean("setupShown", true).apply();
            showFirstRunGuide();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        KeepAliveService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status == null) return;
        refresh();
        // Chrome 과의 연결은 조금 뒤에 맺어지므로 한 번 더 갱신한다.
        status.postDelayed(this::refresh, 1500);
    }

    private void refresh() {
        boolean unrestricted = getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName());
        selfState.setText(unrestricted ? "완료" : "설정하기");
        defaultState.setText(isDefaultBrowser() ? "완료" : "설정하기");
        homepageState.setText(homepageLabel(MainActivity.homepage(this)));
        if (getPackageManager().getLaunchIntentForPackage(KeepAliveService.CHROME) == null) {
            status.setText("Chrome 앱을 찾을 수 없습니다. Chrome을 설치하거나 사용 설정해 주세요.");
        } else if (!KeepAliveService.holdingChrome) {
            status.setText("유지 서비스 켜짐 · Chrome과는 아직 연결되지 않았습니다.");
        } else if (!unrestricted) {
            status.setText("유지 켜짐 · 단, 크롬뷰에 배터리 절전이 걸려 있어 유지가 끊길 수 있습니다. "
                    + "아래 '크롬뷰 배터리 제한 없음'을 눌러 주세요.");
        } else {
            status.setText("유지 켜짐 · 크롬뷰 고정 창의 페이지는 다른 앱을 다녀와도 그대로 남습니다.");
        }
    }

    /**
     * 다른 앱은 Chrome 안의 비밀번호를 직접 읽을 수 없다. 대신 폰의 '자동 완성 서비스'를 Google 로 두면
     * Chrome 이 쓰는 것과 같은 Google 비밀번호 관리자가 크롬뷰의 로그인 칸에도 아이디·비밀번호를 제안한다.
     */
    private void setUpAutofill() {
        new AlertDialog.Builder(this)
                .setTitle("저장된 아이디·비밀번호 쓰기")
                .setMessage("다음 화면에서 'Google'을 선택해 주세요.\n\n"
                        + "그러면 크롬뷰에서 로그인 칸을 누를 때, Chrome에 저장해 둔 아이디와 비밀번호가 "
                        + "제안으로 나타납니다. Google 계정에 저장한 주소·연락처 자동 완성도 함께 쓸 수 있고, "
                        + "새로 로그인한 정보도 같은 곳에 저장됩니다.\n\n"
                        + "(삼성 패스를 쓰고 계셨다면 그대로 두셔도 됩니다. 그 경우 삼성 패스에 저장된 정보가 제안됩니다.)")
                .setPositiveButton("설정 열기", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        toast("설정 > 일반 > 비밀번호 및 자동 완성에서 'Google'을 선택해 주세요");
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void pickBookmarkFile() {
        new AlertDialog.Builder(this)
                .setTitle("북마크 파일 가져오기")
                .setMessage("휴대폰 Chrome에는 북마크를 내보내는 기능이 없어서, 파일을 한 번 만들어 와야 합니다.\n\n"
                        + "• PC Chrome: 메뉴 → 북마크 및 목록 → 북마크 관리자 → ⋮ → 북마크 내보내기\n"
                        + "• 또는 takeout.google.com 에서 Chrome 북마크만 선택해 내려받기\n\n"
                        + "만든 .html 파일을 폰으로 옮긴 뒤 선택해 주세요.")
                .setPositiveButton("파일 선택", (d, w) -> {
                    try {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                                .putExtra(Intent.EXTRA_MIME_TYPES,
                                        new String[]{"text/html", "application/xhtml+xml", "text/plain"}), 3);
                    } catch (ActivityNotFoundException e) {
                        toast("파일을 선택할 앱이 없습니다");
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 3 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 8_000_000) buf.write(chunk, 0, n);
            String html = buf.toString("UTF-8");
            java.util.List<Favorites.Item> found = new java.util.ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<a\\s[^>]*?href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(html);
            while (m.find()) {
                String url = android.text.Html.fromHtml(m.group(1), 0).toString();
                String title = android.text.Html.fromHtml(m.group(2), 0).toString().trim();
                found.add(new Favorites.Item(title.isEmpty() ? url : title, url));
            }
            if (found.isEmpty()) {
                toast("이 파일에서 북마크를 찾지 못했습니다");
                return;
            }
            int added = new Favorites(this).addAll(found);
            Toast.makeText(this, "북마크 " + found.size() + "개 중 " + added + "개를 즐겨찾기에 추가했습니다"
                    + (added < found.size() ? " (나머지는 이미 있음)" : ""), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            toast("파일을 읽지 못했습니다");
        }
    }

    private static final String[] HOME_NAMES = {"네이버", "구글", "다음"};
    private static final String[] HOME_URLS = {MainActivity.DEFAULT_HOMEPAGE, "https://www.google.com", "https://m.daum.net"};

    private static String homepageLabel(String url) {
        for (int i = 0; i < HOME_URLS.length; i++) if (HOME_URLS[i].equals(url)) return HOME_NAMES[i];
        String host = Uri.parse(url).getHost();
        return host == null ? url : host.replaceFirst("^(www|m)\\.", "");
    }

    private void chooseHomepage() {
        String[] items = {HOME_NAMES[0], HOME_NAMES[1], HOME_NAMES[2], "직접 입력"};
        new AlertDialog.Builder(this)
                .setTitle("시작 페이지")
                .setItems(items, (d, which) -> {
                    if (which < HOME_URLS.length) {
                        saveHomepage(HOME_URLS[which]);
                        return;
                    }
                    android.widget.EditText input = new android.widget.EditText(this);
                    input.setSingleLine(true);
                    input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI
                            | android.text.InputType.TYPE_CLASS_TEXT);
                    input.setHint("예: m.naver.com");
                    input.setText(MainActivity.homepage(this));
                    android.widget.FrameLayout box = new android.widget.FrameLayout(this);
                    int pad = (int) (20 * getResources().getDisplayMetrics().density);
                    box.setPadding(pad, pad / 2, pad, 0);
                    box.addView(input);
                    new AlertDialog.Builder(this)
                            .setTitle("시작 페이지 주소")
                            .setView(box)
                            .setPositiveButton("저장", (d2, w2) -> {
                                String text = input.getText().toString().trim();
                                if (text.isEmpty()) return;
                                if (!text.matches("(?i)^https?://.*")) text = "https://" + text;
                                saveHomepage(text);
                            })
                            .setNegativeButton("취소", null)
                            .show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void saveHomepage(String url) {
        getSharedPreferences("app", MODE_PRIVATE).edit().putString("homepage", url).apply();
        homepageState.setText(homepageLabel(url));
    }

    private void showDiag() {
        String text = Diag.read(this);
        new AlertDialog.Builder(this)
                .setTitle("진단 기록")
                .setMessage(text)
                .setPositiveButton("복사", (d, w) -> {
                    getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                            android.content.ClipData.newPlainText("크롬뷰 진단 기록", text));
                    toast("복사했습니다");
                })
                .setNegativeButton("닫기", null)
                .show();
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
        if (i == null) {
            toast("Chrome 앱이 설치되어 있지 않습니다");
            return false;
        }
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            toast("Chrome을 실행하지 못했습니다");
            return false;
        }
    }

    private void showFirstRunGuide() {
        new AlertDialog.Builder(this)
                .setTitle("처음 한 번만 설정해 주세요")
                .setMessage("갤럭시는 배터리를 아끼려고 화면에 없는 앱을 정리합니다. 그래서 인증하고 돌아오면 "
                        + "페이지가 처음으로 돌아가 있습니다.\n\n"
                        + "1. 다음 화면에서 '허용'을 눌러 크롬뷰를 배터리 제한 없음으로 둡니다.\n"
                        + "2. 알림을 허용해 주세요. 알림이 떠 있는 동안 페이지를 붙잡아 둡니다.\n"
                        + "3. '기본 브라우저를 크롬뷰로'를 눌러 두면 링크도 고정 창으로 열립니다.")
                .setPositiveButton("설정하기", (d, w) -> askBatteryExemption())
                .setNegativeButton("나중에", null)
                .setOnDismissListener(d -> {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                    }
                })
                .show();
    }

    private boolean isDefaultBrowser() {
        return Build.VERSION.SDK_INT >= 29
                && getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_BROWSER);
    }

    private void askDefaultBrowser() {
        try {
            if (Build.VERSION.SDK_INT >= 29 && !isDefaultBrowser()) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), 2);
                    return;
                }
            }
            // 이미 지정돼 있거나 요청 창을 쓸 수 없으면 기본 앱 설정 화면을 연다.
            startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("설정 > 애플리케이션 > 기본 앱 선택 > 브라우저 앱에서 크롬뷰를 선택해 주세요");
        }
    }

    private void askBatteryExemption() {
        Uri pkg = Uri.parse("package:" + getPackageName());
        try {
            if (getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
                toast("이미 '제한 없음'으로 설정되어 있습니다");
            } else {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg));
            }
        } catch (ActivityNotFoundException e) {
            toast("설정 > 애플리케이션 > 크롬뷰 > 배터리에서 '제한 없음'을 선택해 주세요");
        }
    }

    private void openChromeBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + KeepAliveService.CHROME)));
            Toast.makeText(this, "배터리 → '제한 없음'을 선택해 주세요", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            toast("설정 > 애플리케이션 > Chrome > 배터리에서 '제한 없음'을 선택해 주세요");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
