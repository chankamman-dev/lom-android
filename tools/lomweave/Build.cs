using Mono.Cecil;
using Mono.Cecil.Cil;

// lomweave weave <stage 目录> <咖啡伴侣目录> <替身目录> <unityaot 目录> <输出目录>
// 输出目录 = stage 的全部程序集 + 替身 + 咖啡伴侣（含 Newtonsoft、Mono.Cecil），其中被补丁的游戏程序集、LOMAssistant、Newtonsoft 是织入后的版本。
static class Weave
{
    // 读进内存再写回同一路径；同名程序集只读一次，补丁目标和写回的是同一份定义
    sealed class InMemResolver : BaseAssemblyResolver
    {
        readonly Dictionary<string, AssemblyDefinition> cache = new(StringComparer.OrdinalIgnoreCase);
        public IEnumerable<AssemblyDefinition> Loaded => cache.Values;
        public override AssemblyDefinition Resolve(AssemblyNameReference name)
        {
            if (cache.TryGetValue(name.Name, out var a)) return a;
            a = base.Resolve(name, new ReaderParameters { AssemblyResolver = this, InMemory = true, ReadingMode = ReadingMode.Immediate });
            cache[name.Name] = a;
            return a;
        }
        public AssemblyDefinition Read(string path)
        {
            var a = AssemblyDefinition.ReadAssembly(path, new ReaderParameters { AssemblyResolver = this, InMemory = true, ReadingMode = ReadingMode.Immediate });
            cache[a.Name.Name] = a;
            return a;
        }
    }

    public static int Run(string stage, string modDir, string shimDir, string aot, string outDir)
    {
        Directory.CreateDirectory(outDir);
        foreach (var f in Directory.GetFiles(outDir, "*.dll")) File.Delete(f);
        foreach (var f in Directory.GetFiles(stage, "*.dll")) File.Copy(f, Path.Combine(outDir, Path.GetFileName(f)));
        foreach (var f in Directory.GetFiles(shimDir, "*.dll")) File.Copy(f, Path.Combine(outDir, Path.GetFileName(f)), true);
        foreach (var name in new[] { "LOMAssistant.dll", "Newtonsoft.Json.dll", "Mono.Cecil.dll" })
            File.Copy(Path.Combine(modDir, name), Path.Combine(outDir, name), true);

        var resolver = new InMemResolver();
        foreach (var d in resolver.GetSearchDirectories()) resolver.RemoveSearchDirectory(d);
        resolver.AddSearchDirectory(outDir);
        resolver.AddSearchDirectory(aot);
        resolver.AddSearchDirectory(Path.Combine(aot, "Facades"));

        var lomAsm = resolver.Read(Path.Combine(outDir, "LOMAssistant.dll"));
        var json = resolver.Read(Path.Combine(outDir, "Newtonsoft.Json.dll"));
        var mod = lomAsm.MainModule;
        ModuleDefinition Game(string name) => resolver.Resolve(new AssemblyNameReference(name, null)).MainModule;

        var weaver = new Weaver(mod, Game);
        var order = PatchAllOrder(mod);
        weaver.ClassOrder = t => order.TryGetValue(t.FullName, out int i) ? i : int.MaxValue;
        var patches = Inventory.Read(mod);
        weaver.AddFromInventory(patches);
        Special(mod, weaver, Game);

        // 下屏伴侣的桥接（lom-thor-probe 的 LOMThorBridge.dll）：它的补丁目标在咖啡伴侣内部，织法相同
        AssemblyDefinition bridgeAsm = null;
        string bridgePath = Environment.GetEnvironmentVariable("LOM_BRIDGE");
        if (!string.IsNullOrEmpty(bridgePath))
        {
            File.Copy(bridgePath, Path.Combine(outDir, "LOMThorBridge.dll"), true);
            bridgeAsm = resolver.Read(Path.Combine(outDir, "LOMThorBridge.dll"));
            weaver.AddFromInventory(Inventory.Read(bridgeAsm.MainModule));
            SpecialBridge(bridgeAsm.MainModule, mod, weaver);
        }

        var log = weaver.Log;
        int refs = Fixups.FieldRefs(mod, log);
        var location = mod.ImportReference(Game("BepInEx.Unity.Mono").GetType("BepInEx.Unity.Mono.LomBoot").Methods.Single(m => m.Name == "AssemblyLocation"));
        int loc = Fixups.AssemblyLocation(mod, location);
        var lcmap = mod.ImportReference(Game("BepInEx.Unity.Mono").GetType("BepInEx.Unity.Mono.LomBoot").Methods.Single(m => m.Name == "LCMapStringEx"));
        int cjk = Fixups.PInvoke(mod, "kernel32.dll", "LCMapStringEx", lcmap);
        if (cjk == 0) throw new Exception("没找到咖啡伴侣对 LCMapStringEx 的调用，简繁转换的写法变了");
        Fixups.NewtonsoftNoEmit(json.MainModule);

        weaver.WeaveAll();
        weaver.EmitTable();

        var written = new HashSet<string>();
        void Write(AssemblyDefinition a)
        {
            if (!written.Add(a.Name.Name)) return;
            a.Write(Path.Combine(outDir, a.Name.Name + ".dll"));
        }
        foreach (var m in weaver.Touched) Write(m.Assembly);
        Write(lomAsm); Write(json);
        if (bridgeAsm != null) Write(bridgeAsm);

        File.WriteAllLines(Path.Combine(outDir, "lomweave.log"), log.Concat(weaver.Entries.Select(e =>
            $"#{e.Id} {"前置 后置 收尾 换调用".Split(' ')[e.Kind]} {e.Patch.DeclaringType.Name}.{e.Patch.Name} -> {e.Target.DeclaringType.FullName}.{e.Target.Name}" + (e.CallFrom != null ? " 调用 " + e.CallFrom.Name : ""))));
        Console.WriteLine($"织入 {weaver.Entries.Count} 个补丁，涉及 {weaver.Touched.Count()} 个游戏程序集（{string.Join(", ", weaver.Touched.Select(m => m.Assembly.Name.Name))}）");
        Console.WriteLine($"FieldRef 改写 {refs} 处，Assembly.Location 改写 {loc} 处，简繁转换改写 {cjk} 处，Newtonsoft 改为纯反射；未解析 {log.Count(l => l.StartsWith("未解析"))} 个，详见 lomweave.log");
        return 0;
    }

