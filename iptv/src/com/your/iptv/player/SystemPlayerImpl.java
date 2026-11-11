package com.your.iptv.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.your.iptv.IPlayer;

import java.util.Map;

/**
 * MediaPlayer 引擎(首选)。4.4 NuPlayer 原生支持 HLS(m3u8)与 HTTP-TS 嗅探,
 * 经 MStar OMX 硬解。内含直播卡帧看门狗(§3.2.2 坑 1)与状态机保护(坑 4)。
 */
public class SystemPlayerImpl implements IPlayer {

    private final Context mCtx;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private MediaPlayer mMp;
    private Surface mSurface;
    private Listener mListener;
    private String mUrl;
    private Map<String, String> mHeaders;

    // 状态机(§3.2.1)
    private static final int IDLE = 0, INIT = 1, PREPARING = 2, PREPARED = 3,
            STARTED = 4, PAUSED = 5, STOPPED = 6;
    private int mState = IDLE;

    // 看门狗
    private boolean mWatchdogOn;
    private int mLastPos = -1;
    private int mStallCount = 0;

    public SystemPlayerImpl(Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    @Override public void setSurface(Surface s) {
        mSurface = s;
        if (mMp != null) {
            try { mMp.setSurface(s); } catch (Throwable ignore) {}
        }
    }

    @Override public void open(String url, Map<String, String> headers) throws Exception {
        releasePlayer();
        mUrl = url;
        mHeaders = headers;

        MediaPlayer mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);            // 必须 prepare 之前
        if (mSurface != null) mp.setSurface(mSurface);
        if (headers == null || headers.isEmpty()) {
            mp.setDataSource(mCtx, Uri.parse(url));
        } else {
            mp.setDataSource(mCtx, Uri.parse(url), headers);          // 自定义请求头
        }
        mp.setOnPreparedListener(mPrepared);
        mp.setOnErrorListener(mError);
        mp.setOnInfoListener(mInfo);
        mp.setOnCompletionListener(mComplete);
        mp.setOnBufferingUpdateListener(mBuffering);
        mp.setOnVideoSizeChangedListener(mVideoSize);
        mMp = mp;
        mState = PREPARING;
        try {
            mp.prepareAsync();                                        // 永远异步(坑:同步 prepare 阻塞 UI)
        } catch (Throwable t) {
            // prepareAsync 抛异常时原实现泄漏了刚 new 出来的 MediaPlayer(占着 OMX
            // 名额,连续换台会累积),必须就地释放再交给上层重试链
            mMp = null;
            mState = IDLE;
            try { mp.setSurface(null); } catch (Throwable ignore) {}
            try { mp.release(); } catch (Throwable ignore) {}
            throw new Exception("prepareAsync: " + t);
        }
    }

    private final MediaPlayer.OnPreparedListener mPrepared = new MediaPlayer.OnPreparedListener() {
        @Override public void onPrepared(MediaPlayer p) {
            mState = PREPARED;
            if (mListener != null) mListener.onPrepared(SystemPlayerImpl.this);
            try { p.start(); mState = STARTED; } catch (Throwable t) {
                if (mListener != null) mListener.onError(SystemPlayerImpl.this, -1, "start: " + t);
                return;
            }
            mLastPos = -1; mStallCount = 0;
            startWatchdog();
            if (mListener != null) mListener.onStarted(SystemPlayerImpl.this);
        }
    };

    private final MediaPlayer.OnErrorListener mError = new MediaPlayer.OnErrorListener() {
        @Override public boolean onError(MediaPlayer p, int what, int extra) {
            stopWatchdog();
            mState = IDLE;
            // 100 = SERVER_DIED;-110 = 操作超时;-1010 = 不支持的格式;-2147483648 = 未知
            String msg = "what=" + what + " extra=" + extra;
            int code = (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) ? ERR_NETWORK
                    : (extra == -1010 ? ERR_UNSUPPORTED : ERR_NETWORK);
            if (mListener != null) mListener.onError(SystemPlayerImpl.this, code, msg);
            return true;                                               // 已处理,不弹系统对话框
        }
    };

