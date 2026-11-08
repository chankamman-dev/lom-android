// BepInEx.Core 6.0 的替身，只实现咖啡伴侣用到的部分。配置文件格式与 PC 版一致，可以直接拷贝 PC 的 LOMAssistant.cfg。
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using BepInEx.Configuration;

[assembly: AssemblyVersion("6.0.0.0")]

namespace BepInEx
{
    [AttributeUsage(AttributeTargets.Class, AllowMultiple = false)]
    public class BepInPlugin : Attribute
    {
        public string GUID { get; protected set; }
        public string Name { get; protected set; }
        public string VersionText { get; protected set; }
        public BepInPlugin(string GUID, string Name, string Version) { this.GUID = GUID; this.Name = Name; VersionText = Version; }
    }

    [AttributeUsage(AttributeTargets.Class, AllowMultiple = true)]
    public class BepInDependency : Attribute
    {
        public BepInDependency(string DependencyGUID, string MinimumDependencyVersion) { }
        public BepInDependency(string DependencyGUID) { }
    }

    public class PluginInfo
    {
        public BepInPlugin Metadata { get; set; }
        public string Location { get; set; }
        public object Instance { get; set; }
        public override string ToString() => Metadata?.Name + " " + Metadata?.VersionText;
    }

    /// <summary>安卓上 BepInEx 的各个目录都放在应用数据目录下的 BepInEx 里。</summary>
    public static class Paths
    {
        static string root;
        static string Ensure(string dir) { try { Directory.CreateDirectory(dir); } catch { } return dir; }
        public static string GameRootPath => root ??= UnityEngine.Application.persistentDataPath;
        public static string BepInExRootPath => Ensure(Path.Combine(GameRootPath, "BepInEx"));
        public static string ConfigPath => Ensure(Path.Combine(BepInExRootPath, "config"));
        public static string PluginPath => Ensure(Path.Combine(BepInExRootPath, "plugins"));
        public static string CachePath => Ensure(Path.Combine(BepInExRootPath, "cache"));
        public static string PatcherPluginPath => Ensure(Path.Combine(BepInExRootPath, "patchers"));
        public static string BepInExConfigPath => Path.Combine(ConfigPath, "BepInEx.cfg");
        public static string ManagedPath => Path.Combine(GameRootPath, "Managed");
        public static string ExecutablePath => Path.Combine(GameRootPath, "Mortal.exe");
        public static string ProcessName => "Mortal";
    }

    public static class Utility
    {
        public static bool IsNullOrWhiteSpace(this string self) => self == null || self.All(char.IsWhiteSpace);
        public static string CombinePaths(params string[] parts) => parts.Aggregate(Path.Combine);
    }

    public static class ConsoleManager
    {
        public static readonly ConfigEntry<bool> ConfigConsoleEnabled = new ConfigEntry<bool>(null, new ConfigDefinition("Logging.Console", "Enabled"), false, null);
        public static bool ConsoleActive => false;
        public static void DetachConsole() { }
        public static void CreateConsole() { }
    }
}

namespace BepInEx.Logging
{
    public enum LogLevel { None = 0, Fatal = 1, Error = 2, Warning = 4, Message = 8, Info = 16, Debug = 32, All = 63 }

    public class ManualLogSource
    {
        public string SourceName { get; }
        public ManualLogSource(string sourceName) { SourceName = sourceName; }
        public void Log(LogLevel level, object data)
        {
            string text = "[" + level + " :" + SourceName + "] " + data;
            if (level <= LogLevel.Error && level != LogLevel.None) UnityEngine.Debug.LogError(text);
            else if (level == LogLevel.Warning) UnityEngine.Debug.LogWarning(text);
            else UnityEngine.Debug.Log(text);
        }
        public void LogFatal(object data) => Log(LogLevel.Fatal, data);
        public void LogError(object data) => Log(LogLevel.Error, data);
        public void LogWarning(object data) => Log(LogLevel.Warning, data);
        public void LogMessage(object data) => Log(LogLevel.Message, data);
        public void LogInfo(object data) => Log(LogLevel.Info, data);
        public void LogDebug(object data) => Log(LogLevel.Debug, data);
    }
}

