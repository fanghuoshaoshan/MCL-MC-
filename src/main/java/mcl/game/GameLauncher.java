package mcl.game;

import mcl.util.Dirs;
import mcl.util.Compat;

import mcl.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** 启动子进程并把日志回传给界面。 */
public final class GameLauncher {
    /** 游戏日志上限：够了就不再写，防止某些整合包把日志刷到几个 G。 */
    private static final long MAX_GAME_LOG = 32L * 1024 * 1024;
    public interface Listener {
        void onLog(String line);

        void onExit(int code);
    }

    private GameLauncher() {}

    public static Process launch(List<String> command, Path workDir, Path logFile, Listener listener)
            throws IOException {
        Dirs.ensure(workDir);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Log.info("工作目录：" + workDir);
        Process process = pb.start();

        Thread reader = new Thread(() -> {
            // 必须是追加写。原来每一行都调 Compat.writeString（= Files.write 默认 TRUNCATE），
            // 写第二行就把第一行清掉，最后文件里永远只剩一行 —— 用户报崩溃时我们只能看到
            // 「\t... 8 more」这种栈尾，完整异常早被自己覆盖掉了。
            BufferedWriter log = null;
            if (logFile != null) {
                try {
                    log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND);
                } catch (IOException e) {
                    Log.warn("无法写入游戏日志：" + e.getMessage());
                }
            }
            long written = 0;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (listener != null) listener.onLog(line);
                    if (log != null && written < MAX_GAME_LOG) {
                        try {
                            log.write(line);
                            log.write(System.lineSeparator());
                            written += line.length() + 1;
                            // 攒够一批再落盘：整合包动辄几万行，逐行 flush 会把 IO 拖成瓶颈
                            if (written % 16384 < 200) log.flush();
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                Log.warn("读取游戏输出失败：" + e.getMessage());
            } finally {
                if (log != null) {
                    try {
                        log.flush();
                        log.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                code = -1;
            }
            if (listener != null) listener.onExit(code);
        }, "mcl-game-output");
        reader.setDaemon(true);
        reader.start();
        return process;
    }
}
