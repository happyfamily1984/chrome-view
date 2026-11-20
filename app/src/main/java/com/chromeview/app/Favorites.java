package com.chromeview.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

class Favorites {
    static class Item {
        final String title, url;

        Item(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private final SharedPreferences prefs;
    final List<Item> items = new ArrayList<>();

    Favorites(Context c) {
        prefs = c.getSharedPreferences("favorites", Context.MODE_PRIVATE);
        String raw = prefs.getString("items", null);
        if (raw == null) {
            items.add(new Item("네이버", "https://m.naver.com"));
            items.add(new Item("구글", "https://www.google.com"));
            items.add(new Item("다음", "https://m.daum.net"));
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                items.add(new Item(o.getString("t"), o.getString("u")));
            }
        } catch (Exception ignored) {
        }
    }

    boolean contains(String url) {
        for (Item i : items) if (i.url.equals(url)) return true;
        return false;
    }

    void add(String title, String url) {
        items.add(new Item(title, url));
        persist();
    }

    void remove(Item item) {
        items.remove(item);
        persist();
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (Item i : items) arr.put(new JSONObject().put("t", i.title).put("u", i.url));
            prefs.edit().putString("items", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
