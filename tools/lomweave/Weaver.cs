using Mono.Cecil;
using Mono.Cecil.Cil;
using Mono.Cecil.Rocks;

// 把咖啡伴侣的 Harmony 补丁在编译前织进游戏程序集。
// 每个被补丁的方法：原方法体移到「原名__lomorig」，原方法改成包装：前置 → 原方法 → 后置，有 Finalizer 时整体 try/catch。
// 每个补丁前都判断 HarmonyLib.LomWeave.On[编号]，由替身的 PatchAll/Patch 在运行时打开，保留原 Mod 按配置开关补丁的行为。
sealed class Weaver
{
    // 0 前置 1 后置 2 Finalizer 3 调用点替换（原 Mod 的 Transpiler）
    public sealed class Entry
    {
        public int Id;
        public MethodDefinition Patch;   // LOMAssistant 里的补丁方法
        public MethodDefinition Target;  // 游戏里的目标方法
        public int Kind;
        public int Priority = 400;
        public MethodReference CallFrom; // Kind 3：被替换的调用
    }

    readonly ModuleDefinition mod;            // LOMAssistant
    readonly Func<string, ModuleDefinition> game;
    readonly List<Entry> entries = new();
    readonly HashSet<ModuleDefinition> touched = new();
    public IReadOnlyList<Entry> Entries => entries;
    // 同优先级时按原 Mod 运行时 PatchAll 的先后（Harmony 的排序规则）
    public Func<TypeDefinition, int> ClassOrder = _ => int.MaxValue;
    public IEnumerable<ModuleDefinition> Touched => touched;
    public readonly List<string> Log = new();

    public Weaver(ModuleDefinition mod, Func<string, ModuleDefinition> game) { this.mod = mod; this.game = game; }

    // ---------------- 登记 ----------------

    public void AddFromInventory(List<Inventory.Patch> patches)
    {
        foreach (var p in patches)
        {
            if (p.Kind == "Transpiler") continue; // 单独按调用点替换处理
            if (p.Target == null) { Log.Add("未解析，跳过：" + p.Class.FullName + "." + p.Method.Name + "：" + p.TargetText); continue; }
            Add(p.Method, Mine(p.Target), p.Kind switch { "Prefix" => 0, "Postfix" => 1, _ => 2 }, p.Priority);
        }
    }

    public Entry Add(MethodDefinition patch, MethodDefinition target, int kind, int priority = 400, MethodReference callFrom = null)
    {
        var e = new Entry { Id = entries.Count, Patch = patch, Target = target, Kind = kind, Priority = priority, CallFrom = callFrom };
        entries.Add(e);
        return e;
    }

    // 目标方法一律取我们自己读进来的那份游戏模块里的定义（要写回的是它）
    public MethodDefinition Mine(MethodDefinition m)
    {
        var module = game(m.Module.Assembly.Name.Name);
        if (module == null) throw new Exception("游戏程序集没读进来：" + m.Module.Assembly.Name.Name);
        var type = module.GetType(m.DeclaringType.FullName) ?? module.GetTypes().First(t => t.FullName == m.DeclaringType.FullName);
        return type.Methods.Single(x => x.Name == m.Name && x.Parameters.Count == m.Parameters.Count
            && x.Parameters.Select((pp, i) => pp.ParameterType.FullName == m.Parameters[i].ParameterType.FullName).All(b => b));
    }

    // ---------------- 织入 ----------------

    public void WeaveAll()
    {
        foreach (var g in entries.Where(e => e.Kind != 3).GroupBy(e => e.Target))
            Wrap(g.Key, g.ToList());
        foreach (var g in entries.Where(e => e.Kind == 3).GroupBy(e => e.Target))
            Redirect(g.Key, g.ToList());
        foreach (var e in entries) Expose(e.Patch);
    }

