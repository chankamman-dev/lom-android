// 0Harmony 2.10.2 的替身，只实现咖啡伴侣用到的部分。
// 补丁在编译前由 lomweave 织进游戏程序集；这里的 PatchAll/Patch/UnpatchSelf 只是打开、关闭织好的补丁，
// GetPatchInfo 按登记表回答。AccessTools/Traverse 用普通反射实现（IL2CPP 支持）。
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Reflection;

[assembly: AssemblyVersion("2.10.2.0")]

namespace HarmonyLib
{
    public enum MethodType { Normal, Getter, Setter, Constructor, StaticConstructor, Enumerator }
    public enum ArgumentType { Normal, Ref, Out, Pointer }

    public static class Priority
    {
        public const int Last = 0, VeryLow = 100, Low = 200, LowerThanNormal = 300, Normal = 400, HigherThanNormal = 500, High = 600, VeryHigh = 700, First = 800;
    }

    public class HarmonyMethod
    {
        public MethodInfo method;
        public Type declaringType;
        public string methodName;
        public Type[] argumentTypes;
        public int priority = -1;
        public HarmonyMethod() { }
        public HarmonyMethod(MethodInfo method) { this.method = method; declaringType = method?.DeclaringType; methodName = method?.Name; }
        public HarmonyMethod(Type methodType, string methodName, Type[] argumentTypes = null)
        {
            declaringType = methodType; this.methodName = methodName; this.argumentTypes = argumentTypes;
            method = AccessTools.Method(methodType, methodName, argumentTypes);
        }
    }

    public class HarmonyAttribute : Attribute { public HarmonyMethod info = new HarmonyMethod(); }

    [AttributeUsage(AttributeTargets.Class | AttributeTargets.Struct | AttributeTargets.Method | AttributeTargets.Delegate, AllowMultiple = true)]
    public class HarmonyPatch : HarmonyAttribute
    {
        public HarmonyPatch() { }
        public HarmonyPatch(Type declaringType) { info.declaringType = declaringType; }
        public HarmonyPatch(Type declaringType, Type[] argumentTypes) { info.declaringType = declaringType; info.argumentTypes = argumentTypes; }
        public HarmonyPatch(Type declaringType, string methodName) { info.declaringType = declaringType; info.methodName = methodName; }
        public HarmonyPatch(Type declaringType, string methodName, params Type[] argumentTypes) { info.declaringType = declaringType; info.methodName = methodName; info.argumentTypes = argumentTypes; }
        public HarmonyPatch(Type declaringType, string methodName, Type[] argumentTypes, ArgumentType[] argumentVariations) { info.declaringType = declaringType; info.methodName = methodName; info.argumentTypes = argumentTypes; }
        public HarmonyPatch(Type declaringType, MethodType methodType) { info.declaringType = declaringType; }
        public HarmonyPatch(Type declaringType, MethodType methodType, params Type[] argumentTypes) { info.declaringType = declaringType; info.argumentTypes = argumentTypes; }
        public HarmonyPatch(Type declaringType, string methodName, MethodType methodType) { info.declaringType = declaringType; info.methodName = methodName; }
        public HarmonyPatch(string methodName) { info.methodName = methodName; }
        public HarmonyPatch(string methodName, params Type[] argumentTypes) { info.methodName = methodName; info.argumentTypes = argumentTypes; }
        public HarmonyPatch(string methodName, MethodType methodType) { info.methodName = methodName; }
        public HarmonyPatch(MethodType methodType) { }
        public HarmonyPatch(Type[] argumentTypes) { info.argumentTypes = argumentTypes; }
    }

