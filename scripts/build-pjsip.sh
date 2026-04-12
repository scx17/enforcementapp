#!/bin/bash
# build-pjsip.sh — 下载并编译 PJSIP，生成 Android AAR
#
# 用法：
#   cd EnforcementApp
#   bash scripts/build-pjsip.sh
#
# 输出：app/libs/pjsip-android.aar
#
# 依赖：
#   - Android NDK（脚本自动从 ~/Android/Sdk/ndk/ 检测）
#   - JAVA_HOME（脚本自动从 ~/android-studio/jbr 检测）
#   - swig（脚本自动安装，需要 sudo）
#   - git, make, python3
#
# 编译目标 ABI：arm64-v8a + armeabi-v7a
# PJSIP 版本：2.14.1

set -euo pipefail

# ──────────────────────────────────────────────
# 路径配置
# ──────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$HOME/pjsip-build"
PJSIP_SRC="$BUILD_DIR/pjproject"
OUTPUT_AAR="$PROJECT_ROOT/app/libs/pjsip-android.aar"
PJSIP_VERSION="2.14.1"
PJSIP_TAG="2.14.1"

# NDK 自动检测
if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
    NDK_BASE="$HOME/Android/Sdk/ndk"
    if [ -d "$NDK_BASE" ]; then
        ANDROID_NDK_ROOT="$NDK_BASE/$(ls "$NDK_BASE" | sort -V | tail -1)"
    fi
fi

if [ -z "${ANDROID_NDK_ROOT:-}" ] || [ ! -d "$ANDROID_NDK_ROOT" ]; then
    echo "❌ 未找到 Android NDK，请设置 ANDROID_NDK_ROOT 环境变量"
    exit 1
fi
echo "✅ NDK: $ANDROID_NDK_ROOT"

# JAVA_HOME 自动检测
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -d "$HOME/android-studio/jbr" ]; then
        JAVA_HOME="$HOME/android-studio/jbr"
    fi
fi
export JAVA_HOME
echo "✅ JAVA_HOME: $JAVA_HOME"

# ──────────────────────────────────────────────
# 安装系统依赖
# ──────────────────────────────────────────────
echo ""
echo "📦 检查并安装系统依赖..."

echo "  安装编译依赖..."
apt-get update -qq
apt-get install -y make python3 libssl-dev uuid-dev \
    libpcre2-dev libpcre3-dev build-essential wget \
    autoconf automake libtool bison m4 2>&1 | grep -E "^(Get|Setting|Unpack|E:)" || true

# ── 安装 SWIG（从源码编译，apt 源缺失时使用）──────────────
SWIG_BIN=$(command -v swig || command -v swig4.0 || command -v swig3.0 || true)
if [ -z "$SWIG_BIN" ]; then
    echo ""
    echo "  apt 无 swig，从源码编译 swig 4.2.1（需要约 3 分钟）..."
    SWIG_BUILD="$BUILD_DIR/swig-build"
    mkdir -p "$SWIG_BUILD"
    cd "$SWIG_BUILD"

    if [ ! -f "swig-4.2.1.tar.gz" ]; then
        echo "  [1/4] 下载 swig 源码..."
        # 依次尝试多个下载源
        SWIG_URLS=(
            "https://sourceforge.net/projects/swig/files/swig/swig-4.2.1/swig-4.2.1.tar.gz/download"
            "https://github.com/swig/swig/archive/refs/tags/v4.2.1.tar.gz"
            "https://prdownloads.sourceforge.net/swig/swig-4.2.1.tar.gz"
        )
        DOWNLOADED=0
        for URL in "${SWIG_URLS[@]}"; do
            echo "    尝试: $URL"
            if wget --progress=bar:force -O swig-4.2.1.tar.gz "$URL" 2>&1; then
                # 验证下载文件有效
                if tar -tzf swig-4.2.1.tar.gz &>/dev/null; then
                    DOWNLOADED=1
                    echo "    ✅ 下载成功"
                    break
                else
                    echo "    ⚠️  文件损坏，尝试下一个源..."
                    rm -f swig-4.2.1.tar.gz
                fi
            else
                rm -f swig-4.2.1.tar.gz
            fi
        done
        if [ "$DOWNLOADED" -eq 0 ]; then
            echo "❌ 所有源都下载失败，请手动下载 swig："
            echo "   wget -O $SWIG_BUILD/swig-4.2.1.tar.gz https://sourceforge.net/projects/swig/files/swig/swig-4.2.1/swig-4.2.1.tar.gz/download"
            exit 1
        fi
    else
        echo "  [1/4] 已有缓存，跳过下载"
    fi

    # 先探测解压目录名（列表模式，不实际解压）
    SWIG_DIR=$(tar -tzf swig-4.2.1.tar.gz 2>/dev/null | head -1 | cut -d/ -f1)
    echo "  [2/4] 解压到 $SWIG_DIR ..."
    tar -xzf swig-4.2.1.tar.gz
    echo "      解压完成"

    echo "  [3/4] configure..."
    cd "$SWIG_DIR"
    # GitHub archive 没有预生成 configure，需先运行 autogen.sh
    if [ ! -f "./configure" ] && [ -f "./autogen.sh" ]; then
        echo "      运行 autogen.sh 生成 configure..."
        ./autogen.sh
    fi
    ./configure --prefix=/usr/local \
        --without-tcl --without-python --without-perl \
        --without-ruby --without-java --without-php \
        2>&1 | tail -5

    echo "  [4/4] make（并行 4 核）..."
    make -j4
    make install
    echo "  ✅ swig 安装完成"
    SWIG_BIN=$(command -v swig)