    // LOMAssistantPlugin 里 TryPatchAll(typeof(X)) 的出现顺序（ApplyPatches 优先），即 Harmony 登记补丁的先后
    static Dictionary<string, int> PatchAllOrder(ModuleDefinition mod)
    {
        var plugin = mod.GetType("LOMAssistant.LOMAssistantPlugin");
        var order = new Dictionary<string, int>();
        foreach (var m in plugin.Methods.OrderBy(m => m.Name == "ApplyPatches" ? 0 : 1).Where(m => m.HasBody))
        {
            var ins = m.Body.Instructions;
            for (int i = 0; i + 2 < ins.Count; i++)
                if (ins[i].OpCode == OpCodes.Ldtoken && ins[i].Operand is TypeReference t
                    && ins[i + 2].Operand is MethodReference call && (call.Name == "TryPatchAll" || call.Name == "PatchAll"))
                    order.TryAdd(t.FullName, order.Count);
        }
        if (order.Count == 0) throw new Exception("没在 LOMAssistantPlugin 里找到 TryPatchAll(typeof(...))，原 Mod 的启用方式变了");
        return order;
    }

    // 桥接在运行时用 harmony.Patch 挂的 9 个补丁（HintRelay 构造函数、ModPages 构造函数），照它的代码列出来；
    // 运行时它照常调用 Patch()，替身按「目标 + 补丁方法」打开这里织好的那一个。
    static void SpecialBridge(ModuleDefinition bridge, ModuleDefinition mod, Weaver w)
    {
        MethodDefinition Method(string type, string name, Func<MethodDefinition, bool> pick = null)
        {
            var t = mod.GetType(type) ?? throw new Exception("咖啡伴侣里没有 " + type);
            var ms = t.Methods.Where(m => m.Name == name && (pick == null || pick(m))).ToList();
            if (ms.Count != 1) throw new Exception($"{type}.{name} 有 {ms.Count} 个候选");
            return ms[0];
        }
        MethodDefinition Patch(string type, string name) => bridge.GetType(type).Methods.Single(m => m.Name == name);

        // 两份窗口清单直接从桥接的静态数组读（HintRelay.Windows「编号|类型」、ModPages.RedirectWindows），与它运行时挂的补丁一致
        int n = 0;
        var hint = Patch("LOMThor.HintRelay", "BeforeWindow");
        foreach (var s in StaticStrings(bridge.GetType("LOMThor.HintRelay"), "Windows"))
        { w.Add(hint, Method(s.Substring(s.IndexOf('|') + 1), "OnGUI", m => m.Parameters.Count == 0), 0); n++; }
        w.Add(Patch("LOMThor.ModPages", "AfterPanelUpdate"), Method("LOMAssistant.Features.Story.StoryReviewPanel", "Update", m => m.Parameters.Count == 0), 1); n++;
        var window = Patch("LOMThor.ModPages", "BeforeWindow");
        foreach (var t in StaticStrings(bridge.GetType("LOMThor.ModPages"), "RedirectWindows"))
        { w.Add(window, Method(t, "OnGUI", m => m.Parameters.Count == 0), 0); n++; }
        w.Add(Patch("LOMThor.ModPages", "Hotkey"), Method("LOMAssistant.InputGuard", "HotkeyDown", m => m.Parameters.Count == 1 && m.Parameters[0].ParameterType.FullName == "UnityEngine.KeyCode"), 0); n++;
        w.Log.Add($"桥接：{n} 个手动补丁已登记");
    }

