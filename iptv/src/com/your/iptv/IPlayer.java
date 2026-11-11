package com.your.iptv;

import android.view.Surface;

import java.util.Map;

/** 播放引擎抽象。两套实现:SystemPlayerImpl(MediaPlayer)/ ExoPlayerImpl。 */
public interface IPlayer {

    int ERR_STALL = 9001;          // 直播流卡帧(看门狗判定)
    int ERR_NETWORK = 9002;        // 网络类错误
    int ERR_UNSUPPORTED = 9003;    // 格式不支持

    /** 任意时刻可调用(含 null;Surface 销毁回调里必须传 null)。 */
    void setSurface(Surface surface);

    /** 异步打开并自动开始播放。绝不阻塞调用线程。 */
    void open(String url, Map<String, String> headers) throws Exception;

    void start();
    void pause();

    /** 停止但保留实例,可再次 open。 */
    void stop();

    /** 彻底销毁,之后实例不可复用。 */
    void release();

    /** @return 毫秒;直播流为已播时间;失败返回 -1 */
    int getCurrentPosition();

    /** @return 点播时长 ms;直播返回 -1 */
    int getDuration();

    boolean isPlaying();

    void setVolume(float left, float right);

    void setListener(Listener l);

    /** 视频分辨率回调(信息面板用)。 */
    void setVideoSizeListener(VideoSizeListener l);

    /** @return 估算带宽 bps;不支持返回 -1 */
    long getBitrateEstimate();

    interface VideoSizeListener { void onVideoSize(IPlayer p, int w, int h); }

    interface Listener {
        void onPrepared(IPlayer p);
        void onStarted(IPlayer p);
        void onBufferingStart(IPlayer p);
        void onBufferingEnd(IPlayer p);
        void onCompleted(IPlayer p);
        /** 一切错误统一出口;上层(PlayerActivity)负责重试/降级决策。 */
        void onError(IPlayer p, int code, String msg);
    }
}
