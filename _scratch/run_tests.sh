#!/bin/bash
# IPTV 一键回归:构建 + 全部自动化测试(不含需真机的实机清单)
# 用法: bash _scratch/run_tests.sh
set -e -o pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")"
cd "$ROOT/iptv"
JBR="D:/Program Files/Android/Android Studio/jbr/bin"
AJ="D:/Program Files/Android/Sdk/platforms/android-35/android.jar"
ZX="D:/workspace/IPTVPlayer/libs/zxing-core-3.4.1.jar"

echo "== [1/4] 构建 APK =="
bash build.sh | tail -2

echo "== [2/4] M3uParser 多线路/回归 JVM 测试 =="
cd "$ROOT/_scratch/jvmtest"
"$JBR/javac.exe" -encoding UTF-8 -cp "$AJ;$ZX;out" -d out \
    "$ROOT/iptv/src/com/your/iptv/source/Channel.java" \
    "$ROOT/iptv/src/com/your/iptv/source/M3uParser.java" Test317.java TestM3u.java
"$JBR/java.exe" -Dfile.encoding=UTF-8 -cp "out;$AJ;$ZX" Test317 | tail -1
"$JBR/java.exe" -Dfile.encoding=UTF-8 -cp "out;$AJ;$ZX" TestM3u | tail -1

echo "== [3/4] TS 探针解析(PAT/PMT→编码) =="
"$JBR/javac.exe" -encoding UTF-8 -cp "$AJ;$ZX;out" -d out \
    "$ROOT/iptv/src/com/your/iptv/PlayerActivity.java" \
    "$ROOT/iptv/src/com/your/iptv/App.java" \
    "$ROOT/iptv/src/com/your/iptv/source/ChannelRepository.java" \
    "$ROOT/iptv/src/com/your/iptv/tvcontroller/TVController.java" \
    "$ROOT/iptv/src/com/your/iptv/util/ConfigServer.java" \
    "$ROOT/iptv/src/com/your/iptv/util/TsProbe.java" \
    "$ROOT/iptv/src/com/your/iptv/util/QrUtil.java" \
    "$ROOT/iptv/src/com/your/iptv/util/RetryPolicy.java" \
    "$ROOT/iptv/src/com/your/iptv/util/TvUtils.java" \
    "$ROOT/iptv/src/com/your/iptv/IPlayer.java" \
    "$ROOT/iptv/src/com/your/iptv/player/PlayerFactory.java" \
    "$ROOT/iptv/src/com/your/iptv/player/SystemPlayerImpl.java" \
    "$ROOT/iptv/src/com/your/iptv/boot/BootReceiver.java" Test317.java
"$JBR/java.exe" -Dfile.encoding=UTF-8 -cp "out;$AJ;$ZX" Test317 | tail -1

echo "== [4/4] APK 资源完整性(SPA 文件在包内) =="
cd "$ROOT/iptv"
python -I - <<'PYEOF'
import zipfile, sys
z = zipfile.ZipFile('build/IPTV.apk')
names = z.namelist()
need = ['assets/player/index.html', 'assets/player/assets/player-LtuQUSeN.js',
        'assets/player/assets/use-theme-CNhYan1H.css', 'assets/player/assets/use-theme-D5AEK__r.js']
missing = [n for n in need if n not in names]
print('SPA assets in APK:', 'OK' if not missing else 'MISSING ' + str(missing))
sys.exit(0 if not missing else 1)
PYEOF

echo "== 全部通过 =="
