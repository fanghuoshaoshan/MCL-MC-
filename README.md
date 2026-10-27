# MCL · Minecraft 启动器

用 **Java 8 + Swing + FlatLaf** 从零写的 Minecraft 启动器。核心只有一个 jar，
外加一个不到 2 MB 的单文件 `exe`（原生引导器 + 追加在尾部的 jar），不捆绑 JVM。

目标很朴素：把日常真正用得上的东西做扎实 —— 版本安装、加载器、整合包、账户、皮肤、存档，
一站式齐活，并且接上现在仍然可用的官方 / BMCLAPI 资源接口。

> 状态：个人项目，功能完整可日常使用，主要在 Windows 上开发与实测。

---

## 特性

### 版本与启动

- 官方源 / BMCLAPI 双源，可切换，失败自动回退
- 安装：客户端 jar、依赖库（`rules` 规则求值）、natives 解压、素材索引与全部 objects
  （含 pre-1.6 的 virtual / resources 映射）、日志配置
- 下载引擎：多线程、SHA1 校验、`.part` 原子落盘、按文件换源重试
- 启动：同时支持 `arguments`（1.13+）与 `minecraftArguments`（1.12-）两种参数格式、
  变量替换、`rules` 过滤、classpath 拼装、Windows 空格路径处理
- Java 管理：注册表 / PATH / 常见目录自动探测；本机版本不够时按 Mojang 官方运行时清单
  **自动下载 JRE** 到 `runtime/`（解决「只有 Java 8，玩不了 1.17+」）

### 加载器：属于版本，而不是并列的版本

这是本项目和多数启动器不太一样的地方：

- 给 `1.20.1` 装 Forge，不会在版本列表里多出一个 `1.20.1-forge-xx`，
  而是把加载器**并进 `versions/1.20.1/`**，原版目录里保存多套 profile
  （`.mcl-profiles/`：`vanilla` + `forge-47.x` + …），详情页用胶囊条切换。
- 合并是在 **JSON 层**做的：库按 `group:artifact[+classifier]` 去重、加载器版本覆盖原版，
  `arguments` 原版在前加载器在后，`inheritsFrom` 会被展开成完整 profile ——
  和官方安装器拼出来的结果逐项一致。
- 原版原文单独备份（`vanilla.json`），**永远不从被合并过的 JSON 里再叠一层**；
  重新下载原版会自动恢复当前生效的那份配置。
- 整合包实例各自私有一份加载器，互不共享；历史遗留的独立加载器目录会在启动时自动并入并清理，
  清理采用「先搬后删、目标已存在就跳过」，不会覆盖原版目录里的存档。

### 模组加载器与 OptiFine

- 一键装 **Fabric / Quilt / Forge / NeoForge**（自动补装原版，版本可选）
- Forge / NeoForge 走官方安装器流程：解析 `install_profile.json`、下载依赖库、
  按序执行 processors（extract、mcp_data、download_mojmaps、merge_mapping、jarsplitter、
  FART 重命名、binarypatcher 打补丁），1.12.2 那种把 forge jar 塞在 `maven/` 里的老格式也支持
- **OptiFine 按 Mod 处理**，不是加载器：
  - 原版 → 跑官方安装器（等效于点「Install」）
  - Forge / NeoForge → 直接作为模组放进 `mods/`（重复安装只替换、不叠加，
    避免 `modId` 冲突导致 Forge 拒绝启动）
  - Fabric / Quilt → 模组 + 自动补装 OptiFabric

### 整合包

| 格式 | 识别依据 | 处理方式 |
| --- | --- | --- |
| Modrinth `.mrpack` | `modrinth.index.json` | 读 dependencies 定加载器，逐文件下载，解压 `overrides/` |
| CurseForge `.zip` | `manifest.json` | 解析 projectID / fileID，走 CurseForge API（需自备 Key） |
| 裸压缩包 | 两者都没有 | 整包解压，可手动指定游戏版本与加载器 |

导入后生成独立实例（`versions/<包名>/` + `.mcl-isolate`），可再导出成
CurseForge / HMCL 通用的 `manifest.json + overrides/` zip。

### 模组、光影与资源包

- 本地模组管理：列出 `mods/`、启用 / 禁用（`.jar` ↔ `.jar.disabled`，不改文件内容）、删除
- Modrinth 在线搜索：模组 / 整合包 / 资源包 / 光影，按游戏版本与加载器过滤，自动补齐 required 依赖
- 更新检查：本地 jar 的 SHA1 批量反查 Modrinth 项目，比对适配当前版本的最新版
- 冲突检测：重复 `modId`、Fabric / Forge 混装、缺前置，能识别 Fabric API 这类「合集」里嵌套的子模块

