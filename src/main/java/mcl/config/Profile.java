package mcl.config;

/** 游戏配置（对应 HMCL 的"配置"概念：一套独立目录 + Java + 内存设置）。 */
public class Profile {
    public String name = "Default";
    public String gameDir = ".minecraft";
    public String selectedVersion = "";
    public String javaPath = "";
    public int maxMemory = 4096;
    public boolean autoMemory = true;
    public int width = 854;
    public int height = 480;
    public boolean fullscreen = false;
    public String extraJvmArgs = "";
    public String extraGameArgs = "";
    public String serverIp = "";
    public String serverPort = "";
    public boolean showLogWindow = true;
    /**
     * 版本隔离：每个版本使用 versions/&lt;id&gt; 作为游戏目录，mods / saves / config 各存一份。
     *
     * <p>默认开启。Minecraft 的模组、存档、按键配置都是按游戏版本走的，共用一个目录时
     * 「1.20.1 的模组装到 1.21.11 上」几乎必然崩；旧版本默认关闭只是历史遗留。
     */
    public boolean isolate = true;

    public Profile() {}

    public Profile(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
