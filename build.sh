#!/bin/bash
#
# 从「已发布的 gemstone AAR」构建并装进 Android 模拟器
#
# 用法: ./build.sh [--clean]
#         --clean   先卸载再装，清空钱包数据（默认覆盖安装，数据保留）
#
# 前置环境变量：
#   ANDROID_HOME    Android SDK 路径
#   GITHUB_ACTOR    GitHub 用户名
#   GITHUB_TOKEN    带 read:packages 的 token
#                   🔴 GitHub Packages 即使对 public 仓库也强制鉴权，
#                      不传会拿到 401（不是 404，容易误判成包不存在）
#
set -euo pipefail
cd "$(dirname "$0")"

CLEAN=0
for a in "$@"; do
    case "$a" in
        --clean) CLEAN=1 ;;
    esac
done

APP_ID="com.example.gemdemo"
GEMSTONE_VERSION="${GEMSTONE_VERSION:-2.114.10}"
AVD="${AVD:-GemTest}"

step() { printf '\n\033[36m==> %s\033[0m\n' "$1"; }
info() { printf '    %s\n' "$1"; }
die()  { printf '\033[31merror: %s\033[0m\n' "$1" >&2; exit 1; }

: "${ANDROID_HOME:?请先 export ANDROID_HOME}"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || die "找不到 adb: $ADB"

if [ -z "${GITHUB_TOKEN:-}" ]; then
    die "缺少 GITHUB_TOKEN。GitHub Packages 即使 public 仓库也要鉴权，
       否则拉 AAR 会 401。需要一个带 read:packages 的 token。"
fi

# ── 1. 编译 ────────────────────────────────────────────────
# dl.google.com / Maven Central 偶发 TLS 握手失败（实测本机成功率可低至 2/5，
# 挂 VPN 时更明显）。Gradle 默认不重试依赖解析，一次抖动就整个构建挂掉。
NET="-Dorg.gradle.internal.repository.max.retries=10"
NET="$NET -Dorg.gradle.internal.repository.initial.backoff=2000"
NET="$NET -Dorg.gradle.internal.http.connectionTimeout=120000"
NET="$NET -Dorg.gradle.internal.http.socketTimeout=120000"

step "编译 APK（gemstone $GEMSTONE_VERSION）"
BUILT=0
for attempt in 1 2 3; do
    if ./gradlew --quiet $NET -PgemstoneVersion="$GEMSTONE_VERSION" assembleDebug; then
        BUILT=1; break
    fi
    info "第 $attempt 次失败，等 8s 重试（多为网络抖动）"
    sleep 8
done
[ "$BUILT" -eq 1 ] || die "编译失败（已重试 3 次）"

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "找不到 $APK"
info "APK $(du -h "$APK" | cut -f1)"

# 自检：确认三个 ABI 的 .so 真被打进去了。
# 「编译通过」只证明坐标能解析，不证明 native 库在包里。
SO_COUNT=$(unzip -l "$APK" | grep -c "lib/.*/libgemstone.so" || true)
[ "$SO_COUNT" -ge 1 ] || die "APK 里没有 libgemstone.so"
info "libgemstone.so × $SO_COUNT 个 ABI"

# ── 2. 启模拟器 ────────────────────────────────────────────
step "准备模拟器"
if ! "$ADB" devices | grep -q "device$"; then
    info "没有已连接设备，启动 AVD: $AVD"
    nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" \
        -no-snapshot-load -gpu auto -dns-server 8.8.8.8,1.1.1.1 \
        >/tmp/gem-emulator.log 2>&1 &
    "$ADB" wait-for-device
fi
# wait-for-device 只等到 adb 可连，不等于系统起来了
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 5
done
info "已就绪: $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"

# ── 3. 安装启动 ────────────────────────────────────────────
step "安装并启动"
if [ "$CLEAN" -eq 1 ]; then
    "$ADB" uninstall "$APP_ID" >/dev/null 2>&1 || true
    info "已清除既有数据（--clean）"
fi
# -r 覆盖安装，数据保留
"$ADB" install -r "$APK" >/dev/null
"$ADB" shell am start -n "$APP_ID/.MainActivity" >/dev/null

printf '\n\033[32m✅ 已启动\033[0m\n'
printf '   截图:   %s exec-out screencap -p > /tmp/s.png\n' "$ADB"
printf '   自检:   %s shell am start -n %s/.MainActivity --ez selftest true\n' "$ADB" "$APP_ID"
printf '   看结果: %s shell run-as %s cat files/selftest.txt\n' "$ADB" "$APP_ID"