    // 补丁方法及其所在的（嵌套）类要能从游戏程序集调用
    static void Expose(MethodDefinition m)
    {
        m.IsPrivate = false; m.IsFamily = false; m.IsAssembly = false; m.IsFamilyOrAssembly = false; m.IsPublic = true;
        for (var t = m.DeclaringType; t != null; t = t.DeclaringType)
        {
            if (t.IsNested) { t.IsNestedPrivate = false; t.IsNestedAssembly = false; t.IsNestedFamily = false; t.IsNestedPublic = true; }
            else t.IsPublic = true;
        }
    }

    MethodReference OnField(ModuleDefinition target, out FieldReference on)
    {
        var lw = mod.AssemblyResolver.Resolve(mod.AssemblyReferences.First(a => a.Name == "0Harmony")).MainModule.GetType("HarmonyLib.LomWeave");
        on = target.ImportReference(lw.Fields.Single(f => f.Name == "On"));
        return null;
    }

    void Wrap(MethodDefinition target, List<Entry> list)
    {
        var module = target.Module;
        touched.Add(module);
        if (!target.HasBody) throw new Exception("目标没有方法体：" + target.FullName);
        if (target.IsConstructor) throw new Exception("暂不支持构造函数目标：" + target.FullName);

        // 1. 原方法体搬到 原名__lomorig
        var orig = new MethodDefinition(target.Name + "__lomorig",
            MethodAttributes.Private | MethodAttributes.HideBySig | (target.IsStatic ? MethodAttributes.Static : 0), target.ReturnType);
        foreach (var p in target.Parameters) orig.Parameters.Add(new ParameterDefinition(p.Name, p.Attributes, p.ParameterType));
        target.DeclaringType.Methods.Add(orig);
        MoveBody(target, orig);

        // 2. 新的包装方法体
        var body = target.Body;
        body.InitLocals = true;
        var il = body.GetILProcessor();
        var voidRet = target.ReturnType.MetadataType == MetadataType.Void;
        var result = voidRet ? null : new VariableDefinition(target.ReturnType);
        var runOriginal = new VariableDefinition(module.TypeSystem.Boolean);
        var exType = CoreRefs.Type(module, "System", "Exception");
        var ex = new VariableDefinition(exType);
        if (result != null) body.Variables.Add(result);
        body.Variables.Add(runOriginal); body.Variables.Add(ex);
        OnField(module, out var on);

        // __state：同一个补丁类的前置/后置/Finalizer 共用一个
        var states = new Dictionary<TypeDefinition, VariableDefinition>();
        VariableDefinition StateOf(MethodDefinition patch, TypeReference type)
        {
            if (!states.TryGetValue(patch.DeclaringType, out var v))
            {
                v = new VariableDefinition(module.ImportReference(type is ByReferenceType br ? br.ElementType : type));
                body.Variables.Add(v); states[patch.DeclaringType] = v;
            }
            return v;
        }

        var prefixes = list.Where(e => e.Kind == 0).OrderByDescending(e => e.Priority).ThenBy(e => ClassOrder(e.Patch.DeclaringType)).ThenBy(e => e.Id).ToList();
        var postfixes = list.Where(e => e.Kind == 1).OrderByDescending(e => e.Priority).ThenBy(e => ClassOrder(e.Patch.DeclaringType)).ThenBy(e => e.Id).ToList();
        var finalizers = list.Where(e => e.Kind == 2).OrderByDescending(e => e.Priority).ThenBy(e => ClassOrder(e.Patch.DeclaringType)).ThenBy(e => e.Id).ToList();
        bool guarded = finalizers.Count > 0;

        var retLabel = Instruction.Create(OpCodes.Nop);
        Instruction tryStart = null, tryEnd = null, handlerStart = null, handlerEnd = null;

        il.Append(il.Create(OpCodes.Ldnull)); il.Append(il.Create(OpCodes.Stloc, ex));
        il.Append(il.Create(OpCodes.Ldc_I4_1)); il.Append(il.Create(OpCodes.Stloc, runOriginal));
        if (guarded) { tryStart = il.Create(OpCodes.Nop); il.Append(tryStart); }

        foreach (var e in prefixes)
        {
            var skip = il.Create(OpCodes.Nop);
            Gate(il, on, e.Id, skip);
            PushArgs(il, target, e, result, runOriginal, ex, StateOf);
            il.Append(il.Create(OpCodes.Call, module.ImportReference(e.Patch)));
            if (e.Patch.ReturnType.MetadataType == MetadataType.Boolean)
            {
                var keep = il.Create(OpCodes.Nop);
                il.Append(il.Create(OpCodes.Brtrue, keep));
                il.Append(il.Create(OpCodes.Ldc_I4_0)); il.Append(il.Create(OpCodes.Stloc, runOriginal));
                il.Append(keep);
            }
            else if (e.Patch.ReturnType.MetadataType != MetadataType.Void) il.Append(il.Create(OpCodes.Pop));
            il.Append(skip);
        }

        var afterOrig = il.Create(OpCodes.Nop);
        il.Append(il.Create(OpCodes.Ldloc, runOriginal));
        il.Append(il.Create(OpCodes.Brfalse, afterOrig));
        if (!target.IsStatic) il.Append(il.Create(OpCodes.Ldarg_0));
        for (int i = 0; i < target.Parameters.Count; i++) il.Append(il.Create(OpCodes.Ldarg, target.Parameters[i]));
        il.Append(il.Create(OpCodes.Call, MakeSelfRef(orig, target)));
        if (result != null) il.Append(il.Create(OpCodes.Stloc, result));
        il.Append(afterOrig);

        foreach (var e in postfixes)
        {
            var skip = il.Create(OpCodes.Nop);
            Gate(il, on, e.Id, skip);
            PushArgs(il, target, e, result, runOriginal, ex, StateOf);
            il.Append(il.Create(OpCodes.Call, module.ImportReference(e.Patch)));
            // 透传式后置：返回新的结果
            if (result != null && e.Patch.ReturnType.FullName == target.ReturnType.FullName) il.Append(il.Create(OpCodes.Stloc, result));
            else if (e.Patch.ReturnType.MetadataType != MetadataType.Void) il.Append(il.Create(OpCodes.Pop));
            il.Append(skip);
        }

        if (guarded)
        {
            var leaveTarget = il.Create(OpCodes.Nop);
            tryEnd = il.Create(OpCodes.Leave, leaveTarget);
            il.Append(tryEnd);
            handlerStart = il.Create(OpCodes.Stloc, ex);
            il.Append(handlerStart);
            il.Append(il.Create(OpCodes.Leave, leaveTarget));
            handlerEnd = leaveTarget;
            il.Append(leaveTarget);
            body.ExceptionHandlers.Add(new ExceptionHandler(ExceptionHandlerType.Catch)
            {
                CatchType = exType, TryStart = tryStart, TryEnd = handlerStart, HandlerStart = handlerStart, HandlerEnd = handlerEnd,
            });

            foreach (var e in finalizers)
            {
                var skip = il.Create(OpCodes.Nop);
                Gate(il, on, e.Id, skip);
                PushArgs(il, target, e, result, runOriginal, ex, StateOf);
                il.Append(il.Create(OpCodes.Call, module.ImportReference(e.Patch)));
                if (e.Patch.ReturnType.FullName == "System.Exception") il.Append(il.Create(OpCodes.Stloc, ex));
                else if (e.Patch.ReturnType.MetadataType != MetadataType.Void) il.Append(il.Create(OpCodes.Pop));
                il.Append(skip);
            }
            var noThrow = il.Create(OpCodes.Nop);
            il.Append(il.Create(OpCodes.Ldloc, ex));
            il.Append(il.Create(OpCodes.Brfalse, noThrow));
            il.Append(il.Create(OpCodes.Ldloc, ex));
            il.Append(il.Create(OpCodes.Throw));
            il.Append(noThrow);
        }

        if (result != null) il.Append(il.Create(OpCodes.Ldloc, result));
        il.Append(il.Create(OpCodes.Ret));
        body.Optimize();
    }

