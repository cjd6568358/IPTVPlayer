package com.your.iptv.source;

import android.content.Context;

import com.your.iptv.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** 频道列表:网络拉取 → 本地 JSON 缓存 → 断网离线可用。多线路(rtp2httpd 3.17.2)序列化。 */
public final class ChannelRepository {

    private static final String CACHE = "channels.json";

    private ChannelRepository() {}

    /** 优先本地缓存(同步,秒回);无缓存时返回内置测试频道。 */
    public static List<Channel> loadCached(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), CACHE);
            if (f.exists()) {
                JSONArray arr = new JSONArray(readFile(f));
                List<Channel> out = new ArrayList<Channel>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    List<String> urls = new ArrayList<String>();
                    List<String> labels = new ArrayList<String>();
                    JSONArray ju = o.optJSONArray("urls");
                    JSONArray jl = o.optJSONArray("labels");
                    if (ju != null) {
                        for (int k = 0; k < ju.length(); k++) urls.add(ju.getString(k));
                        for (int k = 0; jl != null && k < jl.length(); k++) labels.add(jl.getString(k));
                    }
                    if (urls.isEmpty()) urls.add(o.getString("url"));
                    while (labels.size() < urls.size()) labels.add("线路" + (labels.size() + 1));
                    out.add(new Channel(o.getString("name"), urls, labels,
                            o.optString("group", "其他"), out.size() + 1));
                }
                if (out.size() > 0) return out;
            }
        } catch (Throwable ignore) {}
        return demoList();
    }

    /** 技术验证用内置频道(公开 HLS 测试流,H.264 1080p)。 */
    public static List<Channel> demoList() {
        List<Channel> l = new ArrayList<Channel>();
        List<String> urls = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        urls.add("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8");
        labels.add("线路1");
        l.add(new Channel("测试流(BigBuckBunny HLS)", urls, labels, "测试", 1));
        return l;
    }

    /** 后台线程调用:从 URL(m3u 列表)拉取并写缓存。 */
    public static List<Channel> fetchAndCache(String listUrl) throws Exception {
        String text = httpGet(listUrl);
        List<Channel> list = M3uParser.parse(text);
        JSONArray arr = new JSONArray();
        for (Channel c : list) {
            JSONObject o = new JSONObject();
            o.put("name", c.name);
            o.put("group", c.group);
            JSONArray ju = new JSONArray();
            for (String u : c.urls) ju.put(u);
            o.put("urls", ju);
            JSONArray jl = new JSONArray();
            for (String s : c.labels) jl.put(s);
            o.put("labels", jl);
            arr.put(o);
        }
        writeFile(new File(App.get().getFilesDir(), CACHE), arr.toString(2));
        return list;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "LetvY43-IPTV/1.0");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    private static void writeFile(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes("UTF-8"));
        out.flush();
        out.getFD().sync();
        out.close();
    }
}
