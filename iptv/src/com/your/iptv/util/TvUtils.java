package com.your.iptv.util;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.view.WindowManager;

/** TV 专属工具:overscan 安全区、常亮、网络检测。 */
public final class TvUtils {

    /** 1080p 下四边 5% 安全区像素(overscan)。 */
    public static int overScan() {
        return 48;
    }

    public static void keepScreenOn(Activity a) {
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public static boolean isNetworkUp(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private TvUtils() {}
    /** 枚举网卡取局域网 IPv4(优先 eth0/wlan0)。 */
    public static String getTvIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (name.startsWith("eth")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Throwable t) { return null; }
    }
}
