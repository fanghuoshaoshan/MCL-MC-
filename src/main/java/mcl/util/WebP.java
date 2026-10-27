package mcl.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * WebP 无损（VP8L）解码器，纯 Java 实现，零外部依赖。
 *
 * <p>为什么需要它：Modrinth 的图标全部是 WebP（{@code cdn.modrinth.com/..._96.webp}），
 * 而 JDK 自带的 ImageIO 只认 JPEG / PNG / GIF / BMP，{@code ImageIO.read} 对 WebP
 * 一律返回 null —— 直接后果就是搜索结果里只有少数用 PNG 的项目能出图标。
 * CurseForge 的缩略图是 JPG/PNG 所以一直正常。
 *
 * <p>格式依据 RFC 9649 第 3 章（WebP Lossless Bitstream）。只做解码，只支持
 * 单帧 VP8L（覆盖 Modrinth / CurseForge 图标、以及绝大多数无损 WebP）。
 * 有损 VP8 不在这里处理，遇到时返回 null，调用方回退到 ImageIO。
 *
 * <p>解码顺序：位流头 → 变换列表（预测/颜色/减绿/调色板）→ 颜色缓存 →
 * 熵图（多个前缀码组）→ 像素流（字面量 / LZ77 回溯 / 颜色缓存）→ 逆变换。
 */
public final class WebP {

    private static final int TRANSFORM_PREDICTOR = 0;
    private static final int TRANSFORM_COLOR = 1;
    private static final int TRANSFORM_SUBTRACT_GREEN = 2;
    private static final int TRANSFORM_COLOR_INDEXING = 3;

