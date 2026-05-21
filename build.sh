#!/bin/bash
#=========================================
# QQ 上号器 - 一键构建 & 部署脚本
# 支持: macOS / Linux / Windows(Git Bash)
#=========================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
APP_NAME="QQAccountSwitcher"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[✓]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[✗]${NC} $1"; }

# ====================
# 1. 环境检查
# ====================
check_env() {
    echo ""
    echo "═══════════════════════════════════"
    echo "  QQ 上号器 - 环境检查"
    echo "═══════════════════════════════════"
    
    # Java
    if command -v java &>/dev/null; then
        JAVA_VER=$(java -version 2>&1 | head -1)
        info "Java: $JAVA_VER"
    else
        warn "Java 未安装，尝试自动安装..."
        install_java
    fi

    # ANDROID_HOME
    if [ -n "$ANDROID_HOME" ]; then
        info "ANDROID_HOME: $ANDROID_HOME"
    elif [ -d "$HOME/Library/Android/sdk" ]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
        info "ANDROID_HOME: $ANDROID_HOME"
    elif [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
        info "ANDROID_HOME: $ANDROID_HOME"
    else
        warn "未找到 Android SDK，尝试自动安装..."
        install_sdk
    fi

    # ADB
    if command -v adb &>/dev/null; then
        info "ADB: $(which adb)"
    elif [ -f "$ANDROID_HOME/platform-tools/adb" ]; then
        export PATH="$ANDROID_HOME/platform-tools:$PATH"
        info "ADB: $ANDROID_HOME/platform-tools/adb"
    elif [ -f "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
        info "ADB: $HOME/Library/Android/sdk/platform-tools/adb"
    else
        warn "ADB 未找到，跳过自动安装"
    fi

    # Gradle Wrapper
    if [ -f "$PROJECT_DIR/gradlew" ]; then
        info "Gradle Wrapper 已存在"
    else
        info "生成 Gradle Wrapper..."
        cd "$PROJECT_DIR"
        if command -v gradle &>/dev/null; then
            gradle wrapper --gradle-version 8.2
        else
            # 下载 wrapper
            mkdir -p gradle/wrapper
            curl -sL "https://services.gradle.org/distributions/gradle-8.2-bin.zip" -o /tmp/gradle-8.2-bin.zip
            warn "手动下载 Gradle 并设置 wrapper（或从已安装 Gradle 的项目复制 gradlew）"
        fi
    fi
}

# ====================
# 2. 安装 Java (macOS)
# ====================
install_java() {
    if [[ "$OSTYPE" == "darwin"* ]]; then
        warn "正在通过 Homebrew 安装 OpenJDK 17..."
        brew install openjdk@17 2>&1 | tail -3
        sudo ln -sfn $(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
        export PATH="$(brew --prefix)/opt/openjdk@17/bin:$PATH"
        info "Java 安装完成"
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        warn "正在安装 OpenJDK 17..."
        sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-17-jdk 2>&1 | tail -3
        info "Java 安装完成"
    else
        error "请手动安装 JDK 17: https://adoptium.net/"
        exit 1
    fi
}

# ====================
# 3. 安装 Android SDK (macOS)
# ====================
install_sdk() {
    if [[ "$OSTYPE" == "darwin"* ]]; then
        warn "正在通过 Homebrew 安装 Android SDK..."
        brew install --cask android-commandlinetools android-platform-tools 2>&1 | tail -3
        export ANDROID_HOME="$HOME/Library/Android/sdk"
        mkdir -p "$ANDROID_HOME"
        yes | sdkmanager --sdk_root="$ANDROID_HOME" \
            "platforms;android-34" \
            "build-tools;34.0.0" \
            "platform-tools" 2>&1 | tail -5
        info "Android SDK 安装完成"
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        warn "正在安装 Android SDK..."
        mkdir -p "$HOME/Android/Sdk"
        cd /tmp
        curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-10406996_latest.zip" -o cmdline-tools.zip
        unzip -q cmdline-tools.zip
        mkdir -p "$HOME/Android/Sdk/cmdline-tools"
        mv cmdline-tools "$HOME/Android/Sdk/cmdline-tools/latest"
        export ANDROID_HOME="$HOME/Android/Sdk"
        yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
            "platforms;android-34" \
            "build-tools;34.0.0" \
            "platform-tools" 2>&1 | tail -5
        info "Android SDK 安装完成"
    fi
}

# ====================
# 4. 构建 APK
# ====================
build_apk() {
    echo ""
    echo "═══════════════════════════════════"
    echo "  构建 APK"
    echo "═══════════════════════════════════"
    
    cd "$PROJECT_DIR"
    
    if [ -f "./gradlew" ]; then
        chmod +x gradlew
        info "开始构建 Debug APK（可能需要几分钟）..."
        ./gradlew assembleDebug 2>&1 | tail -20
    else
        # 直接使用系统 Gradle 构建
        gradle assembleDebug 2>&1 | tail -20
    fi

    APK_PATH=$(find "$PROJECT_DIR/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | head -1)
    
    if [ -f "$APK_PATH" ]; then
        info "APK 构建成功: $APK_PATH"
        echo ""
        echo "    文件大小: $(ls -lh "$APK_PATH" | awk '{print $5}')"
        echo "    输出路径: $APK_PATH"
    else
        error "APK 构建失败，请检查错误信息"
        exit 1
    fi
}

# ====================
# 5. 安装到模拟器/设备
# ====================
install_to_device() {
    echo ""
    echo "═══════════════════════════════════"
    echo "  检查连接设备"
    echo "═══════════════════════════════════"
    
    # 先尝试连接 LDPlayer
    echo "尝试连接 雷电模拟器..."
    adb connect 127.0.0.1:5555 2>/dev/null || true
    adb connect 127.0.0.1:7555 2>/dev/null || true
    
    DEVICES=$(adb devices | grep -v "List" | grep "device$" | awk '{print $1}')
    DEVICE_COUNT=$(echo "$DEVICES" | grep -v "^$" | wc -l | tr -d ' ')
    
    if [ "$DEVICE_COUNT" -eq 0 ]; then
        warn "未检测到连接的设备/模拟器"
        warn "请手动安装 APK"
        return
    fi
    
    echo "找到 $DEVICE_COUNT 台设备:"
    echo "$DEVICES"
    echo ""
    
    APK_PATH=$(find "$PROJECT_DIR/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | head -1)
    if [ -z "$APK_PATH" ]; then
        warn "APK 文件不存在，跳过安装"
        return
    fi
    
    for DEVICE in $DEVICES; do
        info "安装到: $DEVICE"
        adb -s "$DEVICE" install -r "$APK_PATH" 2>&1 | tail -3
        info "已安装到 $DEVICE"
        
        # 启动 App
        info "启动 App..."
        adb -s "$DEVICE" shell am start -n "com.qqswitcher.demo/.MainActivity"
    done
    
    echo ""
    info "安装完成！请查看模拟器屏幕"
}

# ====================
# 6. 一键三连
# ====================
all_in_one() {
    check_env
    build_apk
    echo ""
    echo "═══════════════════════════════════"
    echo "  ✅ 构建完成"
    echo "═══════════════════════════════════"
    echo ""
    echo "APK 位置:"
    find "$PROJECT_DIR/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null
    echo ""
    echo "接下来：将此 APK 拖入雷电模拟器即可安装"
    echo "或者运行: bash build.sh install"
}

# ====================
# Main
# ====================
case "${1:-build}" in
    build)
        all_in_one
        ;;
    install)
        install_to_device
        ;;
    env)
        check_env
        ;;
    *)
        echo "用法: bash build.sh [命令]"
        echo "   build     检查环境 + 构建 APK（默认）"
        echo "   install   安装到连接的模拟器/设备"
        echo "   env       仅检查环境"
        ;;
esac