    [AttributeUsage(AttributeTargets.Method)] public class HarmonyPrefix : Attribute { }
    [AttributeUsage(AttributeTargets.Method)] public class HarmonyPostfix : Attribute { }
    [AttributeUsage(AttributeTargets.Method)] public class HarmonyFinalizer : Attribute { }
    [AttributeUsage(AttributeTargets.Method)] public class HarmonyTranspiler : Attribute { }
    [AttributeUsage(AttributeTargets.Method)] public class HarmonyPrepare : Attribute { }
    [AttributeUsage(AttributeTargets.Method)] public class HarmonyCleanup : Attribute { }
    [AttributeUsage(AttributeTargets.Method)] public class HarmonyTargetMethod : Attribute { }
    [AttributeUsage(AttributeTargets.Method)] public class HarmonyTargetMethods : Attribute { }
    [AttributeUsage(AttributeTargets.Class | AttributeTargets.Method)]
    public class HarmonyPriority : HarmonyAttribute { public HarmonyPriority(int priority) { info.priority = priority; } }

    public class Patch : IComparable
    {
        public readonly int index;
        public readonly string owner;
        public readonly int priority;
        public readonly string[] before = new string[0];
        public readonly string[] after = new string[0];
        readonly MethodInfo patchMethod;
        public MethodInfo PatchMethod => patchMethod;
        internal Patch(MethodInfo method, int index, string owner, int priority) { patchMethod = method; this.index = index; this.owner = owner; this.priority = priority; }
        public int CompareTo(object obj) => obj is Patch p ? p.priority.CompareTo(priority) : 0;
    }

    public class Patches
    {
        public readonly ReadOnlyCollection<Patch> Prefixes, Postfixes, Transpilers, Finalizers;
        public ReadOnlyCollection<string> Owners =>
            Prefixes.Concat(Postfixes).Concat(Transpilers).Concat(Finalizers).Select(p => p.owner).Distinct().ToList().AsReadOnly();
        internal Patches(List<Patch> pre, List<Patch> post, List<Patch> trans, List<Patch> fin)
        {
            Prefixes = pre.AsReadOnly(); Postfixes = post.AsReadOnly(); Transpilers = trans.AsReadOnly(); Finalizers = fin.AsReadOnly();
        }
    }

    /// <summary>织入登记表。On 由织进游戏方法里的代码读取；表格内容由 lomweave 生成在 LOMAssistant 里的 LomWeaveTable。</summary>
    public static class LomWeave
    {
        public const int Capacity = 4096;
        public static readonly bool[] On = new bool[Capacity];
        static readonly string[] owners = new string[Capacity];
        public static Action<string> Log = s => UnityEngine.Debug.Log("[LomWeave] " + s);

        // 0 前置 1 后置 2 Finalizer 3 Transpiler（调用点分派）
        static Type[] patchTypes; static string[] patchNames; static int[] kinds; static int[] priorities; static MethodBase[] targets;
        internal static int Count => Load() ? patchTypes.Length : 0;

        static bool Load()
        {
            if (patchTypes != null) return true;
            var table = Type.GetType("LOMAssistant.LomWeaveTable, LOMAssistant", false);
            if (table == null) { Log("没有找到织入登记表 LOMAssistant.LomWeaveTable"); return false; }
            patchTypes = (Type[])table.GetField("PatchTypes").GetValue(null);
            patchNames = (string[])table.GetField("PatchNames").GetValue(null);
            kinds = (int[])table.GetField("Kinds").GetValue(null);
            priorities = (int[])table.GetField("Priorities").GetValue(null);
            targets = (MethodBase[])table.GetField("Targets").GetValue(null);
            return true;
        }

        static bool SameMethod(MethodBase a, MethodBase b)
        {
            if (a == null || b == null) return false;
            if (a.Equals(b)) return true;
            if (a.DeclaringType != b.DeclaringType || a.Name != b.Name) return false;
            var pa = a.GetParameters(); var pb = b.GetParameters();
            return pa.Length == pb.Length && pa.Select((p, i) => p.ParameterType == pb[i].ParameterType).All(x => x);
        }

        internal static int EnableClass(Type type, string owner)
        {
            if (!Load()) return 0;
            int n = 0;
            for (int i = 0; i < patchTypes.Length; i++)
                if (patchTypes[i] == type) { On[i] = true; owners[i] = owner; n++; }
            if (n == 0) Log("PatchAll(" + type.FullName + ")：这个类没有织入的补丁");
            return n;
        }