    static void Gate(ILProcessor il, FieldReference on, int id, Instruction skip)
    {
        il.Append(il.Create(OpCodes.Ldsfld, on));
        il.Append(il.Create(OpCodes.Ldc_I4, id));
        il.Append(il.Create(OpCodes.Ldelem_U1));
        il.Append(il.Create(OpCodes.Brfalse, skip));
    }

    // 泛型类里的方法要用实例化后的引用调用（本 Mod 没有，留着防御）
    static MethodReference MakeSelfRef(MethodDefinition m, MethodDefinition context)
    {
        if (!m.DeclaringType.HasGenericParameters) return m;
        var inst = new GenericInstanceType(m.DeclaringType);
        foreach (var gp in m.DeclaringType.GenericParameters) inst.GenericArguments.Add(gp);
        var r = new MethodReference(m.Name, m.ReturnType, inst) { HasThis = m.HasThis, ExplicitThis = m.ExplicitThis, CallingConvention = m.CallingConvention };
        foreach (var p in m.Parameters) r.Parameters.Add(new ParameterDefinition(p.ParameterType));
        return r;
    }

    // 按 Harmony 的参数约定把实参压栈
    void PushArgs(ILProcessor il, MethodDefinition target, Entry e, VariableDefinition result, VariableDefinition runOriginal, VariableDefinition ex,
        Func<MethodDefinition, TypeReference, VariableDefinition> stateOf)
    {
        var module = target.Module;
        foreach (var p in e.Patch.Parameters)
        {
            bool byRef = p.ParameterType.IsByReference;
            var want = byRef ? ((ByReferenceType)p.ParameterType).ElementType : p.ParameterType;
            string n = p.Name;
            if (n == "__instance")
            {
                if (target.IsStatic) { il.Append(il.Create(OpCodes.Ldnull)); continue; }
                il.Append(il.Create(OpCodes.Ldarg_0));
                if (target.DeclaringType.IsValueType && !byRef) il.Append(il.Create(OpCodes.Ldobj, target.DeclaringType));
                continue;
            }
            if (n == "__result")
            {
                if (result == null) throw new Exception("void 方法没有 __result：" + e.Patch.FullName);
                if (byRef) il.Append(il.Create(OpCodes.Ldloca, result));
                else { il.Append(il.Create(OpCodes.Ldloc, result)); Convert(il, module, target.ReturnType, want); }
                continue;
            }
            if (n == "__state") { var v = stateOf(e.Patch, p.ParameterType); il.Append(il.Create(byRef ? OpCodes.Ldloca : OpCodes.Ldloc, v)); continue; }
            if (n == "__runOriginal") { il.Append(il.Create(byRef ? OpCodes.Ldloca : OpCodes.Ldloc, runOriginal)); continue; }
            if (n == "__exception") { il.Append(il.Create(byRef ? OpCodes.Ldloca : OpCodes.Ldloc, ex)); continue; }
            if (n == "__originalMethod")
            {
                il.Append(il.Create(OpCodes.Ldtoken, target));
                il.Append(il.Create(OpCodes.Ldtoken, target.DeclaringType));
                il.Append(il.Create(OpCodes.Call, CoreRefs.GetMethodFromHandle(module)));
                continue;
            }
            if (n.StartsWith("___"))
            {
                var field = FindField(target.DeclaringType, n.Substring(3)) ?? throw new Exception("找不到字段 " + n + "：" + e.Patch.FullName);
                field.IsPrivate = false; field.IsFamily = false; field.IsAssembly = false; field.IsPublic = true;
                var fr = module.ImportReference(field);
                if (field.IsStatic) il.Append(il.Create(byRef ? OpCodes.Ldsflda : OpCodes.Ldsfld, fr));
                else { il.Append(il.Create(OpCodes.Ldarg_0)); il.Append(il.Create(byRef ? OpCodes.Ldflda : OpCodes.Ldfld, fr)); }
                continue;
            }
            int index = -1;
            if (n.StartsWith("__") && int.TryParse(n.Substring(2), out int k)) index = k;
            else index = target.Parameters.ToList().FindIndex(x => x.Name == n);
            if (index < 0 || index >= target.Parameters.Count) throw new Exception("补丁参数 " + n + " 对不上原方法：" + e.Patch.FullName + " -> " + target.FullName);
            var tp = target.Parameters[index];
            bool srcRef = tp.ParameterType.IsByReference;
            var srcType = srcRef ? ((ByReferenceType)tp.ParameterType).ElementType : tp.ParameterType;
            if (byRef) il.Append(il.Create(srcRef ? OpCodes.Ldarg : OpCodes.Ldarga, tp));
            else
            {
                il.Append(il.Create(OpCodes.Ldarg, tp));
                if (srcRef) il.Append(il.Create(OpCodes.Ldobj, srcType));
                Convert(il, module, srcType, want);
            }
        }
    }

