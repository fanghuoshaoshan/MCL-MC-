// ============================================================================
//  MCL native launcher
//
//  职责（只有一个）：找到可用的 Java 8+，然后把它叫起来跑 MCL.jar。
//  GUI 全部还在 Java 里，这里不重复实现任何界面。
//
//  两种分发形态共用一个二进制：
//    1) 单文件：exe 尾部追加了 [index][payload][footer]，启动时解到
//       %LOCALAPPDATA%\MCL\lite\<stamp>\ 再运行。双击即用，约 2 MB。
//    2) 目录版：exe 旁边直接放 MCL.jar 与 lib\*.jar，不追加任何东西。
//
//  Java 搜索顺序：
//    自带 jre\  ->  MCL_JAVA  ->  JAVA_HOME  ->  PATH  ->
//    注册表（JavaSoft / Adoptium / Microsoft / Zulu / Corretto）  ->
//    常见安装目录  ->  其他启动器留下的运行时
//
//  编译见 build-native.sh（MSVC，静态 CRT，/SUBSYSTEM:WINDOWS）。
// ============================================================================

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <shellapi.h>
#include <string>
#include <vector>
#include <algorithm>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "advapi32.lib")

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------
// 启动器自身只用到 Java 8 的 API（编译时 --release 8），所以门槛就是 8：
// 机器上只要有任意一个能用的 Java 就能开，不会因为"只装了 8/17"就打不开。
static const int MIN_JAVA = 8;

static const char kMagic[8] = { 'M', 'C', 'L', 'P', 'K', 'G', '0', '1' };

#pragma pack(push, 1)
struct Footer {
    char  magic[8];
    unsigned long long indexOffset;
    unsigned int entryCount;
    unsigned int reserved;
};
struct Entry {
    unsigned int nameLen;
    unsigned int size;
    unsigned long long dataOffset;
};
#pragma pack(pop)

// ---------------------------------------------------------------------------
// 全局状态
// ---------------------------------------------------------------------------
static std::wstring g_exeDir;      // 以反斜杠结尾
static std::wstring g_exePath;
static std::wstring g_java;        // 检测用的 java.exe
static std::wstring g_javaLaunch;  // 启动用的 javaw.exe（没有则回退 java.exe）
static std::wstring g_javaVer;
static std::wstring g_appDir;      // 放着 MCL.jar 的目录，以反斜杠结尾

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------
static bool fileExists(const std::wstring& p) {
    DWORD a = GetFileAttributesW(p.c_str());
    return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY);
}

static bool dirExists(const std::wstring& p) {
    DWORD a = GetFileAttributesW(p.c_str());
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
}

static std::wstring envOf(const wchar_t* name) {
    wchar_t buf[32768];
    DWORD n = GetEnvironmentVariableW(name, buf, 32768);
    if (n == 0 || n >= 32768) return std::wstring();
    return std::wstring(buf, n);
}

static std::wstring joinPath(const std::wstring& a, const std::wstring& b) {
    if (a.empty()) return b;
    if (a.back() == L'\\' || a.back() == L'/') return a + b;
    return a + L"\\" + b;
}

static std::wstring quote(const std::wstring& s) {
    return L"\"" + s + L"\"";
}

static void ensureDir(const std::wstring& path) {
    if (path.empty()) return;
    std::wstring cur;
    for (size_t i = 0; i < path.size(); ++i) {
        cur.push_back(path[i]);
        bool cut = (path[i] == L'\\') || (i + 1 == path.size());
        if (!cut) continue;
        if (cur.size() >= 2 && cur[1] == L':' && cur.size() <= 3) continue; // "D:\"
        CreateDirectoryW(cur.c_str(), nullptr);
    }
}

