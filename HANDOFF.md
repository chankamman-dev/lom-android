# 活侠传原生 Android 移植：交接

2026-10-31：最小验证通过。Thor 上原生运行到标题画面，见 `shots/thor-title-screen.png`。

## 现状

- 包名 `dev.kmlom.mortal`，IL2CPP arm64，GLES3，Unity 2020.3.49f1（游戏本体 build 26237817）。
- 全程未使用 Unity Editor 的 GUI，也不需要 Unity 登录：`il2cpp.exe` + NDK r19c + `tools/lomtool`。
- 只验证到标题画面出现。没有测过：菜单操作、进游戏、读档、战斗、视频、帧率、手柄和触屏的实际按键。
- Steam 是空桩。音频由 LomAudio 接管，音效就绪，音乐和环境声缺解码。

下一步：解码音乐 → 手柄和触屏实测 → 进游戏验证 → 下屏与 LOMAssistant。

## 目录

- 源码和脚本：`G:\ai\android-recompile\lom-android\tools`
- 大文件：`E:\android-recompile-work\lom-android-work`（Unity、NDK、HLSLcc、il2cpp 输出、转换后资产）
- 设备：APK 已安装；bundle 在 `/sdcard/Android/data/dev.kmlom.mortal/files/aa`

## 工具

先构建：`dotnet build -c Release tools/lomtool`（bin/obj 不留在仓库里）。

| 命令 | 作用 |
| --- | --- |
| `lomtool stage <Managed> <unityaot> <Android Managed> <out>` | 组装 il2cpp 输入并打桩 |
| `lomtool rt-data <Mortal_Data> <out> <tmp> <hlslcc-cli> <tpk> <android default resources>` | 重定向松散资产，转换着色器 |
| `lomtool rt-bundles <src> <out> <tmp> <hlslcc-cli> <tpk>` | 重定向 bundle（输出未压缩，共 3.3 GB） |
| `lomtool rt-catalog <aa> <out> <设备路径>` | 改写 Addressables 路径，清掉 CRC |
| `lomtool shgold` / `shprobe` / `shdump` | 着色器对照与解析 |
| `tools/build-apk.sh` | 打包签名 |
| `tools/run-thor.sh <日志> <秒>` | 在下屏启动并抓日志 |

`tools/hlslcc-cli/main.cpp` 是 HLSLcc 的批处理前端，源码在 `E:\android-recompile-work\lom-android-work\HLSLcc`，编译目录 `hlslcc-build`。

il2cpp 命令见 `E:\android-recompile-work\lom-android-work\il2cpp`：`--convert-to-cpp --compile-cpp --libil2cpp-static --platform=Android --architecture=ARM64 --dotnetprofile=unityaot`，全量编译约 15 分钟，增量看改动。

## 非显而易见的坑

- Unity 安装包 7-Zip 解不开，用 `__COMPAT_LAYER=RunAsInvoker` + `/S /D=路径` 静默安装；Android 模块要在 Editor 装完后再装。
- il2cpp 要求 baselib 在 `Editor/Data/il2cpp/build/baselib/release_android_aarch64/baselib.a`，从 Android 模块复制过去。
- 2020.3 的 Android 版把 `unity default resources` 放在 Data 根目录，不是 `Resources/`；否则报 Internal-ErrorShader 后崩溃。
- `unity_builtin_extra` 是游戏的，必须转换，留在 `Resources/`。
- 程序参数分两处：程序记录尾部，和 pass 级 `m_CommonParameters`。DXBC 的 RDEF 被 Unity 剥掉了，必须用这两处重建，否则 HLSLcc 崩溃。
- HLSLcc 标志见 `Converter.Flags`，不能带 `COMBINE_TEXTURE_SAMPLERS`。输出和官方 GLES3 样本一致，只缺 `mediump`（DXBC 里没有最小精度信息），全部是 highp。
- GLES3 的 VS 和 FS 文本合在 progVertex 的一个程序里，progFragment 清空。
- 结构化缓冲变体（程序化实例化）跳过，GLES3.0 不支持。
- Addressables 的 CRC 要清零，用空格补位保持偏移不变。
- 目录路径里是反斜杠，改写时要换成 `/`。
- 多个会话共用 Thor 时，主屏会被顶掉。验证用 `am start --display 4`（下屏）；截下屏用 `screencap -d 4630946482288158084`。
- adb 有时多出 emulator，命令要带 `-s <Thor 序列号>`（脚本读环境变量 ANDROID_SERIAL）。
- bash 工具里长 heredoc 偶尔解析失败，源文件用 Write 写。

## 已知问题

1. 音乐和环境声没有声音：47 个 WEM 是 Wwise Vorbis（标记 0xFFFF），ffmpeg 解不了，需要 vgmstream 或同类解码器。下载运行 vgmstream 被权限策略拦下，等用户决定。解码后走 ffmpeg 转 ogg，文件名 <id>.ogg，推到 files/audio。
2. 音量是近似值：没有解析 HIRC，各音效的节点音量、音乐分段和转场都没还原，音乐整轨循环。
3. 1 个粒子变体（Particles/Unlit 的程序化实例化）转换失败，不影响普通粒子。
4. LomAudio 启动时有两条 "Only custom filters can be played" 警告，无害，来源未确认。

## 2026-10-31 第二轮