    // 值类型交给 object 形参时装箱；其余类型 Harmony 要求一致
    static void Convert(ILProcessor il, ModuleDefinition module, TypeReference from, TypeReference to)
    {
        if (from.FullName == to.FullName) return;
        if (from.IsValueType && !to.IsValueType) il.Append(il.Create(OpCodes.Box, module.ImportReference(from)));
    }

    static FieldDefinition FindField(TypeDefinition t, string name)
    {
        for (; t != null; t = t.BaseType?.Resolve()) { var f = t.Fields.FirstOrDefault(x => x.Name == name); if (f != null) return f; }
        return null;
    }

    // 把 from 的方法体（指令、局部变量、异常块）整体搬给 to；参数按序号对应
    static void MoveBody(MethodDefinition from, MethodDefinition to)
    {
        var src = from.Body;
        var dst = to.Body;
        dst.InitLocals = src.InitLocals;
        dst.MaxStackSize = src.MaxStackSize;
        foreach (var v in src.Variables) dst.Variables.Add(v);
        var map = new Dictionary<ParameterDefinition, ParameterDefinition>();
        for (int i = 0; i < from.Parameters.Count; i++) map[from.Parameters[i]] = to.Parameters[i];
        if (from.HasThis) map[src.ThisParameter] = dst.ThisParameter;
        foreach (var ins in src.Instructions)
        {
            if (ins.Operand is ParameterDefinition pd && map.TryGetValue(pd, out var np)) ins.Operand = np;
            dst.Instructions.Add(ins);
        }
        foreach (var h in src.ExceptionHandlers) dst.ExceptionHandlers.Add(h);
        // 原方法体清空，留给包装
        from.Body = new MethodBody(from);
    }

