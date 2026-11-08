using Mono.Cecil;
using Mono.Cecil.Cil;
using Mono.Cecil.Rocks;

// 织入时对咖啡伴侣和 Newtonsoft 本身做的改写：IL2CPP 下做不到的运行时手段，改成编译期就定好的等价写法。
static class Fixups
{
    // AccessTools.FieldRefAccess<T,F>("名") / StaticFieldRefAccess<F>(AccessTools.Field(typeof(X), "名"))
    // Harmony 靠动态生成代码返回字段引用；这里生成直接取字段地址的静态方法，调用点改成用它构造委托。
    public static int FieldRefs(ModuleDefinition mod, List<string> log)
    {
        var gen = new TypeDefinition("LOMAssistant", "LomFieldRefs", TypeAttributes.Public | TypeAttributes.Abstract | TypeAttributes.Sealed, mod.TypeSystem.Object);
        mod.Types.Add(gen);
        int n = 0;
        foreach (var method in mod.GetTypes().ToList().SelectMany(t => t.Methods).Where(m => m.HasBody).ToList())
        {
            var ins = method.Body.Instructions;
            for (int i = 0; i < ins.Count; i++)
            {
                if (ins[i].OpCode != OpCodes.Call || ins[i].Operand is not GenericInstanceMethod gm || gm.DeclaringType.FullName != "HarmonyLib.AccessTools") continue;
                bool instance = gm.Name == "FieldRefAccess" && gm.GenericArguments.Count == 2 && gm.Parameters.Count == 1 && gm.Parameters[0].ParameterType.FullName == "System.String";
                bool stat = gm.Name == "StaticFieldRefAccess" && gm.GenericArguments.Count == 1 && gm.Parameters[0].ParameterType.FullName == "System.Reflection.FieldInfo";
                if (!instance && !stat) continue;
                TypeReference owner; string name;
                if (instance)
                {
                    if (ins[i - 1].OpCode != OpCodes.Ldstr) throw new Exception("FieldRefAccess 的字段名不是常量：" + method.FullName);
                    owner = gm.GenericArguments[0]; name = (string)ins[i - 1].Operand;
                }
                else
                {
                    // ldtoken X; call GetTypeFromHandle; ldstr 名; call AccessTools.Field; call StaticFieldRefAccess
                    if (ins[i - 1].Operand is not MethodReference f || f.Name != "Field" || ins[i - 2].OpCode != OpCodes.Ldstr || ins[i - 4].OpCode != OpCodes.Ldtoken)
                        throw new Exception("StaticFieldRefAccess 的写法认不出：" + method.FullName);
                    owner = (TypeReference)ins[i - 4].Operand; name = (string)ins[i - 2].Operand;
                }
                var fieldType = gm.GenericArguments[instance ? 1 : 0];
                var fd = FindField(owner.Resolve(), name) ?? throw new Exception("找不到字段 " + owner.FullName + "." + name);
                fd.IsPrivate = false; fd.IsFamily = false; fd.IsAssembly = false; fd.IsPublic = true;
                var accessor = new MethodDefinition("Ref" + n, MethodAttributes.Public | MethodAttributes.Static | MethodAttributes.HideBySig, new ByReferenceType(mod.ImportReference(fieldType)));
                if (instance) accessor.Parameters.Add(new ParameterDefinition("instance", ParameterAttributes.None, mod.ImportReference(owner)));
                var ail = accessor.Body.GetILProcessor();
                if (instance) { ail.Append(ail.Create(OpCodes.Ldarg_0)); ail.Append(ail.Create(OpCodes.Ldflda, mod.ImportReference(fd))); }
                else ail.Append(ail.Create(OpCodes.Ldsflda, mod.ImportReference(fd)));
                ail.Append(ail.Create(OpCodes.Ret));
                gen.Methods.Add(accessor);

                // 委托类型 AccessTools.FieldRef<T,F> / FieldRef<F>，取它的构造函数
                var retGeneric = (GenericInstanceType)gm.ElementMethod.ReturnType;
                var delegateType = new GenericInstanceType(retGeneric.ElementType);
                foreach (var a in gm.GenericArguments) delegateType.GenericArguments.Add(a);
                var ctorDef = delegateType.Resolve().Methods.Single(m => m.IsConstructor && !m.IsStatic);
                var ctor = new MethodReference(".ctor", mod.TypeSystem.Void, delegateType) { HasThis = true };
                foreach (var p in ctorDef.Parameters) ctor.Parameters.Add(new ParameterDefinition(mod.ImportReference(p.ParameterType)));
                // 原调用的实参（字段名或 FieldInfo）出栈，换成委托
                var il = method.Body.GetILProcessor();
                var call = ins[i];
                il.InsertBefore(call, il.Create(OpCodes.Pop));
                il.InsertBefore(call, il.Create(OpCodes.Ldnull));
                il.InsertBefore(call, il.Create(OpCodes.Ldftn, accessor));
                call.OpCode = OpCodes.Newobj; call.Operand = mod.ImportReference(ctor);
                i += 3; n++;
                log.Add($"FieldRef {owner.Name}.{name} -> LomFieldRefs.{accessor.Name}（{method.DeclaringType.Name}.{method.Name}）");
            }
        }
        return n;
    }