        internal static bool EnableOne(MethodBase target, HarmonyMethod patch, int kind, string owner)
        {
            if (patch == null || !Load()) return false;
            string name = patch.methodName ?? patch.method?.Name;
            Type type = patch.declaringType ?? patch.method?.DeclaringType;
            for (int i = 0; i < patchTypes.Length; i++)
                if (kinds[i] == kind && patchTypes[i] == type && patchNames[i] == name && SameMethod(targets[i], target))
                { On[i] = true; owners[i] = owner; return true; }
            Log("Patch(" + target?.DeclaringType?.FullName + "." + target?.Name + ", " + type?.Name + "." + name + ")：没有织入，跳过");
            return false;
        }

        internal static void DisableOwner(string owner)
        {
            if (!Load()) return;
            for (int i = 0; i < patchTypes.Length; i++) if (owners[i] == owner) { On[i] = false; owners[i] = null; }
        }

        internal static Patches Info(MethodBase target)
        {
            if (!Load()) return null;
            var lists = new[] { new List<Patch>(), new List<Patch>(), new List<Patch>(), new List<Patch>() };
            for (int i = 0; i < patchTypes.Length; i++)
            {
                if (!On[i] || !SameMethod(targets[i], target)) continue;
                var m = AccessTools.Method(patchTypes[i], patchNames[i]);
                int slot = kinds[i] == 0 ? 0 : kinds[i] == 1 ? 1 : kinds[i] == 2 ? 3 : 2;
                lists[slot].Add(new Patch(m, lists[slot].Count, owners[i], priorities[i]));
            }
            return lists.All(l => l.Count == 0) ? null : new Patches(lists[0], lists[1], lists[2], lists[3]);
        }

        internal static IEnumerable<MethodBase> Patched(string owner)
        {
            if (!Load()) return Enumerable.Empty<MethodBase>();
            var seen = new List<MethodBase>();
            for (int i = 0; i < patchTypes.Length; i++)
                if (On[i] && (owner == null || owners[i] == owner) && targets[i] != null && !seen.Any(s => SameMethod(s, targets[i]))) seen.Add(targets[i]);
            return seen;
        }
    }

    public class Harmony
    {
        public string Id { get; }
        public Harmony(string id) { Id = id; }
        public void PatchAll(Type type) => LomWeave.EnableClass(type, Id);
        public void PatchAll() { }
        public MethodInfo Patch(MethodBase original, HarmonyMethod prefix = null, HarmonyMethod postfix = null, HarmonyMethod transpiler = null, HarmonyMethod finalizer = null, HarmonyMethod ilmanipulator = null)
        {
            LomWeave.EnableOne(original, prefix, 0, Id);
            LomWeave.EnableOne(original, postfix, 1, Id);
            LomWeave.EnableOne(original, finalizer, 2, Id);
            LomWeave.EnableOne(original, transpiler, 3, Id);
            return original as MethodInfo;
        }
        public void UnpatchSelf() => LomWeave.DisableOwner(Id);
        public void UnpatchAll(string harmonyID = null) => LomWeave.DisableOwner(harmonyID ?? Id);
        public IEnumerable<MethodBase> GetPatchedMethods() => LomWeave.Patched(Id);
        public static IEnumerable<MethodBase> GetAllPatchedMethods() => LomWeave.Patched(null);
        public static Patches GetPatchInfo(MethodBase method) => LomWeave.Info(method);
        public static bool HasAnyPatches(string harmonyID) => LomWeave.Patched(harmonyID).Any();
    }

    public class CodeInstruction
    {
        public object operand;
        public CodeInstruction(object operand = null) { this.operand = operand; }
    }

    public static class CodeInstructionExtensions
    {
        public static bool Calls(this CodeInstruction code, MethodInfo method) => code?.operand is MethodInfo m && m.Equals(method);
    }

