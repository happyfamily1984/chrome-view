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

/**
 * 크롬뷰의 유일한 화면(홈). 웹 페이지는 여기서 열지 않는다. 로그인·메뉴는 전부 실제 Chrome 이 맡고,
 * 이 앱은 유지 서비스를 켜고 Chrome 을 실행해 주기만 한다.
 */
public class MainActivity extends Activity {
    static final String ACTION_SETTINGS = "com.chromeview.app.SETTINGS";

    private TextView status, selfState, defaultState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("app", MODE_PRIVATE);
        boolean firstRun = !prefs.getBoolean("setupShown", false);
        boolean fromIcon = savedInstanceState == null
                && !ACTION_SETTINGS.equals(getIntent().getAction())
                && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;

        // 아이콘으로 켠 경우: 유지 서비스를 올리고 곧바로 Chrome 으로 넘긴 뒤 이 화면은 닫는다.
        if (fromIcon && !firstRun && prefs.getBoolean("autoChrome", true)) {
            KeepAliveService.start(this);
            if (launchChrome()) {
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        selfState = findViewById(R.id.selfState);
        findViewById(R.id.btnChrome).setOnClickListener(v -> {
            // 이 화면을 닫아 두어야 다음에 아이콘을 눌렀을 때 곧바로 Chrome 이 열린다.
            if (launchChrome() && prefs.getBoolean("autoChrome", true)) finish();
        });
        defaultState = findViewById(R.id.defaultState);
        findViewById(R.id.rowDefault).setOnClickListener(v -> askDefaultBrowser());
        findViewById(R.id.rowSelf).setOnClickListener(v -> askBatteryExemption());
        Switch boot = findViewById(R.id.autoStart);
        boot.setChecked(prefs.getBoolean("autoStart", true));
        boot.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("autoStart", on).apply());
        findViewById(R.id.rowChrome).setOnClickListener(v -> openChromeBatterySettings());
        Switch auto = findViewById(R.id.autoChrome);
        auto.setChecked(prefs.getBoolean("autoChrome", true));
        auto.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("autoChrome", on).apply());

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
        if (getPackageManager().getLaunchIntentForPackage(KeepAliveService.CHROME) == null) {
            status.setText("Chrome 앱을 찾을 수 없습니다. Chrome을 설치하거나 사용 설정해 주세요.");
        } else if (!KeepAliveService.holdingChrome) {
            status.setText("Chrome과 연결하는 중입니다. 'Chrome 열기'를 눌러 주세요.");
        } else if (!unrestricted) {
            status.setText("Chrome 유지 켜짐 · 단, 크롬뷰에 배터리 절전이 걸려 있어 유지가 끊길 수 있습니다. "
                    + "아래 '크롬뷰 배터리 제한 없음'을 눌러 주세요.");
        } else {
            status.setText("Chrome 유지 켜짐 · 다른 앱을 다녀와도 Chrome이 정리되지 않게 붙잡고 있습니다.");
        }
    }

    /** 홈(첫 화면)에서 뒤로가기: 종료 여부를 묻는다. 열려 있던 안내창은 시스템이 먼저 닫는다. */
    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setMessage("앱을 종료하겠습니까?")
                .setPositiveButton("예", (d, w) -> {
                    KeepAliveService.stop(this); // 종료하면 Chrome 유지도 함께 멈춘다
                    finishAndRemoveTask();
                })
                .setNegativeButton("아니오", null)
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
                        + "Chrome 페이지가 처음으로 돌아가 있습니다.\n\n"
                        + "1. 다음 화면에서 '허용'을 눌러 크롬뷰를 배터리 제한 없음으로 둡니다.\n"
                        + "2. 이 화면의 'Chrome 배터리 제한 없음'도 눌러 설정합니다.\n"
                        + "3. 알림을 허용해 주세요. 알림이 떠 있는 동안 Chrome을 붙잡아 둡니다.\n"
                        + "4. '기본 브라우저를 크롬뷰로'를 눌러 두면 링크를 열 때도 자동으로 유지가 켜집니다.")
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
