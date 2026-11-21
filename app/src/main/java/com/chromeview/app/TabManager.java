package com.chromeview.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 열린 탭 목록과, 앱이 완전히 꺼졌다 켜져도 되살릴 수 있는 디스크 저장을 맡는다. */
class TabManager {
    final List<Tab> tabs = new ArrayList<>();
    boolean atHome = true;
    private long currentId = -1;
    private long nextId = 1;
    private final File dir;

    TabManager(Context app) {
        dir = new File(app.getFilesDir(), "tabs");
        dir.mkdirs();
    }

    Tab create(String url, long openerId) {
        Tab t = new Tab();
        t.id = nextId++;
        t.url = url == null ? "" : url;
        t.openerId = openerId;
        tabs.add(t);
        return t;
    }

    Tab current() {
        return byId(currentId);
    }

    void select(Tab t) {
        currentId = t.id;
        atHome = false;
    }

    Tab byId(long id) {
        for (Tab t : tabs) if (t.id == id) return t;
        return null;
    }

    void remove(Tab t) {
        tabs.remove(t);
        if (t.web != null) {
            t.web.destroy();
            t.web = null;
        }
        new File(dir, "state_" + t.id + ".bin").delete();
    }

    /** 메모리의 페이지를 모두 내린다(디스크 저장분은 그대로). */
    void destroyAll() {
        for (Tab t : tabs) {
            if (t.web != null) {
                if (t.incognito) {
                    t.web.destroy();
                    t.web = null;
                    continue;
                }
                Bundle b = new Bundle();
                t.saved = t.web.saveState(b) != null ? b : null;
                t.web.destroy();
                t.web = null;
            }
        }
    }

    void save() {
        try {
            CookieManager.getInstance().flush();
            JSONArray arr = new JSONArray();
            Set<String> keep = new HashSet<>();
            for (Tab t : tabs) {
                Bundle state = t.saved;
                if (t.web != null) {
                    Bundle b = new Bundle();
                    state = t.web.saveState(b) != null ? b : null;
                }
                // 시크릿 탭은 디스크에 남기지 않는다. 아무것도 불러오지 않은 빈 팝업도 되살릴 내용이 없다.
                if (t.incognito || (state == null && t.url.isEmpty())) continue;
                arr.put(new JSONObject().put("id", t.id).put("opener", t.openerId)
                        .put("title", t.title).put("url", t.url));
                String name = "state_" + t.id + ".bin";
                if (state != null) {
                    Parcel p = Parcel.obtain();
                    try {
                        p.writeBundle(state);
                        write(new File(dir, name), p.marshall());
                        keep.add(name);
                    } finally {
                        p.recycle();
                    }
                }
            }
            JSONObject root = new JSONObject().put("tabs", arr).put("current", currentId)
                    .put("atHome", atHome).put("nextId", nextId);
            write(new File(dir, "tabs.json"), root.toString().getBytes(StandardCharsets.UTF_8));
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("state_") && !keep.contains(f.getName())) f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    void load() {
        tabs.clear();
        try {
            File f = new File(dir, "tabs.json");
            if (!f.exists()) return;
            JSONObject root = new JSONObject(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            nextId = root.optLong("nextId", 1);
            currentId = root.optLong("current", -1);
            atHome = root.optBoolean("atHome", true);
            JSONArray arr = root.getJSONArray("tabs");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Tab t = new Tab();
                t.id = o.getLong("id");
                t.openerId = o.optLong("opener", -1);
                t.title = o.optString("title");
                t.url = o.optString("url");
                t.saved = readState(new File(dir, "state_" + t.id + ".bin"));
                tabs.add(t);
                nextId = Math.max(nextId, t.id + 1);
            }
        } catch (Exception e) {
            tabs.clear();
        }
        if (current() == null) atHome = true;
    }

    private Bundle readState(File f) {
        if (!f.exists()) return null;
        Parcel p = Parcel.obtain();
        try {
            byte[] d = Files.readAllBytes(f.toPath());
            p.unmarshall(d, 0, d.length);
            p.setDataPosition(0);
            return p.readBundle(getClass().getClassLoader());
        } catch (Throwable e) {
            return null; // 형식이 달라졌으면 주소만으로 다시 연다
        } finally {
            p.recycle();
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename");
    }
}
