#!/usr/bin/env bash
# 只产出一个分发形态：单文件版
#
#   package/lite/MCL.exe   约 1.9 MB   原生引导器 + 内嵌 jar，运行时找本机 Java 8+
#
# 思路和 HMCL 的 4.7 MB exe 一样：界面代码只有 350 KB，占体积的是 JVM，
# 所以不带 JVM 就能做到 2 MB 以内；代价是需要机器上已有 Java 8+（引导器的
# MIN_JAVA = 8，会自己扫注册表/PATH/常见目录，找不到会给出下载指引）。
#
# 曾经的 jpackage 自带运行时版本（package/MCL/，51 MB）已经删掉，不再构建。
#
# 需要：Visual Studio 的 C++ 桌面开发工作负载（编译原生引导器）；不编译 Java。
set -e
cd "$(dirname "$0")"

if [ ! -f dist/MCL.jar ]; then
  echo "dist/MCL.jar 不存在，先跑 ./build.sh" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# app 内容：要追加进 exe 尾部的 jar 与依赖
# ---------------------------------------------------------------------------
echo "== 1/2 准备 app 内容 =="
APPSTAGE="build/appstage"
rm -rf "$APPSTAGE"
mkdir -p "$APPSTAGE/lib"
cp dist/MCL.jar "$APPSTAGE/"
cp dist/lib/*.jar "$APPSTAGE/lib/" 2>/dev/null || true
echo "  $(du -sh "$APPSTAGE" | cut -f1)"

# ---------------------------------------------------------------------------
# 单文件版
# ---------------------------------------------------------------------------
echo "== 2/2 单文件版（原生引导器）=="
if bash build-native.sh build/native/MCL.exe >/dev/null 2>&1 && [ -f build/native/MCL.exe ]; then
  mkdir -p package/lite
  python tools/pack-lite.py build/native/MCL.exe "$APPSTAGE" package/lite/MCL.exe | tail -1
else
  echo "  失败：没有可用的 MSVC（需要 Visual Studio 的 C++ 桌面开发工作负载）" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
echo "== 结果 =="
printf "  单文件  %-10s %s\n" "$(du -h package/lite/MCL.exe | cut -f1)" "package/lite/MCL.exe"