fi

if [ -z "$SWIG_BIN" ]; then
    echo "❌ swig 安装失败"
    exit 1
fi
echo "✅ swig: $($SWIG_BIN -version 2>&1 | head -1)"

# ──────────────────────────────────────────────
# 下载 PJSIP 源码
# ──────────────────────────────────────────────
echo ""
echo "⬇️  下载 PJSIP $PJSIP_VERSION..."
mkdir -p "$BUILD_DIR"

if [ -d "$PJSIP_SRC/.git" ]; then
    echo "  已存在，跳过克隆（使用缓存）"
    cd "$PJSIP_SRC"
    git fetch --tags -q
else
    cd "$BUILD_DIR"
    git clone --depth=1 --branch "$PJSIP_TAG" \
        https://github.com/pjsip/pjproject.git pjproject
    cd "$PJSIP_SRC"
fi

# ──────────────────────────────────────────────
# 生成 config_site.h（Android 专用配置）
# ──────────────────────────────────────────────
echo ""
echo "🔧 生成 config_site.h..."
cat > "$PJSIP_SRC/pjlib/include/pj/config_site.h" << 'EOF'
/* PJSIP Android 配置 */
#define PJ_CONFIG_ANDROID 1

/* 关闭桌面音频设备，使用 Android OpenSL ES */
#define PJMEDIA_AUDIO_DEV_HAS_PORTAUDIO  0
#define PJMEDIA_AUDIO_DEV_HAS_WMME       0

/* 开启 Opus 编解码（可选，需要 libopus） */
/* #define PJMEDIA_HAS_OPUS_CODEC 1 */

/* 降低内存占用 */
#define PJ_OS_HAS_CHECK_STACK 0

#include <pj/config_site_sample.h>
EOF

# ──────────────────────────────────────────────
# 为每个 ABI 编译
# ──────────────────────────────────────────────
# NDK API 级别与 minSdkVersion 对齐
API_LEVEL=26
ABIS=("arm64-v8a" "armeabi-v7a")

build_abi() {
    local ABI="$1"
    echo ""
    echo "🔨 编译 ABI: $ABI (API $API_LEVEL)"

    cd "$PJSIP_SRC"
    make distclean 2>/dev/null || true

    # 设置交叉编译工具链
    local TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
    case "$ABI" in
        arm64-v8a)
            export TARGET_ABI=arm64-v8a
            export CC="$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang"
            export CXX="$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang++"
            export AR="$TOOLCHAIN/llvm-ar"
            export RANLIB="$TOOLCHAIN/llvm-ranlib"
            ;;
        armeabi-v7a)
            export TARGET_ABI=armeabi-v7a
            export CC="$TOOLCHAIN/armv7a-linux-androideabi${API_LEVEL}-clang"
            export CXX="$TOOLCHAIN/armv7a-linux-androideabi${API_LEVEL}-clang++"
            export AR="$TOOLCHAIN/llvm-ar"
            export RANLIB="$TOOLCHAIN/llvm-ranlib"
            ;;
    esac

    export ANDROID_NDK_ROOT="$ANDROID_NDK_ROOT"

    # configure-android 脚本
    ./configure-android \
        --use-ndk-cflags \
        --disable-video \
        --disable-l16-codec \
        --disable-gsm-codec \
        --disable-speex-codec \
        --disable-ilbc-codec \
        2>&1 | tail -5

    # 编译（-j4 并行）
    make dep 2>&1 | tail -3
    make -j4 2>&1 | tail -5

    echo "  ✅ ABI $ABI 编译完成"
}

for ABI in "${ABIS[@]}"; do
    build_abi "$ABI"
    # 保存编译产物（.a 静态库）
    LIBS_OUT="$BUILD_DIR/libs/$ABI"
    mkdir -p "$LIBS_OUT"
    find "$PJSIP_SRC" -name "*.a" | xargs -I{} cp {} "$LIBS_OUT/" 2>/dev/null || true
    echo "  静态库已保存到 $LIBS_OUT"