// 引导器自己的日志：<exe>\logs\launcher.log（UTF-8）。
// 界面日志在 Java 侧的 logs\mcl.log；这里只记"找了哪些 Java、选了哪个、起没起来"，
// 出问题时把这两个文件一起看就够定位了。
static void logLine(const std::wstring& msg) {
    if (g_exeDir.empty()) return;
    std::wstring dir = joinPath(g_exeDir, L"logs");
    static bool dirReady = false;
    if (!dirReady) {
        ensureDir(dir);
        dirReady = true;
    }
    HANDLE h = CreateFileW(joinPath(dir, L"launcher.log").c_str(), FILE_APPEND_DATA,
                           FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return;

    SYSTEMTIME st;
    GetLocalTime(&st);
    char head[64];
    int n = sprintf_s(head, sizeof(head), "[%02d:%02d:%02d] ", st.wHour, st.wMinute, st.wSecond);
    DWORD w = 0;
    WriteFile(h, head, (DWORD)n, &w, nullptr);

    int need = WideCharToMultiByte(CP_UTF8, 0, msg.c_str(), -1, nullptr, 0, nullptr, nullptr);
    if (need > 1) {
        std::string utf8((size_t)need, '\0');
        WideCharToMultiByte(CP_UTF8, 0, msg.c_str(), -1, &utf8[0], need, nullptr, nullptr);
        WriteFile(h, utf8.c_str(), (DWORD)strlen(utf8.c_str()), &w, nullptr);
    }
    WriteFile(h, "\r\n", 2, &w, nullptr);
    CloseHandle(h);
}

// 把 argv[0] 之后的部分原样拿出来，用于透传参数
static std::wstring rawArgs() {
    const wchar_t* c = GetCommandLineW();
    if (!c) return std::wstring();
    if (*c == L'"') {
        ++c;
        while (*c && *c != L'"') ++c;
        if (*c == L'"') ++c;
    } else {
        while (*c && *c != L' ' && *c != L'\t') ++c;
    }
    while (*c == L' ' || *c == L'\t') ++c;
    return std::wstring(c);
}

// ---------------------------------------------------------------------------
// Java 探测
// ---------------------------------------------------------------------------
static bool javaMajorOf(const std::wstring& exe, int& major, std::wstring& full) {
    SECURITY_ATTRIBUTES sa;
    ZeroMemory(&sa, sizeof(sa));
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;

    HANDLE rd = nullptr, wr = nullptr;
    if (!CreatePipe(&rd, &wr, &sa, 0)) return false;
    SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);

    STARTUPINFOW si;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.hStdOutput = wr;
    si.hStdError = wr;
    si.hStdInput = nullptr;
    si.wShowWindow = SW_HIDE;

    PROCESS_INFORMATION pi;
    ZeroMemory(&pi, sizeof(pi));

    std::wstring cmd = quote(exe) + L" -version";
    std::vector<wchar_t> mutableCmd(cmd.begin(), cmd.end());
    mutableCmd.push_back(0);

    BOOL ok = CreateProcessW(nullptr, mutableCmd.data(), nullptr, nullptr, TRUE,
                             CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi);
    CloseHandle(wr);
    if (!ok) {
        CloseHandle(rd);
        return false;
    }

    std::string out;
    char tmp[4096];
    DWORD n = 0;
    while (ReadFile(rd, tmp, sizeof(tmp), &n, nullptr) && n > 0) {
        out.append(tmp, n);
        if (out.size() > 65536) break; // 正常只有几行，防御性上限
    }
    WaitForSingleObject(pi.hProcess, 8000);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    CloseHandle(rd);

    // "openjdk version "21.0.12" 2025-07-15 LTS"
    size_t p = out.find("version \"");
    if (p == std::string::npos) return false;
    p += 9;
    size_t e = out.find('"', p);
    if (e == std::string::npos) return false;
    std::string v = out.substr(p, e - p);
    full.assign(v.begin(), v.end());

    int a = atoi(v.c_str());
    if (a == 1) { // 1.8.0_411 那种老编号
        size_t d = v.find('.');
        a = (d == std::string::npos) ? 1 : atoi(v.c_str() + d + 1);
    }
    major = a;
    return true;
}

// 给定 java.exe 路径，合格就记下来。
//   strong = true  ：显式来源（自带 jre\、MCL_JAVA、JAVA_HOME），第一个就用，不再挑
//   strong = false ：从 PATH / 注册表 / 常见目录扫到的，取版本最高的那个
static std::vector<std::wstring> g_tried;
static int g_bestMajor = 0;          // 见过最高的主版本号，用于弹窗里说清"你装的是几"
static std::wstring g_bestVer;
static int g_javaMajor = 0;
static bool g_locked = false;        // 已经由显式来源定下来

