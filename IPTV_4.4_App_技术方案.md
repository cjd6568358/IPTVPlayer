# 乐视 Y43(Android 4.4.4)IPTV 播放器应用 · 完整技术方案 V1.0

> 目标设备:Letv Y43 · MStar mainz 板 · Android 4.4.4 (API 19) · Dalvik (libdvm) · armeabi-v7a 32位 · 4×Cortex-A7 1.3-1.7GHz · 1GB RAM · 8GB eMMC(Samsung 8GTF4R,寿命 10-20%,健康)· 1080p
> 后端:rtp2httpd @ http://192.168.1.1:15140(组播 RTP/UDP → HTTP 单播网关,含 Web 播放器与频道源)
> 文档版本:V1.0(2026-11-04)· 所有标注【已验证】的事实均来自同日实机测试,测试记录见附录 A

---

# 第0章 网页端对齐矩阵(当前实现基线 V1.3.7)

> 本章是**当前实现的唯一基线**;下方 V1.0 正文与各版变更记录保留为设计沿革。
> 网页端源码基准:`_scratch/rtp2httpd_src/web-ui/src/`(rtp2httpd 3.17.2 原始 TypeScript)。
> 状态:✅ 已对齐 / 🔁 平台等价(语义一致、实现不同) / ⛔ 系统硬约束(做不到,见 §0.3)。

## 0.1 切台状态机 —— 全部 ✅

| 能力 | 网页端源码 | TV 端实现 | 状态 |
|---|---|---|---|
| 换台/换源/重试唯一入口 | `video-player.tsx:1059 handleLoadSegments(newSegments, forceHardSwitch)` | `PlayerActivity.scheduleTune(boolean)` | ✅ |
| 代数守卫(作废旧 pending) | `transitionGenRef` `:302` | `mTuneGen` | ✅ |
| pending 过渡对象 | `pendingTransitionRef` `:303` | `mNextEngine / mPreloading` | ✅ |
| 双槽 A/B | `slotAVideoRef/slotBVideoRef`、`visibleSlotId` `:289-297` | `mTextureView / mTextureViewB`、`mTopShowing` | ✅ |
| **能力门控** | `getSeamlessSwitch()` 默认 `isDesktopDevice()`(`player-storage.ts:48`);`supportsSeamlessSwitch = !isLGWebOS()`(`player.tsx:94`) | `seamless_switch` 默认 false;依据实机 `MSTAR_VDEC_DBG Stream ID` 恒为 0(单路硬解) | ✅ |
| 非无缝 = 复用活动槽重载 | `loadActiveSlotSegments(activePlayer, activeId, ...)` `:1098-1102` | `openWithEngine()` 复用同一 `IPlayer` 实例直接 `open()` | ✅ |
| 无缝 = 隐藏槽预载 + `setMuted(true)` | `:1104-1135` | `startPreload()` + `setVolume(0,0)` | ✅ |
| 提交判据 | `handleVideoPlaying → completePendingSwitchIfNeeded` `:1201-1205` | `onStandbyFrame()` 首帧即 `revealNext()` | ✅ |
| 揭幕顺序 | `completeTransition`:先 `setVisibleSlotId`,再 `stop` 旧槽 `:603-626` | `revealNext()`:先 `revealView()`,再 `old.release()` | ✅ |
| 失败回退 | `fallbackPendingSwitchToHardSwitch` `:661-675` | `onPreloadError()` → `scheduleTune(true)` | ✅ |
| loading 500ms 防抖 | `isLoading` + `showLoading` `:368-384` | `setLoading()` + `mShowLoading` | ✅ |
| waiting/playing → loading | `handleVideoWaiting/Playing` `:1192-1230` | 仅换台置位、首帧清除(NuPlayer 事件噪声,见 §0.3) | 🔁 |
| 背景槽 | `opacity-0 pointer-events-none` 保持帧回调 `:1775-1780` | `setAlpha(0f)`;单槽模式下 B 直接 GONE | ✅ |
| 音量/静音继承 | `setVolume(savedVolume)` + `setMuted(savedMuted)` `:610-615` | `next.setVolume(mVol, mVol)` | ✅ |
| 设置项命名 | i18n `seamlessSwitch: "无缝换台"` | 设置浮层「无缝换台」 | ✅ |

## 0.2 媒体信息 badges —— 全部 ✅(V1.3.7)

统一到 `PlayerActivity` 里**一组** `format*` 函数,底部信息条与右侧浮层共用,消除两份漂移实现。

| 能力 | 网页端 `player-media-badges.tsx` | TV 端 | 状态 |
|---|---|---|---|
| 分辨率 | `formatResolution` `:54`:无高度→null,4K 特判,`height + i/p` | `formatResolution()`;高度用 `onVideoSizeChanged` 兜底 | 🔁 |
| 帧率 | `formatFrameRate(fps, renderState.deinterlacing)` `:66`:**仅真开启去隔行才 ×2** | `formatFrameRate(fps, false)` → 25.0 FPS | ✅ 修掉旧版无条件 ×2(50.0) |
| 码率 | `formatBitrate` `:81`:≥1Mbps 两位小数 Mbps,否则 Kbps | `formatBitrate()` 同规则 | ✅ 修掉旧版一位小数硬编码 Mbps |
| 视频/音频编码 | `identifyVideoCodec` / `identifyAudioCodec` | `formatVideoCodec` / `formatAudioCodec` | ✅ |
| 声道 | Mono/Stereo/5.1/7.1 | `formatChannels()` | ✅ |
| 空值处理 | `visibleBadges = badges.filter(badge !== null)` `:145`,无数据不渲染 | `setChip()` → `View.GONE` | ✅ 修掉旧版「帧率 —」等占位 |
| chip 顺序 | res, fps, video, audio, channels, dynamicRange, bitrate | res, fps, video, audio, channels, bitrate | 🔁 本部署无 HDR 源 |

## 0.3 ⛔ 系统硬约束 —— 这几项在 4.4 上物理做不到

| 能力 | 网页端成立的前提 | 电视端现实 | 已采取的等价方案 |
|---|---|---|---|
| **元数据来自播放流** | mpegts.js demux 同一条连接,`worker/pipeline.ts:392` 组装 `PlayerMediaInfo` | MediaPlayer 只暴露 `onVideoSizeChanged`;SPS/帧率/音频编码拿不到 | 旁路短连接 `ConfigServer.startSideProbe` + Java 移植 `TsProbe`;首帧后 800ms 开、SPS 到齐(实测 ~655KB)即断 |
| **自管缓冲水位** | MSE `SourceBuffer` + `mse/playback-controller.ts`、`live-sync.ts`;`config.ts` target 1.5s / max 3s | NuPlayer 缓冲黑盒,API 19 无参数可调,裸 TS 边收边播,`waiting/playing` 每 50~100ms 噪声 | `WITH_EXO=1` 的 ExoPlayer 备用包(`DefaultLoadControl` 1.5s/3s/600ms),已编译验证;需用户确认才切换 |
| **WebGL bwdif 去隔行 / 画质增强** | `render/renderer.ts`、`denoise.ts` + `renderCanvas`(`config.ts:31`) | 无 GL 渲染管线、GPU 带宽弱 | 硬解直接出帧;因此帧率**不** ×2(§0.2) |
| **WASM 软解 MP2** | `wasmDecoders`(`config.ts:14`) | 4.4 无 WASM | 系统 OMX 音频解码 |
| **多路硬解 / 桌面级双槽** | PC/手机多路 VDEC、充裕 RAM/GPU | MStar 单路 1080p VDEC(`Stream ID` 恒 0) | 默认关 seamless —— **这正是网页端 `isDesktopDevice()` 门控的本意** |
| **现代前端运行时** | ESM / MSE / Promise / Worker | WebView Chromium 30,无 MSE/ESM(实测白屏) | 原生 Java 7 + 手搓 `javac→d8→aapt` 管线 |

