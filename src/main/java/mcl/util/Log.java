package mcl.util;

import mcl.util.Compat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 极简日志：控制台 + 内存环形缓冲（供 UI 日志窗口）+ 文件。 */
public final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final List<String> BUFFER = new ArrayList<>();
    private static final List<Consumer<String>> LISTENERS = new ArrayList<>();
    private static Path logFile;
    private static final int MAX_BUFFER = 2000;

    private Log() {}

    /** 单个日志文件的上限，超了就滚到 mcl.log.1（只保留一份旧的）。 */
    private static final long MAX_LOG_BYTES = 4L << 20;

    public static void setFile(Path file) {
        logFile = file;
        // 日志是追加写的，所以启动时先看要不要滚动，避免无限长大
        try {
            if (file != null && Files.isRegularFile(file) && Files.size(file) > MAX_LOG_BYTES) {
                Path bak = file.resolveSibling("mcl.log.1");
                Files.deleteIfExists(bak);
                Files.move(file, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        }
    }

    public static void addListener(Consumer<String> l) {
        synchronized (LISTENERS) {
            LISTENERS.add(l);
        }
    }

    public static List<String> buffer() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    /** 当前日志文件（自检里要临时改指向再还原）。 */
    public static Path file() {
        return logFile;
    }

    public static void info(String msg) {
        write("INFO", msg);
    }

    public static void warn(String msg) {
        write("WARN", msg);
    }

    public static void error(String msg, Throwable t) {
        String extra = "";
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            extra = "\n" + sw;
        }
        write("ERROR", msg + extra);
    }

    private static void write(String level, String msg) {
        String line = "[" + LocalDateTime.now().format(TS) + "][" + level + "] " + msg;
        System.out.println(line);
        synchronized (BUFFER) {
            BUFFER.add(line);
            if (BUFFER.size() > MAX_BUFFER) BUFFER.remove(0);
        }
        if (logFile != null) {
            try {
                // 用静默版：日志自己不能反过来再写日志，否则 logs 目录被文件占用时会递归到栈溢出
                Dirs.ensureQuiet(logFile.getParent());
                // 必须追加：原来用 Files.write（默认 TRUNCATE）每写一行就把整个文件清空，
                // 所以用户发来的 mcl.log 里永远只剩最后一行，出错原因全丢了
                Files.write(logFile, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            } catch (Exception ignored) {
            }
        }
        synchronized (LISTENERS) {
            for (Consumer<String> l : LISTENERS) {
                try {
                    l.accept(line);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
