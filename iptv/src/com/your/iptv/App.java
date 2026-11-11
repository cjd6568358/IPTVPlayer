package com.your.iptv;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

/** 全局单例:配置存取 + WifiLock(播放期间保持 WiFi 高性能)。 */
public class App extends Application {

    private static App sInst;
    private WifiManager.WifiLock mWifiLock;

    public static App get() { return sInst; }

    @Override
    public void onCreate() {
        super.onCreate();
        sInst = this;
        // 常驻 HTTP 服务:手机遥控器 + 配置(应用运行期间一直在)
        com.your.iptv.util.ConfigServer.start(this);
        bootAutoStart();
        WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iptv");
            mWifiLock.setReferenceCounted(false);
        }
    }

    /**
     * 开机自启(绕过乐视框架广播黑名单):isDisableBootRunInBlackListDB 按包名剥离
     * BOOT_COMPLETED/CONNECTIVITY_CHANGE/MEDIA_MOUNTED 三通道广播(实锤见 V1.3 记录)。
     * APK 装入 /system/priv-app 后 android:persistent 生效,AMS 开机直接拉起本进程,
     * 不经过广播;此处检测到"开机期进程启动"且开关打开时,延迟 8s 进前台
     * (等 tvos/桌面初始化完毕,过早会被顶掉 —— BootReceiver 同款时序)。
     * elapsedRealtime 门槛:进程由用户点图标拉起时设备已运行很久,不会误触发。
     * systemReady 之前 startActivity 可能抛错,退避重试直至成功。
     */
    private void bootAutoStart() {
        if (!prefBool("autostart", false)) {
            android.util.Log.i("IPTV", "boot: autostart off");
            return;
        }
        long uptime = android.os.SystemClock.elapsedRealtime();
        android.util.Log.i("IPTV", "boot: autostart on, uptime=" + uptime + "ms");
        if (uptime > 120000) return;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries;
            @Override public void run() {
                try {
                    Intent it = new Intent(App.this, PlayerActivity.class);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                    android.util.Log.i("IPTV", "boot: activity launched (try " + tries + ")");
                } catch (Throwable t) {
                    android.util.Log.i("IPTV", "boot: start failed " + t);
                    if (++tries < 10) h.postDelayed(this, 2000);
                }
            }
        }, 1500);
    }

    public WifiManager.WifiLock wifiLock() { return mWifiLock; }

    public void wifiLock(boolean on) {
        if (mWifiLock == null) return;
        if (on) { if (!mWifiLock.isHeld()) mWifiLock.acquire(); }
        else mWifiLock.release();
    }

    /** 设置页显示的唯一版本来源:versionName (versionCode),从 PackageManager 取。 */
    public static String version() {
        try {
            android.content.pm.PackageInfo pi = sInst.getPackageManager()
                    .getPackageInfo(sInst.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) { return "unknown"; }
    }

    // ---- 配置存取(SharedPreferences,4.4 无需运行时权限)----
    // apply() 异步落盘:这些写入发生在 UI 线程(按键/换台/配置),commit 的同步 IO 会卡帧

    private static android.content.SharedPreferences cfg() {
        return sInst.getSharedPreferences("cfg", MODE_PRIVATE);
    }

    public static String pref(String k, String def) { return cfg().getString(k, def); }

    public static void prefPut(String k, String v) {
        cfg().edit().putString(k, v).apply();
    }

    public static boolean prefBool(String k, boolean def) { return cfg().getBoolean(k, def); }

    public static void prefPutBool(String k, boolean v) {
        cfg().edit().putBoolean(k, v).apply();
    }

    public static int prefInt(String k, int def) { return cfg().getInt(k, def); }

    public static void prefPutInt(String k, int v) {
        cfg().edit().putInt(k, v).apply();
    }
}
