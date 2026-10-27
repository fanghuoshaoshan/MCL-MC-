package mcl.install;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * OptiFine 安装入口（在独立进程中运行）。
 *
 * <p>OptiFine 安装器 jar 里的 {@code optifine.Installer} 提供
 * {@code public static void doInstall(File)}，它就是安装器点"Install"后做的事：
 * 复制原版版本、安装 OptiFine 库、写入 launchwrapper、更新版本 JSON。
 * 这里通过反射调用，避免编译期依赖 OptiFine 的 jar。
 *
 * <p>运行方式：{@code java -cp mcl.jar;OptiFine.jar mcl.install.OptiFineHook <.minecraft 目录>}
 * 必须在 headless 模式下运行，出错时 OptiFine 会尝试弹窗，headless 会直接抛异常而不是卡住。
 */
public final class OptiFineHook {

    /** 安装成功标记，父进程据此判定结果。 */
    public static final String DONE_MARK = "MCL_OPTIFINE_DONE";

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: OptiFineHook <minecraft 目录>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        if (!dir.isDirectory()) {
            System.err.println("目录不存在: " + dir);
            System.exit(2);
        }
        try {
            Class<?> installer = Class.forName("optifine.Installer");
            Method m = installer.getMethod("doInstall", File.class);
            m.invoke(null, dir);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String msg = String.valueOf(cause.getMessage());
            System.err.println("OptiFine 安装器抛出异常: " + cause.getClass().getName() + ": " + msg);
            if ("QUIET".equals(msg)) {
                System.err.println("提示: 安装器在写 launcher_profiles.json 时失败，"
                        + "通常是 .minecraft 下缺少该文件，或原版版本不完整。");
            }
            cause.printStackTrace();
            System.exit(1);
        } catch (ReflectiveOperationException e) {
            System.err.println("无法调用 optifine.Installer.doInstall: " + e);
            e.printStackTrace();
            System.exit(1);
        }
        System.out.println(DONE_MARK);
    }
}
