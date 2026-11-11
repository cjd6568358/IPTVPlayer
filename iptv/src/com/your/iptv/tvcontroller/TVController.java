package com.your.iptv.tvcontroller;

import com.your.iptv.source.Channel;

import java.util.List;

/**
 * 电视端控制桥:HTTP 服务(手机遥控/配置)与 PlayerActivity 之间的桥。
 * PlayerActivity 在 onCreate 绑定、onDestroy 解绑;手机端所有 action 经由这里落到播放器。
 */
public final class TVController {

    public interface Host {
        List<Channel> getChannels();
        int getCurrentIndex();
        void tuneIndex(int i);              // 手机端切台 → 电视立即换台
        boolean isPaused();
        void setPaused(boolean p);          // 手机端播放/暂停
        float getVolume();
        void setVolume(float v);            // 手机端音量
        void onConfigApplied();             // 手机端保存配置后 → 电视端刷新生效
    }

    private static volatile Host sHost;

    private TVController() {}

    public static void bind(Host h) { sHost = h; }

    public static void unbind() { sHost = null; }

    public static Host host() { return sHost; }
}