- 已修：IsRunningOnSteamDeck 返回 false，设置读取不再中断。
- 已修：Input System 1.5.1 按 Android 重编（tools/build-inputsystem.sh，源码在 E:lom-android-workpkg）。Thor 手柄识别为 AndroidGamepadWithDpadAxes / Odin Controller，触屏和键盘也识别。实际按键未测。
- 音频：游戏只用 PostEvent、SetRTPCValue、GetRTPCValue，全部重定向到 tools/lomandroid/LomAudio.cs（Unity 音频播放）。globalgamemanagers 的 m_DisableAudio 原本是 true，rt-data 已改成 false。
- 音频表来自 SoundbanksInfo.xml：lomtool audio 提取，audio-pcm 转 wav，ffmpeg 转 ogg。187 个已转好并推到 Thor。
- 已验证：用 PCM 音乐顶替标题音乐时，系统里有本应用的 AudioTrack，状态 started。没有人耳确认。
- 完整重建：tools/rebuild.sh，约 15 分钟（链接占大头）。


## 2026-10-31 音乐解码

- vgmstream 在 `E:\android-recompile-work\lom-android-work\vgmstream`（官方 win64 发布包，sha256 692d4507…a229）。47 个 Vorbis 全部解码，转 ogg（q6）。234 个音频都在 `E:\android-recompile-work\lom-android-work\audio\ogg`，共 160 MB，表里的引用没有缺失。
- 这些音频没有非零循环起点，整轨循环不会重放前奏。
- 待做：Thor 断开了，音乐还没推上去，也没验证。接上后推送 ogg 目录到 `/sdcard/Android/data/dev.kmlom.mortal/files/audio`，再用 tools/run-thor.sh 启动看日志和 AudioTrack。
- 已推送并验证：234 个 ogg 在设备上，标题音乐加载无错误，AudioTrack 状态 started（下屏和主屏都测了）。主屏 1920×1080 横向全屏正常，见 shots/thor-main-screen.png。人耳尚未确认。
- 分辨率：游戏默认取列表最后一项 960×540，Thor 上是 540p 放大。Patch.cs 改默认为 1920×1080（索引 3），PlayerPrefs 键改名避开旧值。确认渲染分辨率：`dumpsys SurfaceFlinger` 看 SurfaceView 的 activeBuffer。

## 2026-10-31 项目暂停

- 用户要求暂停，并删除 Thor 上的相关文件。`dev.kmlom.mortal` 已卸载，`Android/data` 和 `Android/obb` 下的目录已删除，共约 3.4 GB。
- 卸载前把 Save 目录备份到 `E:\android-recompile-work\lom-android-work\thor-save-backup\Save`。
- 分辨率修复已写进 Patch.cs，但构建被中止，没有验证过。`E:\android-recompile-work\lom-android-work\il2cpp` 和 `stage` 可能是半成品，恢复时先跑 `tools/rebuild.sh`。
- 恢复时要重新推送：`bundles/aa` 到 `files/aa`，`audio/ogg` 和 `audio/audio.tsv` 到 `files/audio`。
- 没有动 lom-thor-probe 和 GameHub 里的活侠传。

## 2026-11-03 模拟器验证 + 重新装 Thor

- 用户要求以后测试用本机模拟器：lime-sim（emulator-5600，`-gpu host`，带 arm64 转译）。它的数据分区被柚子社项目占用约 7 GB（/sdcard/0lime-pack，别动）。
- 打包改为 APK（136 MB，只含代码和 boot.config）+ OBB（`main.1.dev.kmlom.mortal.obb`，2.4 GB，含 Data、bundle、音频）。原因：模拟器装不下 2.5 GB 的单 APK；外部目录里 adb 推送的文件在 Android 14 上应用读不到。OBB 靠 manifest 里的 `unity.build-id` 和 OBB 内的空文件 `assets/<GUID>` 匹配。
- bundle 用 `lomtool rt-pack` 压回 LZ4（3.3 GB → 777 MB），放在 `E:\android-recompile-work\lom-android-work\f-data\bundles-lz4`（E 盘持续写入慢）。
- 修了 Addressables CRC：ExtraData 里的 JSON 是 UTF-16LE，原来按 ASCII 搜，一个也没清掉。
- 修了视频：`Hidden/VideoDecodeAndroid` 由 `lomtool video-android` 生成（手写 GLES3 外部纹理程序），PathID 必须是 16003，还要把 ScriptMapper 里这个名字的空指针指过去。已并入 rt-data。
- 分辨率修复已验证：渲染缓冲 1920×1080。
- 模拟器上已验证：标题、开新档、难度选择、剧情对话、立绘背景、骰子判定、选项、唐门地图、弟子房、存档（Save_002 写入并显示）、触屏、标题音乐音轨。没测到：战斗、CG 视频实际播放、手柄按键、读档。
- 用户授权后已装到 Thor（APK + OBB），由用户自己测试。
- 测试辅助：tools/emu-step.sh <名字> "<adb shell 命令>" <秒>，截图和日志在 E:\android-recompile-work\lom-android-work\steps。
- 小残留：`F:\lomtool-run` 是我误复制的构建输出，删除被安全检查拦下，留给用户删。

## 2026-11-03 Steam 云存档

