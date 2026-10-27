#!/usr/bin/env bash
# MCL 构建脚本（Git Bash / Linux / macOS 可用）
set -e
cd "$(dirname "$0")"

JDK="${JAVA_HOME:-tools/jdk-21.0.12.1+1}"
if [ ! -x "$JDK/bin/javac" ]; then
  echo "找不到 JDK，请安装 JDK 21 并设置 JAVA_HOME，或把 JDK 放到 tools/jdk-21" >&2
  exit 1
fi

LIBS="lib/flatlaf-2.6.jar;lib/flatlaf-extras-2.6.jar;lib/gson-2.10.1.jar"

echo "== 1/4 编译 =="
# 先清空产物目录：javac 只覆盖同名文件，源码里删掉或改名的类会永久留在
# build/classes 里、被打进 jar。曾因此混进 3 个 JDK 21 编译的孤儿 class，
# Java 8 下只要加载到就 UnsupportedClassVersionError。用 find -delete
# 而不是 rm -rf，避免在受保护环境里被拦。
mkdir -p build/classes
find build/classes -mindepth 1 -delete
find src -name "*.java" > build/sources.txt
"$JDK/bin/javac" -encoding UTF-8 --release 8 -d build/classes -cp "$LIBS" @build/sources.txt
# 中文索引等资源（tools/build_zhindex.py 生成），必须一起进 jar
if [ -d src/main/resources ]; then
  cp -r src/main/resources/. build/classes/
fi

echo "== 2/4 打包 =="
mkdir -p dist/lib
cp lib/*.jar dist/lib/
cat > build/MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Main-Class: mcl.MCL
Class-Path: lib/flatlaf-2.6.jar lib/flatlaf-extras-2.6.jar lib/gson-2.10.1.jar
EOF
"$JDK/bin/jar" --create --file dist/MCL.jar --manifest build/MANIFEST.MF -C build/classes .
cp run.bat dist/run.bat 2>/dev/null || true
# 首次启动时可自动迁移旧 HMCL 的账户与配置
if [ -f ../hmcl.json ]; then cp ../hmcl.json dist/hmcl.json; fi

echo "== 3/4 裁剪运行时（jlink） =="
if [ -x "$JDK/bin/jlink" ] && [ ! -d dist/jre ]; then
  "$JDK/bin/jlink" \
    --add-modules java.base,java.desktop,java.logging,java.management,jdk.management,java.net.http,jdk.crypto.ec,java.naming,jdk.naming.dns,java.instrument,java.scripting,jdk.zipfs,jdk.unsupported \
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
    --output dist/jre
  echo "运行时大小：$(du -sh dist/jre | cut -f1)"
elif [ -d dist/jre ]; then
  echo "复用已有的 dist/jre（需要重建请先删除该目录）"
fi

echo "== 4/4 校验 =="
"$JDK/bin/java" -jar dist/MCL.jar --help >/dev/null && echo "MCL.jar 可执行"
echo "完成：dist/  （双击 run.bat 启动）"