### 账户

- 离线账户（官方同款 `OfflinePlayer:` UUID 算法），可选 Steve / Alex 或导入本地皮肤
- 微软正版：OAuth 设备码 → Xbox Live → XSTS → Minecraft 服务链，令牌过期自动续期
- 外置登录（皮肤站 / authlib-injector）：填 API 地址 + 账号密码，启动时自动注入 `-javaagent`
- 皮肤预览：把 64×64 皮肤拼成纸娃娃（含帽子层与披风），支持上传与本地导入；
  离线账户想在**单人游戏里**看到自己的皮肤，启动时会临时起一个本地 Yggdrasil 服务指向本地 PNG
  （连别人的离线服仍然看不到，这点不藏着）

### 存档与诊断

- 存档列表：自实现 NBT 解析 `level.dat` → 世界名 / 模式 / 版本 / 最后游玩 / 大小 / 图标
- 备份成 zip、从 zip 或直链导入、复制、删除、导出
- 服务器列表：直接读写游戏自己的 `servers.dat`，带 Server List Ping（MOTD / 人数 / 延迟 / 图标）
- 崩溃分析：非 0 退出时扫 `crash-reports/`、`latest.log`、`hs_err_pid*.log`，
  按已知特征给结论（内存不足、Java 版本过低、缺前置、Mixin 失败、OpenGL / 驱动、路径特殊字符……）

### 界面

侧边栏导航、版本列表、下载页、账户页、存档页、设置页、状态栏进度、日志窗口、
主题色与自定义壁纸（FlatLaf）。

---

## 下载 / 直接运行

仓库里不带构建产物。自己打包出来的 `package/lite/MCL.exe` 约 **1.8 MB**：