- 已验证：PC 的 Steam 存档（BinaryFormatter）能直接在安卓版读取，养成和战斗存档都能进。
- 模块在 `cloud/`（Gradle 8.7 java-library，JDK 17，`gradle.bat jar collectRuntimeDeps`），依赖 JavaSteam 1.8.0 等 32 个 jar。协议参考 SilksongAndroid（MIT）。
- 启动入口改为 `dev.kmlom.cloud.LauncherActivity`：已登录就先同步再进游戏；未登录可扫码或直接进。
- 同步规则：云端路径 `%WinAppDataLocalLow%Obb Studio/Mortal/<SteamID64>/`，只同步 `Save_*`、`Legend_*` 的 .dat/.json。记录每个文件上次同步的 SHA-1：只有一边改了就以那边为准，两边都改了就新的赢。首次同步一律以云端为准。本地被覆盖的文件、冲突时被替换的云端文件，都先备份到 `Save/.cloud-backup/<时间>/`。
- 游戏内：Patch.cs 在 SaveSystem.ExecuteSaveData / ExecuteSaveJsonData / DeleteSaveData 的每个 ret 前调用 LomAndroid.LomCloud.Saved() → CloudBridge.onSaved，4 秒防抖后只上传（不改本地文件）。日志 tag `LomCloud`。
- 已验证：模拟器连上 Steam，拿到登录二维码；离线进入正常；存档钩子能调到 Java。未验证：真实登录、下载、上传，需用户扫码。
- 构建：build-apk.sh 改用 build-tools 36（34 的 d8 处理 commons-lang3 时会崩）；资产默认在 F 盘（DATA、BUNDLES）。
- 着色器：转换器改成按 Unity 规则配对顶点/片元变体（取各自的最大关键字子集），变体 650→2008。Fixups.cs 修 D3D 反向深度：FlatKit 描边的 `_OutlineDepthOffset` 符号（战斗中主角头部黑块）。
- 另一个会话（LOM Thor Probe）在做 LOMAssistant 移植：改了 build-apk.sh 的 OUT/IL2CPP/EXTRA_ASSEMBLIES/RUNTIME_INIT，输出在 E:\android-recompile-work\lom-android-work\mod，说明见 MOD-PORT.md。别覆盖它的改动。它占用着 lime-sim，测完会通知。
- Thor 上已装带云存档的版本（01:28），待用户扫码实测。

## 2026-11-03 还原度复盘：音频按 Wwise 层级重做
- 音乐发闷：`MusicLowPassValue` 在 Init.bnk 的默认值是 0（不滤波），原来按 100 处理，音乐一直被压到 500 Hz。已改。
- `lomtool hirc-gains <banks> gain.tsv`：沿节点、父节点、总线累加 Volume/BusVolume（prop 0/5），得到每个文件的 dB。音乐总线 -10、主总线 +5，所以音乐 -5 dB，音效多为 +3/+8，按钮悬停 +16（原文件峰值 -14 dB）。安卓端用 `LomGain`（OnAudioFilterRead）在样本上乘增益，可超过 1，削波和 PC 最终混音一样。
- `lomtool audio-events <banks> events.tsv`：从 HIRC 导出每个事件的动作（停止总线带淡出、延迟播放、Pause/Resume All、SetState）、一次性音效容器（随机、不重复上一个）、音乐/环境播放列表（片段、裁剪、入口/出口提示点、循环次数、片段淡入淡出）、音乐切换容器（Music_Boss1 随 PlayerState 切到结尾段）。bank 140 的节点参数里多了 metadata 效果和 attachment 字节，bank 135（Player.bnk）没有。
- RTPC 曲线（Init.bnk）：主/音乐/音效音量是 dB 显示、线性振幅插值，即 0..100 → 0..1；BackgroundMusic/EnvSound 淡入淡出是 -80..0 dB 线性；低通值一比一送到总线 LPF。
- LomAudio 重写：播放列表用两个 AudioSource 交替，PlayScheduled 让下一轮的入口点正好落在上一轮的出口点，尾音和前奏可重叠；剪辑到裁剪终点停止；剪辑用压缩在内存（不流式），最近 4 首缓存。原来整首循环，会多播几秒到 54 秒不该出现的尾段。
- 构建：`build-lomandroid.sh` 现在遇到 C# 编译错误会中断（之前错误被吞掉，旧 DLL 继续打包）。
- `LomDebug`：在 `<persistentDataPath>/lomdebug.txt` 写 `video=<key>`，按剧情同样的方式播放 CG（CgVideoPanel.Open），日志 `[LomDebug] video ... finished`。
- 模拟器实测（02:00 前后）：事件表 196、一次性音效 136、播放列表 53、增益 234 全部加载；标题曲循环点 170.67 s、增益 -5 dB；Env_DrumRoll1 按 5.418 s 周期循环且前后两轮重叠；Music_Boss1 默认段 → `State_PlayerState_End` 切到结尾段；换曲时旧曲停掉；Pause_Music/Resume_Music 暂停全部声部，等待中的循环轮次在恢复后重新调度；Stop_All_Fade 3 秒淡出。一次性音效（共 0.8 MB）启动后全部预载，首次播放无延迟。
- CG 视频实测：剧情存档里 `video=event_001` 正常播放（VP8 走 MediaCodec 硬解，竖版画面，3.8 s 后协程结束回到剧情），不会卡住剧情。
- 粒子：缺的那个程序化实例化变体用不到。全部 1435 个 ParticleSystemRenderer 里，网格模式（4）的 8 个都关了 GPU 实例化（`lomtool psr-scan`）。
- 画质：安卓同样走 m_CurrentQuality=5（Ultra），同一个 URP 资源；语言默认值和 PC 同一逻辑（跟随系统语言）。
- il2cpp 偶尔会卡死（0 CPU、内存 6 GB 不动），结束进程重跑即可。`build-il2cpp.sh` 现在 il2cpp 非零退出就中断，不再拿旧的 libil2cpp.so 去打包。
- 写调试脚本注意：bash 工具里给 node 的 heredoc，`\n` 会被变成真实换行写进 C#，带反斜杠的脚本用 Write 工具写文件。

