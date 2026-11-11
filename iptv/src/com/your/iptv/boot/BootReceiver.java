package com.your.iptv.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.your.iptv.App;
import com.your.iptv.PlayerActivity;
import com.your.iptv.util.TvUtils;

/**
 * 开机自启。乐视框架 isDisableBootRunInBlackListDB(services.odex 实锤)按包名剥离
 * 经典开机广播,实测三通道全拦;故除保留经典通道外,增加开机窗口内的"非开机类"广播
 * 作为穿透通道:NTP 对时(TIME_SET)、USB 枚举(USB_STATE)、WiFi 状态、亮屏(SCREEN_ON)、
 * 解锁(USER_PRESENT)、QUICKBOOT_POWERON。
 * 全部通道加 120s 开机窗口门控:窗口外(日常亮屏/改时间)不触发,避免干扰正常使用。
 * 任一通道命中 → 延时 8s 抢前台(等 tvos/桌面初始化完毕,过早会被顶掉)。
 */
public class BootReceiver extends BroadcastReceiver {

    private static volatile boolean sPending;      // 防抖:多条广播只排一次延迟启动

    private static boolean isTrigger(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || "android.intent.action.TIME_SET".equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_SCREEN_ON.equals(action)
                || "android.hardware.usb.action.USB_STATE".equals(action)
                || ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                || Intent.ACTION_MEDIA_MOUNTED.equals(action)
                || Intent.ACTION_MEDIA_REMOVED.equals(action)
                || "android.net.wifi.WIFI_STATE_CHANGED".equals(action)
                || "android.net.wifi.supplicant.CONNECTION_CHANGE".equals(action);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        android.util.Log.i("IPTV", "boot rx: " + action);
        if (!isTrigger(action)) return;
        if (!App.prefBool("autostart", false)) return;
        // 开机窗口门控:进程被日常事件(改时间/插拔USB/亮屏)拉起时不自启
        long uptime = SystemClock.elapsedRealtime();
        if (uptime > 120000) {
            android.util.Log.i("IPTV", "boot skip(窗口外): " + action + " uptime=" + uptime);
            return;
        }
        // CONNECTIVITY_CHANGE 在断网时也会发:仅在网络可用时触发
        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                && !TvUtils.isNetworkUp(ctx)) return;
        if (sPending) return;
        sPending = true;

        // 与乐视"force stop pkg in blacklist"竞速:广播唤醒进程后它很快强停黑名单包,
        // 必须立刻进前台(前台应用不被清理);原 8s 延迟实测 2/3 次输给强停
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                sPending = false;
                try {
                    Intent it = new Intent(ctx, PlayerActivity.class);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(it);
                    android.util.Log.i("IPTV", "boot: activity launched");
                } catch (Throwable t) {
                    android.util.Log.i("IPTV", "boot: start failed " + t);
                }
            }
        }, 500);
    }
}
