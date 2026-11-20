package com.chromeview.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 크롬뷰에서 방문한 페이지 기록. 주소창 자동완성에 쓴다. 폰 안에만 저장된다. */
class History {
    private static final int MAX = 400;
    private final SharedPreferences prefs;
    /** 최근 방문이 앞에 온다. {제목, 주소, 방문 횟수} */
    final List<String[]> items = new ArrayList<>();

    History(Context c) {
        prefs = c.getSharedPreferences("history", Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                items.add(new String[]{o.optString("t"), o.getString("u"), String.valueOf(o.optInt("n", 1))});
            }
        } catch (Exception ignored) {
        }
    }

    synchronized void add(String title, String url) {
        if (url == null || !url.matches("(?i)^https?://.*")) return;
        if (title == null) title = "";
        // 같은 페이지가 제목만 바뀌어 연달아 들어오는 것은 한 번의 방문으로 친다.
        if (!items.isEmpty() && items.get(0)[1].equals(url)) {
            items.get(0)[0] = title;
            persist();
            return;
        }
        int count = 1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i)[1].equals(url)) {
                count = Integer.parseInt(items.remove(i)[2]) + 1;
                break;
            }
        }
        items.add(0, new String[]{title, url, String.valueOf(count)});
        while (items.size() > MAX) items.remove(items.size() - 1);
        persist();
    }

    synchronized void clear() {
        items.clear();
        persist();
    }

    synchronized List<String[]> snapshot() {
        return new ArrayList<>(items);
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (String[] i : items) arr.put(new JSONObject().put("t", i[0]).put("u", i[1]).put("n", Integer.parseInt(i[2])));
            prefs.edit().putString("items", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