## 2026-11-03 Steam 游戏时长与成就

- 游戏只在 `SaveSystem.SetAchivement`（PC 上调 `SteamUserStats.SetAchievement("ACH_"+id)` + `StoreStats`）一处用到成就；没有别的统计。安卓版因为 `_savePlatformType` 被固定为 Local，这条分支本来被跳过。
- 织入（Patch.cs）：SetAchivement 开头调 `LomCloud.Achievement(id)`；`LibrarySystem.Load` 结尾调 `LomCloud.LibraryLoaded(this)`，它启动时长上报，并把存档里已获得的成就（Achievement 集合里 Count>0 的）全部补报一次。
- Java `cloud/.../SteamPlay.java`：
  - 游戏在前台时保持一条 Steam 连接（loginId 与云同步不同，互不顶掉），发 `ClientGamesPlayedWithDataBlob`（game 1859910）让 Steam 计时；切到后台就断开。
  - 另一台设备正在玩时（PlayingSessionState.blocked）不上报。
  - 成就：`getUserStats` 取 schema 和当前位，把对应 bit OR 进去，带 crc 发 `ClientStoreUserStats2`，等 `ClientStoreUserStatsResponse`。
  - 待发的成就存在 `lom_play` 偏好里，断网时先存着，下次连上再发；已确认的记进 done，退出登录时清空。日志 tag `LomCloud`。
- 已验证（模拟器，未登录）：存档里 7 个成就被读到并排队，无崩溃。
- Thor 实测（09:16）：`playing 1859910`，公开资料 ?xml=1 显示 In-Game Legend of Mortal；7 个成就 Steam 上早已解锁，名称全对上（无 not in schema）。时长要等退出后看 hoursPlayed 是否增长（当时 7.4h / 55h）。
- 踩过的坑：SteamClient 的 handlers 是普通 HashMap，登录后再 addHandler 会让网络线程出错断线（表现为登录 1ms 后 disconnected, by us: false）——处理器必须在 connect 前加。只登录不设 persona 是隐身，好友看不到在玩，要 setPersonaState(Online)。PC 客户端自己的名字下不会显示别的设备在玩什么，要看公开资料或好友视角。
- 构建注意：lomtool 编译失败时 rebuild.sh 不会停，会拿旧 lomtool 打包；改 lomtool 后先单独 `dotnet build` 确认通过。

## 2026-11-04 Thor 上现在是咖啡伴侣 Mod 版

- 00:27 另一个会话（LOM Thor Probe）应用户要求，把 Thor 上的 `dev.kmlom.mortal` 覆盖安装成咖啡伴侣 Mod 版：用 11-03 08:35 的 stage 织入，用本项目的 build-apk.sh 打包，含云存档启动页，版本号 release_1.0.6001.0。OBB 也换成了 Mod 版。Steam 登录和存档都保留了，装前备份在 `E:\android-recompile-work\lom-android-work\mod\thor-backup-20261104\Save.tar`。
- **往 Thor 装本项目的原版之前，先跟用户确认**：装上去会把咖啡伴侣去掉。Mod 版的状态看 MOD-PORT.md。
- 同一时刻，云同步在 `Save/.cloud-backup/20261104-002703/` 留了一份 `Save_003.dat.cloud`。我核对过，它和本地 `Save_003.dat` 的 SHA-1 完全相同（b18fc845…）。这是一次误判冲突：两边都被判成"已改动"，再按时间取了本地，没有丢任何进度。最可能的原因是装机前的 force-stop 打断了一次游戏内上传：Steam 收到了文件，但 cloud-state.json 没来得及写入。可以做的小改进：冲突分支里先比对云端内容的 SHA，相同就只更新记录，不备份也不重传。
- 00:41 那个会话又覆盖装了一次 Thor，新版本带下屏桥接 LOMThorBridge.dll（游戏进程监听 127.0.0.1:23879，供下屏应用取数据）。存档在装前备份过，装后 40 个文件的哈希一致。它也借 lime-sim 测过：桥接端口能连上，快照和页面请求都正常。所以 **lime-sim 上现在装的也是 Mod 版**，要测原版得先重装。
- UnityPlayerActivity 是不导出的（exported=false），普通 shell 用 `am start` 启动它会报 Permission Denial。所以改了脚本：run-emu.sh 先执行 `adb root` 再直接启动它，以绕过未登录时的启动页；run-thor.sh 改成从 `dev.kmlom.cloud.LauncherActivity` 进入，也就是先云同步再进游戏。两个脚本都还没实跑验证。

## 2026-11-06 成就位号 bug：已修，核对结果是没有误解锁