    /** 19 个码长码的读取顺序（RFC 9649 §3.7.2.1.2）。 */
    private static final int[] CODE_LENGTH_ORDER = {
            17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    /**
     * 距离码 [1..120] 对应的近邻偏移，按 RFC 9649 图 20 顺序排列。
     * 121 及以上直接表示扫描线距离（distance_code - 120）。
     */
    private static final int[] DIST_X = {
            0, 1, 1, -1, 0, 2, 1, -1, 2, -2, 2, -2, 0, 3, 1, -1, 3, -3, 2, -2,
            3, -3, 0, 4, 1, -1, 4, -4, 3, -3, 2, -2, 4, -4, 0, 3, -3, 4, -4, 5,
            1, -1, 5, -5, 2, -2, 5, -5, 4, -4, 3, -3, 5, -5, 0, 6, 1, -1, 6, -6,
            2, -2, 6, -6, 4, -4, 5, -5, 3, -3, 6, -6, 0, 7, 1, -1, 5, -5, 7, -7,
            4, -4, 6, -6, 2, -2, 7, -7, 3, -3, 7, -7, 5, -5, 6, -6, 8, 4, -4, 7,
            -7, 8, 8, 6, -6, 8, 5, -5, 7, -7, 8, 6, -6, 7, -7, 8, 7, -7, 8, 8
    };

    private static final int[] DIST_Y = {
            1, 0, 1, 1, 2, 0, 2, 2, 1, 1, 2, 2, 3, 0, 3, 3, 1, 1, 3, 3,
            2, 2, 4, 0, 4, 4, 1, 1, 3, 3, 4, 4, 2, 2, 5, 4, 4, 3, 3, 0,
            5, 5, 1, 1, 5, 5, 2, 2, 4, 4, 5, 5, 3, 3, 6, 0, 6, 6, 1, 1,
            6, 6, 2, 2, 5, 5, 4, 4, 6, 6, 3, 3, 7, 0, 7, 7, 5, 5, 1, 1,
            6, 6, 4, 4, 7, 7, 2, 2, 7, 7, 3, 3, 6, 6, 5, 5, 0, 7, 7, 4,
            4, 1, 2, 6, 6, 3, 7, 7, 5, 5, 4, 7, 7, 6, 6, 5, 7, 7, 6, 7
    };

    private WebP() {}

    /** 快速判断一段字节是不是 WebP 容器（只看 RIFF/WEBP 头，不解析）。 */
    public static boolean looksLikeWebp(byte[] data) {
        return data != null && data.length >= 16
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    /**
     * 解码 WebP。
     *
     * @return 解码出的 ARGB 图；不是无损 WebP（例如有损 VP8）时返回 null
     * @throws IOException 位流损坏
     */
    public static BufferedImage decode(byte[] data) throws IOException {
        if (!looksLikeWebp(data)) return null;
        int p = 12;
        while (p + 8 <= data.length) {
            String fourcc = new String(data, p, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int len = le32(data, p + 4);
            int body = p + 8;
            if ("VP8L".equals(fourcc)) {
                if (len < 5 || body + len > data.length) throw new IOException("VP8L 块长度非法");
                return decodeVp8l(data, body, len);
            }
            if (len < 0) break;
            p = body + len + (len & 1);
        }
        // 只有有损 VP8 / 动画：交给调用方用别的办法
        return null;
    }

    private static int le32(byte[] d, int at) {
        return (d[at] & 0xff) | ((d[at + 1] & 0xff) << 8) | ((d[at + 2] & 0xff) << 16) | ((d[at + 3] & 0xff) << 24);
    }

    private static BufferedImage decodeVp8l(byte[] data, int start, int len) throws IOException {
        BitReader br = new BitReader(data, start, len);
        if (br.read(8) != 0x2f) throw new IOException("VP8L 签名不对");
        int width = br.read(14) + 1;
        int height = br.read(14) + 1;
        br.read(1); // alpha_is_used，只是提示位，解码不依赖
        int version = br.read(3);
        if (version != 0) throw new IOException("VP8L 版本号非 0：" + version);

        int[] argb = decodeStream(br, width, height, true);
        if (argb.length != width * height) throw new IOException("像素数量对不上");

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, argb, 0, width);
        return img;
    }

    // ---------------- 变换 / 图像流 ----------------

    private static final class Transform {
        int type;
        int bits;
        int xsize;   // 变换作用前的宽度（逆变换要靠它还原）
        int[] data;
    }

    /**
     * 解码一段"图像流"。level0 表示它是 ARGB 主图（可以有变换和熵图），
     * 否则是子图（预测器/颜色变化表/调色板/熵图），只能有颜色缓存和前缀码。
     */
    private static int[] decodeStream(BitReader br, int width, int height, boolean level0) throws IOException {
        List<Transform> transforms = new ArrayList<Transform>();
        int curW = width;
        int curH = height;

        if (level0) {
            while (br.read(1) != 0) {
                int type = br.read(2);
                Transform t = new Transform();
                t.type = type;
                t.xsize = curW;
                if (type == TRANSFORM_PREDICTOR || type == TRANSFORM_COLOR) {
                    t.bits = br.read(3) + 2;
                    t.data = decodeStream(br, subSample(curW, t.bits), subSample(curH, t.bits), false);
                } else if (type == TRANSFORM_COLOR_INDEXING) {
                    int numColors = br.read(8) + 1;
                    // 色表越小，打包得越紧：1~2 色 8 个一字节，17~256 色不打包
                    int bits = numColors > 16 ? 0 : (numColors > 4 ? 1 : (numColors > 2 ? 2 : 3));
                    t.bits = bits;
                    t.data = expandColorTable(decodeStream(br, numColors, 1, false), numColors);
                    // 读完之后宽度按打包比例缩小，影响后续图像流
                    curW = subSample(curW, bits);
                }
                // SUBTRACT_GREEN 没有附带数据
                transforms.add(t);
            }
        }

        int cacheBits = 0;
        if (br.read(1) != 0) {
            cacheBits = br.read(4);
            if (cacheBits < 1 || cacheBits > 11) throw new IOException("颜色缓存位数非法：" + cacheBits);
        }

        int[] entropy = null;
        int prefixBits = 0;
        int prefixW = 0;
        int numGroups = 1;
        if (level0 && br.read(1) != 0) {
            prefixBits = br.read(3) + 2;
            prefixW = subSample(curW, prefixBits);
            int prefixH = subSample(curH, prefixBits);
            entropy = decodeStream(br, prefixW, prefixH, false);
            int max = 0;
            for (int v : entropy) {
                int g = (v >> 8) & 0xffff;
                if (g > max) max = g;
            }
            numGroups = max + 1;
            if (numGroups > 0x10000) throw new IOException("熵图里的前缀码组过多：" + numGroups);
        }

        int cacheSize = cacheBits > 0 ? (1 << cacheBits) : 0;
        Huffman[][] groups = new Huffman[numGroups][5];
        for (int g = 0; g < numGroups; g++) {
            groups[g][0] = readPrefixCode(br, 256 + 24 + cacheSize);
            groups[g][1] = readPrefixCode(br, 256);
            groups[g][2] = readPrefixCode(br, 256);
            groups[g][3] = readPrefixCode(br, 256);
            groups[g][4] = readPrefixCode(br, 40);
        }

        int[] argb = decodePixels(br, curW, curH, cacheBits, groups, entropy, prefixBits, prefixW);

        // 逆变换：按读到的相反顺序来（编码时最后一个读出的最先作用）
        for (int i = transforms.size() - 1; i >= 0; i--) {
            Transform t = transforms.get(i);
            if (t.type == TRANSFORM_PREDICTOR) {
                inversePredictor(argb, curW, curH, t);
            } else if (t.type == TRANSFORM_COLOR) {
                inverseColor(argb, curW, curH, t);
            } else if (t.type == TRANSFORM_SUBTRACT_GREEN) {
                inverseSubtractGreen(argb);
            } else {
                argb = inverseColorIndexing(argb, curW, curH, t);
                curW = t.xsize;
            }
        }
        return argb;
    }

    private static int subSample(int size, int bits) {
        return (size + (1 << bits) - 1) >> bits;
    }

    // ---------------- 像素流 ----------------

    private static int[] decodePixels(BitReader br, int w, int h, int cacheBits, Huffman[][] groups,
                                      int[] entropy, int prefixBits, int prefixW) throws IOException {
        int total = w * h;
        int[] out = new int[total];
        int[] cache = null;
        int cacheShift = 0;
        if (cacheBits > 0) {
            cache = new int[1 << cacheBits];
            cacheShift = 32 - cacheBits;
        }
        boolean multi = groups.length > 1;
        Huffman[] cur = groups[0];
        int pos = 0;

        while (pos < total) {
            if (multi) {
                int y = pos / w;
                int x = pos - y * w;
                int m = (entropy[(y >> prefixBits) * prefixW + (x >> prefixBits)] >> 8) & 0xffff;
                cur = m < groups.length ? groups[m] : groups[groups.length - 1];
            }
            int s = cur[0].decode(br);
            if (s < 256) {
                int g = s;
                int r = cur[1].decode(br);
                int b = cur[2].decode(br);
                int a = cur[3].decode(br);
                int argb = (a << 24) | (r << 16) | (g << 8) | b;
                out[pos++] = argb;
                if (cache != null) cache[(argb * 0x1e35a7bd) >>> cacheShift] = argb;
            } else if (s < 280) {
                int length = decodeValue(br, s - 256);
                int distCode = decodeValue(br, cur[4].decode(br));
                int dist = distance(distCode, w);
                if (dist < 1 || dist > pos) throw new IOException("回溯距离越界：" + dist);
                int end = Math.min(pos + length, total);
                while (pos < end) {
                    int px = out[pos - dist];
                    out[pos++] = px;
                    if (cache != null) cache[(px * 0x1e35a7bd) >>> cacheShift] = px;
                }
            } else {
                int idx = s - 280;
                if (cache == null || idx >= cache.length) throw new IOException("颜色缓存下标越界：" + idx);
                int argb = cache[idx];
                out[pos++] = argb;
                cache[(argb * 0x1e35a7bd) >>> cacheShift] = argb;
            }
        }
        return out;
    }

    /** RFC 9649 §3.6.2.2：前缀码 + extra bits → length / distance_code。 */
    private static int decodeValue(BitReader br, int prefixCode) throws IOException {
        if (prefixCode < 4) return prefixCode + 1;
        int extraBits = (prefixCode - 2) >> 1;
        int offset = (2 + (prefixCode & 1)) << extraBits;
        return offset + br.read(extraBits) + 1;
    }

    /** 距离码 → 扫描线距离（RFC 9649 §3.6.2.2.1）。 */
    private static int distance(int distanceCode, int width) throws IOException {
        if (distanceCode > 120) return distanceCode - 120;
        int i = distanceCode - 1;
        if (i < 0 || i >= DIST_X.length) throw new IOException("距离码非法：" + distanceCode);
        int dist = DIST_X[i] + DIST_Y[i] * width;
        return dist < 1 ? 1 : dist;
    }

    // ---------------- 前缀码 ----------------

    private static final class Huffman {
        final int[] count;
        final int[] symbol;
        /** 只有一个符号时完全不读位（RFC 9649 §3.7.2.1）。 */
        final int single;

        Huffman(int[] count, int[] symbol, int single) {
            this.count = count;
            this.symbol = symbol;
            this.single = single;
        }

        int decode(BitReader br) throws IOException {
            if (single >= 0) return single;
            int code = 0;
            int first = 0;
            int index = 0;
            for (int len = 1; len <= 15; len++) {
                code |= br.read(1);
                int c = count[len];
                if (code - first < c) return symbol[index + (code - first)];
                index += c;
                first = (first + c) << 1;
                code <<= 1;
            }
            throw new IOException("前缀码解不出符号（位流损坏）");
        }
    }

    private static Huffman build(int[] lengths, int alphabetSize) throws IOException {
        int[] count = new int[16];
        for (int i = 0; i < alphabetSize; i++) {
            int l = lengths[i];
            if (l < 0 || l > 15) throw new IOException("码长越界：" + l);
            count[l]++;
        }
        count[0] = 0;
        int left = 1;
        int used = 0;
        for (int len = 1; len <= 15; len++) {
            left <<= 1;
            left -= count[len];
            if (left < 0) throw new IOException("前缀码过完备");
            used += count[len];
        }
        if (used == 0) {
            // 空码表（例如没有回溯引用的距离码表）：当成"永远解出符号 0"
            int[] empty = new int[]{0};
            return new Huffman(count, empty, 0);
        }
        int[] offs = new int[16];
        for (int len = 1; len < 15; len++) offs[len + 1] = offs[len] + count[len];
        int[] symbol = new int[used];
        int[] cursor = offs.clone();
        for (int i = 0; i < alphabetSize; i++) {
            if (lengths[i] != 0) symbol[cursor[lengths[i]]++] = i;
        }
        return new Huffman(count, symbol, used == 1 ? symbol[0] : -1);
    }

    private static Huffman readPrefixCode(BitReader br, int alphabetSize) throws IOException {
        int[] lengths = new int[alphabetSize];
        if (br.read(1) != 0) {
            // 简单码：只含 1~2 个符号，长度都是 1
            int numSymbols = br.read(1) + 1;
            int firstIs8Bits = br.read(1);
            int s0 = br.read(firstIs8Bits == 0 ? 1 : 8);
            if (s0 < alphabetSize) lengths[s0] = 1;
            if (numSymbols == 2) {
                int s1 = br.read(8);
                if (s1 < alphabetSize) lengths[s1] = 1;
            }
        } else {
            int numCodes = br.read(4) + 4;
            int[] clLengths = new int[19];
            for (int i = 0; i < numCodes; i++) {
                clLengths[CODE_LENGTH_ORDER[i]] = br.read(3);
            }
            Huffman codeLengthCode = build(clLengths, 19);

            int maxSymbol = alphabetSize;
            if (br.read(1) != 0) {
                int nbits = 2 + 2 * br.read(3);
                maxSymbol = 2 + br.read(nbits);
                if (maxSymbol > alphabetSize) throw new IOException("max_symbol 超出字母表范围");
            }

            int prev = 8; // 规范：16 号重复码在还没出现非零码长时重复 8
            int symbol = 0;
            // max_symbol 限制的是"读多少个码长条目"，不是"填多少个符号"：
            // 一个 16/17/18 号重复码只算一个条目，却能填 3~138 个符号。
            // 当成符号数上限会让循环提前结束、位流从此错位（高熵图必现）。
            int budget = maxSymbol;
            while (symbol < alphabetSize) {
                if (budget-- <= 0) break;
                int c = codeLengthCode.decode(br);
                if (c < 16) {
                    lengths[symbol++] = c;
                    if (c != 0) prev = c;
                } else if (c == 16) {
                    int rep = 3 + br.read(2);
                    for (int k = 0; k < rep && symbol < alphabetSize; k++) lengths[symbol++] = prev;
                } else if (c == 17) {
                    int rep = 3 + br.read(3);
                    for (int k = 0; k < rep && symbol < alphabetSize; k++) lengths[symbol++] = 0;
                } else {
                    int rep = 11 + br.read(7);
                    for (int k = 0; k < rep && symbol < alphabetSize; k++) lengths[symbol++] = 0;
                }
            }
        }
        return build(lengths, alphabetSize);
    }

    // ---------------- 逆变换 ----------------

    private static void inversePredictor(int[] argb, int w, int h, Transform t) {
        int tw = subSample(t.xsize, t.bits);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int trow = (y >> t.bits) * tw;
            for (int x = 0; x < w; x++) {
                int mode = (t.data[trow + (x >> t.bits)] >> 8) & 0xff;
                int pred;
                if (x == 0 && y == 0) {
                    pred = 0xff000000;
                } else if (y == 0) {
                    pred = argb[row + x - 1];          // 首行一律用左像素
                } else if (x == 0) {
                    pred = argb[row - w];              // 首列一律用上像素
                } else {
                    int left = argb[row + x - 1];
                    int top = argb[row - w + x];
                    int topLeft = argb[row - w + x - 1];
                    // 最右列的右上角像素取"本行最左像素"（规范特例）
                    int topRight = (x + 1 < w) ? argb[row - w + x + 1] : argb[row];
                    pred = predict(mode, left, top, topLeft, topRight);
                }
                argb[row + x] = addPixels(argb[row + x], pred);
            }
        }
    }

    private static int predict(int mode, int l, int t, int tl, int tr) {
        switch (mode) {
            case 0: return 0xff000000;
            case 1: return l;
            case 2: return t;
            case 3: return tr;
            case 4: return tl;
            case 5: return avg2(avg2(l, tr), t);
            case 6: return avg2(l, tl);
            case 7: return avg2(l, t);
            case 8: return avg2(tl, t);
            case 9: return avg2(t, tr);
            case 10: return avg2(avg2(l, tl), avg2(t, tr));
            case 11: return select(l, t, tl);
            case 12: return clampAddSubtractFull(l, t, tl);
            case 13: return clampAddSubtractHalf(avg2(l, t), tl);
            default: return l; // 14/15 不在规范里，按左像素兜底
        }
    }

    private static int avg2(int a, int b) {
        return (a & b) + (((a ^ b) & 0xfefefefe) >>> 1);
    }

    private static int select(int l, int t, int tl) {
        int pa = ch(l, 24) + ch(t, 24) - ch(tl, 24);
        int pr = ch(l, 16) + ch(t, 16) - ch(tl, 16);
        int pg = ch(l, 8) + ch(t, 8) - ch(tl, 8);
        int pb = ch(l, 0) + ch(t, 0) - ch(tl, 0);
        int pl = Math.abs(pa - ch(l, 24)) + Math.abs(pr - ch(l, 16))
                + Math.abs(pg - ch(l, 8)) + Math.abs(pb - ch(l, 0));
        int pt = Math.abs(pa - ch(t, 24)) + Math.abs(pr - ch(t, 16))
                + Math.abs(pg - ch(t, 8)) + Math.abs(pb - ch(t, 0));
        return pl < pt ? l : t;
    }

    private static int clampAddSubtractFull(int a, int b, int c) {
        return (clamp(ch(a, 24) + ch(b, 24) - ch(c, 24)) << 24)
                | (clamp(ch(a, 16) + ch(b, 16) - ch(c, 16)) << 16)
                | (clamp(ch(a, 8) + ch(b, 8) - ch(c, 8)) << 8)
                | clamp(ch(a, 0) + ch(b, 0) - ch(c, 0));
    }

    private static int clampAddSubtractHalf(int a, int b) {
        return (clamp(ch(a, 24) + (ch(a, 24) - ch(b, 24)) / 2) << 24)
                | (clamp(ch(a, 16) + (ch(a, 16) - ch(b, 16)) / 2) << 16)
                | (clamp(ch(a, 8) + (ch(a, 8) - ch(b, 8)) / 2) << 8)
                | clamp(ch(a, 0) + (ch(a, 0) - ch(b, 0)) / 2);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int ch(int argb, int shift) {
        return (argb >> shift) & 0xff;
    }

    /** 逐通道相加取低 8 位（无 clamp）。 */
    private static int addPixels(int a, int b) {
        return (((ch(a, 24) + ch(b, 24)) & 0xff) << 24)
                | (((ch(a, 16) + ch(b, 16)) & 0xff) << 16)
                | (((ch(a, 8) + ch(b, 8)) & 0xff) << 8)
                | ((ch(a, 0) + ch(b, 0)) & 0xff);
    }

    private static void inverseColor(int[] argb, int w, int h, Transform t) {
        int tw = subSample(t.xsize, t.bits);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int trow = (y >> t.bits) * tw;
            for (int x = 0; x < w; x++) {
                int cte = t.data[trow + (x >> t.bits)];
                // 规范：alpha=255，红=red_to_blue，绿=green_to_blue，蓝=green_to_red
                int greenToRed = cte & 0xff;
                int greenToBlue = (cte >> 8) & 0xff;
                int redToBlue = (cte >> 16) & 0xff;
                int p = argb[row + x];
                int a = ch(p, 24);
                int g = ch(p, 8);
                int nr = (ch(p, 16) + colorDelta(greenToRed, g)) & 0xff;
                int nb = (ch(p, 0) + colorDelta(greenToBlue, g)) & 0xff;
                nb = (nb + colorDelta(redToBlue, nr)) & 0xff;
                argb[row + x] = (a << 24) | (nr << 16) | (g << 8) | nb;
            }
        }
    }

    /** (int8 系数 × int8 通道) >> 5，3.5 定点。 */
    private static int colorDelta(int t, int c) {
        return (((byte) t) * ((byte) c)) >> 5;
    }

    private static void inverseSubtractGreen(int[] argb) {
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int g = ch(p, 8);
            argb[i] = (ch(p, 24) << 24) | (((ch(p, 16) + g) & 0xff) << 16) | (g << 8)
                    | ((ch(p, 0) + g) & 0xff);
        }
    }

