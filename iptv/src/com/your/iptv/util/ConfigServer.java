package com.your.iptv.util;

import android.content.Context;

import com.your.iptv.App;
import com.your.iptv.source.Channel;
import com.your.iptv.tvcontroller.TVController;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 常驻 HTTP 服务(App.onCreate 启动,应用运行期间一直在)。
 *
 * 手机端 = rtp2httpd 3.17.2 官方网页播放器原版(assets/player,首次启动解压到 filesDir/web):
 *   GET  / , /assets/* , /player.webmanifest   → 官方 SPA(index.html 注入 TV 同步面板)
 *   GET  /playlist.m3u                          → 代理 M3U 列表,URL 重写为经本机代理(解 CORS)
 *   GET  /s/<enc>                               → 流代理:rtp2httpd → 手机(mpegts.js 拉流)
 *   注入 /tvsync.js + 面板:TV 设置读取(/api/config)与保存(/save)、电视当前频道显示
 *
 * TV 控制/配置(保留):
 *   GET  /api/state | /api/tune?i= | /api/pause | /api/volume?v=
 *   GET  /api/config      GET|POST /save(PRG)   GET /config
 */
public final class ConfigServer {

    public interface OnConfigChanged { void onConfigChanged(); }

    private static Thread sThread;
    private static volatile boolean sRunning;
    private static OnConfigChanged sCallback;
    public static final int PORT = 8123;
    private static final String UA = "LetvY43-IPTV/1.0";
    /** rtp2httpd 源站(由 list_url 的 origin 推得;运行期缓存)。 */
    private static volatile String sUpstreamOrigin;

    private ConfigServer() {}

    public static void setOnConfigChanged(OnConfigChanged cb) { sCallback = cb; }

    /** 常驻启动(App.onCreate 调用一次;重复调用无副作用)。 */
    public static synchronized void start(Context ctx) {
        if (sRunning) return;
        sRunning = true;
        extractWebAssets(ctx);
        sThread = new Thread(new Runnable() {
            @Override public void run() {
                ServerSocket server = null;
                try {
                    server = new ServerSocket(PORT);
                    while (sRunning) {
                        final Socket sock = server.accept();
                        handle(sock);
                    }
                } catch (Throwable ignore) {
                } finally {
                    if (server != null) try { server.close(); } catch (Throwable ignore) {}
                    sRunning = false;
                }
            }
        }, "RemoteServer");
        sThread.start();
    }

    /** assets/player/** → filesDir/web/**(每次启动覆盖,保证升级生效)。 */
    private static void extractWebAssets(Context ctx) {
        try {
            copyAssetDir(ctx, "player", new File(ctx.getFilesDir(), "web"));
        } catch (Throwable ignore) {}
    }

    private static void copyAssetDir(Context ctx, String assetPath, File dstDir) {
        try {
            String[] children = ctx.getAssets().list(assetPath);
            if (children == null || children.length == 0) {
                // 是文件
                dstDir.getParentFile().mkdirs();
                InputStream in = ctx.getAssets().open(assetPath);
                OutputStream out = new FileOutputStream(dstDir);
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
                return;
            }
            dstDir.mkdirs();
            for (String c : children) copyAssetDir(ctx, assetPath + "/" + c, new File(dstDir, c));
        } catch (Throwable ignore) {}
    }

    // ================= 连接处理 =================

    private static void handle(final Socket sock) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    sock.setSoTimeout(15000);       // 拉流代理为长连接,读侧超时仅约束头部
                    BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    String requestLine = r.readLine();
                    if (requestLine == null) { sock.close(); return; }
                    String[] parts = requestLine.split(" ");
                    String method = parts.length > 0 ? parts[0] : "GET";
                    String target = parts.length > 1 ? parts[1] : "/";
                    String host = "host-unknown";
                    int contentLength = 0;
                    String h;
                    while ((h = r.readLine()) != null && h.length() > 0) {
                        String hl = h.toLowerCase();
                        if (hl.startsWith("host:")) host = h.substring(5).trim();
                        else if (hl.startsWith("content-length:")) {
                            try { contentLength = Integer.parseInt(h.substring(15).trim()); }
                            catch (Throwable ignore) {}
                        }
                    }
                    StringBuilder body = new StringBuilder();
                    if (contentLength > 0) {
                        char[] cb = new char[contentLength];
                        int read = 0;
                        while (read < contentLength) {
                            int n = r.read(cb, read, contentLength - read);
                            if (n < 0) break;
                            read += n;
                        }
                        body.append(cb, 0, read);
                    }