- **需要 Java 8 或更高**（推荐 21）。引导器会按顺序找：
  同级 `jre\` → `MCL_JAVA` → `JAVA_HOME` → PATH → 注册表 → 常见安装目录 → 其他启动器留下的运行时，
  找不到就弹窗给下载指引。
- 想拿到没装 Java 的机器上用：把任意一份 Java 8+ 解压到 exe 同级的 `jre\`，整个文件夹拷走即可。

首次运行会把内置 jar 解到 `%LOCALAPPDATA%\MCL\lite\payload-<戳>\`；
主目录（`.minecraft` / 配置 / 日志）就在 exe 旁边，可用 `-Dmcl.home=...` 或环境变量 `MCL_HOME` 改。

---

## 从源码构建

### 需要

- **JDK 21**（编译用，产物是 Java 8 字节码；`JAVA_HOME` 指向它）
- Git Bash / MSYS2（Windows）或任意 POSIX shell
- 依赖已经在 `lib/` 里：FlatLaf 2.6、flatlaf-extras 2.6、Gson 2.10.1
- 只有打单文件 exe 时才需要 **Visual Studio 的 C++ 桌面开发工作负载**

```bash
./build.sh        # 编译 + 打 jar + jlink 裁剪运行时，产物在 dist/
dist/run.bat      # 双击启动（用 dist/jre，不依赖系统 Java）
```

### 打单文件 exe

```bash
./build-native.sh                 # 编译原生引导器 build/native/MCL.exe（需要 MSVC）
./package.sh                      # 一键：编译引导器 + 把 jar 追加到尾部
# 产物：package/lite/MCL.exe（约 1.8 MB）
```

`package.sh` 会先把 `MCL.jar` 和 `lib/*.jar` 整理进 `build/appstage/`，
再由 `tools/pack-lite.py` 按 `[exe][索引][名字区][数据区][footer]` 的格式追加到 exe 尾部；
引导器启动时从文件末尾读 footer，把内容解到 `%LOCALAPPDATA%` 下缓存复用。

图标与版本信息在 `package/app.rc`，编译时由 `build-native.sh` 调 `rc.exe` 注入。

---

## 命令行

不开界面也能干活，适合脚本化和排障：

```
MCL                                启动图形界面
MCL --help                         全部参数

MCL --check-net                    探测官方源 / BMCLAPI 与版本解析
MCL --install <版本>               安装版本（--no-assets 跳过素材）
MCL --launch <版本> [--run]        打印 / 直接执行启动命令
MCL --fabric | --forge | --neoforge <版本>   装加载器（--loader 指定加载器版本）
MCL --loaders <版本>               列出该版本可用的加载器
MCL --optifine <版本>              装 OptiFine（--edition 指定版本）
MCL --tidy                         整理版本目录（把加载器并回它所属的版本）

MCL --modrinth <关键词>            搜索模组 / 整合包 / 资源包 / 光影
MCL --mod <slug>                   从 Modrinth 装模组（--version 指定版本）
MCL --mods                         列出当前版本的本地模组
MCL --mod-check                    检查模组重复 / 混装 / 缺依赖
MCL --mod-update                   检查模组更新
MCL --modpack <文件>               导入整合包（.mrpack / .zip）
MCL --export <文件>                导出当前版本为整合包

MCL --login-ms                     微软正版登录（设备码）
MCL --login-authlib <地址>         外置登录（--username / --password）
MCL --skin <账户名>                拉取并缓存皮肤 / 披风

MCL --crash                        分析最近一次崩溃
MCL --worlds                       列出当前版本的存档
MCL --world-backup <名称>          备份存档为 zip
MCL --world-import <zip>           导入存档压缩包
MCL --servers / --server-add / --server-ping
```

---

## 目录结构

```
├── build.sh              编译（javac --release 8 → dist/MCL.jar + jlink 运行时）
├── build-native.sh       编译原生引导器（MSVC）
├── package.sh            产出单文件 exe
├── run.bat               开发期启动脚本
├── lib/                  编译依赖（FlatLaf / Gson）
├── tools/pack-lite.py    把 jar 追加到 exe 尾部
├── package/              图标与资源脚本（app.rc / icon.ico / icon.png）
└── src/
    ├── native/launcher.cpp   原生引导器：解包 payload、找 Java、拉起 JVM
    └── main/
        ├── java/mcl/
        │   ├── MCL.java          入口与命令行
        │   ├── game/             版本、安装、启动、Java 探测、profile 合并、存档、崩溃分析
        │   ├── modpack/          整合包导入导出、加载器私有化
        │   ├── install/          Forge / NeoForge / Fabric / Quilt / OptiFine 安装
        │   ├── mod/              本地模组、Modrinth、冲突检测、中文检索
        │   ├── auth/             离线 / 微软 / 外置登录、本地皮肤服务
        │   ├── ui/               Swing 界面
        │   ├── meta/ net/ util/  版本元数据、HTTP、小工具
        │   └── config/           配置与多套 profile
        └── resources/mcl/        图标、内置皮肤、中文索引
```

---

## 几个实现上的取舍

- **只用 Java 8 的 API**：`--release 8` 编译，`mcl/util/Compat.java` 补了 `isBlank` / `strip`
  这些 Java 11 的便利方法，网络层用 `HttpURLConnection` 而不是 `HttpClient`，
  UI 用 FlatLaf 2.6 而不是需要 Java 11 的 3.x。好处是同一份 jar 在 Java 8 / 11 / 17 / 21
  上行为一致，没有「按版本分支」的代码。
- **合并 profile 走 JSON 而不是对象**：版本 JSON 里有 `logging`、`complianceLevel`、`priority`
  这类我们并不解析的字段，用对象序列化会静默丢字段；直接操作 `JsonObject` 只改该改的部分。
- **清理目录先搬后删**：判断「这个目录是不是可以删」的启发式规则一旦被上游偷偷塞了东西就会误判，
  所以宁可逐个文件搬过去、同名就跳过，再删空目录。

## 已知限制

- 主要面向 Windows；其他平台的路径与运行时逻辑没有实测
- CurseForge 搜索 / 下载需要自己在设置里填 API Key（官方已不给启动器发公共 Key）
- 离线账户的本地皮肤只在单人 / 本地存档生效，联机时对方服务端不认
- 中文检索是内置的静态映射表，词条有限，认不出的会回落英文搜索

## 第三方与声明

- 依赖 [FlatLaf](https://github.com/JFormDesigner/FlatLaf) 与 [Gson](https://github.com/google/gson)（均为 Apache-2.0）
- 本仓库**不包含**任何 Minecraft 游戏文件、素材或 Mojang 资产，运行时按官方清单自行下载
- 与 Mojang / Microsoft 无关联

## 许可证

尚未选定（上传前请自行补一份 `LICENSE`，如 MIT 或 Apache-2.0）。