namespace BepInEx.Configuration
{
    public class ConfigDefinition : IEquatable<ConfigDefinition>
    {
        public string Section { get; }
        public string Key { get; }
        public ConfigDefinition(string section, string key) { Section = section; Key = key; }
        public bool Equals(ConfigDefinition other) => other != null && Section == other.Section && Key == other.Key;
        public override bool Equals(object obj) => Equals(obj as ConfigDefinition);
        public override int GetHashCode() => (Section ?? "").GetHashCode() ^ (Key ?? "").GetHashCode() * 31;
        public override string ToString() => Section + "." + Key;
    }

    public class ConfigDescription
    {
        public string Description { get; }
        public object[] Tags { get; }
        public ConfigDescription(string description, object acceptableValues = null, params object[] tags) { Description = description ?? ""; Tags = tags ?? new object[0]; }
        public static ConfigDescription Empty { get; } = new ConfigDescription("");
    }

    public abstract class ConfigEntryBase
    {
        public ConfigFile ConfigFile { get; }
        public ConfigDefinition Definition { get; }
        public ConfigDescription Description { get; }
        public Type SettingType { get; }
        public object DefaultValue { get; }
        public abstract object BoxedValue { get; set; }
        protected internal ConfigEntryBase(ConfigFile file, ConfigDefinition definition, Type type, object defaultValue, ConfigDescription description)
        {
            ConfigFile = file; Definition = definition; SettingType = type; DefaultValue = defaultValue; Description = description ?? ConfigDescription.Empty;
        }
        public string GetSerializedValue() => TomlTypeConverter.ConvertToString(BoxedValue, SettingType);
        public void SetSerializedValue(string value)
        {
            try { BoxedValue = TomlTypeConverter.ConvertToValue(value, SettingType); }
            catch (Exception e) { UnityEngine.Debug.LogWarning("[BepInEx] 配置 " + Definition + " 的值读不了，用默认值：" + e.Message); }
        }
        internal void Write(StringBuilder sb)
        {
            if (!string.IsNullOrEmpty(Description.Description)) sb.AppendLine("## " + Description.Description.Replace("\n", "\n## "));
            sb.AppendLine("# Setting type: " + SettingType.Name);
            sb.AppendLine("# Default value: " + TomlTypeConverter.ConvertToString(DefaultValue, SettingType));
            if (SettingType.IsEnum)
            {
                sb.AppendLine("# Acceptable values: " + string.Join(", ", Enum.GetNames(SettingType)));
            }
            sb.AppendLine(Definition.Key + " = " + GetSerializedValue());
        }
        protected void OnSettingChanged(object sender) => ConfigFile?.OnSettingChanged(sender, this);
    }

    public sealed class ConfigEntry<T> : ConfigEntryBase
    {
        public event EventHandler SettingChanged;
        T typedValue;
        public T Value
        {
            get => typedValue;
            set
            {
                if (Equals(typedValue, value)) return;
                typedValue = value;
                SettingChanged?.Invoke(this, EventArgs.Empty);
                OnSettingChanged(this);
            }
        }
        public override object BoxedValue { get => Value; set => Value = (T)value; }
        internal ConfigEntry(ConfigFile file, ConfigDefinition definition, T defaultValue, ConfigDescription description)
            : base(file, definition, typeof(T), defaultValue, description) { typedValue = defaultValue; }
    }

    public class SettingChangedEventArgs : EventArgs
    {
        public ConfigEntryBase ChangedSetting { get; }
        public SettingChangedEventArgs(ConfigEntryBase changed) { ChangedSetting = changed; }
    }