    static FieldDefinition FindField(TypeDefinition t, string name)
    {
        for (; t != null; t = t.BaseType?.Resolve()) { var f = t.Fields.FirstOrDefault(x => x.Name == name); if (f != null) return f; }
        return null;
    }

    // IL2CPP 里 Assembly.Location 是空串；咖啡伴侣用它找自己的目录，改成问替身
    public static int AssemblyLocation(ModuleDefinition mod, MethodReference replacement)
    {
        int n = 0;
        foreach (var m in mod.GetTypes().SelectMany(t => t.Methods).Where(m => m.HasBody))
            foreach (var ins in m.Body.Instructions)
                if ((ins.OpCode == OpCodes.Callvirt || ins.OpCode == OpCodes.Call) && ins.Operand is MethodReference r
                    && r.Name == "get_Location" && r.DeclaringType.FullName == "System.Reflection.Assembly")
                { ins.OpCode = OpCodes.Call; ins.Operand = replacement; n++; }
        return n;
    }

    // 咖啡伴侣对某个 Windows P/Invoke 的调用改成调用替身里同签名的托管实现
    public static int PInvoke(ModuleDefinition mod, string dll, string name, MethodReference replacement)
    {
        int n = 0;
        foreach (var m in mod.GetTypes().SelectMany(t => t.Methods).Where(m => m.HasBody))
            foreach (var ins in m.Body.Instructions)
                if (ins.OpCode == OpCodes.Call && ins.Operand is MethodReference r && r.Name == name)
                {
                    var d = r.Resolve();
                    if (d != null && d.IsPInvokeImpl && string.Equals(d.PInvokeInfo.Module.Name, dll, StringComparison.OrdinalIgnoreCase)) { ins.Operand = replacement; n++; }
                }
        return n;
    }

    // Newtonsoft 在 IL2CPP 上不能动态生成代码：反射委托工厂固定用纯反射版本（官方 AOT 版也是这样）
    public static void NewtonsoftNoEmit(ModuleDefinition json)
    {
        var reflector = json.GetType("Newtonsoft.Json.Serialization.JsonTypeReflector");
        var late = json.GetType("Newtonsoft.Json.Utilities.LateBoundReflectionDelegateFactory").Methods.Single(m => m.Name == "get_Instance");
        Replace(reflector.Methods.Single(m => m.Name == "get_DynamicCodeGeneration"), il => { il.Append(il.Create(OpCodes.Ldc_I4_0)); il.Append(il.Create(OpCodes.Ret)); });
        Replace(reflector.Methods.Single(m => m.Name == "get_ReflectionDelegateFactory"), il => { il.Append(il.Create(OpCodes.Call, late)); il.Append(il.Create(OpCodes.Ret)); });
    }

    static void Replace(MethodDefinition m, Action<ILProcessor> emit)
    {
        m.Body = new MethodBody(m);
        emit(m.Body.GetILProcessor());
    }
}