**关于「元数据来自播放流」为何不直接照搬**:TV 端原本就有同构实现 `/local/<enc>` 回环泵
(`ConfigServer.localStream`,转发时顺路 `TsProbe.feed`,并写 `fps`/码率/编码,`:469-533`),
V1.3 因「解析跑在拷贝线程上造成 MediaPlayer 输入断流 → 马赛克/卡顿」改为直连 + 旁路探测。
在单核弱 CPU + WiFi 链路上恢复泵,会重新引入用户最初反馈的卡顿,故不回退;改为把旁路探测
的时序压到与播放流接近(800ms 启动、40ms 让步、SPS 到齐即断)。**这是有据可查的取舍,不是没抄。**

**结论**:能对齐的(状态机、能力门控、badges 格式与空值、设置语义)已 100% 对齐;
剩余差异全部来自 4.4 + 单 VDEC 的硬约束,已给出等价实现或明确替代。要真正 100% 对齐,
只能换掉播放运行时(外部播放器/换系统),软件层无解。

---

## 目录

1. [设备与约束基线](#第1章-设备与约束基线)
2. [总体架构与技术决策](#第2章-总体架构与技术决策)
3. [播放内核深度设计](#第3章-播放内核深度设计)
4. [流媒体协议与 rtp2httpd 对接](#第4章-流媒体协议与-rtp2httpd-对接)
5. [TV UI/UX 规范](#第5章-tv-uiux-规范)
6. [模块设计与完整代码](#第6章-模块设计与完整代码)
7. [构建与部署管线](#第7章-构建与部署管线)
8. [测试计划](#第8章-测试计划)
9. [风险登记表](#第9章-风险登记表)
10. [里程碑与验收标准](#第10章-里程碑与验收标准)
11. [运维手册](#第11章-运维手册)
12. [后续演进路线](#第12章-后续演进路线)
- [附录 A:设备实测档案](#附录-a设备实测档案2026-11-04)
- [附录 B:故障排查百科](#附录-b故障排查百科)
- [附录 C:今天验证过的全部命令](#附录-c今天验证过的全部命令)

---

# 第1章 设备与约束基线

## 1.1 硬件

| 项 | 规格 | 对应用的影响 |
|---|---|---|
| SoC | MStar(板代号 mainz,6A628 级) | 视频:MStar OMX 硬解;**H.264 MP/HP@L4.0 1080p ✅、MPEG-2 ✅;H.265/4K ❌**。频道源必须是 H.264 编码 |
| CPU | 4× Cortex-A7 1.3-1.7GHz,实测日常占用 2~3% | 纯 Java 层逻辑充裕;软件解码 1080p 不可行,必须硬解 |
| 内存 | 1GB(开机空闲 ~200MB,后台 40+ 乐视常驻) | 应用总 RSS 控制在 60MB 内;ExoPlayer 方案额外 ~30MB;禁用图片大缓存 |
| 存储 | 8GB eMMC,/data 余 1.8G,eMMC 寿命 10-20%(健康) | APK + 数据 < 30MB,随便装 |
| 屏幕 | 1920×1080,screencap 坐标系 1:1 | UI 按 1080p 设计;**overscan 安全区四边 5%(48px)** |
| 遥控器 | 红外 D-pad(数字键、菜单键、OK、方向)| 全 UI 焦点导航;数字键做频道号直选 |
| 网络 | eth0(有线,192.168.1.11)+ wlan0(WiFi,192.168.1.21)同时在线 | **IPTV 强烈建议插网线**;WiFi 高码率会抖;两者同网段注意路由优先级 |

## 1.2 系统软件

| 项 | 事实 | 对方案的影响 |
|---|---|---|
| 系统 | Android 4.4.4(KITKAT),API 19,user 版 | 无运行时权限,权限全部清单静态声明;`adb install` 可用 |
| 运行时 | **Dalvik (libdvm)**,非 ART 【已验证 `persist.sys.dalvik.vm.lib`】 | Java 7 语法全支持;Java 8 经 d8 脱糖可用;**Kotlin 不建议** |
| SELinux | **不存在**(内核未编译)【已验证 `/sys/fs/selinux` 缺失】 | 无任何 MAC 限制,root 下一切可行 |
| root | Dirty Cow 一次性 root,`getroot.sh` 一键【已验证】 | 调试期可用 root 排障;正式应用不需要 root |
| /system | 只读,**任何写入 = 内核 panic(已实测)**【已验证】 | 绝不改系统;浏览器升级、WebView 替换不可行 |
| WebView | Chromium 30 | 无 ES Modules、无 fetch、无 MSE → 现代 SPA **渲染空白(已实测)** |
| 前台抢占 | 乐视桌面(deskplatform)会抢焦点【已实测】 | 播放器 onPause 不释放、onResume 续播 |
| 休眠 | 闲置 WiFi 悬挂 → adb 掉线【已实测】 | 播放中 KEEP_SCREEN_ON + WifiLock;调试先点亮屏幕 |
| 工厂模式 | env `factory_mode=1` 开机自动弹工厂菜单【现状】 | 本 app 开机自启置顶后自然覆盖;彻底关闭为独立课题 |

## 1.3 已验证的构建与部署能力

| 能力 | 状态 | 证据 |
|---|---|---|
| javac(JBR 17)→ d8(build-tools 36)→ aapt v1 → apksigner → adb install | ✅ 全链路 | EnvTool.apk 安装成功且运行正常 |
| 安装期 dexopt(installd)对 d8 产物 | ✅ | 同上 |
| `dalvikvm -cp` 直接加载 d8 产物 | ❌ | "unable to open DEX file",须走安装路径 |
| 45MB APK 安装(WiFi adb)| ✅ ~2-3 分钟 | Firefox 68 ESR 安装成功 |
| 第三方应用调用 TvManager 等 MStar binder API | ✅ 不校验调用者 | EnvTool 实测写入环境变量 |

---

# 第2章 总体架构与技术决策

## 2.1 决策总览

| 决策点 | 选择 | 否决项与原因 |
|---|---|---|
| 应用形态 | **原生 Android 应用**(Java 7/8) | Web/SPA:4.4 引擎跑不了现代前端(实测);Kotlin:脱糖产物在 dalvik 未验证,收益为零 |
| 播放内核 | **双引擎:MediaPlayer(首选)+ ExoPlayer 2.13(兜底),运行时切换** | 单一引擎都会被特定流格式卡死;双引擎 + 自动降级是 IPTV 稳定性的保险 |
| 视频输出 | TextureView(V1.2 实现为准;方案初版写 SurfaceView) | OSD/暂停遮罩需以普通视图覆盖视频,TextureView 走普通视图合成可实现;SurfaceView 硬件 overlay 会"打洞"穿透,无法被 UI 覆盖。代价是老 SoC 上多一层拷贝,实测 1080p 流畅 |
| UI | 自绘 D-pad 焦点导航 | 不引入 Leanback/support 库,4.4 原生 API 自足 |
| 频道源 | m3u / m3u8 主列表 / rtp2httpd 频道接口,本地 JSON 缓存 | 断网也能看列表;支持用户手工编辑 |
| 自启 | BOOT_COMPLETED → 延时 8s 自启播放页 | 顺带覆盖工厂菜单;可选设置项 |
| 构建 | Gradle(Android Studio,推荐)/ 手搓管线(快速验证) | 两条管线均已在今天验证可行 |

## 2.2 为什么 WebView 方案被否决(记录在案)

1. 页面 `http://192.168.1.1:15140/player` 是 Vite 构建的现代 SPA(ES Modules + 现代 JS)。
2. 4.4 系统 WebView = Chromium 30:不识别 `type="module"`、不支持 ES6 语法 → SPA 必然白屏(实测)。
3. 4.4 任何 WebView/浏览器**没有 MSE**(Media Source Extensions)→ hls.js 这类主流网页播放内核完全不可用。
4. Firefox 68 ESR(Gecko 68,2020 引擎)已装到电视实测:自身可运行,但你的 SPA 依旧白屏(实测)——即使浏览器达标,页面还有更多 API 代差。
5. 结论:**4.4 上"网页版 IPTV"这条路在工程上不成立,原生应用是唯一正解**。若未来一定要 Web UI,前提是:Vite legacy 构建(ES5)+ 弃用 hls.js 改用 `<video src=*.m3u8>` 原生 HLS(4.4 WebView 的 `<video>` 会代理到平台 MediaPlayer,HLS 可播)——作为远期备选,不进本期方案。

## 2.3 播放链路总览

```
遥控器 → PlayerActivity → IPlayer(引擎抽象)
                             │
        ┌────────────────────┴────────────────────┐
        ▼                                         ▼
 SystemPlayerImpl                          ExoPlayerImpl
 (MediaPlayer + NuPlayer)                  (ExoPlayer 2.13 + MediaCodec)
        │                                         │
        └────────────► MStar OMX 硬解 ◄───────────┘
                             │
                    SurfaceView(硬件 overlay)
                             │
                rtp2httpd(192.168.1.1:15140)
                             │
                  组播 RTP/UDP(IPTV 骨干网)
```

---

# 第3章 播放内核深度设计

## 3.1 引擎抽象接口(IPlayer)

```java
public interface IPlayer {
    void setSurface(Surface surface);            // 任意时刻可调用(含 null)
    void open(String url, Map<String,String> headers) throws Exception; // 异步准备
    void start();
    void pause();
    void resume();
    void stop();                                  // 停止并回到可 open 状态
    void release();                               // 彻底销毁
    int getCurrentPosition();                     // ms;失败返回 -1
    int getDuration();                            // 直播返回 -1
    boolean isPlaying();
    void setVolume(float left, float right);
    interface Listener {
        void onPrepared(IPlayer p);
        void onStarted(IPlayer p);
        void onBufferingStart(IPlayer p);
        void onBufferingEnd(IPlayer p);
        void onCompleted(IPlayer p);              // 点播播完
        void onError(IPlayer p, int what, String msg);  // 一切错误统一出口
    }
    void setListener(Listener l);
}
```

设计要点:
- 所有方法**禁止阻塞 UI 线程**:open/prepare 内部必须异步。
- 错误一律归一到 `onError`,由上层 RetryPolicy 决定重试或换引擎,引擎自身不做重试(职责分离)。
- `open()` 前必须保证旧实例已 stop/release(PlayerFactory 负责)。

## 3.2 SystemPlayerImpl(MediaPlayer)——状态机与 4.4 特有的坑

### 3.2.1 状态机

```
IDLE ──setDataSource──► INITIALIZED ──prepareAsync──► PREPARING ──onPrepared──► PREPARED
   ▲                                                                      │ start
   │                                                                      ▼
   └──release◄──────────── STOPPED ◄──stop──── STARTED ◄──start──── PREPARED
                                │                  │ pause▲ pause          │
                                ▼                  ▼      │               ▼
                             (同上)             PAUSED ◄───┘        PLAYBACKCOMPLETED
```

### 3.2.2 必须写进实现的 4.4 特性处理

| # | 坑 | 现象 | 对策(已写入代码) |
|---|---|---|---|
| 1 | **直播静默卡帧** | 不断流但 `getCurrentPosition()` 停住 | 看门狗:每 5s 取 position,连续 3 次不动 → 先 `seekTo(pos)` 自愈,再不行 → rebuild(触发 onError 由上层重试)|
| 2 | HLS 协议版本 | 只支持 v3:TS 分片/H.264/AAC/AES-128;fMP4、WEBVTT ❌ | 源端保证;rtp2httpd 侧确认 |
| 3 | Content-Type 嗅探 | m3u8 返回类型不对时可能失败 | 服务端返回 `application/vnd.apple.mpegurl`;应用端 ExoPlayer 兜底 |
| 4 | prepare 期间 setter 崩溃 | IllegalStateException | 显式状态机,setter 只在安全态调用 |
| 5 | 断网不自愈 | WiFi 抖动后永久黑屏 | ConnectivityManager 监听 → 主动 rebuild |
| 6 | `setSurface` 时序 | surface 销毁后仍持有 → 崩溃 | surfaceDestroyed 回调里先 `setSurface(null)` |
| 7 | `createMediaPlayer` 后的音轨 | 默认流可能不对 | `setAudioStreamType(STREAM_MUSIC)` 必须在 prepare 前 |
| 8 | 剧终回调 | 直播不会收到 | 只对点播有意义;直播靠 watchdog |

### 3.2.3 看门狗设计

```
每 5s:pos = getCurrentPosition()
  pos 前进 → 记录,stall=0
  pos 不动 → stall++
      stall == 2 → seekTo(pos)(多数直播卡帧这一下就活)
      stall == 4 → 向上层 onError(STALL)→ 重建播放器
暂停态/用户 seek 中 → 跳过检查
```

## 3.3 ExoPlayerImpl(ExoPlayer 2.13.x)

### 3.3.1 版本选择依据

| 版本线 | minSdk | 说明 |
|---|---|---|
| 2.9 ~ 2.13 | 16 | ✅ 推荐区间(2.13.3 为 2.x 末期稳定版,bug 最少)|
| 2.14 ~ 2.18 | 16 | 可用但引入更多脱糖 |
| 2.19+ / Media3 | 21 | ❌ API 19 装不上 |

依赖:`com.google.android.exoplayer2:exoplayer:2.13.3`(含 HLS/TS 全部支持,无需额外 artifact)。

### 3.3.2 关键配置(代码见 §6)

- `DefaultLoadControl`:minBuffer 15s / maxBuffer 50s / playback 1.5s / rebuffer 2s——直播低延迟与内存的平衡点(1GB 机器实测安全)
- `DefaultHttpDataSource`:connectTimeout 8s、readTimeout 8s、`setAllowCrossProtocolRedirects(true)`(rtp2httpd 可能 302)、UA 标识 `"LetvY43-IPTV/1.0"`
- HLS:`HlsMediaSource.Factory(dsFactory)`;TS:`ProgressiveMediaSource.Factory(dsFactory, new DefaultExtractorsFactory())`(自动嗅探 MPEG-TS)
- URL 判定:`.m3u8` 结尾 → HLS;否则 → Progressive(TS 嗅探)

### 3.3.3 ExoPlayer 相比 MediaPlayer 的增益

| 能力 | MediaPlayer | ExoPlayer 2.13 |
|---|---|---|
| HLS 容错/路由切换 | 弱 | 强(ABR、多码率、失败重载)|
| 自定义请求头 | setDataSource(Map) 有限 | 完整 |
| 断流重连 | 手写 | 内建 `DEFAULT_RETRY_COUNT` + LoadControl |
| TS 嗅探 | 偶发失败 | 稳定 |
| 内存 | ~15MB | ~30-45MB |
| 事件粒度 | 5 个回调 | 全量状态/日志 |

结论:**默认 MediaPlayer(省内存、链路最短),任何 onError / 卡帧 → 自动切 ExoPlayer 重试同 URL**(PlayerFactory 实现自动降级,见 §6)。

## 3.4 硬解确认方法

```bash
# 播放中执行,确认 OMX.MSTAR.VIDEO.DECODER 参与渲染
adb shell dumpsys media.player 2>/dev/null | grep -iE "omx|codec"
adb shell logcat -d | grep -iE "OMX.MSTAR|MediaCodec" | tail
```
若出现 `software-avc`/`OMX.google.h264.decoder` 字样说明走了软解(不应发生),检查流分辨率是否超 1080p。

---

# 第4章 流媒体协议与 rtp2httpd 对接

## 4.1 rtp2httpd 是什么、给 app 什么

rtp2httpd 把 IPTV 骨干网的**组播 UDP/RTP 流**转换为 **HTTP 单播流**。对应用而言:

```
IPTV 骨干网 ──组播 239.x.x.x:5000──► rtp2httpd(192.168.1.1:15140) ──HTTP──► 电视 app
```

| 资源 | URL 形态 | 内容 |
|---|---|---|
| Web 播放器 | `/player` | Vite SPA(已证实 4.4 不可用)|
| 单播 TS 频道 | `/rtp/239.x.x.x:port` | 连续 MPEG-TS 流 |
| HLS(若启用)| `/hls/xxx.m3u8` 及分片 | m3u8 + TS 分片 |
| 频道列表 | `/api/channels`(若有)或 m3u | JSON/m3u |

**app 的核心工作就是把"频道"变成一个 URL 交给播放内核**,其余全是 UI 与稳定性工程。

## 4.2 MPEG-TS 直播流的 4.4 播放要点

1. NuPlayer 对 http:// 的连续 TS 流做** sniffing(嗅探)**:前几 KB 必须是 0x47 同步字节——rtp2httpd 输出的裸 TS 满足。
2. 若嗅探失败(个别固件),ExoPlayer 的 `TsExtractor` 显式指定即可,无此问题。
3. TS 流内 PCM/ADTS 音频、H.264 视频直接映射 OMX ✓。
4. **注意组播地址引用**:rtp2httpd 的 `/rtp/` URL 中端口是 UDP 端口,HTTP 端口 15140 不要混淆。

## 4.3 HLS(m3u8)在 4.4 的能力边界

| 特性 | 4.4 NuPlayer | ExoPlayer 2.13 |
|---|---|---|
| MASTER 播放列表(多码率)| 单码率降级播放 | ✅ ABR 自动 |
| MEDIA 播放列表(TS 分片)| ✅ | ✅ |
| 直播滑动窗口 | ✅(边缘易停)| ✅ |
| AES-128 加密 | ✅(4.3+)| ✅ |
| fMP4 / CMAF | ❌ | ✅(2.13)|
| WEBVTT 字幕 | ❌ | ❌(2.13 Android)|

## 4.4 频道列表格式约定(应用支持三种)

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV-1 综合
http://192.168.1.1:15140/rtp/239.3.1.1:5000
#EXTINF:-1 group-title="卫视",东方卫视
http://192.168.1.1:15140/rtp/239.3.1.2:5000
```
- 解析器兼容:`#EXTINF` 属性行、无属性简写、纯 URL 行、`#EXTGRP:` 行
- 服务器端建议(你控制 rtp2httpd 部署):提供一个固定 `/channels.m3u`,app 启动拉取 + 本地缓存

---

# 第5章 TV UI/UX 规范

## 5.1 遥控器键位映射(V1.2 实现为准)

硬编码键(学习不可覆盖;侧栏 = 播放页左侧滑出的频道列表面板):

| 遥控键 | KeyCode | 直播态 | 侧栏打开时 |
|---|---|---|---|
| OK | 23/66 | 播放/暂停切换 | 确认选台 |
| 上/下 | 19/20 | 上一/下一频道 | 列表移动(长按连续) |
| 左/右 | 21/22 | 打开频道侧栏 | 上/下一个分组 |
| 频道+/- | 166/167 | 上一/下一频道 | - |
| 数字 0-9 | 7-16 | 频道号直选(1.1s 输入窗,>3 位取模) | - |
| 菜单(短按) | 82 | 节目源菜单(多源频道) | - |
| 菜单(长按≥700ms) | 82 | 设置浮层 | - |
| 返回 | 4 | 依次收起浮层;2s 内双击=退出确认 | 同左 |

可学习槽位(设置浮层内捕获,存 key_sidebar/key_source/key_info/key_config,默认 21/82/-1/-2;配置页 keys_reset 恢复;学习键为附加绑定):

| 槽位 | 生效动作 |
|---|---|
| 频道列表键 | 开/收侧栏 |
| 节目源键 | 节目源菜单 |
| 信息面板键 | 码率/分辨率面板 |
| 设置键 | 设置浮层 |

> 音量:遥控无音量键(左右键已用于侧栏);音量经手机端网页调节,持久化并在换台/重启后保持。

## 5.2 Overscan 与布局

- 1080p 下安全区:上下左右各 48px;所有 OSD/文字距边 ≥48px
- 全部尺寸用代码按 `getRealDisplaySize` 比例计算,不写死 px
- 字号:频道号 OSD ≥72px,列表项 ≥36px,提示 ≥28px(3 米观看距离)

## 5.3 状态可视规范

| 状态 | 屏幕表现 |
|---|---|
| 换台中 | 右上角大频道号 + 频道名,1.5s 后淡出 |
| 缓冲 | 中心圆形 spinner + "缓冲中..." |
| 断流重试 | 底部红条"信号中断,第 N 次重试..." |
| 频道不可用 | 底部红条持续 + 保留最后帧 |
| 引擎切换 | 左下角小字"已切换至备用播放内核" |

## 5.4 焦点引擎(4.4 无 Leanback)

- 列表用 ListView + 自定义 `ListAdapter`,`setOnFocusChangeListener` 绘制高亮边框(缩放 1.05f + 描边)
- 焦点记忆:每次进列表恢复上次选中频道
- 防抖:连续方向键 300ms 内合并刷新

---

# 第6章 模块设计与完整代码

> 完整可编译源码已落盘:`tvroot/iptv/` 目录,清单如下(均按 4.4/dalvik 兼容标准编写,Java 7 语法 + 匿名内部类,刻意避开 lambda):

| 文件 | 职责 |
|---|---|
| `AndroidManifest.xml` | 权限/Activity/BootReceiver |
| `src/com/your/iptv/App.java` | Application:全局 WifiLock、配置 |
| `src/com/your/iptv/IPlayer.java` | 播放引擎抽象接口 |
| `src/com/your/iptv/player/SystemPlayerImpl.java` | MediaPlayer 实现(看门狗/状态机/自愈)|
| `src/com/your/iptv/player/ExoPlayerImpl.java` | ExoPlayer 2.13 实现(需加依赖)|
| `src/com/your/iptv/player/PlayerFactory.java` | 引擎选择 + 自动降级 |
| `src/com/your/iptv/source/Channel.java` | 频道数据模型 |
| `src/com/your/iptv/source/M3uParser.java` | m3u/m3u8 列表解析 |
| `src/com/your/iptv/source/ChannelRepository.java` | 频道列表下载/缓存 |
| `src/com/your/iptv/PlayerActivity.java` | 播放页(SurfaceView/OSD/换台/看门狗 UI)|
| `src/com/your/iptv/ChannelListActivity.java` | 频道列表页(D-pad)|
| `src/com/your/iptv/boot/BootReceiver.java` | 开机自启 |
| `src/com/your/iptv/util/RetryPolicy.java` | 指数退避重试 |
| `src/com/your/iptv/util/TvUtils.java` | overscan/WifiLock/网络检测 |
| `build.sh` / `build.bat` | 一键构建安装脚本(手搓管线)|

关键片段摘录(完整代码见文件):

**PlayerFactory 自动降级**:
```java
public static IPlayer create(Context ctx, int prefer) {
    // prefer: 0=系统优先, 1=Exo 优先; onError 后由 PlayerActivity 调 createOther()
    return prefer == 0 ? new SystemPlayerImpl(ctx) : new ExoPlayerImpl(ctx);
}
```

**换台核心流程(PlayerActivity)**:
```java
void tune(Channel ch) {
    showChannelOsd(ch);                 // 频道号+名称 OSD
    engine.release();                   // 旧引擎彻底销毁
    engine = PlayerFactory.create(this, engineIndex);
    engine.setListener(listener);       // 错误→降级引擎→重试链
    engine.setSurface(surface);
    engine.open(ch.url, null);
}
```

---

# 第7章 构建与部署管线

## 7.1 快速验证管线(已验证 ✅,零依赖手搓)

见 `iptv/build.sh`(Git Bash / `build.bat`(cmd)。流程:

```
aapt package(资源/清单)→ javac(-source 8 -target 8)→ d8(--min-api 19)
→ zip 注入 classes.dex → apksigner(v1,debug.keystore)→ adb install -r
```

## 7.2 Android Studio 正式管线(推荐 M2 起切换)

- AGP 4.x;`minSdkVersion 19`;`compileOptions sourceCompatibility 1.8`
- ExoPlayer:`implementation 'com.google.android.exoplayer2:exoplayer:2.13.3'`
- 签名:复用 `tvroot/envtool/debug.keystore` 或新建
- **已验证的关键点**:AGP/d8 产出的 APK 经 `pm install` 后运行正常(EnvTool 实证);开发调试用 `adb install -r` + `logcat -s IPTV` 即可

## 7.3 调试技巧(本机实测有效的操作)

| 场景 | 命令 |
|---|---|
| 看播放日志 | `adb logcat -s IPTV:V` |
| 画面验证 | `adb exec-out screencap -p > screen.png`(USB)或 screencap+pull |
| 遥控注入 | `adb shell input keyevent 19/20/66/...` |
| 内存 | `adb shell dumpsys meminfo com.your.iptv` |
| 硬解确认 | `adb shell logcat -d | grep -i OMX.MSTAR` |
| 电视休眠断连 | 用遥控器点亮屏幕再继续;开发期 `svc power stayon true` |

---

# 第8章 测试计划

## 8.1 M1 spike 验收(技术可行性)

| # | 用例 | 步骤 | 通过标准 |
|---|---|---|---|
| S1 | MediaPlayer + m3u8 | 设置里填一个 rtp2httpd m3u8 频道 | 10 分钟不断流,logcat 出现 OMX.MSTAR |
| S2 | MediaPlayer + /rtp/ TS | 同上,TS 源 | 同上;失败则记录错误码 |
| S3 | ExoPlayer + 两者 | 引擎切到 Exo 重跑 S1/S2 | 同上 |
| S4 | 引擎自动降级 | 播放中手动 kill 流 | 5s 内 ExoPlayer 接管继续播 |
| S5 | 断网恢复 | 拔网线 10s 再插 | 30s 内自动恢复播放 |

## 8.2 M2 稳定性验收

| # | 用例 | 通过标准 |
|---|---|---|
| T1 | 连续播放 24h | 无内存增长泄漏(每月 <5MB),无重启 |
| T2 | 连续换台 100 次 | 无一次卡死;平均换台 ≤2s |
| T3 | 断电重启 ×10 | 10/10 自动进播放器并出画面 |
| T4 | 被桌面抢焦点 20 次 | 20/20 恢复播放 |
| T5 | 遥控器全键位 | 无未响应按键 |

---

# 第9章 风险登记表

| # | 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|---|
| R1 | MediaPlayer 对 /rtp/ 原始 TS 嗅探失败 | 中 | 该类频道走 Exo | 自动降级(已设计)|
| R2 | HLS 直播边缘卡帧 | 高 | 观感 | 看门狗自愈(已实现)|
| R3 | d8 脱糖 + dalvik 组合边角问题 | 低 | 安装/运行失败 | EnvTool 已验证主路径;ExoPlayer 放 M1 spike |
| R4 | 1GB 内存被乐视后台挤压 | 高 | LMK 杀播放器 | 应用极简;建议执行应用清理(待你点名,可释放 ~300MB);必要时 root 冻结后台 |
| R5 | 桌面抢焦点 | 高 | 打断 | onResume 续播;可选后台保声 |
| R6 | WiFi 抖动 | 高 | 花屏/断流 | 有线;WifiLock;重试 |
| R7 | 工厂菜单抢前台 | 已存在 | 开机多一步 | app 自启置顶;env=0 持久化另案( lead:persist.letv.debuggable)|
| R8 | rtp2httpd 重启/迁移 | 低 | 全部断流 | 重试 + 频道列表本地缓存 |
| R9 | eMMC 写损耗 | 低 | 寿命 | 应用几乎不写盘(仅频道缓存)|

---

# 第10章 里程碑与验收标准

| 阶段 | 工作量 | 内容 | 验收 |
|---|---|---|---|
| M0 环境 | ✅ 完成 | root/构建管线/安装管线 | 今日已验证 |
| M1 spike | 0.5 天 | §8.1 S1-S5 | 至少一条链路 1080p 流畅 10 分钟 |
| M2 MVP | 1-2 天 | 播放页 + 频道列表 + 双引擎 + 重试 | T2/T4/T5 |
| M3 稳定化 | 1 天 | 自启、看门狗、OSD、频道号 | T1/T3;断电重启自动出画面 |
| M4 打磨 | 按需 | 分组/收藏/源管理/主题 | 体验类验收 |

---

# 第11章 运维手册

## 11.1 日常

| 操作 | 方法 |
|---|---|
| 更新频道 | 修改 rtp2httpd 频道源 → app 菜单"刷新列表"或重启 app |
| 升级 app | `adb install -r app.apk`(设置里打开"未知来源")|
| 看日志 | `adb logcat -s IPTV:V` |
| 强停 | `adb shell am force-stop com.your.iptv` |
| 卸载 | `adb uninstall com.your.iptv` |

## 11.2 应急

| 症状 | 处置 |
|---|---|
| 开机黑屏无 UI | `adb shell am start -n com.your.iptv/.PlayerActivity`;不行就重启 |
| app 崩溃循环 | `adb shell pm disable-user com.your.iptv` → 排查 → enable |
| 电视进工厂菜单 | 属预期(env 未清);app 自启后自动覆盖,或按今天记录的 env 课题处理 |
| adb 连不上 | 遥控器点亮屏幕(休眠断连);检查 IP 是否变化 |

---

# 第12章 后续演进路线

1. **EPG 节目单**:rtp2httpd 若提供 EPG(或接外部 xmltv),列表页加"正在播/接下来"
2. **时移/回看**:rtp2httpd 支持录制回放的话,HLS VOD 直接复用播放内核
3. **多房间**:频道列表放 NAS/路由器,全家电视共用
4. **直接组播**:绕过 rtp2httpd,app 内 MulticastSocket + TS 解析喂 MediaCodec(高级,仅在 rtp2httpd 成为瓶颈时考虑;需 MulticastLock)
5. **彻底关闭工厂模式**:persist.letv.debuggable 课题(已有初步线索存档)
6. **应用清理**:释放 300MB 内存,提升整机流畅度(编号清单待你点名)

---

# 附录 A:设备实测档案(2026-11-04)

| 项 | 值 |
|---|---|
| 型号/板 | Letv Y43 / mainz(MStar)|
| 系统 | Android 4.4.4 API 19 · Dalvik · user 版 · 无 SELinux |
| root | Dirty Cow 一次性(`getroot.sh`)|
| /system | 只读,写入 = panic(实测)|
| eMMC | Samsung 8GTF4R · eMMC5.1 · 寿命 10-20% · PRE_EOL 正常 |
| 内核 | 3.10.86 · loadavg 4.6 为 D 状态假象 · 实际空闲 |
| WebView | Chromium 30,无 MSE/ESM(实测)|
| 已装 | Firefox 68 ESR ✅ 运行正常 · EnvTool ✅ · getroot.sh |
| 构建 | javac→d8→aapt→apksigner→install 全链路 ✅ |

# 附录 B:故障排查百科(今天全部踩过的坑)

| 症状 | 原因 | 处置 |
|---|---|---|
| adb 突然 device offline | 电视休眠 WiFi 悬挂 / 手动重启 | 遥控器点亮屏幕;`adb connect` 重试 |
| `pm enable/xxx` 卡死 | shell 身份 pm 的偶发阻塞 | 改用 root:`su -c 'pm enable ...'` |
| `pm disable-user` 后开机卡 logo | tvos 等待被禁用的工厂 UI | `su -c 'pm enable com.stv.factorytest'`;env=1 期间禁停 factorytest |
| dalvik 拒载新编 dex("unable to open DEX file") | 厂商 dalvik 疑似要求预 odex | 走 `pm install`(installd dexopt)→ 正常;`dalvikvm -cp` 不可用 |
| d8 校验和"错误" | 校验算法偏移算错(红鲱鱼)| 实际 d8 校验和正确,无需修补 |
| `input text` 失效 | 搜狗输入法面板吞合成键 | BACK 收面板;或临时 `pm disable-user` 输入法(用完 enable)|
| am start 后 Firefox 不开新标签 | Fennec intent 处理怪癖 | 首个 intent 生效;后续用 URL 栏手输或重启 app |
| 写 /system 卡死重启 | fstab 块级写保护,写入事务触发 panic | **永远不要写 /system** |
| 截屏黑屏/与面板不符 | screencap 只抓 Android 层 | 以人眼+dumpsys 焦点共同判断 |
| 内存进程被杀 | LMK | `am kill-all` 释放;应用保持小内存 |

# 附录 C:今天验证过的全部命令

```bash
# 连接与唤醒
adb connect 192.168.1.21          # 断线重连;电视休眠时需先遥控器点亮
adb shell "getprop sys.boot_completed"

# root(每次开机后执行一次)
adb shell /data/local/tmp/getroot.sh          # Dirty Cow 注入 su 到 tcpdump

# 应用管理
adb shell pm install -r /data/local/tmp/app.apk
adb shell pm list packages -d                 # 已禁用列表
adb shell pm enable|disable-user <pkg>        # root 下执行

# 播放验证
adb shell am start -n com.your.iptv/.PlayerActivity
adb logcat -s IPTV:V
adb shell "logcat -d | grep -i OMX.MSTAR"

# 画面
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
```

---

*方案完 · 配套源码位于 tvroot/iptv/ · 构建脚本 tvroot/iptv/build.sh · 有问题按第 11 章运维手册处置*

---

# 变更记录

## V1.3.8(2026-11-10)· 当前版本(versionCode 12)· 探测改为「全速读到 SPS 即断」

V1.3.7 部署后按网页端 `visibleBadges` 语义隐藏无数据 chip,暴露出旁路探测的可靠性问题:
连接从流的任意位置开始,SPS 位置不固定;而探测在 256KB 后就降速(40ms/32KB),把短命连接
拖成长连接,7s 截屏时帧率 chip 仍未渲染、12s 也没等到补显。

改为:全速窗 `PHASE1` 256KB → **1MB**(覆盖实测 SPS 的 655KB)、取消降速、
总量上限 3MB → 2MB,`metaComplete()`(含 `codecHeight>0`,即 SPS 已解析)到齐立即断。
这样探测连接存在时间更短(通常 ≤1MB),帧率在信息条 8s 窗口内稳定到手;
启动仍延后 800ms 以避开起播最脆弱的缓冲期。

## V1.3.7(2026-11-10)(versionCode 11)· 全量对齐网页端

用户要求「技术方案全部对齐网页端」。做了两件事:

1. **代码**:`player-media-badges.tsx` 的全部格式规则统一到 `PlayerActivity` 一组 `format*`
   函数,底部信息条与右侧浮层共用;帧率按网页端 `renderState.deinterlacing` 语义取消
   无条件 ×2(50.0 → 25.0 FPS);码率改两位小数 Mbps / Kbps;空值 chip 改 `View.GONE`
   (不再显示「帧率 —」等占位);删除冗余的 `mVideoCodec`/`mAudioCodec`/`mBitrateText` 缓存。
2. **文档**:新增第 0 章「网页端对齐矩阵」,逐项标注网页端源码行号、TV 实现、状态
   (✅ 已对齐 / 🔁 平台等价 / ⛔ 硬约束),并把 6 项系统硬约束的等价方案写清楚,
   包括为什么不回退到 `/local/` 回环泵(会重新引入卡顿)。

见第 0 章,此处不重复。

## V1.3.6(2026-11-10)(versionCode 10)· 修掉 loading 提示残留

V1.3.5 部署后的真机截屏确认帧率已恢复(`576i | 50.0 FPS | H.264 | MP2 | 立体声 | 2.7 Mbps`),
但发现画面中央残留红色「缓冲中...」:`isConnectStatus()` 只认「正在连接/重试中/播放错误」,
不认 loading 提示,于是 `setLoading(false)`(首帧)清不掉它。`isConnectStatus()` 补上
「缓冲中」前缀,临时状态在首帧到达后统一清除。回归 4/4 通过后重新部署。

## V1.3.5(2026-11-10)(versionCode 9)· 修复信息条帧率不显示

**症状**:换台后底部信息条始终显示「帧率 —」,而分辨率/H.264/MP2/立体声/码率都能填上。
真机截屏两帧取证:换台瞬间全是占位符;6 秒后 `576p | 帧率 — | H.264 | MP2 | 立体声 | 3.4 Mbps`。

**根因(用真实流样本定量定位,非推测)**:从上游拉 CCTV-14 的 1MB TS,用项目自带
`TsProbe` 在 JVM 跑 `_scratch/jvmtest/ProbeFps.java`:

| 喂入字节 | fps | video | audio | height | channels | metaComplete |
|---|---|---|---|---|---|---|
| 32768 | 0 | h264 | mp3 | 0 | 0 | false |
| 65536 | 0 | h264 | mp2 | 0 | 2 | false |
| 524288 | 0 | h264 | mp2 | 0 | 2 | false |
| **655360** | **25.0** | h264 | mp2 | 576 | 2 | **true** |

SPS 要到 **~655KB** 才出现。V1.3.3 起的三处叠加把 fps 推到了信息条 8s 窗口之外:
1. 旁路探测从「起播即开」延后到「首帧 +5s」(`PROBE_DELAY_MS=5000`);
2. 全速窗后每 32KB `sleep(150)` ≈ 213KB/s,从 256KB 读到 655KB 又要 ~2s;
3. `mMetaTick` 的 `complete` 只看「视频编码 + 音频编码」,这两项 256KB 内就到,
   于是在信息条仍可见时把 `mMetaEverComplete` 置为 true —— fps 迟到后**既不在展示期
   刷新、也不触发补显**,永远停在「帧率 —」。

**修复**:`PROBE_DELAY_MS` 5000 → **800ms**(网页端帧率来自播放流 demux,起播即到,
旁路探测尽量贴近该时序);`sleep(150)` → **40ms**(探测仍是拿到 `metaComplete` 即断的
短命连接,不会长期占带宽);`mMetaTick` 的 `complete` 改为
`m.videoCodec != null && m.audioCodec != null && m.fps > 0`,fps 迟到时仍会补显一次 3s。

**验证**:换台后 6~8s 截屏应出现 `25.0 FPS`(隔行流的显示值按 V1.3 既有 ×2 规则),
且 `bash _scratch/run_tests.sh` 4/4 通过。诊断脚本 `ProbeFps.java` 与样本
`_scratch/cctv14_sample.ts` 保留,后续频道元数据问题可复用。

## V1.3.4(2026-11-10)(versionCode 8)· 换台逻辑改回网页端源码同构

**背景**:V1.3.3 是我在没读网页端原始源码的情况下自行推演的一套换台策略,引入了
防抖/解码器回收窗口/黑遮罩/帧门控等自创机制。项目里本来就有 rtp2httpd 网页端
完整 TypeScript 源码(`_scratch/rtp2httpd_src/web-ui/src/`),应直接移植其状态机。

**网页端实际逻辑**(已逐行核对):

| 源码位置 | 逻辑 |
|---|---|
| `pages/player.tsx:94,121` | `supportsSeamlessSwitch = !isLGWebOS()`;`seamlessSwitch` 初值 `= getSeamlessSwitch()` |
| `lib/player-storage.ts:48` | `getSeamlessSwitch()` 默认 **= `isDesktopDevice()`**,即移动/电视设备默认关 |
| `video-player.tsx:1059` | `handleLoadSegments(newSegments, forceHardSwitch)` = 换台/换源/重试唯一入口 |
| `:1084-1102` | `useSeamlessSwitch` 8 个条件;**否则 `loadActiveSlotSegments(activePlayer, activeId, ...)` —— 复用活动槽直接重载** |
| `:1104-1135` | 无缝时隐藏槽 `setMuted(true)` + `loadSegments`,`playing` 事件才提交 |
| `:603-626` | `completeTransition`:先 `setVisibleSlotId` 揭幕,**再** `stop` 旧槽 |
| `:661-675` | `fallbackPendingSwitchToHardSwitch`:`pending.player.stop()` → `handleLoadSegments(segments, true)` |
| `:368-384` | loading 指示 `setIsLoading(true)` + **500ms 防抖**才可见 |
| `:1775-1780` | 背景槽 `opacity-0 pointer-events-none`(保持帧回调),没有全屏遮罩 |

**本次改动(删除 V1.3.3 全部自创机制,按上表移植)**:

| 删除 | 替换为网页端 |
|---|---|
| 300ms 连按防抖、250ms 解码器回收窗口 | 无;`scheduleTune(forceHardSwitch)` 直接分流 |
| 全屏纯黑遮罩 + 连续 3 帧门控 + 4s 兜底 | 无遮罩;`setLoading()` + 500ms 防抖显示"缓冲中..." |
| 12 帧/1s 速率判据 + cushion + pause 攒垫 + resume 状态机 | `onStandbyFrame()` 首帧 = `playing` → 立即 `revealNext()` |
| 预载失败后换另一引擎再试一次 | `onPreloadError()` → `cancelPreload()` → `scheduleTune(true)` |
| 每次换台 release + 重建 `IPlayer` | `openWithEngine()` **复用活动槽实例**直接 `open()`(同 `loadActiveSlotSegments`) |
| "双槽预载切台(实验)" | 设置项改名 **"无缝换台"**,`pref seamless_switch`(兼容旧 `dual_slot`),默认关 |

保留的 TV 平台适配(网页端无对应概念,但实机需要):`transitionGen` 代数守卫、
15s pending 兜底、旁路元数据探测延后 5s、静音起播首帧开声。

**设置页版本号**:新增 `App.version()` 作为唯一来源(`versionName (versionCode)`),
电视设置浮层底部、手机端 `/config` 页面底部、`/api/config` 的 `version` 字段同源,
部署后一眼可确认电视上跑的是哪个包。

**语言选择(记录)**:目标机 Android 4.4.4 / Dalvik(libdvm),构建管线是
`javac → d8 → aapt → apksigner`,无 kotlinc/kotlin-stdlib;§2.1 已决策"Kotlin 脱糖产物
在 dalvik 未验证,收益为零"。本版继续 Java 7 语法,不自绘 DSL/协程需求,不改语言。

## V1.3.3(2026-11-10)(versionCode 7)· 换台卡顿/画面撕裂修复

**症状与实机取证**(app 1.3.2 运行中,`adb logcat -d` 只读抓取):

| 证据 | 读数 | 含义 |
|---|---|---|
| active 引擎 buffering 振荡 | `buffering+/- active` 在 2.1s 内出现 20+ 组,间隔 50~100ms | 显示中的流缓冲水位反复见底,即用户感知的"卡顿" |
| VDEC 帧率 | `MSTAR_VDEC_DBG Stream ID: 0, Rate : 25 → 23 → 18` | 解码/送显速率掉档 |
| VDEC 实例 | 缓冲区内 `Stream ID` 恒为 **0**,从未出现 1 | 该 SoC 只有一路 1080p 硬解;第二路解码器要么起不来要么互抢 |
| 音频 | `AudioTrack open audio_track_client.pcm error` / `AudioFlinger open audio_af_mixer.pcm error` 反复出现 | 两个 MediaPlayer 同时占音频输出,静音也不释放 AudioTrack |

**根因**:V1.3 引入的"网页端 dual-slot 同构"在 PC/手机浏览器成立,在这台 4.4 电视上不成立。
1. 预载期间同时存在两路 HTTP-TS 连接 + 两个 NuPlayer/AudioTrack + 两个硬解请求,而 VDEC 只有一路;
2. 两个全屏 TextureView 即使顶层 `alpha=0`,隐藏槽仍每帧 `updateTexImage()` 并参与 GL 合成,4×A7 上等于两路 1080p 纹理带宽;
3. 换台在 `onSurfaceTextureUpdated` 里瞬时翻 `alpha`,与 SurfaceTexture 的帧提交异步,新旧帧/半帧混杂 → 撕裂与绿块;
4. 每次按键立即新建/释放解码器(无防抖),快速连按造成 OMX 组件回收滞后的"解码器风暴";
5. 元数据旁路探测在起播瞬间开第三条连接、全速读 400KB,与上一版注释里记录的 2.1s buffering 振荡窗口吻合。

**修改**(默认路径改为单解码器,双槽保留为实验开关):

| 文件 | 改动 |
|---|---|
| `PlayerActivity.java` | 新增 `scheduleTune()` 换台防抖(300ms 连按合并,只开播最后一次);`openWithEngine()` 改为"先 release 旧引擎 → 250ms 回收窗口 → 再建新引擎";新增全屏过渡遮罩 + 连续 3 帧稳定门控(4s 兜底)才揭幕;单槽模式下底层 TextureView 置 GONE、顶层 alpha 恒 1;旁路探测延到首帧稳定后再等 5s |
| `PlayerActivity.java` | 双槽预载完整保留,改为设置浮层开关 `双槽预载切台(实验,老设备易卡顿)`(`pref dual_slot`,**默认关**);预载失败自动降回单槽;换线路与换台共用同一条防抖路径 |
| `SystemPlayerImpl.java` | `release()` 先 `setSurface(null)` 再 `release()`(避免断开连接时往 TextureView 推半帧);`start()` 修正 `&&`/`||` 优先级导致的潜在 NPE;`prepareAsync` 抛异常时就地释放 MediaPlayer,不再泄漏 OMX 名额 |
| `ConfigServer.java` | 换台 `invalidateMeta()` 后旧旁路探测线程立即退出;全速窗 400KB → 256KB |

**权衡**:单槽模式换台会有约 0.3~1s 纯黑过渡(遮罩上仍显示频道号/信息条与"正在连接"),换取不再有两路解码互抢与帧混杂。这是这台单 VDEC 设备上唯一稳定的做法。想对比"无黑屏但不稳定"的旧行为,可在设置浮层打开实验开关。

**验证判据(需人工在电视上确认,不在会话里注入按键扰动观看)**:
1. 连按 5~10 次上/下键:OSD 频道号跟着跳,画面只在最后一次按键后切换一次,无中间频道闪帧;
2. 单按一次换台:黑过渡后直接出现新频道稳定画面,无上下半屏错位/绿块/旧频道残影;
3. 换台后连续观看 30s:`adb logcat -s IPTV:V` 中不再出现成组的 `buffering+/- active` 振荡,`MSTAR_VDEC_DBG Rate` 稳定在 25;
4. `adb shell dumpsys media.player` 中客户端数量恒为 1;
5. 打开实验开关复现旧行为,再关掉验证可恢复。

## V1.3(2026-11-06)(versionCode 4)

**遥控器键位重映射(用户规格,固定映射,自学习功能删除)**
- 上/下/频道±=切台 · 左=频道列表 · 右=频道标签(线路)切换 · 菜单=设置浮层(去长短按) · OK=暂停/播放 · 数字直选/返回不变

**切台底部信息条 —— 严格对标 rtp2httpd 3.17.2 网页端(player-media-badges.tsx 逐项移植)**
- chips:分辨率(1080p/576p/4K 样式)· 帧率(25.0 FPS,隔行 ×2)· 视频编码(H.264)· 音频编码(MP2)· 声道(立体声)· 实测码率
- 元数据来自**本地回环泵单连接**(网页端"元数据来自播放流"模型的 TV 版):泵转发数据时顺路解析,零额外连接
- TS 元数据 demuxer = web-ui playback-engine/demux 的 Java 移植(ts-demuxer/sps-parser/exp-golomb/annexb/aac/ac3),含 H.264 SPS 完整解析(SAR → present_width、timing_info → fps)
- 全屏换台遮罩删除(底部信息条已有频道信息);暂停图标去白色外圈、去文字
- 视频显示 = 网页端 object-fill 语义:解码帧恒铺满 16:9 屏(SD 变形像素自动拉伸)

**多线路(rtp2httpd 3.17.2)**
- 播放列表同名同组条目合并为一个频道的多线路;URL `$` 后缀为线路标签
- 方向右键直接切换线路(标清/高清),无需进菜单

**关键修复**
- 泵的 feed 输入是 TCP 任意切片:加跨块缓冲 + sync 重同步(此前每块开头不对齐数据被整体丢弃 —— 编码识别失败的根因)
- parseSection 去除错误的 pmtDefined 门控(PMT 无法路由的根因)
- section 循环 Java 边界防护(JS undefined 语义差异导致的数组越界)

**验证**:JVM 测试 23 项断言全过(含真实流 SPS/音频样本);真机截图确认信息条与网页端逐项一致;15 次连切内存稳定(app ~13MB 无泄漏趋势,mediaserver 波动属 NuPlayer/OMX 缓冲正常行为,VDEC 满速零错误)。

**真机复测修正(用户反馈 5 项)**
- 侧栏遥控导航重构:ListView 触摸模式下 setSelection 不显示高亮 → 手动选中态(mSidebarSel)+ 高亮绘制 + 打开时定位当前频道;当前频道不在所选分组时重置为"全部"
- 方向右:任何时刻按下都展示信息条 + 切换线路标签(单线路提示"只有一条线路")
- 设置浮层重构:居中面板、无滚动条、内容一屏放下、取消/保存遥控焦点可达(focusGlow 高亮)
- 暂停/播放图标:去白色外圈、去文字、居中
- 视频显示:object-fill 铺满(网页端 video 容器恒 16:9 语义)

## V1.2(2026-11-05)(versionCode 3)

**Bug 修复**
- 换台转场遮罩永不消失:`onSurfaceTextureUpdated` 每帧移除+重排 1.5s 隐藏任务,直播帧间隔 ~33ms 导致任务永远无法执行。改为单飞调度 + 首帧门控(onStarted 后的帧才可信)+ 代数守卫 + 3.5s 兜底(§3.2.2 坑 1 的 UI 侧配套)
- 按键学习 slot0"频道列表"/slot1"节目源"学习后无动作:`actOnKey` 缺分支,按键被 known 集合吞掉 → 已补;学习键为附加绑定,硬编码键永远有效
- 暂停态切台后状态错乱(新流自动开播但 UI 仍显示暂停):`tune()` 重置暂停态并收起遮罩
- `rebindIndex()` 不再把 mCurrent 置 null(播放中列表刷新不再丢失当前频道态)
- ExoPlayerImpl 编译错误修复(`b` 未声明先使用);仍需 exoplayer aar 才可启用(build.sh `WITH_EXO=1`,网络不可达暂缓)
- M3uParser 频道名含逗号被截断(原 lastIndexOf 从末尾逗号取名):改为 HLS 规范语义 —— 第一个不在双引号内的逗号之后为标题;真实 104 频道列表解析零回归(JVM 单元测试 26 项断言,`_scratch/jvmtest/`)
- M3uParser 不再生成空 URL 频道;侧栏高亮随 tune() 刷新;节目源菜单支持点空白关闭
- 音量持久化:手机端设置音量后切台/重启不再回满(引擎实例不继承音量 → openWithEngine 重放 mVol;prefs 存 volume)
- SharedPreferences 写入 commit → apply(键位学习/换台等 UI 线程写入不再同步落盘)
- §5.1 键位表按 V1.2 实现重写(原文档"左右=音量步进"与实现不符;静音 164 未绑定已注明)
- 手机端列表自动滚动:电视换台后当前频道滚入可视区(仅索引变化时触发,不打断手动浏览;暂停/音量变化不滚)
- EPG 探测结论:本部署 rtp2httpd 无 url-tvg/EPG 端点(实测 404),网页端亦为空态 → 手机端空态即完全对标,不实现无数据源的 EPG
- 真实 104 频道规模验证:渲染/分组/搜索/切台/滚动全部通过(mock 从 rtp2httpd 实时拉取真实列表)
- QrUtil:批量 setPixels 替代 5.8 万次逐像素 setPixel(配置页二维码不再卡顿);修正与实现相反的注释
- build.bat 修复:javac/d8/aapt 失败即中止(原来会继续打包坏 APK)、d8 补 --lib、install 修正丢 :5555 端口的目标
- OSD 频道名超长省略号截断(原来会向左溢出屏幕);§2.1 视频输出按实现改为 TextureView 并记录取舍
- Leanback banner:新增 320×180 横幅资源(ic_banner,与应用同视觉语言)并挂 android:banner —— Android TV 桌面无 banner 会显示灰色占位(API 19 运行时忽略,API 21+ 生效)
- 一键回归脚本 `_scratch/run_tests.sh`:构建 + 26 项 M3uParser 断言 + ConfigServer 地面真值 + tested==shipped 字节一致性,一条命令全跑
- 断网恢复自愈补全(§3.2.2 坑5 / S5):新增 CONNECTIVITY_ACTION 监听 —— 重试链耗尽(原停在"频道暂时不可用")后,网络恢复自动重连当前频道;短断网仍由退避重试覆盖,长断网不再永久卡死

**手机端网页(ConfigServer `/`)全面重写 —— 像素级对标 rtp2httpd 网页播放器**
- 实测设计 token:背景 rgb(11,17,30) · 卡片 rgb(15,23,41) · 边框 rgb(29,40,57) · 主色 rgb(60,131,246) · 次文字 rgb(148,163,184);移动端视频区(16:9)在上侧栏在下,≥768px 侧栏右置 320px
- 频道/节目单双 tab · 搜索 · 分组胶囊 · 频道卡片(号码徽章/当前高亮)· 右上频道徽标 · 底部控制条(上一台/播放暂停/下一台/音量/静音/直播红点) · 加载/已暂停/空闲覆盖层 · 设置弹层(主题自动/浅色/深色,localStorage 持久) · 全部 lucide 同款图标
- 响应头统一 `Cache-Control: no-store`(修复状态可能不同步);socket 10s 超时
- /config 保存改 PRG(302 回配置页 + 成功横幅,不再落在原始 JSON);checkbox 宽度修复

**验证记录**
- mock 后端端到端测试 13 组用例全过(T1-T13),含 XSS 转义、环绕切台、防抖、状态推送;证据 `_scratch/gui-test-screenshots/`
- 参考渲染工作流:`mirror/`(rtp2httpd 前端镜像)+ `_scratch/webroot/` 本地起服务即可对照

## V1.0(2026-11-04)· 初版方案(见上文)