    public static class CollectionExtensions
    {
        public static T[] AddToArray<T>(this T[] sequence, T item) => (sequence ?? new T[0]).Concat(new[] { item }).ToArray();
        public static void Do<T>(this IEnumerable<T> sequence, Action<T> action) { foreach (var x in sequence) action(x); }
    }

    public static class AccessTools
    {
        public const BindingFlags all = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static | BindingFlags.GetField | BindingFlags.SetField | BindingFlags.GetProperty | BindingFlags.SetProperty;
        public const BindingFlags allDeclared = all | BindingFlags.DeclaredOnly;

        // 由织入器把调用点改成直接取字段地址的生成方法；运行到这里说明有漏改的调用点
        public delegate ref F FieldRef<in T, F>(T instance);
        public delegate ref F FieldRef<F>();
        public static FieldRef<T, F> FieldRefAccess<T, F>(string fieldName) => throw new NotSupportedException("FieldRefAccess 应在织入时改写：" + typeof(T).FullName + "." + fieldName);
        public static FieldRef<F> StaticFieldRefAccess<F>(FieldInfo fieldInfo) => throw new NotSupportedException("StaticFieldRefAccess 应在织入时改写：" + fieldInfo?.Name);

        static T FindIncludingBaseTypes<T>(Type type, Func<Type, T> find) where T : class
        {
            for (var t = type; t != null; t = t.BaseType) { var r = find(t); if (r != null) return r; }
            return null;
        }

        public static FieldInfo Field(Type type, string name)
        {
            if (type == null || name == null) return null;
            return FindIncludingBaseTypes(type, t => t.GetField(name, allDeclared));
        }
        public static FieldInfo DeclaredField(Type type, string name) => type?.GetField(name, allDeclared);

        public static PropertyInfo Property(Type type, string name)
        {
            if (type == null || name == null) return null;
            return FindIncludingBaseTypes(type, t => { try { return t.GetProperty(name, allDeclared); } catch (AmbiguousMatchException) { return t.GetProperties(allDeclared).FirstOrDefault(p => p.Name == name); } });
        }
        public static PropertyInfo DeclaredProperty(Type type, string name) => type?.GetProperty(name, allDeclared);

        public static MethodInfo Method(Type type, string name, Type[] parameters = null, Type[] generics = null)
        {
            if (type == null || name == null) return null;
            MethodInfo result = FindIncludingBaseTypes(type, t =>
            {
                if (parameters == null)
                {
                    try { return t.GetMethod(name, allDeclared); }
                    catch (AmbiguousMatchException)
                    {
                        var m = t.GetMethod(name, allDeclared, null, Type.EmptyTypes, null);
                        if (m == null) throw new AmbiguousMatchException("Ambiguous match in Harmony patch for " + t + ":" + name);
                        return m;
                    }
                }
                return t.GetMethod(name, allDeclared, null, parameters, null);
            });
            if (result != null && generics != null) result = result.MakeGenericMethod(generics);
            return result;
        }
        public static MethodInfo Method(string typeColonMethodname, Type[] parameters = null, Type[] generics = null)
        {
            int i = typeColonMethodname?.LastIndexOf(':') ?? -1;
            return i < 0 ? null : Method(TypeByName(typeColonMethodname.Substring(0, i)), typeColonMethodname.Substring(i + 1), parameters, generics);
        }
        public static MethodInfo DeclaredMethod(Type type, string name, Type[] parameters = null, Type[] generics = null)
        {
            if (type == null || name == null) return null;
            var m = parameters == null ? type.GetMethod(name, allDeclared) : type.GetMethod(name, allDeclared, null, parameters, null);
            return m != null && generics != null ? m.MakeGenericMethod(generics) : m;
        }
        public static ConstructorInfo Constructor(Type type, Type[] parameters = null, bool searchForStatic = false)
        {
            var flags = (searchForStatic ? BindingFlags.Static : BindingFlags.Instance) | BindingFlags.Public | BindingFlags.NonPublic;
            return type?.GetConstructor(flags, null, parameters ?? Type.EmptyTypes, null);
        }
        public static List<MethodInfo> GetDeclaredMethods(Type type) => type == null ? new List<MethodInfo>() : type.GetMethods(allDeclared).ToList();
        public static List<FieldInfo> GetDeclaredFields(Type type) => type == null ? new List<FieldInfo>() : type.GetFields(allDeclared).ToList();
        public static List<PropertyInfo> GetDeclaredProperties(Type type) => type == null ? new List<PropertyInfo>() : type.GetProperties(allDeclared).ToList();
        public static MethodInfo PropertyGetter(Type type, string name) => Property(type, name)?.GetGetMethod(true);
        public static MethodInfo PropertySetter(Type type, string name) => Property(type, name)?.GetSetMethod(true);

