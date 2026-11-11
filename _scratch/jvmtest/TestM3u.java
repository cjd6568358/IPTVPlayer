import com.your.iptv.source.Channel;
import com.your.iptv.source.M3uParser;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** M3uParser JVM 单元测试:真实播放列表 + 边界用例。全部断言通过输出 PASS。 */
public class TestM3u {

    static int failed = 0;

    static void eq(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) {
            failed++;
            System.out.println("FAIL " + name + ": want=" + want + " got=" + got);
        } else {
            System.out.println("ok   " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- T1: 真实播放列表(results/playlist.m3u,104 频道) ----
        byte[] raw = Files.readAllBytes(Paths.get(
                "D:/workspace/IPTVPlayer/results/playlist.m3u"));
        String real = new String(raw, StandardCharsets.UTF_8);
        List<Channel> a = M3uParser.parse(real);
        eq("T1 count=104", 104, a.size());
        eq("T1 first name", "CCTV-1", a.get(0).name);
        eq("T1 first group", "央视", a.get(0).group);
        eq("T1 numbering sequential", "104", String.valueOf(a.get(103).number));
        boolean seq = true;
        for (int i = 0; i < a.size(); i++) if (a.get(i).number != i + 1) seq = false;
        eq("T1 numbers 1..N contiguous", true, seq);
        boolean groupsOk = true;
        for (Channel c : a) if (c.group == null || c.group.length() == 0) groupsOk = false;
        eq("T1 no empty group", true, groupsOk);
        eq("T1 last name", "百事果", a.get(103).name);
        eq("T1 url non-empty", true, a.get(103).getUrl().startsWith("http"));
        // URL 保持原样(URL 编码的中文路径不被改动)
        eq("T1 url preserved", true, a.get(0).getUrl().contains("%E5%A4%AE%E8%A7%86"));

        // ---- T2: 无 group-title → "其他" ----
        List<Channel> b = M3uParser.parse(
                "#EXTM3U\n#EXTINF:-1,新闻台\nhttp://x/1\n");
        eq("T2 group fallback", "其他", b.get(0).group);
        eq("T2 name", "新闻台", b.get(0).name);

        // ---- T3: 纯 URL 列表(无 EXTINF) ----
        List<Channel> c = M3uParser.parse(
                "http://a/1\nhttp://a/2\nhttp://a/3\n");
        eq("T3 count", 3, c.size());
        eq("T3 auto name", "频道1", c.get(0).name);
        eq("T3 url", "http://a/2", c.get(1).getUrl());

        // ---- T4: #EXTGRP 覆盖分组 ----
        List<Channel> d = M3uParser.parse(
                "#EXTM3U\n#EXTINF:-1 group-title=\"默认\",台A\n#EXTGRP:上海\nhttp://a/1\n");
        eq("T4 EXTGRP override", "上海", d.get(0).group);

        // ---- T5: 结尾孤立 EXTINF(无 URL)→ 丢弃,不产生空 URL 频道 ----
        List<Channel> e = M3uParser.parse(
                "#EXTM3U\n#EXTINF:-1,完整台\nhttp://a/1\n#EXTINF:-1 孤儿台\n");
        eq("T5 orphan EXTINF dropped", 1, e.size());
        eq("T5 no empty url", true, e.get(0).getUrl().length() > 0);

        // ---- T6: 空输入 ----
        eq("T6 empty", 0, M3uParser.parse("").size());
        eq("T6 null", 0, M3uParser.parse(null).size());

        // ---- T7: CRLF 行尾 ----
        List<Channel> f = M3uParser.parse(
                "#EXTM3U\r\n#EXTINF:-1 group-title=\"央\",CCTV-1\r\nhttp://a/1\r\n");
        eq("T7 crlf name", "CCTV-1", f.get(0).name);
        eq("T7 crlf group", "央", f.get(0).group);

        // ---- T8: 名称含逗号(lastIndexOf 取名) ----
        List<Channel> g = M3uParser.parse(
                "#EXTINF:-1,CCTV-5+ 体育赛事, 高清\nhttp://a/1\n");
        eq("T8 comma name", "CCTV-5+ 体育赛事, 高清", g.get(0).name);

        // ---- T9: 空行/缩进行容错 ----
        List<Channel> h = M3uParser.parse(
                "\n  \n#EXTM3U\n\n  #EXTINF:-1,台B  \n  http://a/1  \n\n");
        eq("T9 blanks tolerated", 1, h.size());
        eq("T9 trimmed name", "台B", h.get(0).name);
        eq("T9 trimmed url", "http://a/1", h.get(0).getUrl());

        // ---- T10: EXTINF 属性缺引号 group-title ----
        List<Channel> i = M3uParser.parse(
                "#EXTINF:-1 group-title=测试,台C\nhttp://a/1\n");
        // 正则要求成对引号 → 匹配失败 → 回落"其他"(记录行为,不视为崩溃)
        eq("T10 unquoted group no-crash", 1, i.size());

        System.out.println(failed == 0 ? "\nALL PASS" : "\n" + failed + " FAILURES");
        if (failed > 0) System.exit(1);
    }
}
