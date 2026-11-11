# 乐视 Y43(192.168.1.21)root 工具箱 + IPTV 播放器

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

## root 工具箱(日常使用,电视上已部署,重启不丢)

```bash
adb connect 192.168.1.21
adb shell /data/local/tmp/getroot.sh          # 一键获得 root(约 5~10 秒)
adb shell "/data/local/tmp/su -c 'id'"        # 使用 root 执行任意命令
```

- `getroot.sh` 用 Dirty Cow(CVE-2016-5195)把 1.6KB 的 setuid su 注入 `/system/xbin/tcpdump` 的页缓存
- `/data/local/tmp/su` 是个包装脚本,转发到 `/system/xbin/tcpdump`(自带 setuid 位)
- **重启后 root 自动消失**(注入只存在于内存页缓存,磁盘从未被改写),重新运行 `getroot.sh` 即可
- **卸载**:删除电视上 `/data/local/tmp/{dcow,su_new,getroot.sh,su}` 四个文件即彻底清除

## 能力说明(重要)

经 adb 链路获得的 root 能力边界被 adbd 裁剪为 `CapEff=0xc0`(仅 setuid/setgid):
- ✅ 可读写 root 属主文件、杀进程、读块设备、普通文件操作
- ❌ 不可 mount、dmesg、drop_caches、读 system 属主的 0600 文件

需要全能力 root 时用一次性注入(链路不经 adbd,能力全量):

```bash
adb push dcow /data/local/tmp/ && adb push dumpstate /data/local/tmp/dumpstate_orig
adb shell "chmod 755 /data/local/tmp/dcow; /data/local/tmp/dcow /system/bin/dumpstate 46368 /data/local/tmp/XXX.sh; setprop ctl.start dumpstate"
# 用完恢复: dcow /system/bin/dumpstate 46368 /data/local/tmp/dumpstate_orig,并 md5 校验 = ff07cb7c402f9ec26283e077e2210d36
```

## 为什么做不到"改系统持久化"(已验证)

1. Dirty Cow 写入只进页缓存,厂商内核对只读挂载的 ext4 **禁止回写落盘**(重启即还原)
2. `mount -o remount,rw /system` 被内核反 root 补丁拦截(全能力下假成功,实际无效;能力裁剪下直接 EPERM)
3. 全新挂载同一分区会复用只读 superblock;可写分区(/tvconfig 等)上没有开机执行钩子
4. eMMC 型号:三星 8GTF4R(eMMC 5.1),寿命实测 10-20%,PRE_EOL 正常(2026-11-03)

## 本目录文件

| 文件 | 说明 |
|---|---|
| dcow.c / dcow | Dirty Cow 竞争利用程序(ARMv7 freestanding,NDK 27 编译)|
| my_su.c / su_new | 极简 setuid su(仅 setuid+exec,无守护进程)|
| dumpstate | /system/bin/dumpstate 原始备份(md5 ff07cb7c...)|
| tcpdump_orig | /system/xbin/tcpdump 原始备份(md5 b4f941f5...)|
| getroot.sh / su_wrap.sh | 电视上部署的一键脚本源 |
| payload_*.sh | 历次注入用脚本(取证留档)|
| results/ | ext_csd、dmesg、init 侦察等采集数据 |
