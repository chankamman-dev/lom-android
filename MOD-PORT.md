# 咖啡伴侣（LOMAssistant）移植到安卓重编译版

2026-11-03 开始。目标：原 Mod 全部功能在 IL2CPP 安卓版里可用，跟着原 Mod 新版重新织入即可升级。
当前基线：咖啡伴侣 3.4.0（LOMAssistant.dll SHA256 ab2c2b00…c9cc，发布包 LOMAssistant.zip 114a3812…9bd6），游戏 1.0.6001（H: 上的 Steam 版）。

## 为什么不能直接装

原 Mod 是 BepInEx（Mono）插件，靠 Harmony 在运行时生成代码改游戏方法。IL2CPP 没有 JIT，`System.Reflection.Emit` 不可用，
MonoMod/Harmony 的补丁机制跑不起来。所以改成**编译前织入**：在 `lomtool stage` 之后、`il2cpp` 之前，
用 Cecil 把每个补丁写进游戏程序集；原 Mod 的其余代码原样编进 IL2CPP。

## 原 Mod 3.4.0 盘点（tools/lomweave inventory / refs）

- 补丁：247 个 `[HarmonyPatch]`，解析出 227 个目标（Mortal.Core 140、Mortal.Story 31、Fungus 15、Mortal.Combat 10、
  UnityEngine.IMGUIModule 10、Mortal.Free 7、Mortal.Battle 6 等）。前置 110、后置 91、Finalizer 23（22 个是 void，当 finally 用；
  1 个返回 Exception）。没有泛型目标、没有无方法体目标。
- 运行时才算出目标的 3 个：InputGuardActionGate（40 个 "类型:方法" 字符串，参数 CallbackContext）、
  SaveDataCache.GetSaveDataPatch（SaveSystem.GetSaveData(string)）。另有 1 个 Transpiler：InputGuardDialogGate
  把 Fungus.DialogInput.Update 里的 Input.GetMouseButtonDown/GetButtonDown/GetKeyDown 换成自己的版本。
- 手动 `Harmony.Patch`：Diagnostics 挂 Steamworks.SteamAPI（安卓 Steam 是空桩，不织）；EnsurePatched 骰子两处
  （与 PatchAll 是同一组补丁）。
- 补丁参数：__instance 117、__result 39、__state 37、__0..__1 13、__runOriginal 5、__exception 1、___字段 3。
- 原 Mod 按配置开关逐类 `PatchAll(typeof(X))`，并用 `Harmony.GetPatchInfo` 检查是否生效 → 织入的补丁必须**运行时可开关**。
- 外部接口：Harmony 17 个类型 54 个成员（AccessTools、Traverse、FieldRefAccess、GetPatchInfo、PatchAll、Patch、UnpatchSelf…）；
  BepInEx 12 个类型 41 个成员（BaseUnityPlugin、ConfigFile/ConfigEntry、Paths、ManualLogSource、KeyboardShortcut…）；
  Mono.Cecil 只在 BepInEx5Plugins（安卓上找不到插件，自然空转）和 CompatibilityContract（随时存档的游戏代码核对）里用；
  Newtonsoft.Json 是 netstandard 版。

## 设计

1. **替身程序集**（同名，供 LOMAssistant.dll 引用）：`0Harmony`、`BepInEx.Core`、`BepInEx.Unity.Mono`。
   - HarmonyLib：AccessTools/Traverse 用反射实现；`FieldRefAccess<T,F>("名")` 的调用点由织入器改成直接取字段地址的生成方法；
     `PatchAll(type)` / `Patch(...)` / `UnpatchSelf` 只是在登记表里打开/关闭对应补丁；`GetPatchInfo` 按登记表回答。
   - BepInEx：配置文件格式与 PC 版一致（可直接拷 PC 的 LOMAssistant.cfg）；路径都在 `Application.persistentDataPath/BepInEx`。
