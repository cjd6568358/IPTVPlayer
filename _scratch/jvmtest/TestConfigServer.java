import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JVM 地面真值测试:直接调用真实 ConfigServer 的 private static remoteUi()/page()/redirect(),
 * 证明浏览器测过的页面(remote_preview.html)与电视实际下发的页面字节一致。
 */
public class TestConfigServer {

    static int failed = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("com.your.iptv.util.ConfigServer");

        // 1. remoteUi():真实运行时输出落盘,供 diff
        Method m = c.getDeclaredMethod("remoteUi");
        m.setAccessible(true);
        String html = (String) m.invoke(null);
        Files.write(Paths.get(args[0]), html.getBytes("UTF-8"));
        check("remoteUi() invoked, " + html.length() + " chars", html.length() > 20000);
        check("remoteUi doctype", html.startsWith("<!doctype html>"));
        check("remoteUi has search placeholder", html.contains("搜索频道..."));
        check("remoteUi has live dot", html.contains("直播"));
        check("remoteUi theme select", html.contains("自动</option>"));

        // 2. page():200 + 禁缓存 + Content-Length
        Method p = c.getDeclaredMethod("page", String.class, String.class);
        p.setAccessible(true);
        String resp = (String) p.invoke(null, "BODY", "application/json");
        check("page 200", resp.startsWith("HTTP/1.0 200 OK"));
        check("page no-store", resp.contains("Cache-Control: no-store, no-cache, must-revalidate"));
        check("page content-length", resp.contains("Content-Length: 4"));
        check("page utf-8", resp.contains("charset=utf-8"));

        // 3. redirect():302 + Location
        Method r = c.getDeclaredMethod("redirect", String.class);
        r.setAccessible(true);
        String red = (String) r.invoke(null, "/config?saved=1");
        check("redirect 302", red.startsWith("HTTP/1.0 302 Found"));
        check("redirect location", red.contains("Location: /config?saved=1"));

        System.out.println(failed == 0 ? "JVM-GROUND-TRUTH PASS" : failed + " FAILURES");
        if (failed > 0) System.exit(1);
    }
}