        static Dictionary<string, Type> typeCache;
        public static Type TypeByName(string name)
        {
            if (string.IsNullOrEmpty(name)) return null;
            var t = Type.GetType(name, false);
            if (t != null) return t;
            if (typeCache == null)
            {
                typeCache = new Dictionary<string, Type>();
                foreach (var asm in AppDomain.CurrentDomain.GetAssemblies())
                {
                    Type[] types;
                    try { types = asm.GetTypes(); } catch (ReflectionTypeLoadException ex) { types = ex.Types.Where(x => x != null).ToArray(); }
                    foreach (var x in types) if (x.FullName != null && !typeCache.ContainsKey(x.FullName)) typeCache[x.FullName] = x;
                }
            }
            if (typeCache.TryGetValue(name, out t)) return t;
            return typeCache.Values.FirstOrDefault(x => x.Name == name);
        }
        public static IEnumerable<Type> AllTypes() => AppDomain.CurrentDomain.GetAssemblies().SelectMany(a => { try { return a.GetTypes(); } catch { return new Type[0]; } });
        public static bool IsStatic(MemberInfo member) => member is MethodBase m ? m.IsStatic : member is FieldInfo f ? f.IsStatic : member is PropertyInfo p && (p.GetGetMethod(true) ?? p.GetSetMethod(true)).IsStatic;
        public static object GetDefaultValue(Type type) => type != null && type.IsValueType ? Activator.CreateInstance(type) : null;
        public static object CreateInstance(Type type) => Activator.CreateInstance(type, true);
        public static T CreateInstance<T>() => (T)Activator.CreateInstance(typeof(T), true);
        public static Type[] GetTypes(object[] parameters) => parameters?.Select(p => p?.GetType() ?? typeof(object)).ToArray() ?? Type.EmptyTypes;
    }

    /// <summary>Harmony 的 Traverse：按名字层层取字段、属性、方法。</summary>
    public class Traverse
    {
        readonly Type type;
        readonly object root;
        readonly MemberInfo info;
        readonly MethodBase method;
        readonly object[] parameters;

        Traverse() { }
        public Traverse(Type type) { this.type = type; }
        public Traverse(object root) { this.root = root; type = root?.GetType(); }
        Traverse(object root, MemberInfo info, object[] index) { this.root = root; type = root?.GetType() ?? (info as FieldInfo)?.DeclaringType ?? (info as PropertyInfo)?.DeclaringType; this.info = info; parameters = index; }
        Traverse(object root, MethodInfo method, object[] parameters) { this.root = root; type = method.ReturnType; this.method = method; this.parameters = parameters; }

        public static Traverse Create(Type type) => new Traverse(type);
        public static Traverse Create<T>() => new Traverse(typeof(T));
        public static Traverse Create(object root) => new Traverse(root);
        public static Traverse CreateWithType(string name) => new Traverse(AccessTools.TypeByName(name));

        public object GetValue()
        {
            if (info is FieldInfo f) return f.GetValue(root);
            if (info is PropertyInfo p) return p.GetValue(root, AccessTools.all, null, parameters, null);
            if (method != null) return method.Invoke(root, parameters);
            if (root == null && type != null) return type;
            return root;
        }
        public T GetValue<T>() { var v = GetValue(); return v == null ? default : (T)v; }
        public object GetValue(params object[] arguments) => method == null ? throw new Exception("cannot get method value without method") : method.Invoke(root, arguments);
        public T GetValue<T>(params object[] arguments) { var v = GetValue(arguments); return v == null ? default : (T)v; }