    /** 色表是差分编码的：逐项叠加，再按打包位数扩展成完整索引表。 */
    private static int[] expandColorTable(int[] raw, int numColors) {
        int bits = numColors > 16 ? 0 : (numColors > 4 ? 1 : (numColors > 2 ? 2 : 3));
        int total = 1 << (8 >> bits);
        int[] table = new int[total];
        int prev = 0;
        for (int i = 0; i < numColors && i < total; i++) {
            prev = addPixels(raw[i], prev);
            table[i] = prev;
        }
        // 超出色表长度的索引按规范是透明黑（数组默认 0 即可）
        return table;
    }

    private static int[] inverseColorIndexing(int[] argb, int w, int h, Transform t) {
        int outW = t.xsize;
        int[] out = new int[outW * h];
        int bits = t.bits;
        int perByte = 1 << bits;      // 一个字节里塞了几个像素
        int bitsPerPixel = 8 >> bits; // 每个像素占几位
        int mask = (1 << bitsPerPixel) - 1;
        int[] table = t.data;
        for (int y = 0; y < h; y++) {
            int src = y * w;
            int dst = y * outW;
            for (int px = 0; px < w; px++) {
                int g = ch(argb[src + px], 8);
                for (int k = 0; k < perByte; k++) {
                    int x = px * perByte + k;
                    if (x >= outW) break;
                    int idx = (g >> (k * bitsPerPixel)) & mask;
                    out[dst + x] = idx < table.length ? table[idx] : 0;
                }
            }
        }
        return out;
    }

    // ---------------- 位读取 ----------------

    private static final class BitReader {
        private final byte[] data;
        private final int end;
        private int pos;
        private long buf;
        private int count;

        BitReader(byte[] data, int start, int length) {
            this.data = data;
            this.pos = start;
            this.end = Math.min(data.length, start + length);
        }

        int read(int n) throws IOException {
            if (n == 0) return 0;
            while (count < n) {
                if (pos >= end) throw new IOException("WebP 位流提前结束");
                buf |= ((long) (data[pos++] & 0xff)) << count;
                count += 8;
            }
            int v = (int) (buf & ((1L << n) - 1));
            buf >>>= n;
            count -= n;
            return v;
        }
    }
}
