using Mono.Cecil;

// 从目标模块自己的核心库（unityaot mscorlib）解析类型和方法，而不是 lomweave 运行时的 System.Private.CoreLib
static class CoreRefs
{
    public static TypeDefinition Def(ModuleDefinition m, string ns, string name) =>
        new TypeReference(ns, name, m, m.TypeSystem.CoreLibrary).Resolve() ?? throw new Exception("核心库里没有 " + ns + "." + name);

    public static TypeReference Type(ModuleDefinition m, string ns, string name) => m.ImportReference(Def(m, ns, name));

    public static MethodReference Method(ModuleDefinition m, string ns, string type, string name, params string[] paramTypes) =>
        m.ImportReference(Def(m, ns, type).Methods.First(x => x.Name == name && x.Parameters.Count == paramTypes.Length
            && x.Parameters.Select((p, i) => p.ParameterType.FullName == paramTypes[i]).All(b => b)));

    public static MethodReference GetTypeFromHandle(ModuleDefinition m) => Method(m, "System", "Type", "GetTypeFromHandle", "System.RuntimeTypeHandle");
    public static MethodReference GetMethodFromHandle(ModuleDefinition m) => Method(m, "System.Reflection", "MethodBase", "GetMethodFromHandle", "System.RuntimeMethodHandle", "System.RuntimeTypeHandle");
}
