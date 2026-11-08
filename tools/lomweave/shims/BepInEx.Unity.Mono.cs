// BepInEx.Unity.Mono 6.0 的替身，加上安卓版的启动入口 LomBoot。
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using BepInEx.Configuration;
using BepInEx.Logging;
using UnityEngine;

[assembly: AssemblyVersion("6.0.0.0")]

namespace BepInEx.Unity.Mono
{
    public abstract class BaseUnityPlugin : MonoBehaviour
    {
        // Unity 不允许在 MonoBehaviour 构造函数里读 Application 路径，所以都在第一次用到时再建
        PluginInfo info; ManualLogSource logger; ConfigFile config;

        BepInPlugin Metadata => (BepInPlugin)Attribute.GetCustomAttribute(GetType(), typeof(BepInPlugin)) ?? new BepInPlugin(GetType().FullName, GetType().Name, "0.0.0");

        public PluginInfo Info => info ??= new PluginInfo
        {
            Metadata = Metadata,
            Instance = this,
            Location = Path.Combine(Path.Combine(Paths.PluginPath, GetType().Assembly.GetName().Name), GetType().Assembly.GetName().Name + ".dll"),
        };
        public ManualLogSource Logger => logger ??= new ManualLogSource(Metadata.Name);
        public ConfigFile Config => config ??= new ConfigFile(Path.Combine(Paths.ConfigPath, Metadata.GUID + ".cfg"), false, Metadata);
    }

    /// <summary>
    /// 安卓重编译版的启动入口：游戏启动时由织入的代码调用一次，
    /// 像 BepInEx 那样建一个常驻、隐藏的对象，把咖啡伴侣的插件挂上去（随即执行它的 Awake）。
    /// </summary>
    public static class LomBoot
    {
        static bool started;
        public static GameObject Host { get; private set; }

        public static void Start()
        {
            if (started) return;
            started = true;
            try
            {
                // 启动页设置「咖啡伴侣」：0 关闭（不挂插件，所有织入的补丁保持关闭，等于原版游戏）、
                // 1 普通版（窗口在游戏画面里）、2 双屏版（再挂下屏桥接，窗口交给下屏应用）
                int mode = LauncherSetting("companion", 1);
                if (mode == 0) { Debug.Log("[LomBoot] 咖啡伴侣已在启动页关闭"); return; }
                Host = new GameObject("BepInEx_Manager");
                Host.hideFlags = HideFlags.HideAndDontSave;
                UnityEngine.Object.DontDestroyOnLoad(Host);
                var type = Type.GetType("LOMAssistant.LOMAssistantPlugin, LOMAssistant", false);
                if (type == null) { Debug.LogError("[LomBoot] 找不到 LOMAssistant.LOMAssistantPlugin"); return; }
                Debug.Log("[LomBoot] 启动咖啡伴侣，数据目录 " + Paths.BepInExRootPath);
                ExtractGameManaged();
                Host.AddComponent(type);
                // 下屏伴侣的桥接（依赖咖啡伴侣，所以排在它后面）；没打包进来就跳过
                var bridge = Type.GetType("LOMThor.Plugin, LOMThorBridge", false);
                if (bridge != null && mode == 2) { Debug.Log("[LomBoot] 启动下屏桥接"); Host.AddComponent(bridge); }
                else Debug.Log("[LomBoot] 普通版：不启动下屏桥接");
            }
            catch (Exception ex) { Debug.LogError("[LomBoot] 启动失败：" + ex); }
        }

        /// <summary>启动页（dev.kmlom.cloud.Settings）里的整数设置；读不到时用默认值。</summary>
        public static int LauncherSetting(string key, int def)
        {
            try
            {
                using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activity = player.GetStatic<AndroidJavaObject>("currentActivity"))
                using (var bridge = new AndroidJavaClass("dev.kmlom.cloud.CloudBridge"))
                    return bridge.CallStatic<int>("intSetting", activity, key, def);
            }
            catch (Exception ex)
            {
                Debug.LogWarning("[LomBoot] 读不到启动页设置 " + key + "：" + ex.Message);
                return def;
            }
        }