static bool consider(const std::wstring& javaExe, bool strong) {
    if (g_locked) return true;
    if (javaExe.empty() || !fileExists(javaExe)) return false;

    // 同一份 java.exe 可能从注册表、PATH、常见目录各命中一次，别重复 spawn 检测
    for (const std::wstring& t : g_tried) {
        if (_wcsicmp(t.c_str(), javaExe.c_str()) == 0) return false;
    }
    g_tried.push_back(javaExe);

    int major = 0;
    std::wstring ver;
    if (!javaMajorOf(javaExe, major, ver)) return false;

    if (major > g_bestMajor) {
        g_bestMajor = major;
        g_bestVer = ver;
    }

    if (major < MIN_JAVA) {
        logLine(L"跳过 " + javaExe + L"（是 Java " + ver + L"，需要 " + std::to_wstring(MIN_JAVA) + L"+）");
        return false;
    }

    // 非显式来源里挑版本最高的：一台机器常同时装着 8 和 17，跑新的总没错
    if (!g_java.empty() && major <= g_javaMajor && !strong) return false;

    g_java = javaExe;
    g_javaVer = ver;
    g_javaMajor = major;
    if (strong) g_locked = true;
    logLine(L"采用 " + javaExe + L"（Java " + ver + L"）");

    // 启动优先用同目录的 javaw.exe：它没有控制台，不会闪黑框
    std::wstring dir = javaExe.substr(0, javaExe.find_last_of(L"\\/") + 1);
    std::wstring javaw = dir + L"javaw.exe";
    g_javaLaunch = fileExists(javaw) ? javaw : javaExe;
    return true;
}

