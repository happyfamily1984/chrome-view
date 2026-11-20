package com.chromeview.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 크롬뷰를 기본 브라우저로 지정했을 때 링크를 받는 통로. 화면 없이 유지 서비스를 켠 뒤
 * 받은 링크를 그대로 Chrome 에 넘기고 사라진다.
 */
public class LinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KeepAliveService.start(this);
        Uri data = getIntent().getData();
        if (data != null) {
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