    // 静态字符串数组字段在 .cctor 里的初值：从 stsfld 往回找到 newarr，中间的 ldstr 按顺序就是元素
    static List<string> StaticStrings(TypeDefinition type, string field)
    {
        var ins = type.Methods.Single(m => m.Name == ".cctor").Body.Instructions;
        int store = ins.ToList().FindIndex(i => i.OpCode == OpCodes.Stsfld && ((FieldReference)i.Operand).Name == field);
        if (store < 0) throw new Exception($"{type.Name}.{field} 不是静态数组初值");
        int start = store;
        while (start > 0 && ins[start].OpCode != OpCodes.Newarr) start--;
        var list = ins.Skip(start).Take(store - start).Where(i => i.OpCode == OpCodes.Ldstr).Select(i => (string)i.Operand).ToList();
        if (list.Count == 0) throw new Exception($"{type.Name}.{field} 是空的");
        return list;
    }

    // 原 Mod 在运行时才算出目标、或用 Transpiler 的几处：照它的代码在编译期算好
    static void Special(ModuleDefinition mod, Weaver w, Func<string, ModuleDefinition> game)
    {
        var gameTypes = new Dictionary<string, TypeDefinition>();
        foreach (var name in new[] { "Mortal.Core", "Mortal.Story", "Mortal.Free", "Mortal.Battle", "Mortal.Combat", "Fungus" })
            foreach (var t in game(name).GetTypes()) gameTypes.TryAdd(t.FullName, t);

        // InputGuardActionGate：静态字段 Targets 里的 "类型:方法"，参数是 InputAction.CallbackContext
        var gate = mod.GetType("LOMAssistant.InputGuardActionGate");
        var prefix = gate.Methods.Single(m => m.Name == "Prefix");
        var ctxType = prefix.Parameters[0].ParameterType.FullName;
        int found = 0, missing = 0;
        foreach (var s in gate.Methods.Single(m => m.Name == ".cctor").Body.Instructions.Where(i => i.OpCode == OpCodes.Ldstr).Select(i => (string)i.Operand))
        {
            int colon = s.IndexOf(':');
            if (colon < 0) continue;
            var target = gameTypes.TryGetValue(s.Substring(0, colon), out var t)
                ? t.Methods.SingleOrDefault(m => m.Name == s.Substring(colon + 1) && m.Parameters.Count == 1 && m.Parameters[0].ParameterType.FullName == ctxType) : null;
            if (target == null) { missing++; w.Log.Add("输入隔离：游戏里没有 " + s + "，跳过（原 Mod 运行时也会跳过）"); continue; }
            w.Add(prefix, target, 0); found++;
        }
        w.Log.Add($"输入隔离：{found} 个目标，{missing} 个游戏里没有");

        // SaveDataCache.GetSaveDataPatch：SaveSystem.GetSaveData(string) -> GameSave（非泛型那个）
        var gsp = mod.GetType("LOMAssistant.Features.Extras.SaveDataCache").NestedTypes.Single(t => t.Name == "GetSaveDataPatch");
        var saveSystem = gameTypes["Mortal.Core.SaveSystem"];
        var getSave = saveSystem.Methods.Single(m => m.Name == "GetSaveData" && !m.HasGenericParameters && m.ReturnType.FullName == "Mortal.Core.GameSave"
            && m.Parameters.Count == 1 && m.Parameters[0].ParameterType.FullName == "System.String");
        w.Add(gsp.Methods.Single(m => m.Name == "Prefix"), getSave, 0);
        w.Add(gsp.Methods.Single(m => m.Name == "Postfix"), getSave, 1);

        // InputGuardDialogGate 的 Transpiler：Fungus.DialogInput.Update 里三种 Input 调用换成原 Mod 的版本
        var dialog = mod.GetType("LOMAssistant.InputGuardDialogGate");
        var update = gameTypes["Fungus.DialogInput"].Methods.Single(m => m.Name == "Update" && m.Parameters.Count == 0);
        var input = game("UnityEngine.InputLegacyModule").GetType("UnityEngine.Input");
        foreach (var (name, param) in new[] { ("GetMouseButtonDown", "System.Int32"), ("GetButtonDown", "System.String"), ("GetKeyDown", "UnityEngine.KeyCode") })
        {
            var from = input.Methods.Single(m => m.Name == name && m.Parameters.Count == 1 && m.Parameters[0].ParameterType.FullName == param);
            var to = dialog.Methods.Single(m => m.Name == name);
            w.Add(to, update, 3, 400, from);
        }
    }
}