// 扫描 parent 下的一层子目录，找 <sub>\bin\java.exe
static void scanSubdirs(const std::wstring& parent, const std::wstring& tail) {
    if (g_locked) return;
    if (!dirExists(parent)) return;

    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW((joinPath(parent, L"*")).c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return;
    do {
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        if (fd.cFileName[0] == L'.') continue;
        std::wstring base = joinPath(parent, fd.cFileName);
        consider(joinPath(joinPath(base, tail), L"bin\\java.exe"), false);
    } while (FindNextFileW(h, &fd));
    FindClose(h);
}

// 扫注册表某个根下的所有子键，取 valueName 指向的 JavaHome
static void scanRegistry(HKEY root, const wchar_t* sub, const wchar_t* valueName) {
    if (g_locked) return;

    HKEY key;
    if (RegOpenKeyExW(root, sub, 0, KEY_READ | KEY_WOW64_64KEY, &key) != ERROR_SUCCESS) {
        if (RegOpenKeyExW(root, sub, 0, KEY_READ, &key) != ERROR_SUCCESS) return;
    }

    wchar_t name[512];
    DWORD index = 0;
    while (true) {
        DWORD len = 512;
        if (RegEnumKeyExW(key, index++, name, &len, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS)
            break;

        HKEY child;
        if (RegOpenKeyExW(key, name, 0, KEY_READ, &child) != ERROR_SUCCESS) continue;

        wchar_t val[1024];
        DWORD sz = sizeof(val);
        DWORD type = 0;
        if (RegQueryValueExW(child, valueName, nullptr, &type, (BYTE*)val, &sz) == ERROR_SUCCESS &&
            (type == REG_SZ || type == REG_EXPAND_SZ)) {
            // 保证 null 结尾
            size_t chars = sz / sizeof(wchar_t);
            val[(chars < 1023) ? chars : 1023] = 0;
            std::wstring home(val);
            consider(joinPath(home, L"bin\\java.exe"), false);
        }
        RegCloseKey(child);
    }
    RegCloseKey(key);
}

// ---------------------------------------------------------------------------
// 单文件模式：从自身尾部解出 MCL.jar 与 lib
// ---------------------------------------------------------------------------
static bool readAt(HANDLE h, unsigned long long offset, void* buf, DWORD size) {
    LARGE_INTEGER li;
    li.QuadPart = (LONGLONG)offset;
    if (!SetFilePointerEx(h, li, nullptr, FILE_BEGIN)) return false;
    DWORD got = 0;
    return ReadFile(h, buf, size, &got, nullptr) && got == size;
}

static bool writeAll(const std::wstring& path, const std::vector<char>& data) {
    HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    BOOL ok = WriteFile(h, data.data(), (DWORD)data.size(), &written, nullptr);
    CloseHandle(h);
    return ok && written == (DWORD)data.size();
}

// 若 exe 自带 payload，则解包到 %LOCALAPPDATA%\MCL\lite\payload-<stamp>，返回该目录
static bool extractPayload(std::wstring& outDir, std::wstring& err) {
    HANDLE h = CreateFileW(g_exePath.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;

    LARGE_INTEGER size;
    if (!GetFileSizeEx(h, &size) || size.QuadPart < (LONGLONG)sizeof(Footer)) {
        CloseHandle(h);
        return false;
    }

    Footer f;
    if (!readAt(h, (unsigned long long)size.QuadPart - sizeof(Footer), &f, sizeof(Footer))) {
        CloseHandle(h);
        return false;
    }
    if (memcmp(f.magic, kMagic, 8) != 0) { // 目录版，没有 payload
        CloseHandle(h);
        return false;
    }
    if (f.entryCount == 0 || f.entryCount > 4096 ||
        f.indexOffset >= (unsigned long long)size.QuadPart) {
        CloseHandle(h);
        err = L"包索引损坏";
        return false;
    }

    std::vector<Entry> entries(f.entryCount);
    DWORD bytes = (DWORD)(f.entryCount * sizeof(Entry));
    if (!readAt(h, f.indexOffset, entries.data(), bytes)) {
        CloseHandle(h);
        err = L"无法读取包索引";
        return false;
    }

    // 用 exe 大小 + 索引位置做戳，exe 换过就重新解
    wchar_t stamp[64];
    // 注意：wsprintf 不认 %llx，要用 %I64x
    wsprintfW(stamp, L"%I64x-%I64x", (unsigned long long)size.QuadPart,
              (unsigned long long)f.indexOffset);

    std::wstring base = joinPath(envOf(L"LOCALAPPDATA"), L"MCL\\lite");
    std::wstring dir = joinPath(base, std::wstring(L"payload-") + stamp);
    ensureDir(base);

    std::wstring marker = joinPath(dir, L".ok");
    if (!fileExists(marker)) {
        ensureDir(dir);
        for (unsigned int i = 0; i < f.entryCount; ++i) {
            const Entry& e = entries[i];
            if (e.nameLen == 0 || e.nameLen > 260 || e.size > (64u << 20)) {
                err = L"包条目非法";
                CloseHandle(h);
                return false;
            }
            std::wstring rel;
            std::vector<wchar_t> nameBuf(e.nameLen + 1);

            // 文件名集中在 Entry 数组之后的连续区域，按前面的长度累加求偏移。
            // nameLen 记的是 UTF-16 字符数，换算成字节要 ×2。
            unsigned long long nameArea = f.indexOffset + bytes;
            unsigned long long nameOff = nameArea;
            for (unsigned int k = 0; k < i; ++k) nameOff += (unsigned long long)entries[k].nameLen * 2ULL;
            if (!readAt(h, nameOff, nameBuf.data(), e.nameLen * 2)) {
                err = L"包文件名读取失败";
                CloseHandle(h);
                return false;
            }
            nameBuf[e.nameLen] = 0;
            rel.assign(nameBuf.data(), e.nameLen);

            // 只接受安全的相对路径
            if (rel.find(L"..") != std::wstring::npos || rel[0] == L'\\' || rel[0] == L'/') {
                err = L"包内路径不安全：" + rel;
                CloseHandle(h);
                return false;
            }
            for (wchar_t& ch : rel) if (ch == L'/') ch = L'\\';

            std::vector<char> data(e.size);
            if (e.size && !readAt(h, e.dataOffset, data.data(), e.size)) {
                err = L"包数据读取失败：" + rel;
                CloseHandle(h);
                return false;
            }

            std::wstring target = joinPath(dir, rel);
            size_t slash = target.find_last_of(L'\\');
            if (slash != std::wstring::npos) ensureDir(target.substr(0, slash));
            if (!writeAll(target, data)) {
                err = L"写入失败：" + target;
                CloseHandle(h);
                return false;
            }
        }
        writeAll(marker, std::vector<char>{ '1' });
    }

    CloseHandle(h);
    outDir = dir;
    return true;
}

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------
static bool collectJars(const std::wstring& libDir, std::vector<std::wstring>& out) {
    if (!dirExists(libDir)) return false;
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(joinPath(libDir, L"*.jar").c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return false;
    do {
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
        out.push_back(joinPath(libDir, fd.cFileName));
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    std::sort(out.begin(), out.end());
    return true;
}

static std::wstring buildClassPath() {
    std::wstring cp = joinPath(g_appDir, L"MCL.jar");
    std::vector<std::wstring> jars;
    collectJars(joinPath(g_appDir, L"lib"), jars);
    for (const std::wstring& j : jars) {
        cp += L";";
        cp += j;
    }
    return cp;
}

static int launch() {
    std::wstring cp = buildClassPath();
    if (!fileExists(joinPath(g_appDir, L"MCL.jar"))) {
        MessageBoxW(nullptr, L"找不到 MCL.jar，安装包不完整。请重新解压整个目录。",
                    L"MCL 启动器", MB_OK | MB_ICONERROR);
        return 2;
    }

    // 主目录：默认 exe 所在目录；MCL_HOME 可以覆盖（放在移动硬盘、多开等场景）
    std::wstring homeDir = envOf(L"MCL_HOME");
    if (homeDir.empty()) {
        homeDir = g_exeDir;
        while (!homeDir.empty() && (homeDir.back() == L'\\' || homeDir.back() == L'/')) homeDir.pop_back();
    }

    std::wstring cmd = quote(g_javaLaunch);
    cmd += L" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dmcl.home=";
    cmd += quote(homeDir);
    cmd += L" -cp ";
    cmd += quote(cp);
    cmd += L" mcl.MCL";

    std::wstring extra = rawArgs();
    if (!extra.empty()) {
        cmd += L" ";
        cmd += extra;
    }

    logLine(L"主目录 " + g_exeDir);
    logLine(L"启动 " + cmd);

    STARTUPINFOW si;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);

    PROCESS_INFORMATION pi;
    ZeroMemory(&pi, sizeof(pi));

    std::vector<wchar_t> mutableCmd(cmd.begin(), cmd.end());
    mutableCmd.push_back(0);

    if (!CreateProcessW(nullptr, mutableCmd.data(), nullptr, nullptr, FALSE,
                        CREATE_UNICODE_ENVIRONMENT, nullptr, g_appDir.c_str(), &si, &pi)) {
        DWORD err = GetLastError();
        logLine(L"启动失败，错误码 " + std::to_wstring(err));
        wchar_t msg[512];
        wsprintfW(msg, L"启动失败（错误码 %lu）。\n\nJava：%s\n命令：%s",
                  err, g_javaLaunch.c_str(), cmd.c_str());
        MessageBoxW(nullptr, msg, L"MCL 启动器", MB_OK | MB_ICONERROR);
        return 3;
    }

    // 跟着子进程一起活着，任务栏里只有一份
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    logLine(L"进程退出，退出码 " + std::to_wstring((unsigned long)code));
    return (int)code;
}

// ---------------------------------------------------------------------------
// 找不到 Java 时的引导
// ---------------------------------------------------------------------------
static int noJava() {
    wchar_t msg[1024];
    wsprintfW(msg,
              L"没有找到 Java %d 或更高版本。\n\n"
              L"这个启动器不自带运行时（所以只有约 2 MB）。可以任选一种：\n\n"
              L"  1. 点「是」，打开 Adoptium 官方下载页，装任意一个 JRE/JDK 后重开；\n"
              L"  2. 把任意一个 Java 8+ 解压到本程序旁边的 jre\\ 目录里；\n"
              L"  3. 设置环境变量 MCL_JAVA 指向 java.exe 的完整路径。\n\n"
              L"已经装了 Java 还是报这个？可能版本低于 %d，或者只有 Java 8。",
              MIN_JAVA, MIN_JAVA);

    if (g_bestMajor > 0) {
        // 已经扫到过（但版本不够），把实际版本写进提示，省得用户反复折腾
        std::wstring more = L"\n\n检测到本机最高是 Java " + g_bestVer +
                            L"（版本号 " + std::to_wstring(g_bestMajor) +
                            L"），低于要求的 " + std::to_wstring(MIN_JAVA) + L"。";
        std::wstring full = msg;
        full += more;
        int r2 = MessageBoxW(nullptr, full.c_str(), L"MCL 启动器 — 缺少 Java",
                             MB_YESNO | MB_ICONWARNING);
        if (r2 == IDYES) {
            ShellExecuteW(nullptr, L"open",
                          L"https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64&package=jre",
                          nullptr, nullptr, SW_SHOWNORMAL);
        }
        return 4;
    }

    int r = MessageBoxW(nullptr, msg, L"MCL 启动器 — 缺少 Java",
                        MB_YESNO | MB_ICONWARNING);
    if (r == IDYES) {
        ShellExecuteW(nullptr, L"open",
                      L"https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64&package=jre",
                      nullptr, nullptr, SW_SHOWNORMAL);
    }
    return 4;
}

// ---------------------------------------------------------------------------
// 入口
// ---------------------------------------------------------------------------
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE prev, PWSTR cmdLine, int show) {
    wchar_t buf[MAX_PATH * 2];
    DWORD n = GetModuleFileNameW(nullptr, buf, MAX_PATH * 2);
    g_exePath.assign(buf, n);
    size_t slash = g_exePath.find_last_of(L'\\');
    g_exeDir = (slash == std::wstring::npos) ? std::wstring() : g_exePath.substr(0, slash + 1);

    logLine(L"=== 启动 " + g_exePath + L" ===");

    // 形态一：自带 payload（单文件）
    std::wstring payloadDir;
    std::wstring err;
    bool embedded = extractPayload(payloadDir, err);
    if (embedded) {
        g_appDir = payloadDir;
        if (g_appDir.empty() || g_appDir.back() != L'\\') g_appDir += L"\\";
        logLine(L"单文件模式，内容解到 " + payloadDir);
    } else {
        if (!err.empty()) {
            logLine(L"解包失败：" + err);
            MessageBoxW(nullptr, (L"解包失败：" + err).c_str(), L"MCL 启动器", MB_OK | MB_ICONERROR);
            return 2;
        }
        g_appDir = g_exeDir; // 形态二：目录版
        logLine(L"目录模式，app 目录 " + g_appDir);
    }

    // Java 搜索
    consider(joinPath(g_exeDir, L"jre\\bin\\java.exe"), true);           // 便携自带
    consider(joinPath(g_appDir, L"jre\\bin\\java.exe"), true);

    std::wstring custom = envOf(L"MCL_JAVA");
    if (g_java.empty() && !custom.empty()) {
        consider(dirExists(custom) ? joinPath(custom, L"bin\\java.exe") : custom, true);
    }

    std::wstring home = envOf(L"JAVA_HOME");
    if (!home.empty()) consider(joinPath(home, L"bin\\java.exe"), true);

    if (g_java.empty()) {
        wchar_t found[MAX_PATH * 2];
        if (SearchPathW(nullptr, L"java.exe", nullptr, MAX_PATH * 2, found, nullptr))
            consider(found, false);
    }

    if (g_java.empty()) {
        scanRegistry(HKEY_LOCAL_MACHINE, L"SOFTWARE\\JavaSoft\\Java Development Kit", L"JavaHome");
        scanRegistry(HKEY_LOCAL_MACHINE, L"SOFTWARE\\JavaSoft\\Java Runtime Environment", L"JavaHome");
        scanRegistry(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Eclipse Adoptium\\JDK", L"Path");
        scanRegistry(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Eclipse Adoptium\\JRE", L"Path");
        scanRegistry(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\JDK", L"Path");
        scanRegistry(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Azul Systems\\Zulu", L"InstallationPath");
        scanRegistry(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Amazon Corretto", L"InstallationPath");
    }

    if (g_java.empty()) {
        std::wstring pf = envOf(L"ProgramFiles");
        std::wstring pf86 = envOf(L"ProgramFiles(x86)");
        std::wstring local = envOf(L"LOCALAPPDATA");
        std::wstring appdata = envOf(L"APPDATA");
        std::wstring userProfile = envOf(L"USERPROFILE");

        scanSubdirs(joinPath(pf, L"Eclipse Adoptium"), L"");
        scanSubdirs(joinPath(pf, L"Java"), L"");
        scanSubdirs(joinPath(pf, L"Microsoft"), L"");
        scanSubdirs(joinPath(pf, L"Zulu"), L"");
        scanSubdirs(joinPath(pf, L"Amazon Corretto"), L"");
        scanSubdirs(joinPath(pf86, L"Java"), L"");

        // 启动器自己下载过的、以及其他启动器留下的
        consider(joinPath(local, L"MCL\\jre21\\bin\\java.exe"), false);
        scanSubdirs(joinPath(appdata, L".hmcl\\java"), L"");
        scanSubdirs(joinPath(userProfile, L".hmcl\\java"), L"");
        scanSubdirs(joinPath(appdata, L".minecraft\\runtime"), L"");
        scanSubdirs(joinPath(userProfile, L".minecraft\\runtime"), L"");
    }

    if (g_java.empty()) return noJava();
    return launch();
}