    // ---------------- Transpiler：调用点替换 ----------------

    // 目标方法里每处 call CallFrom 改成调用一个分派方法：开关开着走原 Mod 的替换方法，否则走原调用
    void Redirect(MethodDefinition target, List<Entry> list)
    {
        var module = target.Module;
        touched.Add(module);
        OnField(module, out var on);
        foreach (var e in list)
        {
            var from = module.ImportReference(e.CallFrom);
            var disp = new MethodDefinition("__lomcall" + e.Id, MethodAttributes.Private | MethodAttributes.Static | MethodAttributes.HideBySig, from.ReturnType);
            foreach (var p in e.CallFrom.Parameters) disp.Parameters.Add(new ParameterDefinition(p.Name, p.Attributes, module.ImportReference(p.ParameterType)));
            target.DeclaringType.Methods.Add(disp);
            var il = disp.Body.GetILProcessor();
            var orig = il.Create(OpCodes.Nop);
            Gate(il, on, e.Id, orig);
            foreach (var p in disp.Parameters) il.Append(il.Create(OpCodes.Ldarg, p));
            il.Append(il.Create(OpCodes.Call, module.ImportReference(e.Patch)));
            il.Append(il.Create(OpCodes.Ret));
            il.Append(orig);
            foreach (var p in disp.Parameters) il.Append(il.Create(OpCodes.Ldarg, p));
            il.Append(il.Create(OpCodes.Call, from));
            il.Append(il.Create(OpCodes.Ret));
            int n = 0;
            foreach (var ins in target.Body.Instructions)
                if ((ins.OpCode == OpCodes.Call || ins.OpCode == OpCodes.Callvirt) && ins.Operand is MethodReference mr && mr.FullName == e.CallFrom.FullName)
                { ins.OpCode = OpCodes.Call; ins.Operand = disp; n++; }
            if (n == 0) Log.Add("调用点替换：" + target.FullName + " 里没有 " + e.CallFrom.FullName);
        }
    }