    public class ConfigFile
    {
        readonly Dictionary<ConfigDefinition, ConfigEntryBase> entries = new Dictionary<ConfigDefinition, ConfigEntryBase>();
        readonly Dictionary<ConfigDefinition, string> orphans = new Dictionary<ConfigDefinition, string>();
        readonly BepInPlugin owner;
        readonly object ioLock = new object();
        public string ConfigFilePath { get; }
        public bool SaveOnConfigSet { get; set; } = true;
        public event EventHandler<SettingChangedEventArgs> SettingChanged;
        public event EventHandler ConfigReloaded;

        public ConfigFile(string configPath, bool saveOnInit) : this(configPath, saveOnInit, null) { }
        public ConfigFile(string configPath, bool saveOnInit, BepInPlugin ownerMetadata)
        {
            ConfigFilePath = Path.GetFullPath(configPath);
            owner = ownerMetadata;
            if (File.Exists(ConfigFilePath)) Reload();
            else if (saveOnInit) Save();
        }

        public ConfigEntryBase this[ConfigDefinition key] { get { lock (ioLock) return entries[key]; } }
        public ConfigEntryBase this[string section, string key] => this[new ConfigDefinition(section, key)];
        public bool ContainsKey(ConfigDefinition key) { lock (ioLock) return entries.ContainsKey(key); }
        public int Count { get { lock (ioLock) return entries.Count; } }
        public ICollection<ConfigEntryBase> Values { get { lock (ioLock) return entries.Values.ToList(); } }
        public ICollection<ConfigDefinition> Keys { get { lock (ioLock) return entries.Keys.ToList(); } }

        public bool TryGetEntry<T>(ConfigDefinition key, out ConfigEntry<T> entry)
        {
            lock (ioLock)
            {
                if (entries.TryGetValue(key, out var e) && e is ConfigEntry<T> typed) { entry = typed; return true; }
                entry = null; return false;
            }
        }
        public bool TryGetEntry<T>(string section, string key, out ConfigEntry<T> entry) => TryGetEntry(new ConfigDefinition(section, key), out entry);

        public ConfigEntry<T> Bind<T>(ConfigDefinition configDefinition, T defaultValue, ConfigDescription configDescription = null)
        {
            lock (ioLock)
            {
                if (entries.TryGetValue(configDefinition, out var existing)) return (ConfigEntry<T>)existing;
                var entry = new ConfigEntry<T>(this, configDefinition, defaultValue, configDescription);
                entries[configDefinition] = entry;
                if (orphans.TryGetValue(configDefinition, out string text)) { entry.SetSerializedValue(text); orphans.Remove(configDefinition); }
                if (SaveOnConfigSet) Save();
                return entry;
            }
        }
        public ConfigEntry<T> Bind<T>(string section, string key, T defaultValue, ConfigDescription configDescription = null) => Bind(new ConfigDefinition(section, key), defaultValue, configDescription);
        public ConfigEntry<T> Bind<T>(string section, string key, T defaultValue, string description) => Bind(new ConfigDefinition(section, key), defaultValue, new ConfigDescription(description));

        public void Reload()
        {
            lock (ioLock)
            {
                orphans.Clear();
                string section = "";
                foreach (string raw in File.ReadAllLines(ConfigFilePath))
                {
                    string line = raw.Trim();
                    if (line.Length == 0 || line.StartsWith("#")) continue;
                    if (line.StartsWith("[") && line.EndsWith("]")) { section = line.Substring(1, line.Length - 2); continue; }
                    int eq = line.IndexOf('=');
                    if (eq < 0) continue;
                    var def = new ConfigDefinition(section, line.Substring(0, eq).Trim());
                    string value = line.Substring(eq + 1).Trim();
                    if (entries.TryGetValue(def, out var e)) e.SetSerializedValue(value);
                    else orphans[def] = value;
                }
            }
            ConfigReloaded?.Invoke(this, EventArgs.Empty);
        }

