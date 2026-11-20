package com.chromeview.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 크롬뷰를 기본 브라우저로 지정했을 때 링크를, 그리고 다른 앱의 '공유'를 받는 통로.
 * 화면 없이 유지 서비스를 켠 뒤 설정에 따라 크롬뷰 고정 창이나 Chrome 으로 넘기고 사라진다.
 */
public class LinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KeepAliveService.start(this);
        Diag.log(this, "다른 앱이 크롬뷰를 부름: " + getIntent().getAction() + " · "
                + (getIntent().getData() == null ? "주소 없음" : Diag.brief(getIntent().getDataString()))
                + (getReferrer() != null ? " · 보낸 앱 " + getReferrer().getHost() : ""));
        Uri data = getIntent().getData();
        boolean share = Intent.ACTION_SEND.equals(getIntent().getAction());
        boolean pinned = getSharedPreferences("app", MODE_PRIVATE).getBoolean("usePinned", true);
        if (share || (pinned && data != null)) {
            // 공유로 받은 것은 항상, 링크는 설정이 켜져 있을 때 크롬뷰 고정 창에서 연다.
            Intent in = new Intent(this, MainActivity.class).setAction(getIntent().getAction())
                    .setData(data).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) in.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(in);
        } else if (data != null) {
            Diag.log(this, "받은 링크를 Chrome으로 넘김: " + Diag.brief(data.toString()));
            Intent out = new Intent(Intent.ACTION_VIEW, data)
                    .setPackage(KeepAliveService.CHROME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                // 앱 내 브라우저(Custom Tabs) 요청 정보 등 보낸 앱이 붙인 부가 정보도 함께 넘긴다.
                Intent withExtras = new Intent(out);
                if (getIntent().getExtras() != null) withExtras.putExtras(getIntent().getExtras());
                startActivity(withExtras);
            } catch (RuntimeException e) {
                try {
                    startActivity(out);
                } catch (RuntimeException e2) {
                    Toast.makeText(this, "Chrome을 실행하지 못했습니다", Toast.LENGTH_SHORT).show();
                }
            }
        }
        finish();
    }
}
