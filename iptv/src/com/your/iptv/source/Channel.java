package com.your.iptv.source;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道数据模型(rtp2httpd 3.17.2 多线路)。
 * 播放列表中同名同组条目合并为一个频道;每个 URL 为一条"线路",
 * 标签取 URL 的 $ 后缀(如 /央视/CCTV-1/线路1$线路1 → "线路1"),无 $ 后缀按顺序命名。
 */
public class Channel {

    public final String name;           // 显示名(如 CCTV-1)
    public final String group;          // 分组(央视/卫视/本地...)
    public int number;                  // 频道号(列表顺序,从 1 开始)
    public final List<String> urls;     // 各线路播放地址
    public final List<String> labels;   // 各线路标签(标清/高清/线路N...)
    public int sourceIndex;             // 当前线路下标

    public Channel(String name, String url, String group, int number) {
        this(name, single(url), single("线路1"), group, number);
    }

    /** 多线路构造:urls/labels 等长。 */
    public Channel(String name, List<String> urls, List<String> labels, String group, int number) {
        this.name = name;
        this.urls = urls;
        this.labels = labels;
        this.group = group;
        this.number = number;
        this.sourceIndex = 0;
    }

    private static List<String> single(String s) {
        List<String> l = new ArrayList<String>(1);
        l.add(s);
        return l;
    }

    /** 当前线路的播放地址。 */
    public String getUrl() {
        return urls.isEmpty() ? "" : urls.get(Math.min(sourceIndex, urls.size() - 1));
    }

    /** 当前线路标签。 */
    public String getLabel() {
        return labels.isEmpty() ? "线路1" : labels.get(Math.min(sourceIndex, labels.size() - 1));
    }

    public int sourceCount() { return urls.size(); }

    /** 切换线路;返回是否发生变化。 */
    public boolean cycleSource(int d) {
        int n = urls.size();
        if (n <= 1) return false;
        sourceIndex = ((sourceIndex + d) % n + n) % n;
        return true;
    }

    @Override public String toString() {
        return number + " " + name + " [" + group + "] " + getUrl();
    }
}
