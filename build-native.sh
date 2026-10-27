#!/usr/bin/env bash
# 编译 MCL 原生引导器（MSVC，静态 CRT，无控制台）
#
# 不调用 vcvars64.bat：它在受保护环境里会因为 reg.exe 被拦而漏设 INCLUDE/LIB，
# 这里直接按目录约定拼环境变量。
set -e
cd "$(dirname "$0")"

OUT="${1:-build/native/MCL.exe}"
SRC="src/native/launcher.cpp"

# --- 找 VS 安装位置 ---------------------------------------------------------
VS=""
VSWHERE="/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
if [ -x "$VSWHERE" ]; then
  RAW=$(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$VSWHERE" -latest -products '*' -property installationPath 2>/dev/null | tr -d '\r\n')
  [ -n "$RAW" ] && VS="$RAW"
fi
if [ -z "$VS" ]; then
  for c in "/c/Program Files/Microsoft Visual Studio/2022" "/d/VS2022" "/c/Program Files (x86)/Microsoft Visual Studio/2019"; do
    [ -d "$c" ] && VS="$c" && break
  done
fi
[ -z "$VS" ] && { echo "找不到 Visual Studio（需要 C++ 桌面开发工作负载）"; exit 1; }

# VS 路径在 bash 里是 /d/xxx，转成 Windows 形式给 cl.exe
WIN() { printf '%s' "$1" | sed 's|^/\([a-zA-Z]\)/|\1:/|'; }

# --- 取最新的 MSVC 工具集 ---------------------------------------------------
MSVC_ROOT=""
for d in "$VS"/VC/Tools/MSVC/*/; do
  [ -x "$d/bin/Hostx64/x64/cl.exe" ] && MSVC_ROOT="${d%/}"
done
[ -z "$MSVC_ROOT" ] && { echo "在 $VS 下找不到 MSVC x64 工具集"; exit 1; }

# --- 取最新的 Windows SDK ---------------------------------------------------
SDK_ROOT=""
for d in "/c/Program Files (x86)/Windows Kits/10/Include"/*/; do
  v=$(basename "$d")
  [ -f "$d/um/windows.h" ] && SDK_ROOT="/c/Program Files (x86)/Windows Kits/10" && SDK_VER="$v"
done
[ -z "$SDK_ROOT" ] && { echo "找不到 Windows 10/11 SDK"; exit 1; }

M=$(WIN "$MSVC_ROOT")
S=$(WIN "$SDK_ROOT")

export INCLUDE="$M\\include;$S\\Include\\$SDK_VER\\ucrt;$S\\Include\\$SDK_VER\\shared;$S\\Include\\$SDK_VER\\um;$S\\Include\\$SDK_VER\\winrt"
export LIB="$M\\lib\\x64;$S\\Lib\\$SDK_VER\\ucrt\\x64;$S\\Lib\\$SDK_VER\\um\\x64"

mkdir -p "$(dirname "$OUT")"

echo "MSVC : $M"
echo "SDK  : $SDK_VER"
echo "输出 : $OUT"

export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# 图标 + 版本信息（文件属性里要能看出来是个正经程序）
RC=""
for d in "$SDK_ROOT"/bin/*/x64/rc.exe "$SDK_ROOT"/bin/x64/rc.exe; do
  [ -x "$d" ] && RC="$d"
done
RES=""
if [ -n "$RC" ] && [ -f package/app.rc ]; then
  "$RC" /nologo /fo "$(WIN "$(pwd)/build/native/app.res")" "$(WIN "$(pwd)/package/app.rc")" >/dev/null
  RES="$(WIN "$(pwd)/build/native/app.res")"
  echo "资源 : app.res（图标 + 版本信息）"
fi

"$M/bin/Hostx64/x64/cl.exe" \
  /nologo /utf-8 /O2 /MT /EHsc /W3 /DNDEBUG \
  "$(WIN "$(pwd)/$SRC")" \
  /Fo:"$(WIN "$(pwd)/build/native/")" \
  /link /SUBSYSTEM:WINDOWS /OPT:REF /OPT:ICF \
  ${RES:+"$RES"} \
  /OUT:"$(WIN "$(pwd)/$OUT")"

ls -la "$OUT"
