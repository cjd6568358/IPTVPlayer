package com.your.iptv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.your.iptv.IPlayer;
import com.your.iptv.player.PlayerFactory;
import com.your.iptv.player.SystemPlayerImpl;
import com.your.iptv.source.Channel;
import com.your.iptv.source.ChannelRepository;
import com.your.iptv.tvcontroller.TVController;
import com.your.iptv.util.ConfigServer;
import com.your.iptv.util.QrUtil;
import com.your.iptv.util.RetryPolicy;
import com.your.iptv.util.TvUtils;

import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 播放页。键位(V1.3,固定映射,无学习):
 *   上/下/频道±=切台   左=频道列表   右=线路(标清/高清标签)切换
 *   菜单=设置浮层   OK=暂停/播放   数字=频道直选   返回=收浮层/双击退出
 * 切台交互对标 rtp2httpd 3.17.2 网页端:底部信息条(频道号/名称 +
 * 分辨率/视频编码/音频编码/实测码率 chips),自动隐藏。
 * 切台画面同网页端 dual-slot 模型:旧流继续播放,新流在隐藏槽预载,首帧到达才整体替换
 * (无黑屏/冻结帧/缓冲提示)。
 */
public class PlayerActivity extends Activity implements IPlayer.Listener, IPlayer.VideoSizeListener,
        TVController.Host {

    private FrameLayout mRoot;
    private TextureView mTextureView;                       // 顶层槽(先显示用)
    private Surface mSurface;
    // ---- 换台状态机:移植 web-ui/src/components/player/video-player.tsx ----
    // 对应关系:handleLoadSegments(newSegments, forceHardSwitch) → scheduleTune;
    // transitionGenRef → mTuneGen;pendingTransitionRef → mNextEngine/mPreloading;
    // slot A/B → mTextureView/mTextureViewB;activeSlotId/visibleSlotId → mTopShowing;
    // completePendingSwitchIfNeeded(playing) → onStandbyFrame 首帧即 revealNext;
    // completeTransition → revealNext(先 setVisibleSlotId 再 stop 旧槽);
    // fallbackPendingSwitchToHardSwitch → onPreloadError(forceHardSwitch 重入)。
    // 网页端 getSeamlessSwitch() 默认 = isDesktopDevice(),且 supportsSeamlessSwitch
    // = !isLGWebOS() 直接禁用;本机 MStar 单路 VDEC 同理,seamless 默认关闭。
    private TextureView mTextureViewB;                      // 隐藏槽 B(仅 seamless 时用)
    private Surface mSurfaceB;
    private boolean mSurfaceBReady;
    private boolean mTopShowing = true;                     // activeSlotId / visibleSlotId
    private IPlayer mNextEngine;                            // pendingTransitionRef.player
    private Channel mNextChannel;
    private boolean mNextStarted;
    private int mNextFrames;                                // 仅日志度量,不作换槽判据
    private boolean mSeqMuted;                              // pending 槽 muted,换台后恢复
    private long mZapT0;                                    // pending.startedAt
    private Runnable mProbeStart;
    private boolean mPreloading;                            // pendingTransitionRef != null
    private boolean mActiveLive;                            // hasStartedPlaybackRef
    private int mTuneGen;                                   // transitionGenRef
    private boolean mSeamlessSwitch;                        // 网页端 seamlessSwitch 设置
    private boolean mEngineFallback;                        // 当前活动槽是否为降级引擎

    // ---- loading 指示:网页端 setIsLoading + showLoading 的 500ms 防抖 ----
    private boolean mLoading;
    private static final long LOADING_DEBOUNCE_MS = 500;
    // 网页端帧率来自播放流 demux 的 SPS(起播即到)。TV 端只能旁路探测,真实流实测
    // SPS 在 ~655KB 才出现,延后过久会赶不上信息条 8s 窗口 → 帧率永远显示"帧率 —"。
    private static final long PROBE_DELAY_MS = 800;        // 首帧后 0.8s 开探测
    private final Runnable mShowLoading = new Runnable() {
        @Override public void run() {
            if (mLoading && isConnectStatus()) status("缓冲中...");
        }
    };

    private TextView mOsdNumber, mOsdStatus;               // 数字直选 OSD / 错误状态
    private TextView mClock;
    private FrameLayout mPauseOverlay;                     // 暂停全屏遮罩(仅图标)
    private StateBadge mPauseIcon;                         // 暂停徽章(Paint 自绘,双竖条)
    private StateBadge mPlayIcon;                          // 播放徽章(Paint 自绘,三角)
    private LinearLayout mInfoBar;                         // 切台底部信息条(网页端同交互)
    private FrameLayout mSourcePanel;                      // 方向右:右侧浮层(信息+线路切换)
    private TextView mSrcTitle, mSrcRes, mSrcFps, mSrcVideo, mSrcAudio, mSrcChannels, mSrcBitrate;
    private LinearLayout mSrcRows;
    private LinearLayout mSourceCol;                       // 浮层栏体(遮罩内右侧 34% 列)
    private boolean mSourceOpen;
    private int mSourceSel = -1;
    private TextView mInfoBarName, mInfoBarLine;
    private TextView mChipRes, mChipFps, mChipVideo, mChipAudio, mChipChannels, mChipBitrate;
    private FrameLayout mSidebar;
    private LinearLayout mTabsRow;
    private ListView mSideList;
    private FrameLayout mConfigOverlay;
    private EditText mCfgM3u;
    private CheckBox mCfgAutoStart, mCfgShowInfo, mCfgClock, mCfgDualSlot;
    private TextView mCfgOsd, mQrTip;
    private ImageView mCfgQr;

    private IPlayer mEngine;
    private boolean mPreferExo;
    private boolean mTriedOther;
    private boolean mRetryExhausted;              // 重试链耗尽(断网恢复监听据此重连)
    private List<Channel> mChannels = new ArrayList<Channel>();
    private int mIndex = 0;
    private Channel mCurrent;
    private RetryPolicy mRetry = new RetryPolicy();
    private boolean mSurfaceReady;
    private boolean mPaused;

    private boolean mShowInfo;
    private boolean mClockAlways;
    private int mVideoW, mVideoH;

    private boolean mSidebarOpen;
    private String mCurGroup = "全部";
    private List<Channel> mVisible = new ArrayList<Channel>();
    private final SideAdapter mSideAdapter = new SideAdapter();

    private boolean mConfigOpen;

    private int mNumberBuf = -1;
    private long mNumberTs = 0;
    private long mLastBackTs = 0;
    private float mOsdScale = 1.0f;
    private String mLastListUrl = null;
    private int mOsdGen = 0, mGroupIdx = 0;
    private float mVol = 1.0f;
    private final Runnable mNumberCommit = new Runnable() {
        @Override public void run() { commitNumber(); }
    };
    private final Runnable mClockTick = new Runnable() {
        @Override public void run() {
            tickClock();
            mHandler.postDelayed(mClockTick, 30000);
        }
    };
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // ---- 信息条状态 ----
    private int mInfoBarGen;
    private boolean mInfoBarPending;

    // ---- TS 探针(编码方式)与码率采样 ----
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadCfg();
        // 对应网页端 getSeamlessSwitch():默认只在桌面开启;本机单路 VDEC 默认关闭。
        // 兼容已部署 1.3.3 的旧 key dual_slot。
        mSeamlessSwitch = App.prefBool("seamless_switch", App.prefBool("dual_slot", false));
        buildUi();
        TVController.bind(this);
        // 断网恢复自愈(§3.2.2 坑5 / 测试用例 S5):重试链耗尽后,网络回来时自动重连当前频道
        registerReceiver(mNetReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        // 顶层槽:显示当前频道;当顶层是预载目标(mTopShowing=false)时,首帧到达即换槽
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                mSurfaceReady = true;
                mSurface = new Surface(st);
                IPlayer e = (mSeamlessSwitch && !mTopShowing) ? mNextEngine : mEngine;
                if (e != null) e.setSurface(mSurface);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                // 单槽模式顶层恒为显示槽,不依赖 mTopShowing 分支(双槽换槽会翻转该状态)
                if (!mSeamlessSwitch) { onActiveFrame(); return; }
                if (mPreloading && !mTopShowing) onStandbyFrame();
                else if (mTopShowing) onActiveFrame();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                IPlayer e = (mSeamlessSwitch && !mTopShowing) ? mNextEngine : mEngine;
                if (e != null) e.setSurface(null);
                if (mSurface != null) { mSurface.release(); mSurface = null; }
                mSurfaceReady = false;
                return true;
            }
        });
        // 底层槽:仅双槽实验模式使用;单槽模式下 GONE,不再产生任何纹理上传/合成
        mTextureViewB.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                mSurfaceBReady = true;
                mSurfaceB = new Surface(st);
                if (!mSeamlessSwitch) return;
                IPlayer e = mTopShowing ? mNextEngine : mEngine;
                if (e != null) e.setSurface(mSurfaceB);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (!mSeamlessSwitch) return;
                if (mPreloading && mTopShowing) onStandbyFrame();
                else if (!mTopShowing) onActiveFrame();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (mSeamlessSwitch) {
                    IPlayer e = mTopShowing ? mNextEngine : mEngine;
                    if (e != null) e.setSurface(null);
                }
                if (mSurfaceB != null) { mSurfaceB.release(); mSurfaceB = null; }
                mSurfaceBReady = false;
                return true;
            }
        });

        String lu = App.pref("list_url", "").trim();
        if (lu.length() == 0) {
            status("请先在设置浮层里配置 M3U 地址");
            openConfigOverlay();
        } else if (TvUtils.isNetworkUp(this)) {
            loadAndStart(lu);
        } else {
            // 开机直启时网络可能未就绪:静默等待后再加载开播(不进重试链、不报错),
            // 轮询方式不依赖广播投递(乐视会剥广播);显示"等待网络连接"给用户预期
            status("等待网络连接...");
            waitForNet(lu);
        }
        startClock();
    }

    private boolean mNetWaiting;

    /** 开机网络未就绪:3s 轮询等待,网络可用后走正常加载开播。 */
    private void waitForNet(final String lu) {
        mNetWaiting = true;
        final long t0 = android.os.SystemClock.elapsedRealtime();
        android.util.Log.i("IPTV", "net: not ready at boot, waiting");
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!mNetWaiting) return;               // 已被其他路径启动(如换台/浮层)
                if (TvUtils.isNetworkUp(PlayerActivity.this)) {
                    mNetWaiting = false;
                    android.util.Log.i("IPTV", "net: ready after "
                            + (android.os.SystemClock.elapsedRealtime() - t0) / 1000 + "s");
                    status("");
                    loadAndStart(lu);
                } else {
                    mHandler.postDelayed(this, 3000);
                }
            }
        }, 2500);
    }

    /** 加载缓存频道列表 → 起播 → 后台刷新。 */
    private void loadAndStart(String lu) {
        mChannels = ChannelRepository.loadCached(this);
        mLastListUrl = lu;
        if (mChannels.size() > 0) {
            mIndex = parseIntDef(App.pref("last_ch", "0"));
            if (mIndex < 0 || mIndex >= mChannels.size()) mIndex = 0;
            tune(mChannels.get(mIndex));
        } else {
            status("频道列表为空,检查 M3U 地址");
        }
        refreshChannelsInBackground(lu);
    }

    @Override protected void onDestroy() {
        TVController.unbind();
        unregisterReceiver(mNetReceiver);
        mHandler.removeCallbacksAndMessages(null);
        if (mNextEngine != null) { mNextEngine.setListener(null); mNextEngine.release(); mNextEngine = null; }
        if (mEngine != null) { mEngine.setListener(null); mEngine.release(); mEngine = null; }
        if (mSurface != null) { mSurface.release(); mSurface = null; }
        if (mSurfaceB != null) { mSurfaceB.release(); mSurfaceB = null; }
        App.get().wifiLock(false);
        super.onDestroy();
    }

    private void loadCfg() {
        mOsdScale = parseFloat(App.pref("osd_size", "1.0"));
        // 引擎自动选择:ExoPlayer 打包即优先(自管 demux+LoadControl 缓冲水位,
        // = 网页端 mpegts.js+MSE 模型);未打包时回退系统 NuPlayer
        mPreferExo = PlayerFactory.exoAvailable();
        mShowInfo = App.prefBool("show_info", true);
        mClockAlways = App.prefBool("clock_always", true);
        mVol = App.prefInt("volume", 100) / 100f;    // 恢复上次音量(手机端设置)
    }

    /** 配置改变后同步,对应网页端 handleSeamlessSwitchChange。 */
    private void syncSeamlessSwitch() {
        setSeamlessSwitch(App.prefBool("seamless_switch", App.prefBool("dual_slot", false)));
    }

    /** 对应网页端 useEffect([seamlessSwitch]) → stopPendingTransition()。 */
    private void setSeamlessSwitch(boolean on) {
        if (mSeamlessSwitch == on || mTextureView == null) return;
        mSeamlessSwitch = on;
        cancelPreload();                         // 关掉无缝时立即停掉 pending 槽
        mTopShowing = true;
        mTextureView.setAlpha(1f);
        mTextureViewB.setVisibility(on ? View.VISIBLE : View.GONE);
        if (mEngine != null && mSurface != null) mEngine.setSurface(mSurface);
        setLoading(false);
    }

    private void refreshChannelsInBackground(final String url) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                final List<Channel> fresh = ChannelRepository.fetchAndCache(url);
                runOnUiThread(new Runnable() { @Override public void run() {
                    mChannels = fresh;
                    mLastListUrl = url;
                    rebindIndex();
                    rebuildGroups();
                    applyFilter();
                    if (mIndex >= mChannels.size()) mIndex = 0;
                    if (mChannels.size() == 0) { status("频道列表为空,检查 M3U 地址"); return; }
                    Channel want = mChannels.get(mIndex);
                    if (mCurrent == null || !want.getUrl().equals(mCurrent.getUrl())
                            || !want.name.equals(mCurrent.name)) {
                        tune(want);              // 首次拉取/当前频道已不存在:切到新列表位置
                    } else {
                        mCurrent = want;         // 当前频道未变:静默刷新,不打断播放
                        updateInfoBar();
                    }
                }});
            } catch (Throwable t) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    status("频道列表拉取失败:" + t.getMessage());
                }});
            }
        }}).start();
    }

    private int px(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ================= UI 构建 =================

    private void buildUi() {
        mRoot = new FrameLayout(this);

        // 底层槽仅在双槽实验模式下可见;单槽模式 GONE —— 它的 SurfaceTexture 会销毁,
        // 不再有第二路 1080p 纹理上传与全屏 alpha 合成(老 GPU 上这层开销会直接掉帧)。
        mTextureViewB = new TextureView(this);
        if (!mSeamlessSwitch) mTextureViewB.setVisibility(View.GONE);
        mRoot.addView(mTextureViewB, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 视频层(TextureView,普通视图,UI 可覆盖);单槽模式下恒 alpha=1
        mTextureView = new TextureView(this);
        mRoot.addView(mTextureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 网页端没有全屏换台遮罩:背景槽用 opacity-0 pointer-events-none 保持帧回调,
        // 活动槽直接可见,loading 提示做 500ms 防抖。此处同构,不加遮罩层。

        // 暂停全屏遮罩(半透明黑底 + 自绘暂停徽章;按需求:仅图标,无文字)
        mPauseOverlay = new FrameLayout(this);
        mPauseOverlay.setBackgroundColor(Color.argb(160, 0, 0, 0));
        mPauseIcon = new StateBadge(this);
        mPauseIcon.setState(false);
        FrameLayout.LayoutParams pilp = new FrameLayout.LayoutParams(px(170), px(170));
        pilp.gravity = Gravity.CENTER;
        mPauseOverlay.addView(mPauseIcon, pilp);
        mPauseOverlay.setVisibility(View.GONE);
        mRoot.addView(mPauseOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 恢复播放闪烁提示(与暂停徽章同一几何规格,自绘三角)
        mPlayIcon = new StateBadge(this);
        mPlayIcon.setState(true);
        mPlayIcon.setVisibility(View.GONE);
        mRoot.addView(mPlayIcon, new FrameLayout.LayoutParams(px(170), px(170),
                Gravity.CENTER));

        // 数字直选 OSD(顶部大号数字)
        mOsdNumber = osdTv(96 * mOsdScale, Color.WHITE, Gravity.TOP | Gravity.END,
                48 + TvUtils.overScan(), 40 + TvUtils.overScan());
        ((FrameLayout.LayoutParams) mOsdNumber.getLayoutParams()).topMargin =
                40 + TvUtils.overScan();
        mRoot.addView(mOsdNumber);
        mOsdStatus = osdTv(30 * mOsdScale, Color.rgb(255, 130, 130), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                0, 40 + TvUtils.overScan() + px(120));
        mRoot.addView(mOsdStatus);
        mClock = osdTv(26 * mOsdScale, Color.WHITE, Gravity.TOP | Gravity.END,
                48 + TvUtils.overScan(), 36 + TvUtils.overScan());
        ((FrameLayout.LayoutParams) mClock.getLayoutParams()).topMargin =
                36 + TvUtils.overScan();                        // overscan 顶部安全区
        mRoot.addView(mClock);                          // 加入视图树(原缺失:OSD 视图从未显示)
        mClock.setVisibility(App.prefBool("clock_always", true) ? View.VISIBLE : View.GONE);

        buildInfoBar();
        buildSourcePanel();
        buildSidebar();
        buildConfigOverlay();
        setContentView(mRoot);
        applyFullscreen();
        startClock();
    }

    /** 底部信息条:左=频道号+名称;右=分辨率/编码/码率 chips(网页端同交互)。 */
    private void buildInfoBar() {
        mInfoBar = new LinearLayout(this);
        mInfoBar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.argb(216, 0, 0, 0)});
        mInfoBar.setBackgroundDrawable(bg);
        mInfoBar.setPadding(px(24) + TvUtils.overScan(), px(28), px(24) + TvUtils.overScan(),
                28 + TvUtils.overScan());
        mInfoBar.setVisibility(View.GONE);
        mInfoBar.setClickable(false);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.BOTTOM | Gravity.START);

        TextView num = new TextView(this);
        num.setTextColor(Color.WHITE);
        num.setTextSize(34);
        num.setTypeface(null, android.graphics.Typeface.BOLD);
        mInfoBarName = num;                          // 复用:先号后名放同一行
        row1.addView(num);

        TextView name = new TextView(this);
        name.setTextColor(Color.WHITE);
        name.setTextSize(28);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(getResources().getDisplayMetrics().widthPixels / 2);
        name.setPadding(px(14), 0, 0, 0);
        row1.addView(name);
        mInfoBarLine = name;

        mInfoBar.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, px(8), 0, 0);
        mChipRes = chip(chips);
        mChipFps = chip(chips);
        mChipVideo = chip(chips);
        mChipAudio = chip(chips);
        mChipChannels = chip(chips);
        mChipBitrate = chip(chips);
        mInfoBar.addView(chips, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        mRoot.addView(mInfoBar, lp);
    }

    private TextView chip(LinearLayout parent) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setSingleLine(true);
        t.setPadding(px(12), px(3), px(12), px(4));
        GradientDrawable bg = new GradientDrawable();          // 网页端 outline chip 同款观感
        bg.setColor(Color.argb(140, 8, 20, 50));
        bg.setCornerRadius(px(5));
        bg.setStroke(1, Color.argb(90, 140, 190, 255));
        t.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(8);
        parent.addView(t, lp);
        return t;
    }

    /** OSD 字号实时生效(设置保存后无需重启)。 */
    private float parseFloat(String s) {
        try { return Float.parseFloat(s); } catch (Throwable t) { return 1.0f; }
    }

    private int parseIntDef(String s) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return 0; }
    }

    private String osdName(String v) {
        if ("0.8".equals(v)) return "OSD 字号:小";
        if ("1.3".equals(v)) return "OSD 字号:大";
        return "OSD 字号:中";
    }

    private void applyOsdScale() {
        float s = mOsdScale;
        mClock.setTextSize(26 * s);
        mOsdNumber.setTextSize(96 * s);
        mOsdStatus.setTextSize(30 * s);
    }

    private TextView osdTv(float sp, int color, int gravity, int mr, int mb) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setShadowLayer(6, 3, 3, Color.BLACK);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        lp.rightMargin = mr;
        lp.bottomMargin = mb;
        t.setLayoutParams(lp);
        return t;
    }

    private void applyFullscreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    private void tickClock() {
        mClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        // 只显示时间(不带"直播/已暂停"后缀);半点/整点前后 2 分钟整体消失,
        // 给频道自身的报时图标让位。tick 周期 30s,窗口 4 分钟,边界必然覆盖。
        int minute = Calendar.getInstance().get(Calendar.MINUTE);
        boolean blackout = (minute >= 28 && minute <= 31) || minute >= 58 || minute <= 1;
        mClock.setVisibility(mClockAlways && !blackout ? View.VISIBLE : View.GONE);
    }

    private void startClock() {
        mHandler.removeCallbacks(mClockTick);
        tickClock();
        mHandler.postDelayed(mClockTick, 30000);
    }

    // ================= 底部信息条(网页端同交互) =================

    /** 换台/换线路时显示:频道号 + 名称 + 当前线路 + chips;5s 自动隐藏。 */
    private void showInfoBar(Channel ch) {
        mRevealLeft = 1;
        mMetaEverComplete = false;
        mInfoBarGen++;
        cancelInfoBarHide();
        updateInfoBar();
        mInfoBar.setVisibility(View.VISIBLE);
        final int gen = mInfoBarGen;
        mHandler.postDelayed(new Runnable() { @Override public void run() {
            if (gen == mInfoBarGen) {
                mInfoBar.setVisibility(View.GONE);
                mInfoBarPending = false;
            }
        }}, 8000);
    }

    private void cancelInfoBarHide() {
        mInfoBarPending = false;
    }

    private void updateInfoBar() {
        if (mCurrent == null || mInfoBarName == null) return;
        setTextIfChanged(mInfoBarName, String.valueOf(mCurrent.number));
        setTextIfChanged(mInfoBarLine, mCurrent.name);
        ConfigServer.StreamMeta m = ConfigServer.meta();
        // 与网页端 PlayerMediaBadges 共用同一组格式化函数,不再有两份漂移的副本
        setChip(mChipRes, formatResolution(m, mVideoW, mVideoH), mShowInfo);
        setChip(mChipFps, formatFrameRate(m.fps, false), mShowInfo);
        setChip(mChipVideo, formatVideoCodec(m.videoCodec), mShowInfo);
        setChip(mChipAudio, formatAudioCodec(m.audioCodec), mShowInfo);
        setChip(mChipChannels, formatChannels(m.channels), mShowInfo);
        setChip(mChipBitrate, formatBitrate(m.bitrateBps), mShowInfo);
    }

    /** 对齐 player-media-badges.tsx:visibleBadges = badges.filter(badge !== null),
     *  无数据的 chip 直接不渲染(而不是显示"—"占位)。 */
    private void setChip(TextView t, String value, boolean enabled) {
        if (value == null || !enabled) { t.setVisibility(View.GONE); return; }
        t.setVisibility(View.VISIBLE);
        setTextIfChanged(t, value);
    }

    /** formatResolution:无高度返回 null;4K 特判;否则 height + i/p。 */
    private static String formatResolution(ConfigServer.StreamMeta m, int videoW, int videoH) {
        int h = m.height > 0 ? m.height : videoH;          // TV 平台等价:onVideoSizeChanged
        int w = m.width > 0 ? m.width : videoW;
        if (h <= 0) return null;
        if (w >= 3840 || Math.round(h) >= 2160) return "4K";
        return Math.round(h) + ("interlaced".equals(m.scanType) ? "i" : "p");
    }

    /** formatFrameRate:doubleFrameRate 对应网页端 renderState.deinterlacing。
     *  TV 端无 WebGL/bwdif 去隔行(硬解直接输出帧),恒传 false。 */
    private static String formatFrameRate(double fps, boolean doubleFrameRate) {
        if (!(fps > 0) || Double.isNaN(fps) || Double.isInfinite(fps)) return null;
        double rendered = doubleFrameRate ? fps * 2 : fps;
        return (Math.round(rendered * 100) / 100.0) + " FPS";
    }

    /** formatBitrate:>=1Mbps 用两位小数 Mbps,否则一位小数 Kbps。 */
    private static String formatBitrate(long bps) {
        if (bps <= 0) return null;
        if (bps >= 1000000L) return (Math.round(bps / 10000.0) / 100.0) + " Mbps";
        return (Math.round(bps / 100.0) / 10.0) + " Kbps";
    }

    private static String formatChannels(int cc) {
        if (cc <= 0) return null;
        return cc == 1 ? "单声道" : cc == 2 ? "立体声" : cc == 6 ? "5.1"
                : cc == 8 ? "7.1" : cc + " 声道";
    }

    /** 值变化才 setText:信息条每秒采样,恒定值不重复触发重绘。 */
    private static void setTextIfChanged(TextView t, String s) {
        if (s != null && s.contentEquals(t.getText())) return;
        t.setText(s);
    }

    // ---- 信息条格式化(player-media-badges.tsx 移植,单一实现)----

    private static String formatVideoCodec(String codec) {
        if (codec == null) return null;
        switch (codec) {
            case "h264": return "H.264";
            case "hevc": return "HEVC";
            case "vp9": return "VP9";
            case "av1": return "AV1";
            default: return null;
        }
    }

    private static String formatAudioCodec(String codec) {
        if (codec == null) return null;
        switch (codec) {
            case "aac": return "AAC";
            case "ac3": return "AC-3";
            case "eac3": return "E-AC-3";
            case "mp2": return "MP2";
            case "mp3": return "MP3";
            case "opus": return "Opus";
            default: return null;
        }
    }

    // ================= TS 探针:从流头解析编码方式 =================


    // ================= 信息条采样(泵元数据:编码/实测码率) =================

    private boolean mMetaRunning;

    private void startMetaSampler() {
        if (mMetaRunning) return;
        mMetaRunning = true;
        mHandler.post(mMetaTick);            // 立即首拍:chips 尽快填上(旁路探测 1-2s 内出数)
    }

    private void stopMetaSampler() {
        mMetaRunning = false;
        mHandler.removeCallbacks(mMetaTick);
    }

    private double mLastPresentRatio = 0;
    private int mRevealLeft;
    private boolean mMetaEverComplete;                 // 本轮展示期内元数据是否已到齐(防补显循环)

    // 视频显示严格对标网页端:video 元素 object-fill + 16:9 容器 = 解码帧恒铺满屏幕
    // (TextureView 恒为 MATCH_PARENT,硬解缩放等价 object-fill),无任何按比例适配。

    private final Runnable mMetaTick = new Runnable() {
        @Override public void run() {
            if (!mMetaRunning) return;
            // 静默期(信息条隐藏+浮层关闭+无补显欠账)降频到 4s,省唤醒
            boolean active = mInfoBar.getVisibility() == View.VISIBLE || mSourceOpen || mRevealLeft > 0;
            if (active) {
                ConfigServer.StreamMeta m = ConfigServer.meta();
                // 帧率来自 SPS(实测 ~655KB 才到),远晚于音视频编码。判据必须含 fps,
                // 否则编码先到就把 mMetaEverComplete 置位,fps 迟到后再也不会补显。
                boolean complete = m.videoCodec != null && m.audioCodec != null && m.fps > 0;
                if (complete && mInfoBar.getVisibility() == View.VISIBLE) {
                    mMetaEverComplete = true;          // 展示期内已到齐:隐藏后不再补显(修复重复唤起)
                } else if (complete && !mMetaEverComplete && mRevealLeft > 0) {
                    mRevealLeft--;                     // 元数据真正迟到(展示期后才到齐):仅补显一次 3s
                    mMetaEverComplete = true;
                    mInfoBarGen++;
                    mInfoBar.setVisibility(View.VISIBLE);
                    final int gen = mInfoBarGen;
                    mHandler.postDelayed(new Runnable() { @Override public void run() {
                        if (gen == mInfoBarGen) mInfoBar.setVisibility(View.GONE);
                    }}, 3000);
                }
                if (mInfoBar.getVisibility() == View.VISIBLE) updateInfoBar();
                if (mSourceOpen) updateSourceChips();
                mHandler.postDelayed(mMetaTick, 1000);
            } else {
                mHandler.postDelayed(mMetaTick, 4000);
            }
        }
    };

    // ================= 暂停/播放(仅图标) =================

    private void showPauseOverlay() {
        mPauseOverlay.setVisibility(View.VISIBLE);
    }

    private void hidePauseOverlay() {
        mPauseOverlay.setVisibility(View.GONE);
    }

    private void flashPlayIcon() {
        mPlayIcon.setState(true);
        mPlayIcon.setVisibility(View.VISIBLE);
        mPlayIcon.setAlpha(1.0f);
        mHandler.postDelayed(new Runnable() { @Override public void run() {
            mPlayIcon.animate().alpha(0f).setDuration(500).start();
        }}, 600);
        mHandler.postDelayed(new Runnable() { @Override public void run() {
            mPlayIcon.setVisibility(View.GONE);
            mPlayIcon.setAlpha(1.0f);
        }}, 1200);
    }

    // ================= 方向右:右侧浮层(信息条内容 + 线路切换) =================

    private void buildSourcePanel() {
        mSourcePanel = new FrameLayout(this);
        mSourcePanel.setBackgroundColor(Color.argb(226, 10, 16, 26));
        mSourcePanel.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = px(20) + TvUtils.overScan() / 2;
        col.setPadding(pad, pad + TvUtils.overScan(), pad, pad);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.34f),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        mSourcePanel.addView(col, clp);
        mSourceCol = col;

        mSrcTitle = new TextView(this);
        mSrcTitle.setTextColor(Color.WHITE);
        mSrcTitle.setTextSize(22);
        mSrcTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(mSrcTitle);

        // 信息 chips(与底部信息条同源同格式)
        LinearLayout chips1 = new LinearLayout(this);
        chips1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams c1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c1lp.topMargin = px(10);
        chips1.setLayoutParams(c1lp);
        mSrcRes = chip(chips1);
        mSrcFps = chip(chips1);
        mSrcVideo = chip(chips1);
        col.addView(chips1);
        LinearLayout chips2 = new LinearLayout(this);
        chips2.setOrientation(LinearLayout.HORIZONTAL);
        mSrcAudio = chip(chips2);
        mSrcChannels = chip(chips2);
        mSrcBitrate = chip(chips2);
        col.addView(chips2);

        TextView lbl = new TextView(this);
        lbl.setText("线路");
        lbl.setTextColor(Color.rgb(120, 200, 255));
        lbl.setTextSize(16);
        lbl.setPadding(0, px(14), 0, px(6));
        col.addView(lbl);

        mSrcRows = new LinearLayout(this);
        mSrcRows.setOrientation(LinearLayout.VERTICAL);
        col.addView(mSrcRows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mRoot.addView(mSourcePanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 方向右:开/关右侧浮层。 */
    private void toggleSourcePanel() {
        if (mSourceOpen) {
            mSourceOpen = false;
            // 遮罩淡出 + 栏体滑出,结束才真正隐藏。面板是全屏宽,只平移无法滑出屏幕,
            // 也不设 GONE 会永久残留遮罩(历史 bug:返回键/换台关浮层后画面蒙灰)
            mSourcePanel.animate().alpha(0f).setDuration(200).start();
            mSourceCol.animate().translationX(px(500)).setDuration(200)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (!mSourceOpen) {          // 动画期间又被打开:不隐藏
                                mSourcePanel.setVisibility(View.GONE);
                                mSourcePanel.setAlpha(1f);
                                mSourceCol.setTranslationX(0);
                            }
                        }
                    }).start();
            return;
        }
        if (mSidebarOpen) toggleSidebar(false);
        if (mCurrent == null) return;
        mSourceOpen = true;
        mSourceSel = mCurrent.sourceIndex;
        populateSourcePanel();
        mSourceCol.setTranslationX(px(500));             // 从右侧滑入
        mSourcePanel.setAlpha(0f);                       // 遮罩淡入
        mSourcePanel.setVisibility(View.VISIBLE);
        mSourcePanel.animate().alpha(1f).setDuration(200).start();
        mSourceCol.animate().translationX(0).setDuration(200).start();
    }

    private void populateSourcePanel() {
        if (mCurrent == null) return;
        mSrcTitle.setText(mCurrent.number + "  " + mCurrent.name);
        updateSourceChips();
        mSrcRows.removeAllViews();
        for (int i = 0; i < mCurrent.sourceCount(); i++) {
            final int idx = i;
            TextView row = new TextView(this);
            String label = mCurrent.labels.get(i);
            row.setText(label);
            row.setTextSize(18);
            row.setPadding(px(14), px(14), px(14), px(14));
            boolean cur = i == mCurrent.sourceIndex;
            boolean sel = i == mSourceSel;
            row.setTextColor(cur ? Color.rgb(120, 200, 255) : sel ? Color.WHITE : Color.LTGRAY);
            row.setBackgroundColor(sel ? Color.argb(110, 40, 90, 150) : Color.TRANSPARENT);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectSource(idx); }
            });
            mSrcRows.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void updateSourceChips() {
        ConfigServer.StreamMeta m = ConfigServer.meta();
        // 与底部信息条共用同一组格式化函数(网页端 badges 只有一份实现)
        setChip(mSrcRes, formatResolution(m, mVideoW, mVideoH), true);
        setChip(mSrcFps, formatFrameRate(m.fps, false), true);
        setChip(mSrcVideo, formatVideoCodec(m.videoCodec), true);
        setChip(mSrcAudio, formatAudioCodec(m.audioCodec), true);
        setChip(mSrcChannels, formatChannels(m.channels), true);
        setChip(mSrcBitrate, formatBitrate(m.bitrateBps), true);
    }

    /** 选中某条线路(OK 确认)。 */
    private void selectSource(int idx) {
        if (mCurrent == null || idx < 0 || idx >= mCurrent.sourceCount()) return;
        mCurrent.sourceIndex = idx;
        // 不弹线路提示(用户要求):浮层里选中态高亮已经表达所选线路
        mSourceSel = idx;
        populateSourcePanel();
        updateInfoBar();
        mSideAdapter.notifyDataSetChanged();
        scheduleTune(false);                            // 换线路与换台共用 handleLoadSegments
    }

    /** 浮层打开时:上下导航线路行。 */
    private void sourceNav(int d) {
        int n = mCurrent != null ? mCurrent.sourceCount() : 0;
        if (n == 0) return;
        int pos = mSourceSel < 0 ? mCurrent.sourceIndex : mSourceSel;
        pos = Math.max(0, Math.min(n - 1, pos + d));
        mSourceSel = pos;
        populateSourcePanel();
    }

    // ================= 侧边栏 =================

    private void buildSidebar() {
        mSidebar = new FrameLayout(this);
        mSidebar.setBackgroundColor(Color.argb(216, 12, 18, 26));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(16), px(20) + TvUtils.overScan(), px(16), px(16));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        mTabsRow = new LinearLayout(this);
        mTabsRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(mTabsRow);
        col.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mSideList = new ListView(this);
        mSideList.setDivider(null);
        mSideList.setCacheColorHint(Color.TRANSPARENT);
        mSideList.setVerticalScrollBarEnabled(false);
        mSideList.setAdapter(mSideAdapter);
        col.addView(mSideList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mSidebar.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSidebar.setTranslationX(-px(600));
        mRoot.addView(mSidebar, new FrameLayout.LayoutParams(px(560),
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void rebuildGroups() {
        mTabsRow.removeAllViews();
        List<String> groups = new ArrayList<String>();
        groups.add("全部");
        for (Channel c : mChannels) {
            if (c.group != null && c.group.length() > 0 && !groups.contains(c.group)) groups.add(c.group);
        }
        mGroupIdx = 0;
        for (int i = 0; i < groups.size(); i++) {
            final String g = groups.get(i);
            if (g.equals(mCurGroup)) mGroupIdx = i;
            TextView t = new TextView(this);
            t.setText(g);
            t.setTextColor(g.equals(mCurGroup) ? Color.rgb(120, 200, 255) : Color.LTGRAY);
            t.setTextSize(17);
            t.setPadding(px(18), px(10), px(18), px(10));
            t.setFocusable(true);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { mCurGroup = g; applyFilter(); rebuildGroups(); }
            });
            mTabsRow.addView(t);
        }
    }

    private void applyFilter() {
        mVisible.clear();
        for (Channel c : mChannels) {
            if (mCurGroup.equals("全部") || (c.group != null && c.group.equals(mCurGroup)))
                mVisible.add(c);
        }
        mSideAdapter.notifyDataSetChanged();
    }

    private void toggleSidebar(final boolean open) {
        mSidebarOpen = open;
        mSidebar.animate().translationX(open ? 0 : -px(600)).setDuration(200).start();
        if (open) {
            if (mSourceOpen) toggleSourcePanel();          // 左右浮层互斥
            // 当前频道不在所选分组时重置为"全部"(避免分组残留导致选中偏移)
            if (mCurrent != null && mCurGroup != null && !mCurGroup.equals("全部")
                    && !mCurGroup.equals(mCurrent.group)) {
                mCurGroup = "全部";
            }
            rebuildGroups();
            applyFilter();
            int sel = currentVisiblePos();
            mSidebarSel = sel >= 0 ? sel : 0;
            mSideAdapter.notifyDataSetChanged();
            mSideList.post(new Runnable() { @Override public void run() {
                scrollSelToCenter(mSidebarSel);              // 布局完成后居中定位
            }});
            mSideList.setSelection(mSidebarSel);
        } else {
            applyFullscreen();
        }
    }

    private int currentVisiblePos() {
        if (mCurrent == null) return -1;
        for (int i = 0; i < mVisible.size(); i++)
            if (mVisible.get(i).getUrl().equals(mCurrent.getUrl())) return i;
        return -1;
    }

    private class SideAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return mVisible.size(); }
        @Override public Object getItem(int i) { return mVisible.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View cv, ViewGroup p) {
            TextView t = (cv instanceof TextView) ? (TextView) cv : new TextView(PlayerActivity.this);
            Channel c = mVisible.get(i);
            boolean cur = mCurrent != null && c.getUrl().equals(mCurrent.getUrl());
            boolean sel = i == mSidebarSel;
            String multi = c.sourceCount() > 1 ? "  ·" + c.sourceCount() + "线路" : "";
            t.setText(c.number + "  " + c.name + multi);
            t.setTextColor(cur ? Color.rgb(120, 200, 255) : sel ? Color.WHITE : Color.LTGRAY);
            t.setTextSize(18);
            t.setPadding(px(14), px(14), px(14), px(14));
            t.setBackgroundColor(sel ? Color.argb(110, 40, 90, 150) : Color.TRANSPARENT);
            return t;
        }
    }

    // ================= 换台 / 线路切换 =================

    private void tune(Channel ch) {
        mCurrent = ch;
        App.prefPut("last_ch", String.valueOf(mIndex));
        // 暂停态切台:新流会自动 start 播放,必须同步重置 UI 暂停态
        if (mPaused) {
            mPaused = false;
            hidePauseOverlay();
        }
        showInfoBar(ch);
        if (mSourceOpen) toggleSourcePanel();      // 换台关闭线路浮层
        scheduleTune(false);
        mSideAdapter.notifyDataSetChanged();     // 侧栏高亮同步到新频道
    }

    /**
     * 对应网页端 video-player.tsx handleLoadSegments(newSegments, forceHardSwitch):
     * 换台/换源/重试统一入口。代数守卫作废旧 pending;仅当 useSeamlessSwitch 成立
     * 才走隐藏槽预载,否则复用活动槽直接重载(= loadActiveSlotSegments)。
     */
    private void scheduleTune(boolean forceHardSwitch) {
        mTriedOther = false;
        mRetry.reset();
        mRetryExhausted = false;
        ConfigServer.invalidateMeta();           // TV 平台适配:停掉旧旁路探测
        stopMetaSampler();
        mTuneGen++;                             // transitionGenRef++
        mHandler.removeCallbacks(mProbeStart);
        cancelPreload();                        // stopPendingTransition()
        setLoading(true);                       // setIsLoading(true),提示 500ms 防抖
        boolean isStreamSwitch = mActiveLive && mEngine != null;
        // 网页端 useSeamlessSwitch = !forceHardSwitch && seamlessSwitch && !PiP
        //   && hasStartedPlayback && isStreamSwitch && live && shouldAutoPlay && !paused
        boolean useSeamless = !forceHardSwitch && mSeamlessSwitch && isStreamSwitch && !mPaused;
        if (mCurrent == null) return;
        if (useSeamless) {
            startPreload(mCurrent, mTuneGen);
        } else {
            openWithEngine(mCurrent.getUrl());   // hard switch:复用活动槽
        }
    }

    /** 网页端 setIsLoading + showLoading 的 500ms 防抖(快速出画不闪提示)。 */
    private void setLoading(boolean on) {
        if (mLoading == on) return;
        mLoading = on;
        mHandler.removeCallbacks(mShowLoading);
        if (on) {
            status("");
            mHandler.postDelayed(mShowLoading, LOADING_DEBOUNCE_MS);
        } else if (isConnectStatus()) {
            status("");
        }
    }

    private void openWithEngine(String url) { openWithEngine(url, mEngineFallback); }

    /**
     * 对应 loadActiveSlotSegments(activePlayer, activeId, newSegments):复用同一个
     * 播放后端实例重载,不重建槽、不新建 IPlayer(网页端 hard switch 同款)。
     */
    private void openWithEngine(String url, boolean fallback) {
        boolean needNew = (mEngine == null) || (fallback != mEngineFallback);
        if (needNew) {
            releaseActiveEngine();
            mEngine = PlayerFactory.create(this, fallback ? !mPreferExo : mPreferExo);
            mEngine.setListener(this);
            mEngine.setVideoSizeListener(this);
            mEngineFallback = fallback;
        }
        if (!mSeamlessSwitch) {                 // 活动槽恒为 A,不留 alpha=0 残留
            mTopShowing = true;
            mTextureView.setAlpha(1f);
        }
        if (mSurfaceReady && mSurface != null) mEngine.setSurface(mSurface);
        mEngine.setVolume(0f, 0f);              // 起播静音,playing(首帧)后恢复
        mSeqMuted = true;
        mHandler.removeCallbacks(mSeqUnmute);
        mHandler.postDelayed(mSeqUnmute, 2500);
        try {
            mEngine.open(url, null);            // 直连上游(同网页端)
        } catch (Exception e) {
            android.util.Log.i("IPTV", "open failed: " + e);
            onEngineError(IPlayer.ERR_NETWORK,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        // 旁路探测不在起播瞬间打开:见 scheduleSideProbe
    }

    private void releaseActiveEngine() {
        if (mEngine != null) {
            mEngine.setListener(null);
            mEngine.setVideoSizeListener(null);
            mEngine.release();
            mEngine = null;
        }
    }

    /** 旁路探测延到首帧稳定后再等 5s:换台后新流正在建缓冲,探测会抢带宽。 */
    private void scheduleSideProbe(final String url) {
        if (url == null) return;
        final int gen = mTuneGen;
        mHandler.removeCallbacks(mProbeStart);
        mProbeStart = new Runnable() {
            @Override public void run() {
                if (mTuneGen == gen && !mPreloading && mCurrent != null)
                    ConfigServer.startSideProbe(mCurrent.getUrl());
            }
        };
        mHandler.postDelayed(mProbeStart, PROBE_DELAY_MS);
    }

    // ================= 双槽无缝切台(网页端 dual-slot 同构) =================
    // 网页端切台:新流在隐藏槽加载,旧流继续播放,新流首帧到达才整体替换 —— 无黑屏/
    // 冻结帧/缓冲提示。TV 端同构:预载引擎(静音)画到非显示槽,首帧回调里换槽。

    /** 当前非显示槽(预载目标)。 */
    private TextureView standbyView() { return mTopShowing ? mTextureViewB : mTextureView; }

    private Surface standbySurface() { return mTopShowing ? mSurfaceB : mSurface; }

    // 网页端没有"帧速率/攒垫"这类自创判据:handleVideoPlaying(playing 事件)
    // → completePendingSwitchIfNeeded → completeTransition。TV 端首帧回调即 playing。

    private void startPreload(Channel ch, int gen) {
        cancelPreload();                     // 预载中再次切台:作废旧预载,旧画面不受影响
        mPreloading = true;
        mNextStarted = false;
        mNextFrames = 0;                     // 仅日志度量
        mNextChannel = ch;
        mZapT0 = android.os.SystemClock.uptimeMillis();
        android.util.Log.i("IPTV", "zap: preload start " + ch.name);
        ConfigServer.invalidateMeta();       // 探测延迟到换槽后:预载期先作废旧元数据
        mNextEngine = PlayerFactory.create(this, mPreferExo);
        // 预载引擎关卡帧看门狗:预载期带宽被旧流分走,慢启动属正常,不得按 ERR_STALL
        // 误杀(会级联成顺序切换,冻结旧画面数秒)
        if (mNextEngine instanceof SystemPlayerImpl)
            ((SystemPlayerImpl) mNextEngine).disableWatchdog();
        mNextEngine.setListener(this);
        mNextEngine.setVideoSizeListener(this);
        Surface s = standbySurface();
        if (s != null) mNextEngine.setSurface(s);
        mNextEngine.setVolume(0f, 0f);       // 预载静音,换槽瞬间恢复音量
        try {
            mNextEngine.open(ch.getUrl(), null);          // 直连上游(双槽预载同直连)
        } catch (Exception e) {
            onPreloadError(e.getMessage());
            return;
        }
        // 注意:旁路探测连接在此刻不打开 —— 预载期已有旧流+预载流两条连接,
        // 第三条会挤占路由器上的 rtp2httpd(弱 CPU),造成换槽后数秒卡顿;
        // 探测改到 revealNext(旧引擎已释放)再开
        // 兜底:极端情况(无视频轨/第二个解码器不可用)永不渲染 → 看门狗收尾
        mHandler.removeCallbacks(mZapWatchdog);
        mHandler.postDelayed(mZapWatchdog, 15000);
    }

    /**
     * 对应网页端 handleVideoPlaying(slotId) → completePendingSwitchIfNeeded:
     * 隐藏槽首帧(playing)到达即提交换台,不做帧计数/攒垫等自创判据。
     */
    private void onStandbyFrame() {
        if (!mPreloading || mNextEngine == null) return;
        mNextFrames++;
        revealNext();                            // completeTransition
    }

    /** 对应网页端 handleVideoPlaying(activeSlotId):清 loading、恢复音量。 */
    private void onActiveFrame() {
        if (mLoading) setLoading(false);
        if (!mSeqMuted) return;
        mSeqMuted = false;
        mHandler.removeCallbacks(mSeqUnmute);
        if (mEngine != null) {
            mEngine.setVolume(mVol, mVol);
            android.util.Log.i("IPTV", "seq: playing, unmute");
        }
        startMetaSampler();
        scheduleSideProbe(mCurrent != null ? mCurrent.getUrl() : null);
    }

    private final Runnable mSeqUnmute = new Runnable() {
        @Override public void run() {           // 兜底:无视频轨/极端情况也要有声音
            if (mSeqMuted && mEngine != null) {
                mSeqMuted = false;
                mEngine.setVolume(mVol, mVol);
                android.util.Log.i("IPTV", "seq: unmute on timeout");
            }
        }
    };

    /** 网页端 pending 无硬超时;TV 端防"第二解码器起不来"永久挂住,15s 兜底。 */
    private final Runnable mZapWatchdog = new Runnable() {
        @Override public void run() {
            if (!mPreloading) return;
            onPreloadError("no playing event in 15s");
        }
    };

    /** 对应 completeTransition:先 setVisibleSlotId 揭幕,再 stop 旧槽。 */
    private void revealNext() {
        mHandler.removeCallbacks(mZapWatchdog);
        if (!mPreloading || mNextEngine == null) return;
        IPlayer next = mNextEngine;
        IPlayer old = mEngine;
        mNextEngine = null;
        mPreloading = false;
        mNextStarted = false;
        mSeqMuted = false;                  // 画面已在流动,直接开声
        mHandler.removeCallbacks(mSeqUnmute);
        android.util.Log.i("IPTV", "zap: playing -> reveal after "
                + (android.os.SystemClock.uptimeMillis() - mZapT0) + "ms, "
                + mNextFrames + " frames");
        if (mPaused) { mPaused = false; hidePauseOverlay(); }
        next.setVolume(mVol, mVol);         // setVolume(savedVolume)/setMuted(savedMuted)
        revealView(standbyView());          // Hard switch: reveal new stream first
        mEngine = next;
        if (old != null) { old.setListener(null); old.release(); }   // then tear down old slot
        setLoading(false);                  // setIsLoading(false)
        mRetry.reset(); mTriedOther = false; mRetryExhausted = false;
        startMetaSampler();
        scheduleSideProbe(mCurrent != null ? mCurrent.getUrl() : null);
        updateInfoBar();
    }

    /** 换槽显示:只切顶层槽的 alpha(不动视图树,两个 SurfaceTexture 全程存活)。 */
    private void revealView(TextureView shown) {
        if (shown == mTextureView) { mTextureView.setAlpha(1f); mTopShowing = true; }
        else { mTextureView.setAlpha(0f); mTopShowing = false; }
    }

    /** 对应 stopPendingTransition():停掉 pending 槽,不触碰活动槽(旧画面/声音不受影响)。 */
    private void cancelPreload() {
        mHandler.removeCallbacks(mZapWatchdog);
        IPlayer next = mNextEngine;
        mNextEngine = null;
        mPreloading = false;
        mNextStarted = false;
        mNextFrames = 0;
        mNextChannel = null;
        if (next != null) { next.setListener(null); next.release(); }
    }

    /** 对应 fallbackPendingSwitchToHardSwitch():pending.player.stop() →
     *  cancelPendingTransition() → handleLoadSegments(segments, true)。 */
    private void onPreloadError(String msg) {
        android.util.Log.i("IPTV", "zap: fallback to hard switch: " + msg);
        cancelPreload();
        scheduleTune(true);                 // forceHardSwitch=true,重入即走 hard switch
    }

    private void onEngineError(int code, String msg) {
        if (mCurrent == null) return;
        status("播放错误(" + (msg != null ? msg : "未知") + ")");
        if (!mTriedOther) {                       // 第一次失败:换另一引擎立即重试
            mTriedOther = true;
            openWithEngine(mCurrent.getUrl(), true);
            return;
        }
        if (mRetry.attempts() >= 4) {
            mRetryExhausted = true;               // 网络恢复监听将据此自动重连
            status("频道暂时不可用:" + mCurrent.name);
            return;
        }
        mRetry.schedule(new RetryPolicy.Sink() {
            @Override public void fire(long delayMs) {
                if (mCurrent != null) {
                    status("重试中(" + (delayMs / 1000) + "s)...");
                    mTriedOther = false;
                    openWithEngine(mCurrent.getUrl());
                }
            }
        });
    }

    // ================= 暂停/播放 =================

    private void togglePause() {
        if (mEngine == null || mCurrent == null) {
            status("尚未播放任何频道");
            return;
        }
        mPaused = !mPaused;
        if (mPaused) {
            if (mEngine.isPlaying()) mEngine.pause();
            showPauseOverlay();              // 只显示居中图标,不加文字(用户要求)
        } else {
            mEngine.start();
            hidePauseOverlay();
            flashPlayIcon();
        }
        startClock();
    }

    // ================= 引擎回调 =================

    @Override public void onPrepared(IPlayer p) {}
    @Override public void onStarted(IPlayer p) {
        if (p == mNextEngine) { mNextStarted = true; return; }   // 预载开播:等首帧再换槽
        mActiveLive = true;
        if (isConnectStatus()) status("");               // 只清连接类状态,保留提示类
        mRetry.reset(); mTriedOther = false;
        mRetryExhausted = false;
        startMetaSampler();
        updateInfoBar();
    }
    @Override public void onBufferingStart(IPlayer p) {
        // 对应网页端 handleVideoWaiting。但 4.4 NuPlayer 对裸 TS 会每 50~100ms
        // 反复报 waiting/playing(实机实测),不能据此上屏显示 loading —— 会把 500ms
        // 防抖不断重置成"永久提示"(V1.2 踩过的坑)。这里只记日志,loading 由
        // scheduleTune 置位、首帧 onActiveFrame 清除。
        if (p == mNextEngine) return;
        android.util.Log.i("IPTV", "waiting active t=" + android.os.SystemClock.uptimeMillis());
    }
    @Override public void onBufferingEnd(IPlayer p) {
        if (p == mNextEngine) return;
        android.util.Log.i("IPTV", "playing(candidate) t=" + android.os.SystemClock.uptimeMillis());
        if (isConnectStatus()) status("");
    }

    /** 可被"播放已恢复"安全清除的临时状态文本(连接中/重试中/播放错误)。 */
    private boolean isConnectStatus() {
        CharSequence s = mOsdStatus.getText();
        return s.length() == 0 || s.toString().startsWith("正在连接")
                || s.toString().startsWith("重试中")
                || s.toString().startsWith("播放错误")
                || s.toString().startsWith("缓冲中");   // loading 提示也属临时状态,首帧后必须清
    }
    @Override public void onCompleted(IPlayer p) {
        if (p == mNextEngine || mPreloading) return;     // 旧流自然结束但正在切台:忽略
        status("播放结束");
    }
    @Override public void onVideoSize(IPlayer p, int w, int h) {
        mVideoW = w; mVideoH = h;
        if (mInfoBar.getVisibility() == View.VISIBLE) updateInfoBar();
    }
    @Override public void onError(IPlayer p, int code, String msg) {
        android.util.Log.i("IPTV", "engine error: " + code + " " + msg);
        if (p == mNextEngine) { onPreloadError(msg); return; }
        if (mPreloading) return;                         // 旧流报错但画面仍在:等新槽接替
        onEngineError(code, msg);
        stopMetaSampler();
    }

    private void status(String s) {
        mOsdStatus.setText(s);
        mOsdStatus.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** 带代数管理的 OSD 显示:新显示取消旧隐藏任务,防止提前消失。 */
    private void osdShow(final TextView t, long ms) {
        mOsdGen++;
        final int gen = mOsdGen;
        Object old = t.getTag();
        if (old instanceof Runnable) t.removeCallbacks((Runnable) old);
        t.setVisibility(View.VISIBLE);
        Runnable r = new Runnable() {
            @Override public void run() { if (gen == mOsdGen) t.setVisibility(View.GONE); }
        };
        t.setTag(r);
        t.postDelayed(r, ms);
    }

    /** 列表刷新后把 mIndex 对齐到当前频道;当前频道不在新列表则回到 0(播放不打断)。 */
    private void rebindIndex() {
        if (mCurrent != null) {
            for (int i = 0; i < mChannels.size(); i++) {
                if (mChannels.get(i).getUrl().equals(mCurrent.getUrl())
                        && mChannels.get(i).name.equals(mCurrent.name)) { mIndex = i; return; }
            }
        }
        mIndex = 0;
    }

    // 画面显示严格对标网页端:video 元素 object-fill + 16:9 容器 —— 解码帧直接拉伸铺满屏幕
    // (中国 SD 频道为 720×576 变形像素,拉伸后比例正确;TV 硬解缩放等价于 object-fill)。
    // onVideoSize 仅记录分辨率供信息条 chip 显示。

    // ================= TVController.Host =================

    @Override public List<Channel> getChannels() { return mChannels; }
    @Override public int getCurrentIndex() { return mIndex; }

    @Override public void tuneIndex(final int i) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (i < 0 || i >= mChannels.size()) return;
                if (mConfigOpen) closeConfigOverlay(false);
                if (mSidebarOpen) toggleSidebar(false);
                if (mSourceOpen) toggleSourcePanel();
                mIndex = i;
                tune(mChannels.get(i));
            }
        });
    }

    @Override public boolean isPaused() { return mPaused; }

    @Override public void setPaused(final boolean p) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (p != mPaused) togglePause();   // 手机端与遥控器走同一渲染路径
            }
        });
    }

    @Override public float getVolume() { return mVol; }

    @Override public void setVolume(final float v) {
        mVol = v;
        App.prefPutInt("volume", Math.round(v * 100));   // 持久化:重启与切台后保持
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (mEngine != null) mEngine.setVolume(v, v);
            }
        });
    }

    @Override public void onConfigApplied() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                loadCfg();
                syncSeamlessSwitch();                  // /save 不写 dual_slot,读到的是电视浮层的值
                String lu = App.pref("list_url", "").trim();
                if (lu.length() > 0 && !lu.equals(mLastListUrl)) refreshChannelsInBackground(lu);
                Toast.makeText(PlayerActivity.this, "配置已生效", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================= 断网恢复自愈 =================

    /** 网络恢复 → 重试链已耗尽的频道自动重连(短断网由 RetryPolicy 退避覆盖)。 */
    private final android.content.BroadcastReceiver mNetReceiver =
            new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context ctx, Intent intent) {
                    if (!TvUtils.isNetworkUp(PlayerActivity.this)) return;   // 只处理"恢复"
                    if (mCurrent == null || !mRetryExhausted) return;
                    runOnUiThread(new Runnable() { @Override public void run() {
                        if (mCurrent != null && mRetryExhausted) {
                            status("网络已恢复,重新连接 " + mCurrent.name + " ...");
                            tune(mCurrent);
                        }
                    }});
                }
            };

    // ================= 按键(V1.3 固定映射) =================

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 配置浮层:交给原生焦点导航(view 层自己消化按键)
        if (mConfigOpen) return super.dispatchKeyEvent(event);
        // 直播态:全权接管按键,不经 super 的焦点系统
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                onBackPressed();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) return onKeyDown(kc, event);
        if (event.getAction() == KeyEvent.ACTION_UP) return onKeyUp(kc, event);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mConfigOpen) return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);

        // 数字直选:多位数 1.1s 内连按,1.1s 无输入提交
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            if (event.getRepeatCount() == 0) {
                int d = keyCode - KeyEvent.KEYCODE_0;
                long now = android.os.SystemClock.uptimeMillis();
                mNumberBuf = (mNumberBuf >= 0 && now - mNumberTs <= 1100) ? mNumberBuf * 10 + d : d;
                if (mNumberBuf > 999) mNumberBuf %= 1000;
                mNumberTs = now;
                mOsdNumber.setText(String.valueOf(mNumberBuf));
                osdShow(mOsdNumber, 2500);
                mHandler.removeCallbacks(mNumberCommit);
                mHandler.postDelayed(mNumberCommit, 1100);
            }
            return true;
        }

        boolean known =
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MENU;
        if (!known) return super.onKeyDown(keyCode, event);
        if (event.getRepeatCount() > 0) {
            // 长按重复:仅侧栏列表导航受益(连续移动),其余忽略
            if (mSidebarOpen && keyCode == KeyEvent.KEYCODE_DPAD_UP) { sidebarNav(-1); return true; }
            if (mSidebarOpen && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { sidebarNav(1); return true; }
            return true;
        }
        actOnKey(keyCode);
        return true;
    }

    /** 固定键位(方向上下/频道±=切台;左=列表;右=线路切换;菜单=设置;OK=暂停/播放)。 */
    private void actOnKey(int keyCode) {
        if (mSourceOpen) {                                  // 右侧浮层打开:UP/DOWN 选线路,OK 切换
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) sourceNav(-1);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) sourceNav(1);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (mSourceSel >= 0) selectSource(mSourceSel);
            }
            return;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { if (mSidebarOpen) sidebarNav(-1); else step(-1); }
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { if (mSidebarOpen) sidebarNav(1); else step(1); }
        else if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) step(-1);
        else if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) step(1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { if (mSidebarOpen) cycleGroup(-1); else toggleSidebar(true); }
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { if (mSidebarOpen) cycleGroup(1); else toggleSourcePanel(); }
        else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (mSidebarOpen) sidebarOk(); else togglePause();
        }
        else if (keyCode == KeyEvent.KEYCODE_MENU) openConfigOverlay();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mConfigOpen) return super.onKeyUp(keyCode, event);
        return true;   // 全部消费,不再交给焦点系统
    }

    @Override
    public void onBackPressed() {
        if (closeAnyOverlay()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastBackTs < 2000) { finish(); return; }
        mLastBackTs = now;
        Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
    }

    private boolean closeAnyOverlay() {
        if (mConfigOpen) { closeConfigOverlay(false); return true; }
        if (mSourceOpen) { toggleSourcePanel(); return true; }
        if (mSidebarOpen) { toggleSidebar(false); return true; }
        return false;
    }

    private void commitNumber() {
        int want = mNumberBuf;
        mNumberBuf = -1;
        if (want <= 0) return;
        for (int i = 0; i < mChannels.size(); i++) {
            if (mChannels.get(i).number == want) {
                mIndex = i;
                tune(mChannels.get(i));
                return;
            }
        }
        status("无 " + want + " 号频道");
    }

    private void step(int d) {
        int n = mChannels.size();
        if (n == 0) return;
        mIndex = ((mIndex + d) % n + n) % n;
        tune(mChannels.get(mIndex));
    }

    // ================= 侧边栏键盘导航 =================

    private int mSidebarSel = -1;                    // 侧栏手动选中位置(遥控器导航)

    private void sidebarNav(int d) {
        int count = mVisible.size();
        if (count == 0) return;
        int pos = mSidebarSel;
        if (pos < 0 || pos >= count) pos = currentVisiblePos();
        if (pos < 0) pos = 0;
        pos = Math.max(0, Math.min(count - 1, pos + d));
        mSidebarSel = pos;
        mSideAdapter.notifyDataSetChanged();
        scrollSelToCenter(pos);
    }

    /** 选中项滚动到列表中部(IPTV 标准交互,高亮跟随)。 */
    private void scrollSelToCenter(int pos) {
        int h = mSideList.getHeight();
        if (h <= 0) {
            mSideList.setSelection(pos);                     // 未布局:先粗定位
            return;
        }
        int itemH = h / 10;                                  // 可见约 10 行
        if (itemH <= 0) itemH = px(50);
        mSideList.setSelectionFromTop(pos, h / 2 - itemH / 2);
    }

    private void sidebarOk() {
        int pos = mSidebarSel;
        if (pos < 0 || pos >= mVisible.size()) pos = currentVisiblePos();
        if (pos < 0 || pos >= mVisible.size()) return;
        Channel ch = mVisible.get(pos);
        for (int i = 0; i < mChannels.size(); i++) {
            if (mChannels.get(i).getUrl().equals(ch.getUrl())) { mIndex = i; break; }
        }
        toggleSidebar(false);
        tune(ch);
    }

    private void cycleGroup(int d) {
        int n = mTabsRow.getChildCount();
        if (n == 0) return;
        mGroupIdx = ((mGroupIdx + d) % n + n) % n;
        mTabsRow.getChildAt(mGroupIdx).performClick();
    }

    @Override protected void onResume() {
        super.onResume();
        App.get().wifiLock(true);
        applyFullscreen();
    }

    @Override protected void onPause() {
        super.onPause();
        App.get().wifiLock(false);
    }

    // ================= 设置浮层 =================

    private void buildConfigOverlay() {
        mConfigOverlay = new FrameLayout(this);
        mConfigOverlay.setBackgroundColor(Color.argb(150, 0, 0, 0));
        mConfigOverlay.setClickable(true);
        mConfigOverlay.setVisibility(View.GONE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(Color.argb(242, 12, 18, 28));
        pbg.setCornerRadius(px(14));
        pbg.setStroke(2, Color.argb(255, 40, 60, 90));
        panel.setBackgroundDrawable(pbg);
        int pw = (int) (getResources().getDisplayMetrics().widthPixels * 0.62f);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(pw,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        mConfigOverlay.addView(panel, plp);

        int pad = px(20) + TvUtils.overScan() / 2;
        panel.setPadding(pad, pad, pad, pad);

        // M3U 顶部占满一行(placeholder 形式)。深色实底+描边:4.4 默认 EditText 背景是
        // 半透明暗块,hint 灰字与其重叠几乎不可见
        mCfgM3u = new EditText(this);
        mCfgM3u.setSingleLine(true);
        mCfgM3u.setTextColor(Color.WHITE);
        mCfgM3u.setHintTextColor(Color.rgb(148, 163, 184));
        mCfgM3u.setHint("M3U 频道列表地址");
        mCfgM3u.setTextSize(15);
        GradientDrawable ebg = new GradientDrawable();
        ebg.setColor(Color.argb(255, 17, 24, 39));
        ebg.setCornerRadius(px(8));
        ebg.setStroke(px(1), Color.argb(255, 71, 92, 124));
        mCfgM3u.setBackgroundDrawable(ebg);
        mCfgM3u.setPadding(px(12), px(10), px(12), px(10));
        mCfgM3u.setText(App.pref("list_url", ""));
        panel.addView(mCfgM3u, wide());

        // 两栏:左 = 二维码 + http 地址(上下);右 = 设置项
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = px(10);
        cols.setLayoutParams(clp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(llp);
        mCfgQr = new ImageView(this);
        mCfgQr.setBackgroundColor(Color.WHITE);
        String ip = TvUtils.getTvIp();
        if (ip != null) {
            mCfgQr.setImageBitmap(QrUtil.encode("http://" + ip + ":" + ConfigServer.PORT + "/", 300));
        }
        left.addView(mCfgQr, new LinearLayout.LayoutParams(px(230), px(230)));
        mQrTip = new TextView(this);
        mQrTip.setTextColor(Color.rgb(120, 200, 255));
        mQrTip.setTextSize(13);
        mQrTip.setPadding(0, px(6), 0, 0);
        mQrTip.setText(ip != null ? "http://" + ip + ":" + ConfigServer.PORT : "未获取到 IP");
        left.addView(mQrTip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cols.addView(left);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        rlp.leftMargin = px(16);
        right.setLayoutParams(rlp);

        mCfgAutoStart = cfgCheck("开机自启(启动后直接进直播)", App.prefBool("autostart", false));
        right.addView(mCfgAutoStart);
        // "优先 ExoPlayer"开关已移除:默认构建不打包 ExoPlayer 类库(见 build.sh
        // WITH_EXO),该开关此前为空操作。引擎选择保留在 PlayerFactory 代码层。
        mCfgShowInfo = cfgCheck("切台显示分辨率/编码/码率", App.prefBool("show_info", true));
        right.addView(mCfgShowInfo);
        mCfgClock = cfgCheck("时间常显(右上角)", App.prefBool("clock_always", true));
        right.addView(mCfgClock);
        mCfgDualSlot = cfgCheck("无缝换台(网页端同款;本机单路硬解默认关)",
                App.prefBool("seamless_switch", App.prefBool("dual_slot", false)));
        right.addView(mCfgDualSlot);

        TextView osdLabel = new TextView(this);
        osdLabel.setText("OSD 字号(OK 键切换)");
        osdLabel.setTextColor(Color.rgb(120, 200, 255));
        osdLabel.setTextSize(14);
        osdLabel.setPadding(0, px(8), 0, px(4));
        right.addView(osdLabel);
        mCfgOsd = new TextView(this);
        mCfgOsd.setText(osdName(App.pref("osd_size", "1.0")));
        mCfgOsd.setTextColor(Color.WHITE);
        mCfgOsd.setTextSize(16);
        mCfgOsd.setPadding(px(10), px(10), px(10), px(10));
        mCfgOsd.setBackgroundColor(Color.argb(80, 60, 80, 100));
        mCfgOsd.setFocusable(true);
        mCfgOsd.setClickable(true);
        mCfgOsd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String[] vals = {"0.8", "1.0", "1.3"};
                int i = 1;
                for (int k = 0; k < vals.length; k++)
                    if (vals[k].equals(App.pref("osd_size", "1.0"))) i = k;
                String next = vals[(i + 1) % vals.length];
                App.prefPut("osd_size", next);
                mCfgOsd.setText(osdName(next));
            }
        });
        right.addView(mCfgOsd, wide());

        // 呼起系统(原生)设置入口(遥控器可达,焦点高亮同其他按钮)
        TextView sysSettings = new TextView(this);
        sysSettings.setText("打开系统设置");
        sysSettings.setTextColor(Color.WHITE);
        sysSettings.setTextSize(16);
        sysSettings.setPadding(px(10), px(10), px(10), px(10));
        sysSettings.setBackgroundColor(Color.argb(80, 60, 80, 100));
        sysSettings.setFocusable(true);
        sysSettings.setClickable(true);
        sysSettings.setOnFocusChangeListener(focusGlow);
        sysSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchNativeSettings(); }
        });
        LinearLayout.LayoutParams sslp = wide();
        sslp.topMargin = px(8);
        right.addView(sysSettings, sslp);

        cols.addView(right);
        panel.addView(cols);

        // 底部按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = px(14);
        panel.addView(btnRow, brlp);

        TextView cancel = new TextView(this);
        cancel.setText("取 消");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(19);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, px(16), 0, px(16));
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setOnFocusChangeListener(focusGlow);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeConfigOverlay(false); }
        });
        btnRow.addView(cancel, rowHalf());
        TextView save = new TextView(this);
        save.setText("保 存");
        save.setTextColor(Color.rgb(120, 200, 255));
        save.setTextSize(19);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, px(16), 0, px(16));
        save.setFocusable(true);
        save.setClickable(true);
        save.setOnFocusChangeListener(focusGlow);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeConfigOverlay(true); }
        });
        LinearLayout.LayoutParams slp = rowHalf();
        slp.leftMargin = px(12);
        btnRow.addView(save, slp);

        // 版本号(与手机端 /config 同源 App.version(),部署后一眼可确认跑的是哪个包)
        TextView ver = new TextView(this);
        ver.setText("IPTV " + App.version());
        ver.setTextColor(Color.rgb(120, 140, 165));
        ver.setTextSize(13);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, px(10), 0, 0);
        panel.addView(ver, wide());

        mRoot.addView(mConfigOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private final View.OnFocusChangeListener focusGlow = new View.OnFocusChangeListener() {
        @Override public void onFocusChange(View v, boolean has) {
            v.setBackgroundColor(has ? Color.argb(200, 40, 90, 160) : Color.TRANSPARENT);
        }
    };

    /** 呼起系统(原生)设置;ACTION_SETTINGS 失败时显式指名兜底。 */
    private void launchNativeSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
        } catch (Throwable t) {
            try {
                startActivity(new Intent()
                        .setClassName("com.android.settings", "com.android.settings.Settings"));
            } catch (Throwable t2) {
                Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private LinearLayout.LayoutParams rowHalf() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView fieldLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.rgb(120, 200, 255));
        t.setTextSize(17);
        t.setPadding(0, px(20), 0, px(8));
        return t;
    }

    private CheckBox cfgCheck(String s, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(Color.WHITE);
        c.setTextSize(19);
        c.setChecked(v);
        c.setPadding(px(8), px(10), px(8), px(10));
        return c;
    }

    private LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void openConfigOverlay() {
        mCfgM3u.setText(App.pref("list_url", ""));
        mCfgAutoStart.setChecked(App.prefBool("autostart", false));
        mCfgShowInfo.setChecked(App.prefBool("show_info", true));
        mCfgClock.setChecked(App.prefBool("clock_always", true));
        mCfgDualSlot.setChecked(App.prefBool("seamless_switch", App.prefBool("dual_slot", false)));
        mCfgOsd.setText(osdName(App.pref("osd_size", "1.0")));
        String ip = TvUtils.getTvIp();
        if (ip != null) {
            String qrUrl = "http://" + ip + ":" + ConfigServer.PORT + "/";
            mCfgQr.setImageBitmap(QrUtil.encode(qrUrl, 360));
            mQrTip.setText(qrUrl);
        }
        mConfigOpen = true;
        mConfigOverlay.setVisibility(View.VISIBLE);
        mCfgM3u.requestFocus();
    }

    private void closeConfigOverlay(boolean save) {
        if (save) {
            App.prefPut("list_url", mCfgM3u.getText().toString().trim());
            App.prefPutBool("autostart", mCfgAutoStart.isChecked());
            App.prefPutBool("show_info", mCfgShowInfo.isChecked());
            App.prefPutBool("clock_always", mCfgClock.isChecked());
            App.prefPutBool("seamless_switch", mCfgDualSlot.isChecked());
            syncSeamlessSwitch();                      // 立即归一显示槽,无需重启
            mClockAlways = mCfgClock.isChecked();
            mShowInfo = mCfgShowInfo.isChecked();
            mOsdScale = parseFloat(App.pref("osd_size", "1.0"));
            applyOsdScale();
            tickClock();                                    // 时钟常显开关立即应用(否则最长延迟一个 30s tick)
            String lu = App.pref("list_url", "").trim();
            if (lu.length() > 0 && !lu.equals(mLastListUrl)) refreshChannelsInBackground(lu);
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        }
        mConfigOpen = false;
        mConfigOverlay.setVisibility(View.GONE);
        applyFullscreen();
    }

    /**
     * 自绘播放状态徽章:暂停(双竖条)与播放(三角)用同一几何规格绘制,
     * 保证两种状态图标视觉大小完全一致(不依赖系统字体的 Unicode 字形)。
     */
    private static class StateBadge extends View {
        private boolean mPlaying;              // false=暂停(双竖条) true=播放(三角)
        private final android.graphics.Paint mPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        StateBadge(android.content.Context c) { super(c); }

        void setState(boolean playing) {
            mPlaying = playing;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            if (r <= 0) return;
            mPaint.setStyle(android.graphics.Paint.Style.FILL);
            mPaint.setColor(android.graphics.Color.argb(190, 0, 0, 0));
            canvas.drawCircle(cx, cy, r, mPaint);
            float s = r * 0.5f;
            mPaint.setStyle(android.graphics.Paint.Style.FILL);
            mPaint.setColor(android.graphics.Color.WHITE);
            if (!mPlaying) {
                float bw = s * 0.42f;
                float gap = s * 0.34f;
                canvas.drawRect(cx - gap - bw, cy - s, cx - gap, cy + s, mPaint);
                canvas.drawRect(cx + gap, cy - s, cx + gap + bw, cy + s, mPaint);
            } else {
                // 播放三角:竖边在左顶点在右,质心比几何中心偏左 ~0.27s,右移 0.25s 视觉居中
                // (与暂停双竖条对称观感一致,否则切换时三角明显"偏左")
                float shift = s * 0.25f;
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(cx - s * 0.8f + shift, cy - s);
                p.lineTo(cx + s * 0.8f + shift, cy);
                p.lineTo(cx - s * 0.8f + shift, cy + s);
                p.close();
                canvas.drawPath(p, mPaint);
            }
        }
    }
}