                    String path = target;
                    String query = "";
                    int qi = target.indexOf('?');
                    if (qi >= 0) { path = target.substring(0, qi); query = target.substring(qi + 1); }
                    Map<String, String> params = parseQuery(query);
                    if ("POST".equals(method)) params.putAll(parseQuery(body.toString()));
                    String base = "http://" + host;      // 以请求实际到达的 IP 重写代理地址

                    if ("/api/state".equals(path)) {
                        sendJson(sock, apiState());
                    } else if ("/api/tune".equals(path)) {
                        TVController.Host host0 = TVController.host();
                        int idx = intParam(params, "i", -1);
                        if (host0 != null && idx >= 0) host0.tuneIndex(idx);
                        sendJson(sock, "{\"ok\":true,\"index\":" + idx + "}");
                    } else if ("/api/pause".equals(path)) {
                        TVController.Host host0 = TVController.host();
                        if (host0 != null) host0.setPaused(!host0.isPaused());
                        sendJson(sock, "{\"ok\":true}");
                    } else if ("/api/volume".equals(path)) {
                        TVController.Host host0 = TVController.host();
                        float v = (float) doubleParam(params, "v", -1);
                        if (host0 != null && v >= 0) host0.setVolume(v / 100f);
                        sendJson(sock, "{\"ok\":true}");
                    } else if ("/api/config".equals(path)) {
                        sendJson(sock, configJson());
                    } else if ("/save".equals(path) || "/api/save".equals(path)) {
                        applyParams(params);
                        if ("/api/save".equals(path)) {
                            sendJson(sock, "{\"ok\":true}");
                        } else {
                            // PRG:浏览器保存后回配置页
                            OutputStream out = sock.getOutputStream();
                            out.write(("HTTP/1.0 302 Found\r\nLocation: /config?saved=1\r\n"
                                    + "Content-Length: 0\r\nCache-Control: no-store\r\n\r\n")
                                    .getBytes("UTF-8"));
                            out.flush();
                        }
                    } else if ("/config".equals(path)) {
                        sendBytes(sock, configForm(params.containsKey("saved")).getBytes("UTF-8"), "text/html; charset=utf-8");
                    } else if ("/tvsync.js".equals(path)) {
                        sendBytes(sock, tvsyncJs().getBytes("UTF-8"), "application/javascript; charset=utf-8");
                    } else if ("/playlist.m3u".equals(path)) {
                        proxyPlaylist(sock, base);
                    } else if (path.startsWith("/s/")) {
                        streamProxy(sock, path.substring(3));
                    } else if (path.startsWith("/local/")) {
                        localStream(sock, path.substring(7));
                    } else if ("/".equals(path) || "/player".equals(path) || "/index.html".equals(path)) {
                        sendBytes(sock, webFile("index.html", base), "text/html; charset=utf-8");
                    } else if (path.startsWith("/assets/")) {
                        serveWebFile(sock, path.substring(1));
                    } else if ("/player.webmanifest".equals(path)) {
                        serveWebFile(sock, "player.webmanifest");
                    } else {
                        sendJson(sock, "{\"error\":\"not found\"}");
                    }
                } catch (Throwable ignore) {
                } finally {
                    try { sock.close(); } catch (Throwable ignore) {}
                }
            }
        }).start();
    }

    // ================= 手机端:rtp2httpd 官方 SPA =================

    /** index.html 注入 TV 同步面板后返回。 */
    private static byte[] webFile(String name, String base) {
        byte[] raw = readWeb(name);
        if (raw == null) return "{\"error\":\"spa missing\"}".getBytes();
        String html = new String(raw);
        String inject = "<script src=\"/tvsync.js\"></script>";
        if (html.contains("</body>")) {
            html = html.replaceFirst("</body>", inject + "</body>");
        } else {
            html = html + inject;
        }
        return html.getBytes();
    }

    private static void serveWebFile(Socket sock, String name) {
        byte[] data = readWeb(name);
        if (data == null) {
            try { sendJson(sock, "{\"error\":\"not found\"}"); } catch (Throwable ignore) {}
            return;
        }
        String type = "application/octet-stream";
        if (name.endsWith(".js")) type = "application/javascript; charset=utf-8";
        else if (name.endsWith(".css")) type = "text/css; charset=utf-8";
        else if (name.endsWith(".html")) type = "text/html; charset=utf-8";
        else if (name.endsWith(".png")) type = "image/png";
        else if (name.endsWith(".webmanifest")) type = "application/manifest+json";
        try {
            OutputStream out = sock.getOutputStream();
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: "
                    + data.length + "\r\nCache-Control: no-store\r\n\r\n").getBytes("UTF-8"));
            out.write(data);
            out.flush();
        } catch (Throwable ignore) {}
    }

    private static byte[] readWeb(String name) {
        try {
            File f = new File(App.get().getFilesDir(), "web/" + name);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            return buf;
        } catch (Throwable t) { return null; }
    }

    // ================= TV 设置同步面板(注入 SPA) =================

    private static String tvsyncJs() {
        return "(function(){\n" +
            "function h(t,c,s){var e=document.createElement(t);if(c)e.className=c;if(s)e.style.cssText=s;return e;}\n" +
            "function $(id){return document.getElementById(id);}\n" +
            "var btn=h('button',null,'position:fixed;right:14px;bottom:14px;z-index:99999;width:48px;height:48px;border-radius:50%;" +
            "background:#3c83f6;color:#fff;border:0;font-size:15px;font-weight:700;box-shadow:0 4px 12px rgba(0,0,0,.5);cursor:pointer');\n" +
            "btn.textContent='TV';btn.title='TV 设置同步';\n" +
            "var panel=h('div',null,'display:none;position:fixed;inset:0;z-index:100000;background:rgba(5,11,24,.94);" +
            "color:#f8fafc;font-family:ui-sans-serif,system-ui,sans-serif;overflow:auto;padding:18px');\n" +
            "panel.innerHTML='<div style=\"max-width:520px;margin:0 auto\">'+" +
            "'<h2 style=\\\"color:#3c83f6;margin:8px 0\\\">TV 设置同步</h2>'+" +
            "'<p id=tvnow style=\\\"color:#94a3b8;font-size:13px;margin:4px 0 12px\\\">电视状态读取中...</p>'+" +
            "'<label style=\\\"display:block;color:#94a3b8;font-size:13px;margin:12px 0 4px\\\">M3U 频道列表地址</label>'+" +
            "'<input id=tvm3u style=\\\"width:100%;padding:9px;font-size:14px;background:#0b111e;color:#f8fafc;border:1px solid #1d2839;border-radius:8px\\\">'+" +
            "'<label class=tvck style=\\\"display:block;margin:12px 0;font-size:14px\\\"><input type=checkbox id=tvauto> 开机自启</label>'+" +
            "'<label class=tvck style=\\\"display:block;margin:12px 0;font-size:14px\\\"><input type=checkbox id=tvinfo> 切台显示分辨率/编码/码率</label>'+" +
            "'<label class=tvck style=\\\"display:block;margin:12px 0;font-size:14px\\\"><input type=checkbox id=tvclock> 时间常显(右上角)</label>'+" +
            "'<label style=\\\"display:block;color:#94a3b8;font-size:13px;margin:12px 0 4px\\\">OSD 字号</label>'+" +
            "'<select id=tvosd style=\\\"padding:8px;font-size:14px;background:#0b111e;color:#f8fafc;border:1px solid #1d2839;border-radius:8px\\\">'+" +
            "'<option value=0.8>小</option><option value=1.0>中</option><option value=1.3>大</option></select>'+" +
            "'<div style=\\\"margin-top:18px;display:flex;gap:10px\\\">'+" +
            "'<button id=tvsave style=\\\"flex:1;padding:11px;background:#3c83f6;color:#fff;border:0;border-radius:8px;font-size:15px;cursor:pointer\\\">保存并同步到电视</button>'+" +
            "'<button id=tvclose style=\\\"padding:11px 18px;background:#1d2839;color:#f8fafc;border:0;border-radius:8px;font-size:15px;cursor:pointer\\\">关闭</button></div>'+" +
            "'<p id=tvmsg style=\\\"color:#4ade80;font-size:13px;min-height:18px;margin:10px 0\\\"></p></div>';\n" +
            "btn.addEventListener('click',function(){panel.style.display='block';load();});\n" +
            "panel.querySelector('#tvclose').addEventListener('click',function(){panel.style.display='none';});\n" +
            "function get(u,cb){var x=new XMLHttpRequest();x.open('GET',u);x.onload=function(){cb(x.responseText);};x.send();}\n" +
            "function load(){get('/api/config',function(t){try{var c=JSON.parse(t);" +
            "$('tvm3u').value=c.list_url||'';$('tvauto').checked=!!c.autostart;" +
            "$('tvinfo').checked=!!c.show_info;$('tvclock').checked=!!c.clock_always;$('tvosd').value=c.osd_size||'1.0';}catch(e){}});\n" +
            "get('/api/state',function(t){try{var s=JSON.parse(t);var n=s.index>=0&&s.channels[s.index]?s.channels[s.index].name:'';" +
            "$('tvnow').textContent='电视正在播:'+(n||'(未播放)')+(s.paused?'(已暂停)':'');}catch(e){}});}\n" +
            "panel.querySelector('#tvsave').addEventListener('click',function(){\n" +
            "  var b='m3u='+encodeURIComponent($('tvm3u').value)" +
            "+'&autostart=0&autostart='+($('tvauto').checked?'1':'0')" +
            "+'&show_info=0&show_info='+($('tvinfo').checked?'1':'0')+'&clock_always=0&clock_always='+($('tvclock').checked?'1':'0')+'&osd_size='+$('tvosd').value;\n" +
            "  var x=new XMLHttpRequest();x.open('POST','/save');x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');\n" +
            "  x.onload=function(){$('tvmsg').textContent='✓ 已同步到电视';setTimeout(function(){$('tvmsg').textContent='';},2500);};\n" +
            "  x.onerror=function(){$('tvmsg').textContent='同步失败';};x.send(b);});\n" +
            "document.body.appendChild(btn);document.body.appendChild(panel);\n" +
            "})();";
    }

    // ================= 播放列表代理(重写为经本机 /s/) =================

    private static void proxyPlaylist(Socket sock, String base) {
        try {
            String listUrl = App.pref("list_url", "").trim();
            if (listUrl.length() == 0) {
                sendJson(sock, "{\"error\":\"list_url not configured\"}");
                return;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(listUrl).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder();
            boolean sawHeader = false;                       // 头只输出一次(上游自带 #EXTM3U 时不再重复)
            String origin = originOf(listUrl);
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("#EXTM3U")) {
                    if (!sawHeader) { out.append("#EXTM3U\n"); sawHeader = true; }
                } else if (t.length() == 0 || t.startsWith("#")) {
                    out.append(line).append("\n");
                } else if (t.startsWith("http://") || t.startsWith("https://")) {
                    out.append(base).append("/s/").append(URLEncoder.encode(t, "UTF-8")).append("\n");
                } else {
                    out.append(base).append("/s/").append(URLEncoder.encode(origin + "/" + t, "UTF-8")).append("\n");
                }
            }
            r.close();
            if (!sawHeader) out.insert(0, "#EXTM3U\n");      // 上游无头时补一个
            c.disconnect();
            sendBytes(sock, out.toString().getBytes("UTF-8"), "audio/x-mpegurl; charset=utf-8");
        } catch (Throwable t) {
            try { sendJson(sock, "{\"error\":\"playlist proxy: " + t.getMessage() + "\"}"); }
            catch (Throwable ignore) {}
        }
    }

    private static String originOf(String url) {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Throwable t) { return ""; }
    }

    // ================= 旁路探测(直连播放时解析元数据;旧 /local/ 泵保留作后备) =================

    /** 当前播放流的实时元数据(PlayerActivity 每秒采样)。 */
    public static class StreamMeta {
        public volatile String videoCodec;    // "H.264"/"HEVC"/"MPEG-2"
        public volatile String audioCodec;    // "AAC"/"MP2"/"MP3"/"AC-3"
        public volatile int width;            // 显示宽度 = ceil(编码宽 × SAR)
        public volatile int height;           // 编码高度
        public volatile String scanType;      // "interlaced"/"progressive"
        public volatile double fps;           // SPS timing_info
        public volatile int channels;         // 音频声道
        public volatile int sampleRate;
        public volatile long bitrateBps;      // 实测码率(泵转发的字节速率)
        public volatile long bytesTotal;
    }

    private static volatile StreamMeta sMeta = new StreamMeta();
    private static volatile String sMetaKey;  // 当前流的上游 URL(换流时重置元数据)
    private static volatile TsProbe probe = new TsProbe();

    public static StreamMeta meta() { return sMeta; }

    /** 换台开始时作废旧元数据:旁路探测线程据此立刻断开(不再抢新流带宽);
     *  新探测延到首帧稳定后才开,见 PlayerActivity.scheduleSideProbe。 */
    public static void invalidateMeta() {
        synchronized (ConfigServer.class) {
            sMetaKey = null;
            sMeta = new StreamMeta();
            probe = new TsProbe();
        }
    }

    /**
     * 旁路探测:MediaPlayer 直连上游后,元数据(编码/分辨率/声道/码率)改由一条
     * 短命连接拉流头解析 —— 读满 3MB 或元数据到齐即断开,不占播放路径、不占稳态带宽。
     * 同一 URL 已在探测/已探测过则跳过(元数据仍有效)。
     * V1.3.3:全速窗缩到 256KB;换台(invalidateMeta)后旧探测立即退出,
     * 新探测由上层延到首帧稳定 +5s 再开,避免抢新流缓冲造成 buffering 振荡。
     */
    public static void startSideProbe(final String target) {
        synchronized (ConfigServer.class) {
            if (target.equals(sMetaKey)) return;   // 同流:元数据仍有效,无需重开连接
            sMetaKey = target;
            sMeta = new StreamMeta();
            probe = new TsProbe();
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() { sideProbe(target); }
        }, "SideProbe");
        t.setDaemon(true);
        t.start();
    }

    private static void sideProbe(String target) {
        HttpURLConnection c = null;
        InputStream in = null;
        long total = 0;
        long start = 0;
        boolean rateFrozen = false;
        final long PHASE1 = 1024 * 1024;           // 码率窗/SPS 覆盖窗(实测 SPS 最早 ~655KB)
        try {
            TsProbe probe0 = probe;
            c = (HttpURLConnection) new URL(target).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", UA);
            in = c.getInputStream();
            start = android.os.SystemClock.elapsedRealtime();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                // 换台后 invalidateMeta() 会重置 sMetaKey:旧探测必须立刻断开,
                // 否则它仍在全速拉上游,与新流抢 rtp2httpd/带宽(实测造成 buffering 振荡)
                if (!target.equals(sMetaKey)) break;
                total += n;
                probe0.feed(buf, n);
                long now = android.os.SystemClock.elapsedRealtime();
                // 码率只取全速窗的实测(之后的限速读取会低估,不得覆盖)
                if (!rateFrozen && (total >= PHASE1 || probe0.metaComplete())) {
                    rateFrozen = true;
                    if (target.equals(sMetaKey)) {
                        StreamMeta m = sMeta;
                        m.bitrateBps = total * 8000L / Math.max(1, now - start);
                        m.bytesTotal = total;
                    }
                }
                TsProbe.Meta tm = probe0.meta();
                if (target.equals(sMetaKey)) {
                    StreamMeta m2 = sMeta;
                    if (tm.videoCodec != null) m2.videoCodec = tm.videoCodec;
                    if (tm.audioCodec != null) m2.audioCodec = tm.audioCodec;
                    m2.width = tm.presentWidth;
                    m2.height = tm.codecHeight;
                    m2.scanType = tm.scanType;
                    m2.fps = tm.fps;
                    m2.channels = tm.channels;
                    m2.sampleRate = tm.sampleRate;
                }
                if (total > (2L << 20) || probe0.metaComplete()) break;
                // 不再降速:降速会把"短命连接"拖成长连接,SPS(实测 ~655KB)迟迟不到,
                // 帧率 chip 按网页端 visibleBadges 语义就被隐藏。改为全速读到
                // metaComplete 即断(通常 ≤1MB),连接存在时间反而更短、占带宽更久的问题消失。
            }
        } catch (Throwable ignore) {                // 探测失败不影响播放(直连由 MediaPlayer 负责)
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
            // 极早退出(没到全速窗):用已有读数兜底
            StreamMeta m = sMeta;
            if (target.equals(sMetaKey) && !rateFrozen && total > 0) {
                long ms = android.os.SystemClock.elapsedRealtime() - start;
                if (ms > 200) m.bitrateBps = total * 8000L / ms;
            }
        }
    }

    /** 泵:rtp2httpd → MediaPlayer(回环),顺路解析元数据。 */
    private static void localStream(Socket sock, String enc) {
        OutputStream out = null;
        InputStream in = null;
        HttpURLConnection c = null;
        try {
            String target = URLDecoder.decode(enc, "UTF-8");
            final String key = target;
            synchronized (ConfigServer.class) {
                if (!key.equals(sMetaKey)) {
                    sMetaKey = key;
                    sMeta = new StreamMeta();          // 换流:元数据重置
                    probe = new TsProbe();
                }
            }
            TsProbe probe0 = probe;
            c = (HttpURLConnection) new URL(target).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            in = c.getInputStream();
            out = sock.getOutputStream();
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\n"
                    + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));

            byte[] buf = new byte[32 * 1024];
            long total = 0, lastBytes = 0;
            long lastTime = android.os.SystemClock.elapsedRealtime();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                // 元数据到齐后旁路解析:纯转发,消除转发线程的解析/GC 开销
                // (解析跑在拷贝线程上会造成 MediaPlayer 输入断流 → 马赛克/卡顿)
                if (!probe0.metaComplete()) probe0.feed(buf, n);
            // 每秒实测码率(双槽切台存在两条泵连接:只让"当前流"写元数据,防旧流串扰)
                long now = android.os.SystemClock.elapsedRealtime();
                if (key.equals(sMetaKey) && now - lastTime >= 1000) {
                    StreamMeta m = sMeta;
                    m.bitrateBps = (total - lastBytes) * 8000L / (now - lastTime);
                    m.bytesTotal = total;
                    lastBytes = total;
                    lastTime = now;
                }
                // 解析结果 → StreamMeta(信息条采样)
                TsProbe.Meta tm = probe0.meta();
                if (key.equals(sMetaKey)) {
                    StreamMeta m2 = sMeta;
                    if (tm.videoCodec != null) m2.videoCodec = tm.videoCodec;
                    if (tm.audioCodec != null) m2.audioCodec = tm.audioCodec;
                    m2.width = tm.presentWidth;
                    m2.height = tm.codecHeight;
                    m2.scanType = tm.scanType;
                    m2.fps = tm.fps;
                    m2.channels = tm.channels;
                    m2.sampleRate = tm.sampleRate;
                }
            }
        } catch (Throwable ignore) {                  // 客户端断开(切台/退出)或上游结束
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
            try { if (out != null) out.flush(); } catch (Throwable ignore) {}
            try { if (c != null) c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    // ================= 流代理(rtp2httpd → 手机,解 CORS) =================

    private static void streamProxy(Socket sock, String enc) {
        OutputStream out = null;
        InputStream in = null;
        HttpURLConnection c = null;
        try {
            String target = URLDecoder.decode(enc, "UTF-8");
            c = (HttpURLConnection) new URL(target).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            in = c.getInputStream();
            out = sock.getOutputStream();
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\n"
                    + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Throwable ignore) {                  // 客户端断开/远端结束:正常退出
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
            try { if (out != null) out.flush(); } catch (Throwable ignore) {}
            try { if (c != null) c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    // ================= 配置 =================

    private static String configJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("list_url", App.pref("list_url", ""));
            o.put("autostart", App.prefBool("autostart", false));
            o.put("prefer_exo", App.prefBool("prefer_exo", false));
            o.put("show_info", App.prefBool("show_info", true));
            o.put("clock_always", App.prefBool("clock_always", true));
            o.put("osd_size", App.pref("osd_size", "1.0"));
            o.put("version", App.version());
            return o.toString();
        } catch (Throwable t) { return "{}"; }
    }

    private static String configForm(boolean saved) {
        String m3u = App.pref("list_url", "");
        boolean auto = App.prefBool("autostart", false);
        boolean info = App.prefBool("show_info", true);
        boolean clk = App.prefBool("clock_always", true);
        String osd = App.pref("osd_size", "1.0");
        return "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>IPTV 配置</title><style>"
            + "body{background:#0b111e;color:#f8fafc;font-family:ui-sans-serif,system-ui,sans-serif;max-width:560px;margin:0 auto;padding:20px}"
            + "input,select{width:96%;padding:8px;font-size:16px;background:#0b111e;color:#f8fafc;border:1px solid #1d2839;border-radius:8px}"
            + "input[type=checkbox]{width:auto;vertical-align:middle;margin-right:6px}"
            + "label{display:block;margin:14px 0 4px;color:#94a3b8;font-size:14px}h2{color:#3c83f6}"
            + "button{padding:10px 30px;font-size:16px;margin-top:16px;background:#3c83f6;color:#f8fafc;border:0;border-radius:8px}"
            + "a{color:#3c83f6}.saved{color:#4ade80;font-size:14px;margin:4px 0 0}</style></head><body>"
            + "<h2>IPTV 配置</h2>"
            + (saved ? "<p class='saved'>✓ 已保存,配置即时生效</p>" : "")
            + "<form method='POST' action='/save'>"
            + "<label>M3U 频道列表地址</label><input name='m3u' value=\"" + esc(m3u) + "\">"
            // 隐藏 value='0' 字段:HTML 表单未勾选的 checkbox 不会提交,没有它就永远无法"取消勾选保存"
            + "<input type='hidden' name='autostart' value='0'>"
            + "<label><input type='checkbox' name='autostart' value='1' " + (auto ? "checked" : "") + "> 开机自启</label>"
            + "<input type='hidden' name='show_info' value='0'>"
            + "<label><input type='checkbox' name='show_info' value='1' " + (info ? "checked" : "") + "> 切台显示分辨率/编码/码率</label>"
            + "<input type='hidden' name='clock_always' value='0'>"
            + "<label><input type='checkbox' name='clock_always' value='1' " + (clk ? "checked" : "") + "> 时间常显(右上角)</label>"
            + "<label>OSD 字号</label><select name='osd_size'>" + opt("0.8", osd, "小")
            + opt("1.0", osd, "中") + opt("1.3", osd, "大") + "</select>"
            + "<br><button>保存</button></form>"
            + "<p><a href='/'>← 返回播放器</a></p>"
            + "<p style='color:#64748b;font-size:12px;margin-top:18px'>IPTV " + esc(App.version()) + "</p>"
            + "</body></html>";
    }

    private static String opt(String v, String cur, String label) {
        return "<option value='" + v + "'" + (v.equals(cur) ? " selected" : "") + ">" + label + "</option>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }

    private static void applyParams(Map<String, String> params) {
        boolean m3uChanged = false;
        if (params.containsKey("m3u")) {
            String nv = params.get("m3u");
            m3uChanged = !nv.equals(App.pref("list_url", ""));
            App.prefPut("list_url", nv);
        }
        if (params.containsKey("autostart"))
            App.prefPutBool("autostart", truthy(params.get("autostart")));
        if (params.containsKey("prefer_exo"))
            App.prefPutBool("prefer_exo", truthy(params.get("prefer_exo")));
        if (params.containsKey("show_info"))
            App.prefPutBool("show_info", truthy(params.get("show_info")));
        if (params.containsKey("clock_always"))
            App.prefPutBool("clock_always", truthy(params.get("clock_always")));
        if (params.containsKey("osd_size")) App.prefPut("osd_size", params.get("osd_size"));
        TVController.Host host = TVController.host();
        if (host != null && m3uChanged) host.onConfigApplied();
        if (sCallback != null) sCallback.onConfigChanged();
    }

    private static boolean truthy(String v) {
        return "1".equals(v) || "true".equals(v) || "on".equals(v);
    }

    // ================= 状态 JSON(多线路) =================

    private static String apiState() {
        String json = "{\"channels\":[],\"index\":-1,\"name\":\"\",\"paused\":false,\"volume\":100}";
        try {
            TVController.Host host = TVController.host();
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            int idx = -1;
            String name = "";
            if (host != null) {
                List<Channel> chs = host.getChannels();
                idx = host.getCurrentIndex();
                for (Channel c : chs) {
                    JSONObject co = new JSONObject();
                    co.put("name", c.name);
                    co.put("group", c.group);
                    co.put("number", c.number);
                    JSONArray us = new JSONArray();
                    for (String u : c.urls) us.put(u);
                    JSONArray ls = new JSONArray();
                    for (String s : c.labels) ls.put(s);
                    co.put("urls", us);
                    co.put("labels", ls);
                    co.put("source", c.sourceIndex);
                    arr.put(co);
                }
                if (idx >= 0 && idx < chs.size()) name = chs.get(idx).name;
            }
            o.put("channels", arr);
            o.put("index", idx);
            o.put("name", name);
            o.put("paused", host != null && host.isPaused());
            o.put("volume", host != null ? Math.round(host.getVolume() * 100) : 100);
            json = o.toString();
        } catch (Throwable ignore) {}
        return json;
    }

    // ================= 底层收发 =================

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new HashMap<String, String>();
        if (q == null || q.length() == 0) return out;
        for (String p : q.split("&")) {
            int eq = p.indexOf('=');
            String k = eq > 0 ? p.substring(0, eq) : p;
            String v = eq > 0 ? URLDecoder.decode(p.substring(eq + 1)) : "";
            out.put(k, v);
        }
        return out;
    }

    private static int intParam(Map<String, String> params, String k, int def) {
        try { return Integer.parseInt(params.get(k)); } catch (Throwable t) { return def; }
    }

    private static double doubleParam(Map<String, String> params, String k, double def) {
        try { return Double.parseDouble(params.get(k)); } catch (Throwable t) { return def; }
    }

    /** 所有响应禁缓存。 */
    private static void sendJson(Socket sock, String body) {
        try {
            sendBytes(sock, body.getBytes("UTF-8"), "application/json; charset=utf-8");
        } catch (Throwable ignore) {}
    }

    private static void sendBytes(Socket sock, byte[] data, String type) {
        try {
            OutputStream out = sock.getOutputStream();
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + data.length
                    + "\r\nCache-Control: no-store, no-cache, must-revalidate\r\nConnection: close\r\n\r\n")
                    .getBytes("UTF-8"));
            out.write(data);
            out.flush();
        } catch (Throwable ignore) {}
    }
}