2. **织入**（tools/lomweave weave）：对每个被补丁的方法，把原方法体移到 `原名__lomorig`，原方法改成包装：
   按优先级依次「若开关打开则调用前置」→ 原方法（前置返回 false 时跳过）→ 后置 → 有 Finalizer 时整体包 try/catch。
   开关是 `HarmonyLib.LomWeave.On[编号]`（替身里的静态 bool 数组）。Transpiler 改成调用点分派：开关开着走原 Mod 的版本。
   补丁类、补丁方法、`___字段` 用到的游戏字段改为 public（IL2CPP 不做访问检查，但 Cecil/il2cpp 前端更稳妥）。
3. **随时存档兼容核对**：织入时用原 Mod 自己的 `CompatibilityContract.Snapshot` 对原始（未织入）程序集生成快照，
   运行时把 `Snapshot(managed目录)` 的调用改成读这份快照，原 Mod 的比对逻辑不变。
4. **Newtonsoft**：把 `JsonTypeReflector.DynamicCodeGeneration` 固定为 false，走纯反射（与官方 AOT 版同一做法）。
5. **启动**：在游戏最早的启动点织入 `LomBoot.Start()`，建一个常驻 GameObject 并 AddComponent(LOMAssistantPlugin)。

## 状态（2026-11-03）

- [x] 盘点（inventory / refs / check-refs：原 Mod 4457 处、Newtonsoft 2106 处引用在 stage + unityaot + 替身里全部可解析）
- [x] 替身程序集（tools/lomweave/shims，build-shims.sh）
- [x] 织入器：271 个补丁、12 个游戏程序集；FieldRef 18 处、Assembly.Location 14 处、简繁转换 1 处改写；Newtonsoft 纯反射。
      同优先级按原 Mod ApplyPatches 里 TryPatchAll 的先后排序（Harmony 规则）。
- [x] 简繁转换：原 Mod 用 Windows LCMapStringEx（逐字）。`lomweave cjk-table` 在 Windows 上用同一函数导出对照表
      （转简 2619 字、转繁 2191 字，全是一对一），嵌进启动替身，查表结果与 PC 一致。
- [x] 随时存档核对：6 个程序集嵌进启动替身、首启释放到 files/Mortal_Data/Managed；`lomweave contract` 离线核对与 PC 原版结果相同
      （离线唯一的差异 System.Random::Sample 是 .NET 8 浮点转文本格式不同造成的，设备上不存在）。
- [x] il2cpp 编译通过（mod/il2cpp，全量约 7 分钟编译+1 分钟链接）；build-mod.sh 一键出 APK+OBB（mod/apk）。
- [x] 模拟器（lime-sim）：原 Mod 加载，补丁自检 prefixes=1，「已打补丁的游戏方法 191 个」，随时存档核验通过并启用，
      更新检查、开游戏提速、冲突检测正常；标题画面「玩家社群」按钮出现；Tab 打开原 Mod 窗口（9 个页签、首次预设对话框，
      跟随游戏语言显示繁体）；读档界面增强正常。
- [ ] 进游戏后的功能（选项后果、地图提示、骰子自选、随时存档存读、剧情回顾各页）：**还没测**。模拟器上的存档里有同步来的真实存档，
      另一个会话确认：lime-sim 上的应用从没登录 Steam，存档只是 PC 存档副本，存档时只打日志不上传（只有扫码登录过的设备才上传），可以放心读档测。2026-11-03 用户说先不测，暂停在这里。
- [ ] 已知限制：GamepadCursor 的 SendInput（手柄模拟鼠标点击）在安卓上无效（原 Mod 已 try 住）；诊断里「进程启动时间」「游戏程序」读不到（无害）。
- [ ] Application.version 是 0.1（build-apk.sh 的 --version-name），已建议另一个会话改成游戏版本号。
- [x] 2026-11-04 00:27 应用户要求装到 Thor：按 11-03 08:35 的 stage 重新织入（271 个补丁），APK 184.6 MB、OBB 2442250559 字节（SHA256 28151f84…c54e），覆盖安装保留数据（Steam 登录、存档不动）。装前把 files/Save 备份到 E:om-android-workmod	hor-backup-20261104Save.tar（09072c5c…af4c），装后 36 个存档文件哈希与备份逐个相同。OBB 先推成 .new，按原文件设 u0_a245:ext_obb_rw、2770、media_rw_data_file 后替换。没有启动游戏，由用户自己测。
- [ ] Thor 真机进游戏验证（用户测）；下屏伴侣（lom-thor-probe 的下屏页面）接入安卓版：未开始。