        /// <summary>
        /// 随时存档启用前，咖啡伴侣会读 游戏根目录/Mortal_Data/Managed 下的几个程序集核对游戏代码。
        /// 安卓版没有这些文件，构建时把它们作为资源嵌在这里（游戏逻辑的用 PC 原版，mscorlib 和 CoreModule 用安卓实际运行的那份），首次启动释放出来。
        /// </summary>
        static void ExtractGameManaged()
        {
            const string prefix = "LomGameManaged/";
            var asm = typeof(LomBoot).Assembly;
            string dir = Path.Combine(Path.Combine(Paths.GameRootPath, "Mortal_Data"), "Managed");
            foreach (string name in asm.GetManifestResourceNames().Where(n => n.StartsWith(prefix)))
            {
                try
                {
                    string path = Path.Combine(dir, name.Substring(prefix.Length));
                    using (var src = asm.GetManifestResourceStream(name))
                    {
                        if (src == null || File.Exists(path) && new FileInfo(path).Length == src.Length) continue;
                        Directory.CreateDirectory(dir);
                        using (var dst = File.Create(path)) src.CopyTo(dst);
                    }
                    Debug.Log("[LomBoot] 释放 " + path);
                }
                catch (Exception ex) { Debug.LogWarning("[LomBoot] 释放 " + name + " 失败：" + ex.Message); }
            }
        }

        /// <summary>
        /// 咖啡伴侣用 Windows 的 LCMapStringEx 做简繁转换（游戏文本是繁体）。安卓没有 kernel32，
        /// 构建时在 Windows 上用同一个函数逐字导出了对照表（Windows 的转换本身就是逐字的），这里按表转换，结果与 PC 一致。
        /// 签名与原 P/Invoke 相同，织入器把咖啡伴侣对 LCMapStringEx 的调用改到这里。
        /// </summary>
        public static int LCMapStringEx(string lpLocaleName, uint dwMapFlags, string lpSrcStr, int cchSrc, char[] lpDestStr, int cchDest, IntPtr lpVersionInformation, IntPtr lpReserved, IntPtr sortHandle)
        {
            if (lpSrcStr == null) return 0;
            int n = cchSrc < 0 ? lpSrcStr.Length : Math.Min(cchSrc, lpSrcStr.Length);
            if (lpDestStr == null || cchDest == 0) return n;
            if (cchDest < n) return 0;
            var map = (dwMapFlags & 0x02000000u) != 0 ? Table("zh-CN") : (dwMapFlags & 0x04000000u) != 0 ? Table("zh-TW") : null;
            for (int i = 0; i < n; i++)
            {
                char c = lpSrcStr[i];
                lpDestStr[i] = map != null && map.TryGetValue(c, out char r) ? r : c;
            }
            return n;
        }

        static readonly Dictionary<string, Dictionary<char, char>> tables = new Dictionary<string, Dictionary<char, char>>();
        static Dictionary<char, char> Table(string locale)
        {
            lock (tables)
            {
                if (tables.TryGetValue(locale, out var t)) return t;
                t = new Dictionary<char, char>();
                using (var s = typeof(LomBoot).Assembly.GetManifestResourceStream("LomCjk/" + locale + ".tsv"))
                using (var reader = new StreamReader(s))
                {
                    string line;
                    while ((line = reader.ReadLine()) != null)
                        if (line.Length == 3 && line[1] == '\t') t[line[0]] = line[2];
                }
                tables[locale] = t;
                return t;
            }
        }

        /// <summary>IL2CPP 里 Assembly.Location 是空串；咖啡伴侣用它找自己的目录，织入器把这类调用改到这里。</summary>
        public static string AssemblyLocation(Assembly assembly)
        {
            string name = assembly?.GetName().Name ?? "";
            if (name == "LOMAssistant") return Path.Combine(Path.Combine(Paths.PluginPath, name), name + ".dll");
            return Path.Combine(Paths.ManagedPath, name + ".dll");
        }
    }
}

namespace BepInEx.Unity.Mono.Configuration
{
    public struct KeyboardShortcut
    {
        public static readonly KeyboardShortcut Empty = new KeyboardShortcut();
        readonly KeyCode[] keys;
        public KeyboardShortcut(KeyCode mainKey, params KeyCode[] modifiers) { keys = new[] { mainKey }.Concat(modifiers ?? new KeyCode[0]).ToArray(); }
        public KeyCode MainKey => keys != null && keys.Length > 0 ? keys[0] : KeyCode.None;
        public IEnumerable<KeyCode> Modifiers => keys?.Skip(1) ?? Enumerable.Empty<KeyCode>();
        public bool IsDown() => MainKey != KeyCode.None && Input.GetKeyDown(MainKey) && Modifiers.All(Input.GetKey);
        public bool IsPressed() => MainKey != KeyCode.None && Input.GetKey(MainKey) && Modifiers.All(Input.GetKey);
        public bool IsUp() => MainKey != KeyCode.None && Input.GetKeyUp(MainKey) && Modifiers.All(Input.GetKey);
        public override string ToString() => MainKey == KeyCode.None ? "Not set" : string.Join(" + ", keys.Select(k => k.ToString()).ToArray());
    }
}