        public Traverse SetValue(object value)
        {
            if (info is FieldInfo f) f.SetValue(root, value, AccessTools.all, null, null);
            if (info is PropertyInfo p) p.SetValue(root, value, AccessTools.all, null, parameters, null);
            if (method != null) throw new Exception("cannot set value of method " + method.Name);
            return this;
        }

        Traverse Resolve()
        {
            if (root == null && type != null) return this;
            if (info != null || method != null) return new Traverse(GetValue());
            return this;
        }

        public Traverse Type(string name)
        {
            if (name == null || type == null) return new Traverse();
            var t = type.GetNestedType(name, AccessTools.all);
            return t == null ? new Traverse() : new Traverse(t);
        }

        public Traverse Field(string name)
        {
            if (name == null) return new Traverse();
            var r = Resolve();
            if (r.type == null) return new Traverse();
            var f = AccessTools.Field(r.type, name);
            if (f == null) return new Traverse();
            if (!f.IsStatic && r.root == null) return new Traverse();
            return new Traverse(r.root, f, null);
        }
        public Traverse<T> Field<T>(string name) => new Traverse<T>(Field(name));

        public Traverse Property(string name, object[] index = null)
        {
            if (name == null) return new Traverse();
            var r = Resolve();
            if (r.type == null) return new Traverse();
            var p = AccessTools.Property(r.type, name);
            if (p == null) return new Traverse();
            return new Traverse(r.root, p, index);
        }
        public Traverse<T> Property<T>(string name, object[] index = null) => new Traverse<T>(Property(name, index));

        public Traverse Method(string name, params object[] arguments)
        {
            if (name == null) return new Traverse();
            var r = Resolve();
            if (r.type == null) return new Traverse();
            var types = AccessTools.GetTypes(arguments);
            var m = FindMethod(r.type, name, types);
            if (m == null) return new Traverse();
            return new Traverse(r.root, m, arguments);
        }
        public Traverse Method(string name, Type[] paramTypes, object[] arguments = null)
        {
            if (name == null) return new Traverse();
            var r = Resolve();
            if (r.type == null) return new Traverse();
            var m = AccessTools.Method(r.type, name, paramTypes);
            if (m == null) return new Traverse();
            return new Traverse(r.root, m, arguments);
        }

        // 按实参类型找方法：名字相同、参数个数相同、实参能赋给形参（null 可给引用类型）
        static MethodInfo FindMethod(Type type, string name, Type[] argTypes)
        {
            var exact = AccessTools.Method(type, name, argTypes);
            if (exact != null) return exact;
            for (var t = type; t != null; t = t.BaseType)
                foreach (var m in t.GetMethods(AccessTools.allDeclared))
                {
                    if (m.Name != name) continue;
                    var ps = m.GetParameters();
                    if (ps.Length != argTypes.Length) continue;
                    bool ok = true;
                    for (int i = 0; i < ps.Length && ok; i++)
                    {
                        var pt = ps[i].ParameterType.IsByRef ? ps[i].ParameterType.GetElementType() : ps[i].ParameterType;
                        ok = argTypes[i] == typeof(object) && !pt.IsValueType || pt.IsAssignableFrom(argTypes[i]);
                    }
                    if (ok) return m;
                }
            return null;
        }

        public bool FieldExists() => info is FieldInfo;
        public bool PropertyExists() => info is PropertyInfo;
        public bool MethodExists() => method != null;
        public bool TypeExists() => type != null;
        public override string ToString() => (GetValue() ?? "").ToString();
    }

    public class Traverse<T>
    {
        readonly Traverse traverse;
        internal Traverse(Traverse traverse) { this.traverse = traverse; }
        public T Value { get => traverse.GetValue<T>(); set => traverse.SetValue(value); }
    }
}
