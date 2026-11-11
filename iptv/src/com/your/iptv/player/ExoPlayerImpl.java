package com.your.iptv.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.video.VideoListener;

import com.your.iptv.IPlayer;

import java.util.Map;

/**
 * ExoPlayer 2.13.3 引擎(自管 demux + 缓冲,对标网页端 mpegts.js+MSE 模型)。
 *
 * 与 NuPlayer 的本质差异:ExoPlayer 的 TS demux 与缓冲水位(LoadControl)由我们配置,
 * 起播门槛 bufferForPlaybackMs=600ms —— 播放一旦开始就自带 ~600ms 缓冲垫
 * (= 网页端 mpegts.js 先攒后播的语义),双槽换槽不再需要"暂停攒垫"。
 *
 * 打包:libs/exoplayer-2.13.3-merged.jar(common+core+extractor 三模块合并,
 * 见 downloads/exo/;WITH_EXO=1 构建)。
 */
public class ExoPlayerImpl implements IPlayer {

    private final Context mCtx;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private SimpleExoPlayer mPlayer;
    private Surface mSurface;
    private IPlayer.VideoSizeListener mSizeListener;
    private Listener mListener;
    private String mUrl;
    private Map<String, String> mHeaders;

    public ExoPlayerImpl(Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    @Override public void setSurface(Surface s) {
        mSurface = s;
        if (mPlayer != null) mPlayer.setVideoSurface(s);
    }

    @Override public void open(String url, Map<String, String> headers) throws Exception {
        releasePlayer();
        mUrl = url;
        mHeaders = headers;

        DefaultHttpDataSourceFactory dsf = new DefaultHttpDataSourceFactory(
                "LetvY43-IPTV/1.0", null, 8000, 8000, true);   // 302 跨协议跟随

        // 缓冲水位(本引擎的核心价值):起播需 600ms 已缓冲数据,此后维持 1.5-3s。
        // 起播自带垫 = 网页端 MSE 先攒后播;NuPlayer 做不到这一点(其水位黑盒且极低)。
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 3000, 600, 1000)
                .build();

        // MStar OMX 能解 H.264 High@L5.1(NuPlayer 一直在播),但广播的 capability
        // 元数据保守,ExoPlayer 能力检查会拒(实测 CCTV 风云音乐等 1080i High@L5.1
        // 报 NO_EXCEEDS_CAPABILITIES)。自定义 renderer:超能力判为可解,交给硬解。
        DefaultRenderersFactory rf = new DefaultRenderersFactory(mCtx) {
            @Override protected void buildVideoRenderers(android.content.Context ctx,
                    int extensionRendererMode, MediaCodecSelector mediaCodecSelector,
                    boolean enableDecoderFallback,
                    android.os.Handler eventHandler, VideoRendererEventListener eventListener,
                    long allowedVideoJoiningTimeMs,
                    java.util.ArrayList<Renderer> out) {
                out.add(new MediaCodecVideoRenderer(ctx, mediaCodecSelector,
                        allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler,
                        eventListener, 50) {
                    @Override protected int supportsFormat(MediaCodecSelector selector,
                            Format format) throws
                            com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException {
                        int s = super.supportsFormat(selector, format);
                        if (s == C.FORMAT_EXCEEDS_CAPABILITIES) return C.FORMAT_HANDLED;
                        return s;
                    }
                });
            }
        };
        mPlayer = new SimpleExoPlayer.Builder(mCtx, rf)
                .setLoadControl(loadControl)
                .build();
        mPlayer.setVideoSurface(mSurface);
        mPlayer.addListener(mListener2);
        mPlayer.addVideoListener(mVideoListener);

        Uri uri = Uri.parse(url);
        MediaSource ms = new ProgressiveMediaSource.Factory(dsf, new DefaultExtractorsFactory())
                .createMediaSource(uri);                    // TS 由 TsExtractor 嗅探解复用
        mPlayer.prepare(ms);
        mPlayer.setPlayWhenReady(true);
    }

    private final Player.EventListener mListener2 = new Player.EventListener() {
        @Override public void onPlayerStateChanged(boolean playWhenReady, int state) {
            if (mListener == null) return;
            switch (state) {
                case Player.STATE_BUFFERING: mListener.onBufferingStart(ExoPlayerImpl.this); break;
                case Player.STATE_READY:
                    if (playWhenReady) mListener.onStarted(ExoPlayerImpl.this);
                    mListener.onBufferingEnd(ExoPlayerImpl.this);
                    break;
                case Player.STATE_ENDED: mListener.onCompleted(ExoPlayerImpl.this); break;
            }
        }

        @Override public void onPlayerError(ExoPlaybackException e) {
            StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
            Throwable c = e.getCause();
            while (c != null) {
                sb.append(" | cause: ").append(c);
                sb.append(" @ ").append(c.getStackTrace().length > 0
                        ? c.getStackTrace()[0] : "?");
                c = c.getCause();
            }
            String msg = sb.toString();
            android.util.Log.i("IPTV", "exo error: " + msg);
            if (mListener != null) mListener.onError(ExoPlayerImpl.this, ERR_NETWORK, msg);
        }
    };

    private final VideoListener mVideoListener = new VideoListener() {
        @Override public void onVideoSizeChanged(int width, int height,
                int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            if (mSizeListener != null) mSizeListener.onVideoSize(ExoPlayerImpl.this, width, height);
        }
    };

    @Override public void start() { if (mPlayer != null) mPlayer.setPlayWhenReady(true); }

    @Override public void pause() { if (mPlayer != null) mPlayer.setPlayWhenReady(false); }

    @Override public void stop() { if (mPlayer != null) mPlayer.stop(); }

    @Override public void release() {
        releasePlayer();
    }

    @Override public int getCurrentPosition() {
        if (mPlayer == null) return -1;
        try { return (int) mPlayer.getCurrentPosition(); } catch (Throwable t) { return -1; }
    }

    @Override public int getDuration() {
        if (mPlayer == null) return -1;
        try { long d = mPlayer.getDuration(); return d <= 0 ? -1 : (int) d; } catch (Throwable t) { return -1; }
    }

    @Override public boolean isPlaying() {
        return mPlayer != null && mPlayer.getPlayWhenReady()
                && mPlayer.getPlaybackState() == Player.STATE_READY;
    }

    @Override public void setVolume(float l, float r) {
        if (mPlayer != null) mPlayer.setVolume((l + r) / 2f);
    }

    @Override public long getBitrateEstimate() {
        return -1;                                        // 实测码率由旁路探测提供
    }

    @Override public void setVideoSizeListener(IPlayer.VideoSizeListener l) { mSizeListener = l; }

    @Override public void setListener(Listener l) { mListener = l; }

    private void releasePlayer() {
        if (mPlayer != null) {
            try { mPlayer.setVideoSurface(null); mPlayer.release(); } catch (Throwable ignore) {}
            mPlayer = null;
        }
    }
}