    private final MediaPlayer.OnInfoListener mInfo = new MediaPlayer.OnInfoListener() {
        @Override public boolean onInfo(MediaPlayer p, int what, int extra) {
            if (mListener == null) return true;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) mListener.onBufferingStart(SystemPlayerImpl.this);
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) mListener.onBufferingEnd(SystemPlayerImpl.this);
            return true;
        }
    };

    private final MediaPlayer.OnCompletionListener mComplete = new MediaPlayer.OnCompletionListener() {
        @Override public void onCompletion(MediaPlayer p) {
            mState = IDLE; stopWatchdog();
            if (mListener != null) mListener.onCompleted(SystemPlayerImpl.this);
        }
    };

    private final MediaPlayer.OnBufferingUpdateListener mBuffering =
            new MediaPlayer.OnBufferingUpdateListener() {
                @Override public void onBufferingUpdate(MediaPlayer p, int percent) { /* OSD 可选 */ }
            };

    private final MediaPlayer.OnVideoSizeChangedListener mVideoSize =
            new MediaPlayer.OnVideoSizeChangedListener() {
                @Override public void onVideoSizeChanged(MediaPlayer p, int w, int h) {
                    if (mSizeListener != null) mSizeListener.onVideoSize(SystemPlayerImpl.this, w, h);
                }
            };

    // ---- 看门狗(§3.2.2 坑 1:直播静默卡帧)----

    /** 双槽预载引擎调用:预载期带宽被旧流分走,慢启动是常态,看门狗必须关闭
     *  (误报 ERR_STALL 会级联成顺序切换,冻结旧画面数秒)。 */
    public void disableWatchdog() { mWatchdogDisabled = true; }

    private boolean mWatchdogDisabled;

    private void startWatchdog() {
        if (mWatchdogDisabled) return;
        mWatchdogOn = true;
        mHandler.removeCallbacks(mWatchdogTick);
        mHandler.postDelayed(mWatchdogTick, 5000);
    }

    private void stopWatchdog() {
        mWatchdogOn = false;
        mHandler.removeCallbacks(mWatchdogTick);
    }

    private final Runnable mWatchdogTick = new Runnable() {
        @Override public void run() {
            if (!mWatchdogOn || mState != STARTED || mMp == null) return;
            int pos = -1;
            try { pos = mMp.getCurrentPosition(); } catch (Throwable ignore) {}
            if (pos >= 0 && pos == mLastPos) {
                mStallCount++;
                if (mStallCount == 2) {                              // 第一次:seek 自愈
                    try { mMp.seekTo(pos); } catch (Throwable ignore) {}
                } else if (mStallCount >= 4) {                       // 第二次:上报重建
                    mStallCount = 0;
                    if (mListener != null) mListener.onError(SystemPlayerImpl.this, ERR_STALL,
                            "live stalled at " + pos);
                    return;                                          // 停止看门狗,等上层决策
                }
            } else {
                mStallCount = 0;
                if (pos >= 0) mLastPos = pos;
            }
            mHandler.postDelayed(mWatchdogTick, 5000);
        }
    };

    // ---- 控制接口 ----

    @Override public void start() {
        // 原写法 (mMp != null && PREPARED) || PAUSED 在 mMp==null 时会 NPE
        if (mMp != null && (mState == PREPARED || mState == PAUSED)) {
            try { mMp.start(); mState = STARTED; startWatchdog(); } catch (Throwable ignore) {}
        }
    }

    @Override public void pause() {
        if (mMp != null && mState == STARTED) {
            try { mMp.pause(); mState = PAUSED; stopWatchdog(); } catch (Throwable ignore) {}
        }
    }

    @Override public void stop() {
        if (mMp != null) {
            try { mMp.stop(); } catch (Throwable ignore) {}
            mState = STOPPED; stopWatchdog();
        }
    }

    @Override public void release() {
        stopWatchdog();
        if (mMp != null) {
            // 先与 SurfaceTexture 解绑再 release:否则 NuPlayer 断开连接时会往仍在
            // 显示的 TextureView 上推最后一帧/半帧,换台瞬间会出现撕裂或绿块
            try { mMp.setSurface(null); } catch (Throwable ignore) {}
            try { mMp.release(); } catch (Throwable ignore) {}
            mMp = null;
        }
        mState = IDLE;
    }

    @Override public int getCurrentPosition() {
        if (mMp == null) return -1;
        try { return mMp.getCurrentPosition(); } catch (Throwable t) { return -1; }
    }

    @Override public int getDuration() {
        if (mMp == null) return -1;
        try { int d = mMp.getDuration(); return d <= 0 ? -1 : d; } catch (Throwable t) { return -1; }
    }

    @Override public boolean isPlaying() { return mState == STARTED; }

    @Override public void setVolume(float l, float r) {
        if (mMp != null) { try { mMp.setVolume(l, r); } catch (Throwable ignore) {} }
    }

    private IPlayer.VideoSizeListener mSizeListener;

    @Override public void setVideoSizeListener(IPlayer.VideoSizeListener l) { mSizeListener = l; }

    @Override public long getBitrateEstimate() { return -1; }   // MediaPlayer 无带宽估计

    @Override public void setListener(Listener l) { mListener = l; }

    private void releasePlayer() {
        stopWatchdog();
        if (mMp != null) {
            try { mMp.setSurface(null); } catch (Throwable ignore) {}
            try { mMp.release(); } catch (Throwable ignore) {}
            mMp = null;
        }
        mState = IDLE;
    }
}
