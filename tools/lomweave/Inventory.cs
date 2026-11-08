using Mono.Cecil;

// 列出原 Mod 的每个 Harmony 补丁：补丁类、补丁方法、种类、目标方法（解析到游戏程序集）、补丁参数。
// 只读元数据，不加载、不执行任何目标代码。
static class Inventory
{
    public sealed class Patch
    {
        public TypeDefinition Class;
        public MethodDefinition Method;
        public string Kind;            // Prefix / Postfix / Finalizer / Transpiler
        public MethodDefinition Target;
        public string TargetText;      // 解析失败时的说明
        public int Priority = 400;
    }

    // HarmonyLib.MethodType: Normal=0, Getter=1, Setter=2, Constructor=3, StaticConstructor=4, Enumerator=5
    sealed class Spec
    {
        public TypeReference Type; public string Name; public TypeReference[] Args; public int MethodType;
        public Spec Merge(Spec more) => new Spec
        {
            Type = more.Type ?? Type, Name = more.Name ?? Name, Args = more.Args ?? Args,
            MethodType = more.MethodType != 0 ? more.MethodType : MethodType,
        };
    }

    static readonly string[] Kinds = { "Prefix", "Postfix", "Finalizer", "Transpiler" };

    public static List<Patch> Read(ModuleDefinition mod)
    {
        var list = new List<Patch>();
        foreach (var type in mod.GetTypes())
        {
            var classSpec = SpecOf(type.CustomAttributes, null);
            bool patchClass = type.CustomAttributes.Any(a => a.AttributeType.FullName == "HarmonyLib.HarmonyPatch")
                || type.Methods.Any(m => m.CustomAttributes.Any(a => a.AttributeType.FullName == "HarmonyLib.HarmonyPatch"));
            if (!patchClass) continue;
            int classPriority = PriorityOf(type.CustomAttributes) ?? 400;
            foreach (var m in type.Methods)
            {
                string kind = Kinds.FirstOrDefault(k => m.Name == k || m.CustomAttributes.Any(a => a.AttributeType.FullName == "HarmonyLib.Harmony" + k));
                if (kind == null) continue;
                var spec = SpecOf(m.CustomAttributes, classSpec);
                var p = new Patch { Class = type, Method = m, Kind = kind, Priority = PriorityOf(m.CustomAttributes) ?? classPriority };
                if (type.Methods.Any(x => x.Name is "TargetMethod" or "TargetMethods" || x.CustomAttributes.Any(a => a.AttributeType.Name is "HarmonyTargetMethod" or "HarmonyTargetMethods")))
                    p.TargetText = "TargetMethod(s)：运行时计算，需手工列出";
                else Resolve(spec, p);
                list.Add(p);
            }
        }
        return list;
    }

    static int? PriorityOf(IEnumerable<CustomAttribute> attrs)
    {
        var a = attrs.FirstOrDefault(x => x.AttributeType.FullName == "HarmonyLib.HarmonyPriority");
        return a == null ? null : (int)a.ConstructorArguments[0].Value;
    }

    static Spec SpecOf(IEnumerable<CustomAttribute> attrs, Spec baseSpec)
    {
        var spec = baseSpec ?? new Spec();
        foreach (var a in attrs.Where(x => x.AttributeType.FullName == "HarmonyLib.HarmonyPatch"))
        {
            var s = new Spec();
            foreach (var arg in a.ConstructorArguments)
            {
                switch (arg.Value)
                {
                    case TypeReference t: s.Type = t; break;
                    case string n: s.Name = n; break;
                    case CustomAttributeArgument[] arr when arg.Type.GetElementType().FullName == "System.Type":
                        s.Args = arr.Select(x => (TypeReference)x.Value).ToArray(); break;
                    case CustomAttributeArgument[]: break; // ArgumentType[]（ref/out），解析参数时按名字匹配即可
                    default:
                        if (arg.Type.FullName == "HarmonyLib.MethodType") s.MethodType = Convert.ToInt32(arg.Value);
                        break;
                }
            }
            spec = spec.Merge(s);
        }
        return spec;
    }

    static void Resolve(Spec s, Patch p)
    {
        if (s.Type == null) { p.TargetText = "没有目标类型"; return; }
        TypeDefinition t;
        try { t = s.Type.Resolve(); } catch (Exception ex) { p.TargetText = "类型解析失败 " + s.Type.FullName + "：" + ex.Message; return; }
        if (t == null) { p.TargetText = "类型解析失败 " + s.Type.FullName; return; }
        IEnumerable<MethodDefinition> cands = s.MethodType switch
        {
            1 => t.Properties.Where(x => x.Name == s.Name).Select(x => x.GetMethod),
            2 => t.Properties.Where(x => x.Name == s.Name).Select(x => x.SetMethod),
            3 => t.Methods.Where(x => x.IsConstructor && !x.IsStatic),
            4 => t.Methods.Where(x => x.IsConstructor && x.IsStatic),
            _ => t.Methods.Where(x => x.Name == s.Name),
        };
        var list = cands.Where(x => x != null).ToList();
        if (s.Args != null) list = list.Where(x => x.Parameters.Count == s.Args.Length && x.Parameters.Select((pp, i) => Same(pp.ParameterType, s.Args[i])).All(b => b)).ToList();
        if (list.Count == 1) { p.Target = list[0]; return; }
        // 原 Mod 有的补丁目标在基类上
        if (list.Count == 0 && s.MethodType == 0 && t.BaseType != null && s.Name != null)
        {
            Resolve(new Spec { Type = t.BaseType, Name = s.Name, Args = s.Args }, p);
            if (p.Target != null) return;
        }
        p.TargetText = $"{t.FullName}::{s.Name} 候选 {list.Count} 个（MethodType={s.MethodType}）";
    }

    static bool Same(TypeReference a, TypeReference b)
    {
        string Strip(TypeReference t) => (t is ByReferenceType br ? br.ElementType : t).FullName;
        return Strip(a) == Strip(b);
    }

    public static void Print(List<Patch> list)
    {
        foreach (var g in list.GroupBy(p => p.Target?.Module.Name ?? "（未解析）").OrderBy(g => g.Key))
            Console.WriteLine($"{g.Key}: {g.Count()}");
        Console.WriteLine();
        foreach (var p in list.OrderBy(p => p.Target?.FullName ?? p.TargetText))
        {
            string target = p.Target != null ? p.Target.DeclaringType.FullName + "::" + p.Target.Name + "(" + string.Join(",", p.Target.Parameters.Select(x => x.ParameterType.Name)) + ")"
                + (p.Target.IsStatic ? " static" : "") + (p.Target.IsVirtual ? " virtual" : "") + (p.Target.IsAbstract ? " ABSTRACT" : "") + (p.Target.HasBody ? "" : " NOBODY")
                + (p.Target.HasGenericParameters || p.Target.DeclaringType.HasGenericParameters ? " GENERIC" : "")
                : "!! " + p.TargetText;
            string args = string.Join(",", p.Method.Parameters.Select(x => (x.ParameterType.IsByReference ? "ref " : "") + x.Name));
            Console.WriteLine($"{p.Kind,-10} {p.Class.Name}.{p.Method.Name}({args}) -> {p.Method.ReturnType.Name} | {target}" + (p.Priority != 400 ? " prio=" + p.Priority : ""));
        }
    }
}
