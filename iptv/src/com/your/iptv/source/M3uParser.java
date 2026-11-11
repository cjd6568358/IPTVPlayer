package com.your.iptv.source;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * m3u / m3u8 频道列表解析器(rtp2httpd 3.17.2 多线路语义)。
 * 兼容:#EXTINF 属性 / 无属性简写 / 纯 URL 行 / #EXTGRP。
 * 多线路:同名同组条目合并为一个频道;URL 的 $ 后缀为线路标签
 * (如 http://x/央视/CCTV-1/线路2$高清 → 标签"高清"),无 $ 按顺序"线路N"。
 */
public final class M3uParser {

    private static final Pattern GROUP = Pattern.compile("group-title=\"([^\"]*)\"");

    private M3uParser() {}

    public static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<Channel>();
        if (text == null || text.length() == 0) return out;

        String pendingName = null;
        String pendingGroup = "其他";
        int n = 0;

        String[] lines = text.split("\r?\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) continue;

            if (line.startsWith("#EXTINF:")) {
                pendingName = null;
                pendingGroup = "其他";
                Matcher g = GROUP.matcher(line);
                if (g.find()) pendingGroup = g.group(1).trim();
                // 标题 = 第一个"不在双引号内"的逗号之后的内容
                int comma = -1;
                boolean inQuote = false;
                for (int k = 0; k < line.length(); k++) {
                    char ch = line.charAt(k);
                    if (ch == '"') inQuote = !inQuote;
                    else if (ch == ',' && !inQuote) { comma = k; break; }
                }
                if (comma >= 0 && comma + 1 < line.length()) {
                    pendingName = line.substring(comma + 1).trim();
                }
                if (pendingName == null || pendingName.length() == 0) pendingName = "频道" + (n + 1);
            } else if (line.startsWith("#EXTGRP:")) {
                pendingGroup = line.substring(8).trim();
            } else if (line.startsWith("#")) {
                continue;                                    // #EXTM3U 等其他指令
            } else {
                // 频道 URL 行:归属最近的 #EXTINF(同名同组 → 追加为线路);
                // 无 EXTINF 前缀的裸 URL 行(老式列表)各自独立成频道
                String name = pendingName != null ? pendingName : ("频道" + (n + 1));
                boolean mergeable = pendingName != null;
                addOrMerge(out, name, pendingGroup, line, mergeable);
                n++;
            }
        }
        // 重新编号(合并后)
        for (int i = 0; i < out.size(); i++) out.get(i).number = i + 1;
        return out;
    }

    /** 同名同组 → 追加线路(标签 = URL $ 后缀,decode;无 $ = "线路N");否则新建频道。 */
    private static void addOrMerge(List<Channel> out, String name, String group,
                                   String url, boolean mergeable) {
        String label = null;
        int dollar = url.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < url.length()) {
            label = decode(url.substring(dollar + 1)).trim();
            url = url.substring(0, dollar);
        }
        if (mergeable) {
            for (Channel c : out) {
                if (name.equals(c.name) && group.equals(c.group)) {
                    c.urls.add(url);
                    c.labels.add(label != null && label.length() > 0 ? label
                            : "线路" + c.urls.size());
                    return;
                }
            }
        }
        List<String> urls = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        urls.add(url);
        labels.add(label != null && label.length() > 0 ? label : "线路1");
        out.add(new Channel(name, urls, labels, group, out.size() + 1));
    }

    private static String decode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Throwable t) { return s; }
    }
}