    // ---------------- 登记表 ----------------

    // 在 LOMAssistant 里生成 LOMAssistant.LomWeaveTable，替身靠它回答 PatchAll/Patch/GetPatchInfo
    public void EmitTable()
    {
        var ts = mod.TypeSystem;
        var table = new TypeDefinition("LOMAssistant", "LomWeaveTable",
            TypeAttributes.Public | TypeAttributes.Abstract | TypeAttributes.Sealed | TypeAttributes.BeforeFieldInit, ts.Object);
        mod.Types.Add(table);
        var typeT = CoreRefs.Type(mod, "System", "Type");
        var mbT = CoreRefs.Type(mod, "System.Reflection", "MethodBase");
        FieldDefinition F(string name, TypeReference elem)
        {
            var f = new FieldDefinition(name, FieldAttributes.Public | FieldAttributes.Static | FieldAttributes.InitOnly, new ArrayType(elem));
            table.Fields.Add(f); return f;
        }
        var fTypes = F("PatchTypes", typeT); var fNames = F("PatchNames", ts.String); var fKinds = F("Kinds", ts.Int32);
        var fPrio = F("Priorities", ts.Int32); var fTargets = F("Targets", mbT);
        var cctor = new MethodDefinition(".cctor", MethodAttributes.Private | MethodAttributes.Static | MethodAttributes.HideBySig | MethodAttributes.SpecialName | MethodAttributes.RTSpecialName, ts.Void);
        table.Methods.Add(cctor);
        var il = cctor.Body.GetILProcessor();
        var getType = CoreRefs.GetTypeFromHandle(mod);
        var getMethod = CoreRefs.GetMethodFromHandle(mod);
        void NewArray(FieldDefinition f, TypeReference elem, Action<int, Entry> item)
        {
            il.Append(il.Create(OpCodes.Ldc_I4, entries.Count));
            il.Append(il.Create(OpCodes.Newarr, elem));
            foreach (var e in entries) { il.Append(il.Create(OpCodes.Dup)); il.Append(il.Create(OpCodes.Ldc_I4, e.Id)); item(e.Id, e); }
            il.Append(il.Create(OpCodes.Stsfld, f));
        }
        NewArray(fTypes, typeT, (i, e) => { il.Append(il.Create(OpCodes.Ldtoken, mod.ImportReference(e.Patch.DeclaringType))); il.Append(il.Create(OpCodes.Call, getType)); il.Append(il.Create(OpCodes.Stelem_Ref)); });
        NewArray(fNames, ts.String, (i, e) => { il.Append(il.Create(OpCodes.Ldstr, e.Patch.Name)); il.Append(il.Create(OpCodes.Stelem_Ref)); });
        NewArray(fKinds, ts.Int32, (i, e) => { il.Append(il.Create(OpCodes.Ldc_I4, e.Kind)); il.Append(il.Create(OpCodes.Stelem_I4)); });
        NewArray(fPrio, ts.Int32, (i, e) => { il.Append(il.Create(OpCodes.Ldc_I4, e.Priority)); il.Append(il.Create(OpCodes.Stelem_I4)); });
        NewArray(fTargets, mbT, (i, e) =>
        {
            il.Append(il.Create(OpCodes.Ldtoken, mod.ImportReference(e.Target)));
            il.Append(il.Create(OpCodes.Ldtoken, mod.ImportReference(e.Target.DeclaringType)));
            il.Append(il.Create(OpCodes.Call, getMethod));
            il.Append(il.Create(OpCodes.Stelem_Ref));
        });
        il.Append(il.Create(OpCodes.Ret));
    }
}