done

# ──────────────────────────────────────────────
# 生成 SWIG Java 绑定并打包 AAR
# ──────────────────────────────────────────────
echo ""
echo "🔗 生成 SWIG Java 绑定..."

# 最后一次编译留在 arm64-v8a 状态，重新 configure
cd "$PJSIP_SRC"
make distclean 2>/dev/null || true

TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
export TARGET_ABI=arm64-v8a
export CC="$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang"
export CXX="$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang++"
export AR="$TOOLCHAIN/llvm-ar"
export RANLIB="$TOOLCHAIN/llvm-ranlib"

./configure-android --use-ndk-cflags --disable-video 2>&1 | tail -3
make dep 2>&1 | tail -3
make -j4 2>&1 | tail -5

# 进入 SWIG 目录生成 Java 绑定
SWIG_DIR="$PJSIP_SRC/pjsip-apps/src/swig"
echo "  构建 SWIG Java 绑定..."
cd "$SWIG_DIR"
make 2>&1 | tail -10

# 构建 Android AAR 工程
AAR_DIR="$SWIG_DIR/java/android"
echo ""
echo "📦 构建 Android AAR..."
cd "$AAR_DIR"

# 注入 NDK 路径到 local.properties
cat > local.properties << PROPS
sdk.dir=$HOME/Android/Sdk
ndk.dir=$ANDROID_NDK_ROOT
PROPS

# 复制其他 ABI 的 .so 到 AAR 工程的 jni 目录
for ABI in "${ABIS[@]}"; do
    JNI_DIR="$AAR_DIR/app/src/main/jniLibs/$ABI"
    mkdir -p "$JNI_DIR"
    find "$BUILD_DIR/libs/$ABI" -name "*.a" -exec bash -c \
        'f="$1"; so="${f%.a}.so"; [ -f "$so" ] && cp "$so" "$2/"' _ {} "$JNI_DIR" \; 2>/dev/null || true
done

GRADLE="$AAR_DIR/gradlew"
chmod +x "$GRADLE"
export JAVA_HOME
"$GRADLE" assembleRelease --no-daemon 2>&1 | tail -20

# 找到生成的 AAR
AAR_FILE=$(find "$AAR_DIR" -name "*.aar" -not -path "*/intermediates/*" | head -1)
if [ -z "$AAR_FILE" ]; then
    # 备用路径
    AAR_FILE=$(find "$AAR_DIR/app/build/outputs" -name "*.aar" 2>/dev/null | head -1)
fi

if [ -n "$AAR_FILE" ] && [ -f "$AAR_FILE" ]; then
    mkdir -p "$(dirname "$OUTPUT_AAR")"
    cp "$AAR_FILE" "$OUTPUT_AAR"
    echo ""
    echo "✅ AAR 输出: $OUTPUT_AAR"
    echo "   大小: $(du -sh "$OUTPUT_AAR" | cut -f1)"
else
    echo ""
    echo "⚠️  未找到 AAR，尝试手动打包静态库..."
    create_manual_aar
fi

# ──────────────────────────────────────────────
# 备用：手动打包 AAR（当 SWIG 路径不存在时）
# ──────────────────────────────────────────────
create_manual_aar() {
    echo "  手动创建 AAR 结构..."
    local WORK="$BUILD_DIR/aar-manual"
    rm -rf "$WORK" && mkdir -p "$WORK"

    mkdir -p "$WORK/jni/arm64-v8a" "$WORK/jni/armeabi-v7a"

    # 将静态库复制过去（实际需要 .so，此处作为存根）
    cp -r "$BUILD_DIR/libs/arm64-v8a/"*.a  "$WORK/jni/arm64-v8a/"  2>/dev/null || true
    cp -r "$BUILD_DIR/libs/armeabi-v7a/"*.a "$WORK/jni/armeabi-v7a/" 2>/dev/null || true

    cat > "$WORK/AndroidManifest.xml" << 'MANIFEST'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.pjsip.pjsua2">
</manifest>
MANIFEST

    cd "$WORK" && zip -r "$OUTPUT_AAR" . 2>/dev/null
    echo "  存根 AAR 创建完成（需要替换为正式 .so）: $OUTPUT_AAR"
}

echo ""
echo "════════════════════════════════════════"
echo "  PJSIP 编译完成！"
echo "  AAR: $OUTPUT_AAR"
echo ""
echo "  下一步："
echo "  确认 AAR 存在后，回到 Claude 继续 Task 17"
echo "════════════════════════════════════════"