- bug：`SteamPlay.flushAchievements` 用 `bit.get("bit")` 取位号。Steam 成就表的结构是 `stats/<statId>/bits/<位号>/name`，位号是子项的**键名**，并没有 `bit` 字段，所以读出来恒为 0。Brotato 移植复用了这段代码，在 Thor 上误解锁了 chal_farmer，由此发现。
- 修复（cloud/.../SteamPlay.java，和 Brotato 版的修法相同）：位号改用 `Integer.parseInt(bit.getName())`，只接受 0..31；每次写入前打日志 `unlocking <名字> (stat <id> bit <位>)`。偏好 `lom_play` 加 `format=2`：旧版写的 done 不可信，第一次读偏好时（unlock 或 flush，谁先调用都会触发）丢掉一次，游戏下次启动时 LibraryLoaded 会把存档里的成就全部重报，再逐个跟 Steam 核对。不加撤销逻辑，因为本来就没有错误写入。
- 构建：在 cloud 目录跑 `gradle.bat --offline jar collectRuntimeDeps`（Gradle 8.7 在 `~/.gradle/wrapper/dists/gradle-8.7-bin`，JAVA_HOME 用 jdk-17），11-06 02:00 构建通过。javap 确认类里已经没有 `"bit"` 字段读取。build-apk.sh 会把 `cloud/build/libs/lom-cloud.jar` 一起 dex 进 APK，所以原版和 Mod 版下次打包都会自动带上这次修复。**APK 还没重打，也没装机。**
- 活侠传的成就表（PC 缓存 `appcache/stats/UserGameStatsSchema_1859910.bin`）：22 个成就全在 stat 1，位号不连续：没有第 4 位，ACH_30004 在第 5 位，之后的顺延一位（ACH_30021 在第 22 位）。用 JavaSteam 自己的 KeyValue 读这份表，旧代码会把 22 个全映射到第 0 位（ACH_30000）。
- 旧代码在活侠传上的实际行为：主账号（下称 <SteamID64> / <accountid>）的第 0 位从 2024-06-28 起就是点亮的，所以每个待报成就都会被判成"已解锁"，直接记进 done，从来没发出过写入（`changed` 一直是空的）。不会误解锁，副作用反过来：在安卓上新拿到的成就会被吞掉，不会报给 Steam。
- 核对结果（11-06 01:56）：
  - 公开资料 `steamcommunity.com/profiles/<SteamID64>/stats/1859910/achievements/?xml=1`：已解锁 7 个，分别是 30000、30001、30005、30009、30015（都在 2024-06），以及 30016、30018（2025-09-27 22:20:44）。2026-11-03 之后没有任何新解锁。
  - PC 缓存 `UserGameStats_<accountid>_1859910.bin`（11-04 23:20）：stat 1 = 721987，也就是第 0、1、6、10、16、17、19 位，和公开资料一致。
  - 存档：Thor 上现在的 `files/Save/Save_universe.dat`（11-04 00:46），SHA-1 54e4c6d7…与 Steam 云端、PC 本地完全相同，LibraryData3 里正好是这 7 个成就；`Save_universe.json`（11-03 10:10）和 11-04 09:40 的备份 Save.tar 也一样。存档里有的成就 Steam 上都有，Steam 上有的存档里也都有。
  - 结论：活侠传**没有误解锁的成就，也没有漏报的成就**，不需要撤销。Thor 上登录的就是这个主账号：11-03 09:16 实测时，正是这个账号的公开资料显示 In-Game Legend of Mortal；Thor 上的存档也和这个账号的 Steam 云端内容相同。第二个账号 在活侠传上没有任何统计（缓存 crc 0）。
- 关于 Thor 上现在的 Mod 版（11-04 09:41 装的）：它带的还是旧代码。在它上面新拿到的成就会被记进 done 而不上报。等装上带修复的版本，format 迁移会清掉 done，游戏启动时重报存档里所有成就，补报不会丢。所以不急着重装，跟下一次 Mod 版更新一起装就可以。11-06 用户已决定：不单独为这次修复装机，等下次 Mod 版更新时一起装。

## 2026-11-06 Steam 下载/更新、正式版启动页、图标、帧率

### 首次启动从 Steam 下载（设备上生成 OBB）

- APK 不再依赖电脑推 OBB。启动页从 Steam 的 Windows depot（1859911）下载原版文件，在设备上转换，直接写成 Unity 读的 `Android/obb/dev.kmlom.mortal/main.1.dev.kmlom.mortal.obb`（边下载边追加，不占双倍空间）。
- 电脑端 `tools/porttool/PortTool.java`（build-apk.sh 自动跑）把电脑版 OBB 的每个文件对照 depot 分类，并在电脑上模拟设备端还原、逐字节核对：
  - `copy` 23 个：和 Steam 原文件一样（约 1 GB 的 .resS 等）。
  - `stamp` 17 个：散装序列化文件，只改目标平台字段（`BundleStamp.convertSerialized`）。
  - `bstamp` 184 个：AssetBundle，只改平台字段（`BundleStamp.convert`，来自 Princess 项目）。
  - `patch` 11 个：着色器转换过的文件，Steam 原文件 + APK 里的差分补丁（`PortPatch`，11 个合计 0.8 MB）。
  - `apk` 272 个：APK 自带（音频 160 MB、il2cpp 元数据、6 个含着色器修正的 bundle、catalog 等，约 200 MB）。
  - 合计从 Steam 下载 2.2 GB；APK 约 370 MB。
