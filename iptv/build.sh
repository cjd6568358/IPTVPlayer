#!/bin/bash
# IPTV 一键构建安装脚本(Git Bash / MSYS)
# 用法: ./build.sh [install]
set -e
export MSYS_NO_PATHCONV=1

SDK="D:/Program Files/Android/Sdk"
BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-35/android.jar"
JBR="D:/Program Files/Android/Android Studio/jbr/bin"
ROOT="$(cd .. && pwd -W)"
MSTAR="$ROOT/apk_backup/com.mstar.android.jar"
ZXING="$ROOT/libs/zxing-core-3.4.1.jar"
EXO="$ROOT/libs/exoplayer-2.13.3-merged.jar"
KS="$ROOT/envtool/debug.keystore"
PKG="com.your.iptv"
OUT="build"
SRC="src"
DEVICE="192.168.1.21"

cd "$(dirname "$0")"

echo "[1/6] 清理"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "[2/6] 收集源码"
find "$SRC" -name "*.java" > "$OUT/sources.txt"
if [ "$WITH_EXO" != "1" ]; then
    grep -v "ExoPlayerImpl" "$OUT/sources.txt" > "$OUT/sources.txt.tmp" || true
    mv "$OUT/sources.txt.tmp" "$OUT/sources.txt"
fi
echo "源文件数: $(wc -l < "$OUT/sources.txt")"

echo "[3/6] javac"
CP="$MSTAR;$ZXING"
if [ "$WITH_EXO" == "1" ]; then CP="$CP;$EXO"; fi
"$JBR/javac.exe" -source 8 -target 8 -bootclasspath "$AJ" -cp "$CP" \
    -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
grep -aviE "bootstrap|deprecat" "$OUT/javac.log" || true
if [ "$JAVAC_RC" != "0" ]; then
    echo "[!] javac 编译失败 (rc=$JAVAC_RC),中止——绝不打包坏 APK"
    exit 1
fi

echo "[4/6] d8 → classes.dex"
CLASSES=$(find "$OUT/classes" -name "*.class" | tr '\n' ' ')
mkdir -p "$OUT/dex"
D8IN="$CLASSES $ZXING"
if [ "$WITH_EXO" == "1" ]; then D8IN="$D8IN $EXO"; fi
"$JBR/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 19 --lib "$AJ" \
    --output "$OUT/dex" $D8IN > "$OUT/d8.log" 2>&1
D8_RC=$?
grep -aviE "warning" "$OUT/d8.log" || true
if [ "$D8_RC" != "0" ]; then
    echo "[!] d8 失败 (rc=$D8_RC),中止"
    exit 1
fi

echo "[5/6] aapt 打包 + 注入 dex + 签名"
"$BT/aapt.exe" package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -F "$OUT/base.apk"
python - "$OUT/base.apk" "$OUT/dex/classes.dex" << 'PYEOF'
import sys, zipfile, os
base, dex = sys.argv[1], sys.argv[2]
src = zipfile.ZipFile(base)
out = zipfile.ZipFile(base + '.tmp', 'w', zipfile.ZIP_DEFLATED)
for n in src.namelist():
    out.writestr(n, src.read(n))
zi = zipfile.ZipInfo('classes.dex'); zi.compress_type = zipfile.ZIP_STORED
out.writestr(zi, open(dex, 'rb').read())
out.close(); src.close()
os.replace(base + '.tmp', base)
print('dex 注入完成')
PYEOF
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:android --min-sdk-version 19 \
    --out "$OUT/IPTV.apk" "$OUT/base.apk"

echo "[6/6] dex 内容校验"
DD="$BT/dexdump.exe"
NCLASS=$("$DD" "$OUT/dex/classes.dex" 2>/dev/null | grep -c "Class descriptor")
NMINE=$("$DD" "$OUT/dex/classes.dex" 2>/dev/null | grep "Class descriptor" | grep -c "your/iptv")
echo "dex 校验: 总类=$NCLASS 应用类=$NMINE"
if [ "$NMINE" -lt 10 ]; then
    echo "FATAL: dex 缺少应用类,构建中止(不要安装这个包)"
    exit 1
fi
echo "[完成] 产物: $OUT/IPTV.apk ($(stat -c %s "$OUT/IPTV.apk") 字节)"

if [ "$1" == "install" ]; then
    adb connect $DEVICE >/dev/null 2>&1 || true
    adb -s $DEVICE:5555 install -r "$OUT/IPTV.apk" 2>&1 | tail -1
    adb -s $DEVICE:5555 shell "am start -n $PKG/.PlayerActivity" 2>/dev/null || true
    echo "已安装并启动"
fi
