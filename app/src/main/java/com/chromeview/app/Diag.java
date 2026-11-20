package com.chromeview.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 진단 기록. 인증하고 돌아왔을 때 페이지가 왜 바뀌었는지(다른 앱이 링크를 보냈는지, 페이지가
 * 죽었는지, 앱이 통째로 꺼졌는지) 폰 안에서 확인할 수 있게 최근 일들을 남긴다. 폰 밖으로 나가지 않는다.
 */
final class Diag {
    private static final int MAX = 60;

    static synchronized void log(Context c, String msg) {
        try {
            SharedPreferences p = c.getSharedPreferences("diag", Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(p.getString("log", "[]"));
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, old.length() - MAX + 1); i < old.length(); i++) out.put(old.get(i));
            out.put(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + msg);
            p.edit().putString("log", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static synchronized String read(Context c) {
        try {
            JSONArray a = new JSONArray(
                    c.getSharedPreferences("diag", Context.MODE_PRIVATE).getString("log", "[]"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) sb.append(a.getString(i)).append('\n');
            return sb.length() == 0 ? "기록이 없습니다." : sb.toString();
        } catch (Exception e) {
            return "기록을 읽지 못했습니다.";
        }
    }

    /** 주소에서 인증 토큰 같은 민감한 부분(물음표 뒤)은 빼고 남긴다. */
    static String brief(String url) {
        try {
            Uri u = Uri.parse(url);
            if (u.getHost() == null) return u.getScheme() + ":";
            String path = u.getPath() == null ? "" : u.getPath();
            return u.getScheme() + "://" + u.getHost() + (path.length() > 40 ? path.substring(0, 40) + "…" : path);
        } catch (Exception e) {
            return "?";
        }
    }
}