- 设备端：`GameData`（计划/安装/更新检查）、`Obb`（写 stored zip、读中央目录）、`SteamDepot` + 纯 Java `Zstd`/`LZMAInputStream`（从 Princess 复制）。记录在 `files/port/obb.state`（每个条目的偏移、SHA-1、来源 Steam 文件的 SHA-1），中断后从 `partial.state` 续传。APK 的文件总写在 OBB 末尾：只换 APK 时就地截断重写尾部，不用整份复制。
- 旧的 adb 推送 OBB（Thor 上那份）没有记录：换新 APK 时会逐个算 SHA-1，能用的直接复用（bundle 认电脑打包版的 SHA，port.txt 里的 alt 列），不需要重新下载。
- 模拟器验证（11-06 19:36，lom-sim）：用 `tools/push-src.sh` 把原版文件放进应用目录，启动页隐藏参数 `--es localSource /data/data/dev.kmlom.mortal/files/lom-src` 代替 Steam，约 20 秒生成 2,446,056,730 字节、526 个条目的 OBB，全部条目 SHA-1 和预期一致，状态切到「游戏数据已就绪」。**真实 Steam 下载没测**（要用户账号扫码；下载链路和 Princess 同一份代码，Princess 用匿名账号在 PC 上验证过）。

### Steam 更新（可行性结论）

- 用 depotcache 里两份清单对比上一次更新（10-20 → 10-27）：改了 Mortal.Battle/Combat/Core/Free/Story 五个 DLL、globalgamemanagers、所有 level 和 sharedassets、catalog，共 35 个文件 793 MB，没有改 bundle。
- 结论：活侠传的更新基本都改游戏代码，**必须在电脑上重新出 APK**（IL2CPP 重编）。设备端能做的：登录后查 Steam 最新清单，代码/着色器相关文件（port.txt 的 base 和 patch 来源）变了就提示「需要新版移植包」，继续玩当前版本；只改了资源就直接在设备上增量更新。装上新 APK 后，只下载和旧数据不同的文件。
- 电脑上跟进新版本：Steam 更新游戏 → `lomtool rt-data` / `rt-bundles` / `rt-pack`（着色器）→ `rebuild.sh`（build-apk.sh 会自动重新生成 port）。

### 启动页、图标

- `LauncherActivity` 重写：上方是 Steam 库的官方主视觉（`art/hero-src.jpg`），左上书法 logo（`art/logo.png`），下方三项状态（Steam 账户/云存档/游戏数据）、朱红主按钮、账户、设置；登录二维码、设置、提示是宣纸卡片。手柄：十字键移动、A 确认、B 返回。素材来自本机 Steam 的 `appcache/librarycache/1859910`。
- 设置：帧率上限（30/60/90/120，默认 60）、渲染分辨率（100/85/70%）、Steam 在线/离线、帧率日志；显示数据版本和可用空间；「检查并修复游戏数据」。
- 图标：`tools/pctest/LomIcon.java` 从 logo 抠出「活」字（按白底上的视觉亮度去掉背后的灰色晕染），宣纸底 + 淡墨圆环 + 小朱印，输出 legacy/round/adaptive 到 `art/icon`，build-apk.sh 替换 Unity 的图标。

### 性能

- 游戏从不设置 `Application.targetFrameRate`；PC 上等于跟随垂直同步，**安卓上 Unity 默认 30 帧**，所以之前的安卓版一直锁 30 帧。`LomAndroid.LomPerf.Apply`（织入 `SystemSettings.SetResolution` 结尾，启动和改分辨率时都会跑）按启动页设置设帧率上限和 URP renderScale，`backgroundLoadingPriority = High`。帧率日志打开后每 5 秒在 logcat 打 `[LomFps]`。
- 离线模式下不连 Steam（时长、云上传都跳过，成就照常排队）。

### 构建注意

- il2cpp 链接偶尔无提示退出 1，而 ld.bfd 子进程还在跑；等所有 ld.bfd 结束后单独重跑 `build-il2cpp.sh`。Chrono Ark 会话也在用 `E:\android-recompile-work\lom-android-work\ndk`，看到它的 clang/il2cpp 别动。
- 模拟器：新建了 `lom-sim`（H:\android-emu\avd，24 GB 数据分区，端口 5630，带 1240×1080 副屏配置），启动命令同 princess-sim，换 `-avd lom-sim -port 5630 -gpu host`。Chrono Ark 会话也往这台模拟器装过它的包。
- `NO_OBB=1 bash tools/build-apk.sh` 跳过电脑版 OBB（只出 APK）。

### 11-06 晚：设置分设备、咖啡伴侣开关、触屏按键（已在 lom-sim 验证）

