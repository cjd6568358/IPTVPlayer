@echo off
rem IPTV 一键构建安装脚本(Windows CMD 版,功能同 build.sh)
rem 用法: build.bat [install]
setlocal enabledelayedexpansion

set SDK=D:\Program Files\Android\Sdk
set BT=%SDK%\build-tools\36.0.0
set AJ=%SDK%\platforms\android-35\android.jar
set JBR=D:\Program Files\Android\Android Studio\jbr\bin
set PKG=com.your.iptv
set OUT=build
set SRC=src
set KS=..\envtool\debug.keystore
set DEVICE=192.168.1.21

cd /d %~dp0

echo [1/6] 清理
if exist %OUT% rmdir /s /q %OUT%
mkdir %OUT%\classes 2>nul
mkdir %OUT%\dex 2>nul

echo [2/6] 收集源码
dir /s /b %SRC%\*.java > %OUT%\sources.txt
findstr /v "ExoPlayerImpl" %OUT%\sources.txt > %OUT%\sources2.txt
move /y %OUT%\sources2.txt %OUT%\sources.txt >nul

echo [3/6] javac
"%JBR%\javac.exe" -source 8 -target 8 -bootclasspath "%AJ%" -cp "..\apk_backup\com.mstar.android.jar" -d %OUT%\classes @%OUT%\sources.txt
if errorlevel 1 (
    echo [!] javac 编译失败 —— 绝不打包坏 APK
    exit /b 1
)

echo [4/6] d8
echo %OUT%\classes > %OUT%\classes.txt & echo ..\libs\zxing-core-3.4.1.jar >> %OUT%\classes.txt
"%BT%\d8.bat" --release --min-api 19 --lib "%AJ%" --output %OUT%\dex @%OUT%\classes.txt
if errorlevel 1 (
    echo [!] d8 失败
    exit /b 1
)

echo [5/6] 打包签名
"%BT%\aapt.exe" package -f -M AndroidManifest.xml -A assets -I "%AJ%" -F %OUT%\base.apk
if errorlevel 1 (
    echo [!] aapt 失败
    exit /b 1
)
python -c "import sys,zipfile; base=sys.argv[1]; dex=sys.argv[2]; src=zipfile.ZipFile(base); out=zipfile.ZipFile(base+'.t','w',zipfile.ZIP_DEFLATED); [out.writestr(n, src.read(n)) for n in src.namelist()]; zi=zipfile.ZipInfo('classes.dex'); zi.compress_type=zipfile.ZIP_STORED; out.writestr(zi, open(dex,'rb').read()); out.close(); src.close(); import os; os.replace(base+'.t', base)" %OUT%\base.apk %OUT%\dex\classes.dex
"%BT%\apksigner.bat" sign --ks %KS% --ks-pass pass:android --min-sdk-version 19 --out %OUT%\IPTV.apk %OUT%\base.apk

echo [6/6] 产物: %OUT%\IPTV.apk

if "%1"=="install" (
    adb connect %DEVICE% 2>nul
    adb -s %DEVICE%:5555 install -r %OUT%\IPTV.apk
    adb -s %DEVICE%:5555 shell "am start -n %PKG%/.PlayerActivity"
    echo 已安装并启动
)
endlocal
