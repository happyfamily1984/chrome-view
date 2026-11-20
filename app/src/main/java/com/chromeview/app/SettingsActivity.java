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
    private TextView status, selfState, defaultState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("app", MODE_PRIVATE);
        boolean firstRun = !prefs.getBoolean("setupShown", false);

        setContentView(R.layout.activity_settings);
        status = findViewById(R.id.status);
        selfState = findViewById(R.id.selfState);
        findViewById(R.id.btnChrome).setOnClickListener(v -> launchChrome());
        defaultState = findViewById(R.id.defaultState);
        findViewById(R.id.rowDefault).setOnClickListener(v -> askDefaultBrowser());
        findViewById(R.id.rowSelf).setOnClickListener(v -> askBatteryExemption());
        Switch boot = findViewById(R.id.autoStart);
        boot.setChecked(prefs.getBoolean("autoStart", true));
        boot.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("autoStart", on).apply());
        findViewById(R.id.rowChrome).setOnClickListener(v -> openChromeBatterySettings());
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