- **正式包改用带咖啡伴侣的构建**：`NO_OBB=1 bash tools/lomweave/build-mod.sh`（先跑 rebuild.sh 的 stage 步骤），产物 `E:\android-recompile-work\lom-android-work\mod\apk\lom-android.apk`（约 400 MB）。build-apk.sh 按 EXTRA_ASSEMBLIES 写 `assets/port/features.txt`（companion / bridge），启动页据此显示伴侣选项。
- **咖啡伴侣开关**：`LomBoot.Start`（lomweave/shims/BepInEx.Unity.Mono.cs）启动时读启动页设置 `companion`：0 关闭（不挂插件，织入的补丁全部保持关闭 = 原版游戏）、1 普通版（窗口在游戏画面里）、2 双屏版（再挂 LOMThorBridge，窗口交给下屏应用）。首次启动按设备写默认值：有第二块屏且带桥接 → 双屏版，否则普通版。build-shims.sh 加了 AndroidJNIModule 引用。
- **设置页分组**：画面与性能（帧率、渲染分辨率）/ 操作（触屏按键 关·快捷键·虚拟手柄、大小、不透明度、接手柄时隐藏）/ 双屏（只在检测到第二块屏时出现：游戏显示在主屏或副屏、自动在另一块屏打开「活侠下屏」io.github.chankamman_dev.lomthor.dev）/ 咖啡伴侣（单屏设备只有关闭·普通版）/ Steam / 开发者。
- **触屏按键**（cloud/.../TouchPad.java + tools/lomandroid/LomTouch.cs）：按键是叠在 Unity 上面的安卓 View，按到键上的触摸被吃掉、不会传给游戏（游戏对话用旧输入 GetMouseButtonDown，画在 Unity 里会误触）；其它触摸照常进游戏。游戏每帧经 JNI 读状态，写进一个虚拟 Input System 手柄（第一次按下时才创建），游戏动作表本来就绑定了 <Gamepad> 的摇杆/ABXY/肩键/扳机/Start。快速点按会锁存一帧，避免按下和松开落在同一帧之间被漏掉。「伴侣」按钮直接调 StoryReviewPanel.ToggleSettings。接实体手柄（含掌机自带手柄）时默认隐藏，所以 Thor 上默认看不到。
- 验证（lom-sim，带 1240×1080 副屏，`adb emu multidisplay add 1 1240 1080 320 0`）：换新 APK 后「更新游戏数据」就地重写末尾约 15 秒，进游戏到标题（数据全由设备生成）；`[LomPerf] target 60 fps`；双屏版日志「启动下屏桥接」+ 桥接监听 23879；普通版「不启动下屏桥接」；触屏「返回」关掉了游戏设置页；虚拟摇杆下拨移动菜单选中项；「伴侣」打开原 Mod 窗口。截图在 E:\android-recompile-work\lom-android-work\steps（mod-*.png、touch-*.png、pad-*.png、settings-*.png）。
- 没验证：Thor 真机（帧率实测、双屏启动位置、自动开下屏应用）、真实 Steam 账号下载、虚拟手柄在战斗和地图里的手感。已知小问题：虚拟手柄模式下按键会盖住伴侣窗口的一部分，可调低不透明度。
- PortTool 规则补充：改动过的小文件（< 256 KB，如 ScriptingAssemblies.json、boot.config、小 level 文件）整份放进 APK，这样只换 APK 时完全不用联网；就地重写时，新 APK 改为自带的旧条目留作无引用字节，不会出现重名条目。

### 11-06 23:27 装到 Thor
- 用户同意后，把 `E:\android-recompile-work\lom-android-work\mod\apk\lom-android.apk`（Mod 版，带 Steam 下载、正式启动页、图标、60 帧、设置分设备、伴侣开关、触屏按键）覆盖装到 Thor，用时 12 秒。签名一致，版本号仍是 release_1.0.6001.0。
- 装前备份：`E:\android-recompile-work\lom-android-work\thor-backup-20261106\Save.tar`（含 cloud-state.json），40 个存档文件装前装后哈希一致。
- 旧的 adb 推送 OBB（11-04，2.44 GB）没动；首次打开启动页时会逐个哈希接管它，只换 APK 内文件时就地改写末尾。
- 装时活侠传没在运行，前台是 Chrono Ark / Mistria（用户在测），没打断。没有替用户打开游戏。
- 待实测：接管旧 OBB 是否顺利、帧率、伴侣普通版/双屏版切换、下屏应用自动启动、触屏按键（有实体手柄时应自动隐藏）。

### 11-07 下屏并入主包、Steam 成就弹窗（未装 Thor）
- **下屏内置**：build-apk.sh 直接编译 lom-thor-probe/android/src 里的 MainActivity（原样，不改代码，源码路径有空格所以用 javac 参数文件），页面（index.html、app.js、modui*.js、modai.js、icons.js、app.css、fonts/）拷到 APK assets 根目录，features.txt 多一行 `lower`。清单里这个 Activity 跑在独立进程 `:lower`、独立任务（taskAffinity dev.kmlom.mortal.lower），开硬件加速；整个应用加了 network_security_config（tools/res-extra/xml/lom_network.xml），只允许明文连 127.0.0.1（桥接）。
- 启动页 enterGame：咖啡伴侣 = 双屏版、「自动打开下屏伴侣」= 开时，先在另一块屏开内置下屏，再开游戏；包里没有内置下屏时才退回去开单独的 io.github.chankamman_dev.lomthor.dev。Thor 上那个单独应用之后可以卸掉（要问用户）。
- lom-sim 双屏验证：进游戏后 `dev.kmlom.mortal:lower` 进程在副屏，已连上桥接（显示咖啡伴侣的选预设页），游戏在主屏标题画面并保持焦点。副屏截图要用 `adb emu screenrecord screenshot <目录> --display 1`（screencap -d 2 截出空文件）。
- **成就弹窗**：新文件 cloud/.../AchievementToast.java。SteamPlay 写入成就成功后，对 Steam 确认是新解锁的那几个（不含每次启动补报的旧成就）弹卡片：底部居中、从下往上滑入、5 秒后原路退下，多个排队，不拦触摸。名称、说明、图标取自 Steam 成就表（schinese 优先，缓存在 prefs lom_ach_schema），图标从 cdn.akamai.steamstatic.com 下载一次，存 files/ach-icons。设置 → Steam →「成就解锁提示」（achtoast，默认开），切到开会预览。
- 已验证：启动页预览（模拟器上放了一条真实成就数据，图标下载和卡片排版正常）。**未验证**：游戏画面上真实解锁时的弹出（需要登录 Steam 并真的拿到新成就）。
- 包：E:\android-recompile-work\lom-android-work\mod\apk\lom-android.apk（15:40），上一版留在 lom-android-20261106.apk。OBB 没变，Thor 上现有数据可直接用。
- **11-07 15:48 已装 Thor**（用户同意）：覆盖安装成功，签名一致；装前备份 E:\android-recompile-work\lom-android-work\thor-backup-20261107\Save.tar，40 个存档哈希装前装后一致。装时活侠传没在运行（前台是 Brotato）。Thor 上 11-07 00:00 用户打开过 11-06 那版，启动页已接管旧 OBB 并就地改写（obb.state guid ce2a2c1a…，与新包相同），所以这次不用再处理数据。设置里 companion=2（双屏版），进游戏应自动开内置下屏。单独的「活侠下屏」应用还在 Thor 上，卸不卸要问用户。待实测：内置下屏、成就弹窗。

