#!/usr/bin/env python3
"""把 MCL.jar 与 lib/*.jar 追加到原生引导器尾部，产出单文件 MCL.exe。

布局（与 src/native/launcher.cpp 的 Footer/Entry 严格对应）：

    [ MCL.exe 原始字节 ]
    [ Entry 数组 ]  每条 16 字节: u32 nameLen(字符数) u32 size u64 dataOffset
    [ 名字区 ]      按 Entry 顺序连续排列的 UTF-16LE 文件名，无分隔
    [ 数据区 ]      各文件原始字节
    [ Footer ]      8 字节 magic + u64 indexOffset + u32 entryCount + u32 保留

启动时引导器从文件尾部读 Footer，发现 magic 就把内容解到
%LOCALAPPDATA%\\MCL\\lite\\payload-<stamp>\\ 再运行。
"""

import os
import struct
import sys

MAGIC = b"MCLPKG01"


def collect(app_dir):
    """返回 [(包内相对路径, 绝对路径)]，顺序固定，便于复现。"""
    items = []
    jar = os.path.join(app_dir, "MCL.jar")
    if not os.path.isfile(jar):
        sys.exit("缺少 MCL.jar：%s" % jar)
    items.append(("MCL.jar", jar))

    lib = os.path.join(app_dir, "lib")
    if os.path.isdir(lib):
        for name in sorted(os.listdir(lib)):
            if name.lower().endswith(".jar"):
                items.append(("lib/" + name, os.path.join(lib, name)))
    return items


def build(exe_path, app_dir, out_path):
    items = collect(app_dir)
    exe = open(exe_path, "rb").read()

    names = [rel.replace("\\", "/").encode("utf-16-le") for rel, _ in items]
    name_lens = [len(n) // 2 for n in names]          # 单位是字符
    blobs = [open(src, "rb").read() for _, src in items]

    index_offset = len(exe)
    entry_bytes = len(items) * 16
    names_bytes = sum(len(n) for n in names)
    data_start = index_offset + entry_bytes + names_bytes

    offsets = []
    cursor = data_start
    for blob in blobs:
        offsets.append(cursor)
        cursor += len(blob)

    out = bytearray()
    out += exe
    for (name_len, size, off) in zip(name_lens, [len(b) for b in blobs], offsets):
        out += struct.pack("<IIQ", name_len, size, off)
    for n in names:
        out += n
    for blob in blobs:
        out += blob
    out += MAGIC
    out += struct.pack("<QII", index_offset, len(items), 0)

    with open(out_path, "wb") as f:
        f.write(out)

    print("引导器   : %-28s %8.1f KB" % (os.path.basename(exe_path), len(exe) / 1024))
    for (rel, _), blob in zip(items, blobs):
        print("  + %-26s %8.1f KB" % (rel, len(blob) / 1024))
    print("单文件   : %-28s %8.1f KB  (%.2f MB)"
          % (os.path.basename(out_path), len(out) / 1024, len(out) / 1048576))
    return len(out)


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit("用法: pack-lite.py <launcher.exe> <app目录> <输出.exe>")
    build(sys.argv[1], sys.argv[2], sys.argv[3])
