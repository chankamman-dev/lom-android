using Mono.Cecil;
Console.OutputEncoding = System.Text.Encoding.UTF8;

// lomweave inventory <LOMAssistant.dll> <游戏 Managed 目录> [更多搜索目录...]
if (args.Length >= 3 && args[0] == "inventory")
{
    var resolver = new DefaultAssemblyResolver();
    foreach (var dir in args.Skip(2)) resolver.AddSearchDirectory(dir);
    resolver.AddSearchDirectory(Path.GetDirectoryName(Path.GetFullPath(args[1])));
    using var mod = ModuleDefinition.ReadModule(args[1], new ReaderParameters { AssemblyResolver = resolver });
    Inventory.Print(Inventory.Read(mod));
    return 0;
}
// lomweave refs <dll> <程序集名前缀,...>：列出该 dll 引用到的外部类型和成员（按程序集分组）
if (args.Length >= 3 && args[0] == "refs")
{
    using var mod = ModuleDefinition.ReadModule(args[1]);
    var wanted = args[2].Split(',');
    string Scope(TypeReference t) { while (t.DeclaringType != null) t = t.DeclaringType; return t.Scope?.Name ?? ""; }
    bool Want(string s) => wanted.Any(w => s.StartsWith(w, StringComparison.OrdinalIgnoreCase));
    foreach (var g in mod.GetTypeReferences().Where(t => Want(Scope(t))).GroupBy(t => Scope(t)).OrderBy(g => g.Key))
    {
        Console.WriteLine("## " + g.Key);
        foreach (var t in g.Select(t => t.FullName).Distinct().OrderBy(x => x)) Console.WriteLine("  T " + t);
        foreach (var m in mod.GetMemberReferences().Where(m => m.DeclaringType != null && Scope(m.DeclaringType.GetElementType()) == g.Key).Select(m => m.ToString()).Distinct().OrderBy(x => x))
            Console.WriteLine("  M " + m);
    }
    return 0;
}
if (args.Length == 6 && args[0] == "weave") return Weave.Run(args[1], args[2], args[3], args[4], args[5]);
// lomweave contract <咖啡伴侣目录> <Managed 目录>：用原 Mod 自己的 CompatibilityContract 核对（与它启用随时存档前做的一样）
if (args.Length == 3 && args[0] == "contract")
{
    var alc = new System.Runtime.Loader.AssemblyLoadContext("lom", true);
    alc.Resolving += (ctx, name) => { var p = Path.Combine(args[1], name.Name + ".dll"); return File.Exists(p) ? ctx.LoadFromAssemblyPath(Path.GetFullPath(p)) : null; };
    var asm = alc.LoadFromAssemblyPath(Path.GetFullPath(Path.Combine(args[1], "LOMAssistant.dll")));
    var cc = asm.GetType("LOMAssistant.Features.AnytimeSave.CompatibilityContract", true);
    foreach (var res in asm.GetManifestResourceNames().Where(n => n.EndsWith(".tsv.gz")))
    {
        using var gz = new System.IO.Compression.GZipStream(asm.GetManifestResourceStream(res), System.IO.Compression.CompressionMode.Decompress);
        string text = new StreamReader(gz).ReadToEnd();
        var expected = cc.GetMethod("Parse").Invoke(null, new object[] { text });
        var current = cc.GetMethod("Snapshot").Invoke(null, new object[] { args[2] });
        var diff = (System.Collections.Generic.List<string>)cc.GetMethod("Differences").Invoke(null, new[] { expected, current });
        Console.WriteLine($"{res}：{diff.Count} 处不同");
        foreach (var d in diff.Take(20)) Console.WriteLine("  " + d);
    }
    return 0;
}
// lomweave cjk-table <输出目录>：用本机 Windows 的 LCMapStringEx（咖啡伴侣在 PC 上用的同一个函数）逐字导出简繁对照表
if (args.Length == 2 && args[0] == "cjk-table")
{
    Directory.CreateDirectory(args[1]);
    foreach (var (locale, flag, file) in new[] { ("zh-CN", 0x02000000u, "zh-CN.tsv"), ("zh-TW", 0x04000000u, "zh-TW.tsv") })
    {
        var sb = new System.Text.StringBuilder();
        int n = 0, odd = 0;
        var buf = new char[8];
        for (int c = 0x80; c <= 0xFFFF; c++)
        {
            if (c >= 0xD800 && c <= 0xDFFF) continue;
            string s = ((char)c).ToString();
            int len = Cjk.LCMapStringEx(locale, flag, s, 1, buf, buf.Length, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            if (len <= 0) continue;
            string r = new string(buf, 0, len);
            if (r == s) continue;
            if (r.Length != 1) odd++;
            sb.Append((char)c).Append('\t').Append(r).Append('\n');
            n++;
        }
        File.WriteAllText(Path.Combine(args[1], file), sb.ToString(), new System.Text.UTF8Encoding(false));
        Console.WriteLine($"{locale}：{n} 个字有变化，其中 {odd} 个变成多字符");
    }
    return 0;
}
// lomweave check-refs <dll> <替身目录> <其他搜索目录...>：dll 对替身程序集的每个类型/成员引用都必须能解析
if (args.Length >= 3 && args[0] == "check-refs")
{
    var resolver = new DefaultAssemblyResolver();
    foreach (var d in resolver.GetSearchDirectories()) resolver.RemoveSearchDirectory(d);
    foreach (var dir in args.Skip(2)) resolver.AddSearchDirectory(dir);
    using var mod = ModuleDefinition.ReadModule(args[1], new ReaderParameters { AssemblyResolver = resolver });
    var shimNames = Environment.GetEnvironmentVariable("ALLREFS") == "1"
        ? mod.AssemblyReferences.Select(a => a.Name).ToHashSet()
        : Directory.GetFiles(args[2], "*.dll").Select(Path.GetFileNameWithoutExtension).ToHashSet();
    string Scope(TypeReference t) { while (t.DeclaringType != null) t = t.DeclaringType; return t.Scope?.Name ?? ""; }
    int bad = 0, ok = 0;
    foreach (var t in mod.GetTypeReferences().Where(t => shimNames.Contains(Scope(t))))
        if (t.Resolve() == null) { bad++; Console.WriteLine("缺类型 " + t.FullName); } else ok++;
    foreach (var m in mod.GetMemberReferences().Where(m => m.DeclaringType != null && shimNames.Contains(Scope(m.DeclaringType.GetElementType()))))
    {
        IMemberDefinition r = null;
        try { r = m is MethodReference mr ? mr.Resolve() : m is FieldReference fr ? fr.Resolve() : null; } catch { }
        if (r == null) { bad++; Console.WriteLine("缺成员 " + m); } else ok++;
    }
    // 特性里引用的构造函数
    foreach (var a in mod.GetTypes().SelectMany(t => t.CustomAttributes.Concat(t.Methods.SelectMany(x => x.CustomAttributes))).Where(a => shimNames.Contains(Scope(a.AttributeType))))
        if (a.Constructor.Resolve() == null) { bad++; Console.WriteLine("缺特性构造 " + a.Constructor); }
    Console.WriteLine($"引用 {ok} 个可解析，{bad} 个缺失");
    return bad == 0 ? 0 : 1;
}
Console.Error.WriteLine("usage: lomweave inventory <LOMAssistant.dll> <Managed> [dirs...] | refs <dll> <scopes>");
return 2;