### 11-07 目录整理（用户要求把安卓重编译项目集中）
- 源码从 G:\ai liabary\lom-android 搬到 **G:\ai\android-recompile\lom-android**；大件从 E:\lom-android-work 改名到 **E:\android-recompile-work\lom-android-work**；原 F:\lom-android-work（data2、bundles-lz4-2 等）复制到 E:\android-recompile-work\lom-android-work\f-data（948 个文件、字节数核对一致）。所有脚本和本文档里的路径都已替换。
- F:\lom-android-work 原目录、F:\lomtool-pack、F:\lomtool-run 删不掉（安全检查不让删盘符根目录下的文件夹），留给用户手动删。
- 下屏仍在 G:\ai liabary\lom-thor-probe（不是重编译项目，没搬），build-apk.sh 的 LOWER 改成了绝对路径。
- 删掉的：plain 版 apk 目录（过时的测试包）、mod/apk 里的打包中间文件和 11-06 旧包，共约 13 GB。保留 lom-debug.keystore、mod/apk/lom-android.apk（Thor 上这版）、开发用 OBB。
- 注意：il2cpp 的增量缓存里记着旧路径，下次 build-il2cpp(-mod).sh 可能整份重编（约 1 小时）。

### 11-08 攻略页（独立页面，可上下屏切换）+ 桥接攻略素材接口，20:20 已装 Thor
- **攻略页**在 lom-thor-probe/android/assets/guide.html + guide.js + guide.css，由 GuideActivity（lom-thor-probe/android/src/.../GuideActivity.java、GuideStore.java）显示，跑在 `:lower` 进程、独立任务（taskAffinity dev.kmlom.mortal.guide，见 tools/AndroidManifest.xml）。下屏侧栏「攻略」（guide-link.js）打开它；页面右上「到上屏 / 到下屏」换屏（只有一块屏时不显示），返回键先关详情。
- **内容**：三篇 Steam 指南（3267650620 速查、3304060650 武学养成、3821869593 郁竹小梅）在设备上第一次打开时下载（先走系统代理，失败直连），页面里整理成 JSON 存 files/guide/，APK 不带原文。按主题重组：总览、时间线、伴侣、图鉴（结局/猝死/成就/称号）、武学、养成、资料（含三篇原文目录兜底）。截图走 /gimg/ 代理 Steam 图床（640 宽缓存）。
- **游戏素材**：桥接新增 GET /v1/guide（ModPagesGuide.cs：人物立绘/头像、书籍图标、汗青书/生死簿/风云史的 index、标题、达成、配图），图片只登记不画，攻略页取 /v1/image/<id> 404 时才排队渲染（GameArt.Register/TakeWanted）；立绘和汗青书图按 640 高透明 PNG。攻略页缓存目录到 files/guide/art.json、图片到 cache/guide-art，没开游戏也能看。图鉴按「同类序号」对（指南序号=游戏顺序，三类条数一致），共用 6 次以上的默认插图不显示；生死簿本来无图。
- 构建：tools/build-bridge.ps1 出正式桥接（SHA256 6110E853…686B，合约 231 项、ServerTests 含新 5 项通过），再 `NO_OBB=1 bash tools/lomweave/build-mod.sh`（这次 72 分钟，大头是 E 盘被占满时复制资源包）。上一版包留在 mod/apk/lom-android-20261107.apk。
- **20:20 已装 Thor**（用户同意，说边玩边测）：装前备份 E:\android-recompile-work\lom-android-work\thor-backup-20261108\Save.tar，40 个存档哈希装前装后一致；装时游戏没在运行。没在模拟器上验证（用户要求直接装）。
- 待实测：攻略页从下屏打开、换屏、Steam 指南首次下载（Clash）、桥接素材目录在 IL2CPP 下能否出立绘（AiPortrait）和书名（Plugin.Localized(item.GetNameKey())）、图鉴达成情况。本地预览：G:\ai liabary\.claude\launch.json 的 lom-guide-preview（lom-thor-probe/tools/guide-preview.cjs，素材由 tools/guide-preview-art.py、guide-preview-data.py 从电脑上的游戏导出到 E:\...\guide-preview\art）。