## 2026-11-04 下屏伴侣接入安卓版

- 下屏伴侣 = 游戏里的桥接（lom-thor-probe 的 LOMThorBridge.dll，dev.15）+ 下屏网页应用（io.github.chankamman_dev.lomthor.dev，dev.16），经 127.0.0.1:23879 通信。安卓版与下屏应用在同一台 Thor 上，回环照样通，**下屏应用不用改**，桥接源码也没改。
- build-mod.sh 默认带桥接（LOM_BRIDGE 指向 lom-thor-probe/artifacts/LOMThorBridge/LOMThorBridge.dll，设为空串则不带）。织入器把桥接当第二个插件：特性补丁 2 个（BackgroundRun.Tick / ActivateAudio_Prefix 的后置），运行时 harmony.Patch 的 9 个照它的代码在 SpecialBridge 里登记（4 个提示窗口 OnGUI、StoryReviewPanel.Update/OnGUI、EventPicker/SaveNoteEditor.OnGUI、InputGuard.HotkeyDown），目标都在 LOMAssistant 内部。共 282 个补丁。桥接 969 处引用在安卓环境全部可解析。
- LomBoot 在咖啡伴侣之后 AddComponent(LOMThor.Plugin)。
- 模拟器实测（启动页点「不登录，直接进入」；UnityPlayerActivity 现在不导出，要从 dev.kmlom.cloud.LauncherActivity 进）：日志「LOMThor 0.1.0-dev.15 listening on 127.0.0.1:23879」；adb forward 后 /v1/health、带会话头的 /v1/snapshot 正常（panelError 无、hintRouting supported/active）；POST /v1/action 请求 settings 页，返回原 Mod 的 7 个设置分组。测完 emu kill（lime-sim 开着占约 21.6 GB 显存）。
- 00:41 装到 Thor：装前存档备份 E:\android-recompile-work\lom-android-work\mod\thor-backup-20261104b\Save.tar（ecbe61e1…a852），装后 40 个文件哈希一致。APK 185980622 字节，OBB 2442371108 字节（d0a6c35f…e907）。没有启动游戏。
- 用法：先打开安卓版活侠传进游戏，再在下屏打开「活侠下屏 · 验证版」，它会自动连上。

## 2026-11-04 下屏桥接 dev.16（3.x 新功能和 AI 陪玩）

- 桥接 dev.16（lom-thor-probe/HANDOFF.md 最前一段）把原 Mod 3.x 新功能和 AI 陪玩都搬到下屏；下屏应用要配 dev.17。build-mod.sh 用新桥接重编：织入 287 个补丁（桥接的 RedirectWindows 和 HintRelay.Windows 现在由 Build.cs 从桥接里读，手工补丁 14 个），未解析 3 个仍是上面说的运行时目标。
- 安卓版上 AI 陪玩还没验证：原 Mod 的 LlmClient 在 IL2CPP 下能不能连上服务商、AiSessions 导出写到哪里（原 Mod 写桌面），都要真机看。
- 11-04 09:42 装到 Thor（带桥接 dev.16）：装前存档和 BepInEx/config 备份在 E:\android-recompile-work\lom-android-work\mod\thor-backup-20261104c\Save.tar（edde3c1f…06b0），APK 186685134 字节（edc4f742…8413），OBB 2442439260 字节（763ca687…dca7），装后 40 个存档文件哈希一致；原 Mod 的 AI 对话记录在 BepInEx/config/LOMAssistant_AI，保留着。没有启动游戏。