        public void Save()
        {
            lock (ioLock)
            {
                try { Directory.CreateDirectory(Path.GetDirectoryName(ConfigFilePath)); } catch { }
                var sb = new StringBuilder();
                if (owner != null)
                {
                    sb.AppendLine("## Settings file was created by plugin " + owner.Name + " v" + owner.VersionText);
                    sb.AppendLine("## Plugin GUID: " + owner.GUID);
                    sb.AppendLine();
                }
                var all = entries.Select(e => new { e.Key, entry = e.Value, text = (string)null })
                    .Concat(orphans.Select(o => new { o.Key, entry = (ConfigEntryBase)null, text = o.Value }));
                foreach (var section in all.GroupBy(x => x.Key.Section).OrderBy(g => g.Key, StringComparer.Ordinal))
                {
                    sb.AppendLine("[" + section.Key + "]");
                    foreach (var item in section)
                    {
                        sb.AppendLine();
                        if (item.entry != null) item.entry.Write(sb);
                        else sb.AppendLine(item.Key.Key + " = " + item.text);
                    }
                    sb.AppendLine();
                }
                File.WriteAllText(ConfigFilePath, sb.ToString(), new UTF8Encoding(true));
            }
        }

        internal void OnSettingChanged(object sender, ConfigEntryBase changed)
        {
            SettingChanged?.Invoke(sender, new SettingChangedEventArgs(changed));
            if (SaveOnConfigSet) Save();
        }
    }

    /// <summary>与 BepInEx 的 TomlTypeConverter 同样的文本格式：bool 小写、数字用不变区域、字符串转义、枚举按名字。</summary>
    public static class TomlTypeConverter
    {
        public static string ConvertToString(object value, Type type)
        {
            if (type == typeof(string)) return Escape((string)value ?? "");
            if (type == typeof(bool)) return (bool)value ? "true" : "false";
            if (type.IsEnum) return value?.ToString() ?? "";
            if (value is IFormattable f) return f.ToString(null, CultureInfo.InvariantCulture);
            return value?.ToString() ?? "";
        }

        public static object ConvertToValue(string value, Type type)
        {
            if (type == typeof(string)) return Unescape(value);
            if (type == typeof(bool)) return bool.Parse(value);
            if (type.IsEnum) return Enum.Parse(type, value, true);
            if (type == typeof(int)) return int.Parse(value, NumberStyles.Integer, CultureInfo.InvariantCulture);
            if (type == typeof(long)) return long.Parse(value, NumberStyles.Integer, CultureInfo.InvariantCulture);
            if (type == typeof(float)) return float.Parse(value, NumberStyles.Float, CultureInfo.InvariantCulture);
            if (type == typeof(double)) return double.Parse(value, NumberStyles.Float, CultureInfo.InvariantCulture);
            return Convert.ChangeType(value, type, CultureInfo.InvariantCulture);
        }

        static string Escape(string s)
        {
            var sb = new StringBuilder(s.Length);
            foreach (char c in s)
                switch (c)
                {
                    case '\\': sb.Append("\\\\"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    case '\f': sb.Append("\\f"); break;
                    case '\b': sb.Append("\\b"); break;
                    case '"': sb.Append("\\\""); break;
                    case '\'': sb.Append("\\'"); break;
                    default: sb.Append(c); break;
                }
            return sb.ToString();
        }

        static string Unescape(string s)
        {
            if (s.IndexOf('\\') < 0) return s;
            var sb = new StringBuilder(s.Length);
            for (int i = 0; i < s.Length; i++)
            {
                char c = s[i];
                if (c != '\\' || i + 1 >= s.Length) { sb.Append(c); continue; }
                char n = s[++i];
                switch (n)
                {
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case 'f': sb.Append('\f'); break;
                    case 'b': sb.Append('\b'); break;
                    case '0': sb.Append('\0'); break;
                    default: sb.Append(n); break;
                }
            }
            return sb.ToString();
        }
    }
}
