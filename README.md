# 乐视 Y43 IPTV 播放器

## IPTV 播放器应用(iptv/)

为这台电视开发的原生 IPTV 播放器(Android 4.4.4,Dalvik 兼容,Java 7 语法):

```bash
cd iptv && bash build.sh          # 构建(Git Bash);bash build.sh install = 构建并安装启动
bash _scratch/run_tests.sh        # 一键回归(构建 + 全部自动化测试)
```

- **部署**:`adb install -r iptv/build/IPTV.apk`;遥控器操作,D-pad 全键位 + 数字键直选 + 按键学习
- **手机遥控**:app 内设置浮层扫码,或直接访问 `http://<电视IP>:8123/` —— 与 rtp2httpd 网页端同视觉的遥控页(切台/暂停/音量/主题/配置)
- **频道源**:M3U 列表(rtp2httpd `/playlist.m3u`),本地缓存断网可用;默认引擎 MediaPlayer,ExoPlayer 为可选编译(`WITH_EXO=1`)
- **方案与变更记录**:[IPTV_4.4_App_技术方案.md](IPTV_4.4_App_技术方案.md) V1.3.8
  (第 0 章「网页端对齐矩阵」为当前实现基线,逐项标注源码位置与硬约束)
- **换台模型**:移植网页端 `video-player.tsx` 的 `handleLoadSegments` 状态机 —— 默认
  `硬切`(复用活动槽直接重载),`无缝换台`开关默认关(网页端同样只在桌面默认开启);
  换台由 `playing`/首帧提交,loading 提示 500ms 防抖。设置页显示版本号,方便确认部署版本

## 其他本地文件

设备取证脚本、采集数据、历史 APK 与交付 APK 均只保存在本地工作区或电视上,未纳入本仓库(见 `.gitignore`);`iptv/build/` 由 `build.sh` 生成,同样不入库。
