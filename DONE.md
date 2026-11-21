# 已完成（TODO 归档）

> 由 `TODO.md` 归档的已完成项（`[x]`），保留原文与实测证据，按原分区排列；新完成的条目请追加到对应分区。
> 未完成项与仍在生效的上下文见 [`TODO.md`](TODO.md)。

## 安全修复（2026-10-06）

- [x] **B 站凭据不再随 SERVER 配置外发**：`bilibiliCookie` 原放在 `ModConfig.Type.SERVER`（`config/kazumiplayer-server.toml`），而 NeoForge 的 `ConfigSync.syncAllConfigs`（`SyncConfig` 配置阶段任务）会把**每个 SERVER 配置文件的原文 TOML 明文**用 `ConfigFilePayload` 发给每个连入的客户端（只跳过内存连接＝单机自连），重载后 `registerEventListeners` + `syncPendingConfigs` 还会把新内容推给在线玩家；客户端 `ClientPayloadHandler` → `ConfigTracker.acceptSyncedConfig` 装进内存配置后，任意客户端 mod 都能读出服务端 cookie（客户端 UI 虽禁止在多人游戏里编辑 SERVER 配置，但那只是 UI 限制）；官方文档亦写明 SERVER = "Synced across the network to the client"。已迁到 **COMMON**（`config/kazumiplayer-common.toml` 的 `bilibili.bilibiliCookie`，COMMON 不参与网络同步），**删除 `ServerConfig` 与 `ModConfig.Type.SERVER` 注册——本模组不再存在任何 SERVER 类型配置**；两处读取点（`ServerPacketHandlers` / `QueueRequestHandlers`）改读 COMMON。附迁移提示：旧文件里仍有非空 cookie 而新键为空时启动 WARN 一次（`KazumiPlayer.warnLegacyServerCookie`，纯文本匹配不解析 TOML）。顺带消除 `KazumiMessages` 两处 varargs 编译告警（`Component[]` 传 varargs 需显式 `(Object[])` 转换）。

## 弹幕取数（2026-10-06）

- [x] **未登录弹幕量接近翻倍：匿名时合并老弹幕文件**：`seg.so` 按登录态给量是硬约束，且**只认 `SESSDATA`**——同一 cid 全段走完实测：无 cookie 3777 / 仅设备指纹 `buvid3`+`buvid4`（`x/frontend/finger/spi`）3792 / 完整凭据 9087 / 凭据去掉 `SESSDATA` 3768，匿名只回高权重子集（登录态的 41%）。而老弹幕文件 `comment.bilibili.com/<cid>.xml` **完全不看登录态**（匿名与带凭据响应逐字节相同；与 `x/v1/dm/list.so?oid=` 实测同一份内容——同 cid 两处都是 7200 条、id 集合完全相同、共有 id 的字段逐一相同），且与 `seg.so` 是**两个部分重叠的池**（长视频实测交集 3191 / 并集 7781）→ 取数侧改为**匿名时并发多取一路老弹幕文件，按 dmid 与 `seg.so` 的 idStr 去重后合并**（实测 7200 + 分段新增 585 = 7785 条，达登录态 9100 条的 86%）；带凭据时不请求该文件（其内容基本被登录态分段结果包含：新视频超出 10 条以内、短视频 2 条以内；**老视频例外——旧池另有 978 条 protobuf 池没有的弹幕，当前未合并**）。实现细节：响应是**裸 deflate**（`Content-Encoding: deflate`，非 zlib；复用 `BilibiliHttp` 的两步解包），压缩 294 KB / 明文 755 KB；行格式 `<d p="秒(5位小数),模式,字号,颜色,时刻,弹幕池,midHash,dmid,权重">文本</d>` 实测字段数恒为 9；时间覆盖整条时间轴（首条 0.00000、末条 2949 s，**不按时间截断**）；文本只反转义 5 个常见实体（`&amp;` 最后替换；实测样本只有 `&lt;`/`&gt;`：152 个实体分布在 13 行）；老文件另有条数上限（实测长视频 7200、三个短视频恒 1200，与 `dm/web/view` 计数字段不成 0.6 比例 → **规律未证**），故并集仍到不了登录态全量。**离线交叉验证**：用真实样本（deflate 响应 + 9 个分段）驱动生产解析代码——XML 7200 条、分段新增 585 条、并集 7785 条、mode 分布 {SCROLL 6552 / TOP 1225 / BOTTOM 8} 与 Python 侧逐一吻合，含实体行数 13 与原文相同，首条 `0.00000,5,25,15138834` 的字段映射（TOP / 100% / #E6E6E6）正确。实测细节已并入 `reference/plans/f11-bilibili-danmaku-api-facts.md`（§A6 / §4.3 / 未证清单）。另：**本机未配置凭据时首次装载片内弹幕给一条聊天提示**（`kazumiplayer.msg.bilibili_anonymous_danmaku`——「未登录 B 站：片内弹幕只取到一部分，配置界面「B 站」分类可扫码登录」），静态 `AtomicBoolean` 做**会话级去重**（每屏、每集都会 attach，不去重即刷屏）；直播侧不提示（握手恒匿名 uid=0，与登录态无关）。

## 起播状态机与看门狗（2026-10-06）

- [x] **修「有概率一直停在已就绪界面」（起播饥饿 + 状态不收敛）**：生产日志逐条核过，界面停在「已就绪」时**引擎一帧都没出**——`Starvation — BUFFERING` 反复刷而 WaterMedia 侧**永不放弃**（`FFMediaPlayer:1135-1141` 只把时钟转 BUFFERING 并打一行 WARN，无超时无报错，`Consumption loop exited` 只在 stop/release 时出现），实测三类现场：①视频（B 站 720P 单流回退 mp4，`platform=html5`/`f=T_0_0`）`Loaded 1 uri(s)` 后零帧、`R: 0`、**挂了 219 秒**直到用户退出；②另一视频 18 秒零帧后用户手动离开（`Consumption loop exited — R: 0, S: 0, clock: 501ms, status: BUFFERING`）；③直播正常出画 23 分钟后被 CDN 掐住（2 秒只推进 0.6 秒）→ 僵死 20 分钟 → 才 EOF。诱因与内容种类无关：**`videoCacheMode=STREAM`（默认，也是用户配置）＝`ffmpeg.cache=false`＝始终绕过磁盘缓存**（`FFMediaPlayer#shouldUseFFmpegCache` 首行即 return false），而本文档早已实测「bypass 路径下大文件卡在 Starvation 无法起播」。旧行为下四处提示都只说「解析中 · 已就绪 N/N」——因为 READY 是在 `player.play()` 之后立刻上报的（**不是首帧**），媒体加载失败也从不上报 FAILED，且「已上报就绪但一直没帧」除 75 秒僵尸回收（只认 `!everPlayed`，且不报错不提示）外无任何兜底。
  修复分三层：**①状态如实**——新增 `STATUS_LOADING(3)`（拿到地址只报「媒体加载中」，首帧解码上屏＝`WaterMediaPlayer.hasVideoOutput()`（纹理与非零尺寸同时就绪）才由调度器补报「已出画(1)」；媒体加载/起播失败与解析失败一样报 FAILED(2)；BE 状态值校验放开到 3，组内进度行改为「已出画 x/y · 加载中 n · 失败 m」，观看者列表后缀改「解析中…/加载中…/播放中✓/失败✗」；**②起播看门狗**——起播后 STREAM 25s / CACHE 120s（CACHE 是「先下完整文件再解码」首帧天然慢；直播恒 25s 因其缓存被强制关闭）内没有任何视频帧 → 停掉播放器按原档位**重试一次（不切换拉流模式、不启用 CACHE 兜底，按用户要求）**；仍无帧 → 上报 FAILED + 聊天给出可读原因（`msg.playback_no_frames`）+ 记录失败快照（GUI/全屏横幅）+ 交队列容错（多项目队列切播下一个，无队列上下文仅本端退出同步）+ 进入 60s 自动重试冷却（阶段行显示倒计时），消除「静默死循环」；原 75s 僵尸回收保留为兜底；**③加载过程细化**——三处等待提示（GUI 预览区/全屏 HUD/世界屏幕面）改为两行：第一行 `ResolveHint.phaseLine` 展示本端起播阶段（正在解析视频源/正在获取媒体地址/正在打开媒体流/正在缓冲媒体数据/音频已开始等待视频解码/已暂停（尚未出画）/起播超时正在重试/起播失败N秒后重试）＋已等 N 秒＋量化细节（第 N/M 次尝试 · 档位 X · 直播|点播 · 位置 X 秒 · 取址轮询 N 次 · 失败原因），第二行仍是组内进度；阶段由 `ScreenPlayerManager.ScreenPlayer`（本地瞬态，随新集/换源 `resetStartup()`）承载，未加入该屏时 `phaseLine` 返回 null 以保留「无信号」语义；GUI 取消按钮可见期改为「尚未出画」。顺带把该路径上 4 条硬编码中文聊天文案（视频加载失败/加载超时/创建播放器失败/起播失败）收进语言文件。产物校验：`get_file_problems` 全绿 → IDEA `build_project` `isSuccess=true` → `gradlew build` BUILD SUCCESSFUL，jar 内确认含新键（zh/en 各 403 键、键集一致）与 `STATUS_LOADING` 字节码。**实机复测待用户确认**。

- [x] **同批：独立复审揪出的 5 条自相矛盾处已修**（子代理只读复审 16 文件 diff，结论「语言文件与跨端边界可放心，但先修状态机这几条」）：①**CACHE 档位的 120s 宽限永远轮不到**——僵尸回收排在 tick 最前且只认 `!everPlayed`，首帧晚于 75s 时会在出画前被反复回收，看门狗判据（120s）永远不满足 → 每 75s 静默重启、永不报 FAILED（正是这次要消灭的那类「没画面也不报错」）；现僵尸判据与看门狗统一为 `firstFrameAt == 0`、阈值取 `max(僵尸阈值, 看门狗时限 + 5s)`。②**失败冷却期内阶段被覆盖**：MRL 失败回调置 FAILED 后包装播放器仍在册（MediaPlayer 为 null），下一秒被判成 PREPARING → 倒计时与失败原因消失，界面显示「正在获取媒体地址」而实际在冷却；现 `updateStartupPhase` 开头先判 `nextStartupRetryAt` 并固定 FAILED（倒计时行也补上了原因/尝试次数细节）。③**冷却清不掉**：失败横幅写着「可重新加入同步或更换来源后重试」，而 `resetStartup` 原先只在 URL 变更时调用 → 照做要等满 60s；现停播包无条件移除该屏条目（原先只在 player != null 时移除，而 failStartup 已把它置空），GUI 的加入/提交直链动作显式 `clearFailureCooldown()`。④**一轮起播的界定**：已出画之后的同集重启（清晰度预热回退等）原先不會重算总计时，播了 20 分钟再切档失败会显示「已等 1200 秒」；现 `startupAttempts >= MAX || firstFrameAt != 0` 都算新一轮。⑤**全屏同一条组内进度行画了两遍**（无信号分支自绘 + `drawStartupStatus`，间距 6px < 字体 9px 会叠字）→ 统一由 `drawStartupStatus` 绘制。顺带：解析中/取源中分离（`WaterMediaPlayer.markResolving()`，嗅探期不再误报「正在获取媒体地址」，实测 RESOLVING 原先最长只显示 1 秒）、失败回调早于监听登记时补投（`failureMissed`，否则队列不跳过、状态不转 FAILED）、换源清过期档位表（`BilibiliQualityPrefs.clearInfo`）、删除死代码 `isLiveSource()`、解析器两处硬编码中文（含「视频源解析失败：」与超时/未找到两种原因）收入语言文件（键数 403 → 407，zh/en 一致）。复审明确的「无问题」项：新增 Java 无硬编码中文、无改动叙事注释、common 未引入跨端依赖、语言文件键集/占位符逐一核对一致、越界与空指针路径均先判后画。

## B 站清晰度与凭据优先级（2026-11-21）

- [x] **本机填了凭据仍只有 480P（解析优先级与配置注释相反）**：用户报「填入本地凭据后仍无法观看 480P 及以上」。dev 日志（`run/logs/latest.log` 05:55–06:00）直接给出证据：`[bilibili] server-side resolve by Dev for <URL> (qn=0, live=false, credentials=anonymous)` → `bilibili stream resolved via server (qn=32)`，即**服务端代理是匿名的**，只拿到 `dash.video[].id = [16, 32]`（360P/480P），下拉里能选的也只有这两档。同一视频同一时刻的对照实测（BV1fMb16vEcU）：匿名 `quality=64`、`dash_ids=[16,32]`；本机凭据 `quality=80`、`dash_ids=[16,32,64,80,116]`（360P/480P/720P/1080P/1080P60）——**本地凭据完全有效，只是没被用上**。根因：`ClientConfig.bilibiliCookie` 的注释写着「本地填写后优先于服务端下发的凭据」，而 `VideoSourceResolver.resolveBilibili` 的实际逻辑是**无条件先发服务端代理请求**，只有「未连接 / 20s 超时 / 服务端返回 ok=false」才走 `fallbackLocalResolve`；服务端匿名也能解析成功（只是档位低），所以永远走不到回落分支。用户的 cookie 填在 `kazumiplayer_client-client.toml`（len=346，含 SESSDATA），而服务端读的是 `kazumiplayer-common.toml`（len=0）——旧 `kazumiplayer-server.toml` 里也有一份 len=346 的凭据，但 `warnLegacyServerCookie()` 只打 WARN 不搬值，用户按提示手动搬时漏了。
  修复（A+B 同批）：**A** 把凭据复制进 `kazumiplayer-common.toml`（服务端代理从此不再匿名）；**B** 改实现为「本机有凭据则本端优先」——新增 `dispatchResolve` 分流：`BilibiliCredentials.present()` 为真时走 `runLocalResolve(pending, fallbackToServer=true)`（本端失败且在线再回落服务端代理），为假时走 `requestServerResolve`（服务端优先，失败/超时/未连接回落本端匿名）；抽出 `registerPending`（每屏唯一在途请求 + 直播置 bypassSync）供两条通道共用，`fallbackLocalResolve` 更名 `runLocalResolve` 并加 `fallbackToServer` 参数（回落通道不再反向回落，避免成环）；`beginQualitySwitch` 也改走 `dispatchResolve`（切档同样按凭据来源选通道）。顺带修 `BilibiliApi.buildDashStream` 的选档 bug：原先 `requested` 取自 `data.quality`（服务端替你选的档，匿名时恒为低档），**与请求参数无关** → 即使传了 `preferredQn=80` 也只取到 64 档；现改为 `preferredQn > 0 ? preferredQn : data.quality`，`preferredQn` 一路透传进 `buildDashStream`。两处配置注释同步改为与实现一致（`ClientConfig` 说明「本端优先，服务端仅兜底」，`Config` 说明「本项只服务服务端代理，本机配了则本端优先」），并把「未登录上限 720P」纠正为实测的 **480P**。
  产物校验：`gradlew build` BUILD SUCCESSFUL（`clean` 因游戏占用 build 目录失败，改用增量）。**实机复测待用户确认**（需重启游戏让新配置与新 jar 生效）。

## 崩溃修复（2026-11-21）

- [x] **1080P→4K 无缝切档后 64ms 崩溃（`Texture view Sampler0 (801) has been closed!`）**：`run/crash-reports/crash-2026-11-21_06.43.08-client.txt` 栈为 `GlCommandEncoder.trySetup` → `executeDraw` → `GuiRenderer.executeDraw`，即 GUI 渲染管线引用了已关闭的纹理视图。日志时间线精确到毫秒：`06:43:07.771 Seamless quality switch completed (qn=120)` → `06:43:07.835 Unreported exception`，间隔 **64ms**。根因：MC 26.1.2 的帧流程是 `extract()`（`extractLevel` 世界 → `extractGui` GUI）→ `render()`（`renderLevel` 世界 submit → `guiRenderer.render` GUI 绘制）；GUI 元素在 **extractGui** 阶段就通过 `GuiGraphicsExtractor.innerBlit` 抓走 `texture.getTextureView()` 存进 `BlitRenderState`，帧末才真正绘制。而 `VideoScreenTexture.updateFrame` 在 **render 阶段的 submit 里**发现分辨率变化（1080P→4K）→ `getOrCreateDynamicTexture` → `TextureManager.register` 覆盖注册 → `safeClose` 关闭旧纹理（连带关闭其 `GpuTextureView`）→ GUI 手里那份视图已失效 → 绘制时抛异常。旧注释「先建新后覆盖注册即可」是错的——**顺序对，但时机不对**：只要重建发生在 GUI 抓取视图之后、绘制之前，无论怎么建都会崩。
  修复：把纹理重建从 render 阶段移到 extract 阶段。`VideoScreenTexture` 新增 `prepareFor(player)` / `preparePlaceholder()`（extract 阶段调用，负责按当前分辨率重建），`updateFrame` / `fillPlaceholder` 改为**只上传不重建**（发现尺寸不符只跳过本帧并记 DEBUG）。调用点三处：`VideoScreenRenderer.extractRenderState`（世界 BE）、`ClientFullscreenState.onHudLayer`（全屏）、`KazumiPlayerScreen.drawPreview`（GUI 预览）——后两处**必须各自调用**，因为观影/GUI 时玩家可能背对屏幕方块，BE 被视锥剔除后 `extractRenderState` 根本不执行，纹理就永远停在旧尺寸（切档后画面卡住/错乱）。已验证 `extract` 与 `render` 在同一帧内成对调用、中间只有 `RenderSystem.executePendingTasks()` 没有 tick，故 extract 阶段定好的尺寸在 render 阶段不会变。

- [x] **远程观影器「已出画 1/1」永久卡在画面中央**：`ClientFullscreenState.drawStartupStatus` 无条件绘制组内进度，而 `ResolveStates` 只在换集/停播时清空——出画后它仍留在 NBT 里，于是 `ResolveHint.of(be)` 恒非 null，文字永不消失。GUI 预览区（`KazumiPlayerScreen.drawPreview`）与世界屏幕面（`VideoScreenRenderer.submit`）都有「本端无有效帧」守卫，**全屏这条路径漏了**。修复：`drawStartupStatus` 开头取 `VideoScreenRenderer.getScreenTexture(screenPos).hasValidFrame()`，本端已出画时把 `phase` 与 `others` 都置 null（失败横幅仍按原逻辑显示）。

## 崩溃修复（2026-10-06）

- [x] **按 F2 截图后驱动崩溃（`nvoglv64.dll` EXCEPTION_ACCESS_VIOLATION）**：崩溃栈为 `GL11C.nglGetTexImage ← VideoScreenTexture.updateFrame ← VideoScreenRenderer.submit ← LevelRenderer.addMainPass`。根因是**像素打包状态被截图路径污染**：MC 26.1 的截图/纹理下载走 `GlCommandEncoder.copyTextureToBuffer`，其中 `GlStateManager._pixelStore(3330, width)` 把 `GL_PACK_ROW_LENGTH` 设为**帧缓冲宽度**，读完只解绑 PBO、**从不复位**（全 MC 反编译源码内该状态仅此一处写入）→ 此后每帧的 `glGetTexImage` 都按该行距铺满 height 行，远超 `NativeImage` 缓冲容量。**离线端到端复刻**（离屏 GL 3.3 + 与生产代码同序列的调用，NVIDIA 616.92）：先按截图路径留下残留（`PACK_ROW_LENGTH=320`），再把 128×64 纹理读进 32768 B 缓冲——旧序列从偏移 33280（第 26 行起）一路写到 81151，**越界 ≈48 KB 且 `glError` 恒 0**（静默越界）；同一序列加上修复后**越界 0 字节**。修复：读回前显式置 `GL_PACK_ROW_LENGTH`/`SKIP_ROWS`/`SKIP_PIXELS`=0、`GL_PACK_ALIGNMENT`=1，读完复位为 GL 默认值（0/4）；另加一次性 GL 错误告警便于同类问题将来暴露。命中条件与现象吻合：**只有"播放视频 + 按过 F2"之后才会崩**（崩溃日志 elapsed 3696 s，崩点正落在视频读回路径）。实验脚本：`reference/probe/gl/GlPackCheck.java`（纯越界测量）与 `reference/probe/gl/GlScreenshotRepro.java`（截图残留 → 读回端到端复刻）。

## 直播播放（2026-10-06）

- [x] **直播播放源加载失败（用户报「直播间解析失败」）**：实测**解析本身成功**——日志里 `live stream registered as kazumibili://kz-6` 与 `Loaded 1 uri(s) for: kazumibili://kz-6` 都正常打出，失败在装载环节（`MRL loading timeout` → 聊天栏「播放源加载失败」，用户当时改用直链 m3u8 绕过，而直链没有直播间号所以也没有弹幕）。**根因是过期时间**：`MRL.status()` 每次被查询都会把 `expiresAt` 早于当前时刻的条目标成 `EXPIRED`，而 `MRL.get()` 见到 `EXPIRED` 立刻 `reload()`（清空 `sources` 并重新装载）→ 我们 500 ms 一轮的装载循环里 `source(0)` **永远为 null**，直到 15 s 超时。喂进去的纪元 0 从哪来：直播取流地址的签名参数是 **`expires=`**（绝对 Unix 秒），而 `VideoSourceResolver.linkDeadlineOf` 只认点播 DASH 的 **`deadline=`** → 直播拿到 0 → `PlatformData(Instant.ofEpochSecond(0))`。日志指纹完全吻合：`Loaded 1 uri(s)` 每 500 ms 精确重复一次（等于循环节奏），15 s 后超时。修复三处：①`linkDeadlineOf` 同时识别 `expires`（`deadline` 优先）；②`KazumiBiliPlatform` 把 `expiresAtEpochSec <= 0` 映射为 `PlatformData(null, …)`（= 永不过期），**绝不写纪元 0**；③装载循环把 `Status.EXPIRED` 当确定失败即时收尾（新文案 `kazumiplayer.msg.playback_link_expired`），不再白等 15 s。验证：该房间 `getRoomPlayInfo` 正常、12 条流地址实测全部可达（FLV 200 + `FLV` magic、m3u8 200）；房间 `getDanmuInfo`（WBI 签名）code=0、token 256 字符、host_list 3 条 → 修好后房间流程会带上 `liveRoomId` 正常接弹幕；构建通过。**实机复测待用户确认**。
- [x] **「直播切档要更高账号权限」是误报，文案已改**（同批追问）：实测该房间逐档请求——**匿名恒 250**（原画 10000 / 蓝光 400 / 流畅 80 全被夹到 `_2500.flv`）；用本机凭据（非大会员、已登录）则 10000→`_bluray.flv`（1080P 原画）、400→`_4000.flv`（1080P 蓝光）**都被尊重**，且 **accept_qn 本身随账号变**（匿名 `[10000,400,250]` → 登录 `[25000,10000,400,250]`）→ 直播清晰度门槛是**登录/账号权益，不是大会员**。真正原因：解析走服务端代理，而服务端凭据（COMMON `kazumiplayer-common.toml`）为空 → 匿名解析 → 恒 250 → 切档必然不生效，而旧文案一概归因成「该档位需要更高的账号权限」，把用户引向错误方向（同源影响：服务端代理匿名时**视频也一直只解析到 480P**，日志可证 `resolved via server (qn=32)`）。改动：一条文案拆成两条并按解析来源分流（`bili_quality_fallback_server` = 服务端账号，玩家改不了；`bili_quality_fallback_local` = 本机账号），日志补一句「服务端代理解析用的是 common 配置里的凭据」便于排查。**解析优先级已于 2026-11-21 改为「本机有凭据则本端优先」**（见下方条目）。

## 前置 Mod 跟进（2026-10-06）

> 本区仍有未完成项，上下文见 `TODO.md`。

### Rinku 3.0.4：能捡的便宜与两个新坑

> 本区仍有未完成项，上下文见 `TODO.md`。

- [x] **嗅探浏览器降帧（`setWindowlessFrameRate(30)` 定值）**【S】: 嗅探浏览器**从不显示**（全项目无任何 `getTextureIdentifier`/纹理绘制点），但 OSR 每帧都走 `RinkuBrowser.onPaint`：非渲染线程路径会把整块 dirty buffer `memAlloc+memcpy` 塞进 mailbox（`MAX_PENDING_PAINT_STREAMS=2`）再上传纹理 → v3 默认 60fps（MCEF 时代 30）× 最多 5 个 worker = 每秒最多 300 次「没人看」的拷贝+上传。做法：创建后调 `setWindowlessFrameRate(n)`（`CefBrowser_N.java:1318`，**小于 1 抛 `IllegalArgumentException`**；`CefBrowserSettings` 里的 0 只在创建期表示退回 30fps）。定值 30、暂不做配置项（`RinkuSniffBrowser.SNIFF_FRAME_RATE`）：嗅探的一秒轮询是 Java 侧 `executeJavaScript`，与绘制无关，降帧不影响命中率。

### WaterMedia 3.0.2：升级与迁移（2026-11-21）

> 本区仍有未完成项，上下文见 `TODO.md`。

- [x] **升 API + binaries 到 3.0.2 并迁移全部调用点**【M】: 旧钉版 `G922NeHS:FdCZ5Rxq`（3.0.0.23）与 `4997XcoK:fYWsOuBz`（binaries 3.0.0.6）**已从 Modrinth Maven 下架**（`maven-metadata.xml` 只剩 3.0.0/3.0.1/3.0.2，CDN 文件仍在但 Maven 端点 404）→ `./gradlew build` 在 `:compileClientJava` 直接失败。3.0.1 起 API 包结构大改，**不是 Z 级兼容升级**（wiki 的「Z 升级绝不破坏」承诺与实际字节码不符）：`api.media.MediaAPI`→`api.player.PlayerAPI`、`api.media.MRL`/`api.media.players.MediaPlayer`→`api.player.*`、`IPlatform`→`Platform`（`getData(URI)`→`resolve(URI)`，`PlatformData`→`Platform.Resolution`）、`DataSource`→`Platform.Source`、`DataQuality`→`Platform.Quality`、`Slave`→`Track`（`audioSlaves`/`subSlaves`→`audioTracks`/`subtitleTracks`）、`RequestHeaders`→`HttpHeaders`、`WaterMediaConfig.media.ffmpeg`→`codecs.ffmpeg`、`cacheMaxSize`→`cacheMaxResponse`、`liveSource()`→`live()`、`MRL.uri` 字段→`uri()`。binaries 3.0.2 的 `mods.toml` 要求 API `[3.0.1],[3.0.2,3.1)`（3.0.1 那份要求 `[3.0.0],[3.0.1,3.1)`，**不接受 3.0.0.23**）→ 二者必须同批升。`mods.toml` 依赖范围同步改 `[3.0.2,)`。参考源码已解到 `reference/watermedia-src/302`、官方中文 wiki 全量拉到 `reference/watermedia-wiki/zh-CN`。
- [x] **按 wiki「AI 智能体」硬性要求补齐返回值检查**【S】: 3.0.2 的 `start()/startPaused()/pause()/resume()/stop()/seek()/seekQuick()/togglePlay()` 全部返回 `boolean`（生效 true / 被拒 false），而旧代码一律忽略返回值。①`start()` 被拒 → 当起播失败（聊天报错 + 释放引擎 + 触发失败回调），否则会静默停在无画面状态；②`pause()/resume()` 被拒 → **不再通知监听器**（原先音响会跟着"暂停"一个仍在出声的屏幕）；③`seek()` 被拒 → 转存为待应用 seek 交给 tick 重试（原先直接当已生效，同步锚点会永久错位）；④`applyPendingSeek` 被拒 → 保留意图下 tick 再试；⑤构造/起播抛异常时释放已创建的引擎（`releaseQuietly`，防原生资源泄漏）；⑥`SpeakerClientAudio` 补 `getPlayer()` 判空（MRL 异步装载完成前为 null）。另按 wiki 核对确认无违规：未调用 `WaterMedia.start`（加载器负责）、未在游戏/渲染线程调 `mrl.await`、引擎以 supplier 传入且不跨播放器共享、`release()` 只在后台线程调用、未使用反射访问 WaterMedia。
- [x] **文档债（已修）**：`CLAUDE.md`「WaterMedia v3 关键」重写为 3.0.2 版（含完整破坏性变更清单与「为什么必须升」）；`README.md` 前置版本、`reference/wiki/01-overview.md` 技术栈表与安装前置、`reference/wiki/04-playback.md`（`PlayerAPI.player` 调用示例 + `cacheMaxResponse`）、`reference/wiki/08-dev-notes.md` §2.8/§2.9、`reference/wiki/_research/media-rules-search.md` 全部同步；`TODO.md` 的「WaterMedia 3.0.0.23 影响评估」改写为 3.0.2 版并登记 3 条新 API 待评估项（站点白/黑名单、`NetworkAPI.serverEnabled()`、错误码 `wm_err`）。
- [x] **产物校验**：`gradlew clean build` BUILD SUCCESSFUL，`build/libs` 两个 jar 正常产出；jar 内 `mods.toml` 的 watermedia 依赖范围确认为 `[3.0.2,)`；`KazumiBiliPlatform`/`WaterMediaPlayer`/`BiliStreamRegistry` 字节码均在客户端 jar 内；语言文件 zh/en 各 407 键、键集一致。**实机复测待用户确认**（需重装 WaterMedia 3.0.2 + Binaries 3.0.2 前置）。

## 直播延迟（2026-10-06 诊断，待拍板实施）

**现象**：游戏内看 B 站直播比网页端明显落后（用户反馈）。

**实测证据**（房间 6，2026-10-06）：
- 我们走 `http_hls`（`BilibiliApi.resolveLive` 写死优先 HLS，ts 优先于 fmp4）：m3u8 为 `TARGETDURATION:3` + **3 段窗口 = 9s 媒体**；实测「最新段起始距今 2–5s、窗口起点距今 9–10s」→ HLS 的理论下限就是 ~5s，从窗口头起播就是 ~9s。
- 网页端走 `http_stream`/FLV（flv.js），无分段窗口 → 常态 1–3s。
- `getRoomPlayInfo` **同一次响应里就同时下发 6 组**：`http_stream`(flv, avc/hevc) + `http_hls`(ts/fmp4, avc/hevc)——我们只取了 hls。FLV 实测：HTTP 200、`Content-Type: video/x-flv`、magic `FLV`、首包 0.22s、**无需 Referer**。
- WaterMedia 不是元凶（逐行核实）：起播阈值≈第一帧、无「先缓冲 N 秒」、无 live edge 逻辑；但**没有 FFmpeg 选项注入通道**（`DataSource` 7 字段/`MRL.Source`/`Metadata` 均无 options，唯一生效通道是 `RequestHeaders` → `headers`），故 HLS 侧调不了 `live_start_index`/`fflags=nobuffer`/`low_delay`。

- [x] **直播改走 FLV（http_stream）优先、HLS 回落**【S-M】（已实施）：`BilibiliApi.resolveLive` 优先 `http_stream`+`flv`+`avc`，缺失时回落现有 `http_hls`（ts→fmp4）；`preferredQn`/`accept_qn` 逻辑不变。预期延迟 9–12s → 2–4s（贴近网页端）。
- [x] **护栏：播放 FLV 直播强制 `WaterMediaConfig.media.ffmpeg.cache=false`**【S】（已实施）：`FFMediaPlayer#shouldUseFFmpegCache`（源码 :2037-2048）对**非 `.m3u8`/`.mpd`** 的 http(s) URL 一律走磁盘缓存，而 FLV 直播 URL 以 `.flv` 结尾且是无限流 → `videoCacheMode=CACHE`（或 WaterMedia 自身 `cache=true`）下会「先把整个文件下完再解码」＝永远起播不了。现默认 STREAM 模式（cache=false）安全，但 CACHE 模式必须临时降级并在直播结束后恢复。
- [x] **FLV 403 修复（实机暴露）**：直播 FLV 节点按 **UA + Referer 双重防盗链**校验——头组合矩阵实测：Chrome UA 无 Referer=403、Lavf UA 无 Referer=403、Lavf UA + Referer=403、**Chrome UA + `Referer: https://live.bilibili.com/` = 200（magic FLV）**；而 m3u8 四种组合全部 200（这正是 HLS 一直能用、FLV 一上就 403 的原因）。FFmpeg 默认 UA 是 `Lavf/...`，普通 MRL 又表达不了请求头 → 直播改经平台通道注册：`BiliStreamRegistry.registerSingle(url, deadline, LIVE_REFERER)`（原 `BiliDashRegistry` 更名，Entry 增 `referer` 字段、音频流可为空），`KazumiBiliPlatform` 按条取 Referer（缺省 `https://www.bilibili.com/`）、单流时 audioSlaves 留空；有效期取链接自身 `deadline` 且**不扣余量**（直播链接短，扣 600s 会把刚签发的判成过期）。验证：按平台实际下发的三头组合复测 = HTTP 200 + magic FLV
- [x] **起播提速**【S】（已实施）：`media.ffmpeg.analyzeDuration` 7000→2000ms、`probeSize` 10→2MB（只影响起播快慢，不影响稳态延迟；全局生效、含点播，极端源可能探测失败）。

### 实机回归（2026-10-06 全部通过）

- [x] **屏幕前方深度分层**：3 层的方向（`DanmakuDepthLayers.OBSERVER_Z_SIGN` 若相反只翻这一个常量）/层次观感/帧率影响
- [x] **全屏描边观感**：改用 `Style` 阴影单次绘制后（原 9 次绘制有重影），实机确认与 B 站观感一致
- [x] **开关关闭后在屏弹幕不消失（用户反馈，已修）**：快捷开关/配置开关只拦新条目（准入期 `isVisible`），已在屏条目仍按自身寿命飘到出屏。两层到期扫描补上同一判据——来源/模式/彩色过滤任一关闭即在当帧作废（世界层逐深度层、HUD 层同一口径），玩家弹幕不受影响
- [x] **全屏弹幕快捷开关**：手感、与进度条的挤压关系、直播屏底条表现

## 深度审计修复（2026-08-24）

- [x] **P1 RuleManagerScreen 成功回包 NPE**: 服务端 PlayOkPayload 只填 key（title=null），客户端 `Component.literal(null)` 必崩 → 改用 `p.toComponent()`
- [x] **P1 HttpUtil SSRF 重定向绕过**: Redirect.NEVER + 手动循环逐跳 checkSsrf（≤5 跳）；CGNAT/ULA 拦截配置化（`ssrfBlockCgnat` 默认 true / `ssrfBlockUlaIpv6` 默认 false 兼容 TUN 代理）；白名单归一化；单次 getAllByName 全地址校验
- [x] **P2 服务端六项**: 通知组件 getString 扁平化（专用服显示原始 key）/ Component 拼接 toString 调试串 / translatable.getString 系统性违规 12+ 处 / 整屏停止四处收敛 PlaybackController.stopScreen（含通知死代码与 WatchingPlayers 清理）/ RuleManager 损坏文件容错+原子写盘 / playFromSearch 统一播放入口+集数钳制落盘
- [x] **P2 客户端三项**: BrowserCookieStore 移除（收割端从未接线+UA 不配对，文档债同步修正）/ 死配置处理（maxConcurrentPlays 接入调度闸门、videoVolume 总系数接线；mcefLifecycle、autoJoinSync 移除）/ GUI static 会话换屏重置
- [x] **P2 公共层五项**: DNS 单次解析全地址校验 / 内网段字节级判定（CGNAT/ULA/组播/240/4）/ EpisodeUrlNormalizer 孤立 % 容错 / 两处 BE loadAdditional UUID 容错 / common 四文件 net.minecraft.client 引用解耦（ClientPacketSender 钩子 + KazumiClientMessages 迁移）
- [x] **P3 必修两条**（复核确认）: krule 异步回调 mc.execute 投递（CME 路径）/ ScreenPropsScreen Locale.ROOT
- [x] **配套**: 语言键全量比对补缺 8 个（status_done/speaker_connected/disconnected/ok.joined/played_episode_n/cmd.rule.testing/play_limit/item.*×7）；调试物品 debug_verify（外壳永久保留，验证区提交前清空）

## 体验与功能待办（2026-08-24 使用体验分析筛选，任务看板同名卡片）

> 本区仍有未完成项，上下文见 `TODO.md`。

### 功能性 Bug
- [x] **A1 ±10s 与小幅度 seek 无效**: 无本地回显，靠 handleSyncState 阈值 drift>10000ms 严格回灌永不触发，落盘位置恒差 ~10s——已由「seek 定向指令」解决：SyncStatePacket 新增 forceSeek 标志，GUI ±10s 与命令时间调整绕过漂移保护全组立即跳转（applyForcedSeek，仅保留天文位置拒绝）【S】
- [x] **A2 断线重进从 0 重播**: joinScreen 异常恢复分支 setPlayback(url,0) 覆盖已落盘进度——已修：异常恢复沿用落盘 SyncPositionMs（clamp ≥0），不再从 0 重播【S】
- [x] **A3 非队列播放失败整屏连坐**: 一人失败全场被踢且进度清空——已修：无队列上下文降级为仅发起者本端停播并退出同步（QueueRequestHandlers.stopLocalOnly → PlaybackController.leaveOwn），其他观看者不受影响【S】

### GUI 体验

> 本区仍有未完成项，上下文见 `TODO.md`。

- [x] **B1 全屏观影控制条+快捷键**: 底部悬浮控制条（暂停/±10s/拖动seek/音量）+空格/方向键，进度条可拖——已实装：控制条含暂停/±10s/可拖动进度条+时间，空格暂停、←→±10s、↑↓本屏音量、M 静音（OSD 提示）；直播直连屏停用控制条与时间轴类快捷键，音量类保留【M】
- [x] **B4 seek/HLS 重缓冲冻结指示**: 时间戳冻结 >1.5s 角落缓冲动画——已实装（全屏 HUD）：ScreenPlayerManager.isBufferingFrozen（阈值 BUFFER_FREEZE_MS=1500，位置推进即刷新时刻）→ 进度条上方琥珀文字，独立于控制条淡出恒可见【S-M】
- [x] **B5 音量体系升级**: GUI/全屏音量滑块+每屏独立音量+静音快捷键（现全服共享 RECORDS 滑块）——已实装：有效音量=RECORDS×videoVolume×volumeScale（静音=0），每屏状态存 ScreenPlayerManager；GUI 进度条右侧滑块+静音钮、全屏 ↑↓/M、全局 M 键静音准心屏幕【M】
- [x] **B8 番剧单击改选中**: 单击只显示简介，双击/按钮才搜源（现误点即全源搜源+清选集）——已实装：搜索结果行改 addDoubleClickableRow，单击只 selectSubject（选中+显简介，零网络请求），双击才 searchSubject【S】
- [x] **B10 列表行悬停 tooltip 显全文**: 现 SimpleList 剪字无省略号、全局零 tooltip——已实装：悬停且文本被截断时 setTooltipForNextFrame 显示全文（自动换行 200px）【S】
- [x] **B13 设置数值框失焦/Enter 提交**: 现 setResponder 逐键发包致画面抖动+包风暴——已实装：ScreenPropsScreen 数值框改失焦/关闭时提交（propCommits 登记 + commitBox，等值输入早退），逐键发包与画面抖动消除【S】
- [x] **B14 加入/离开按钮按观看态置灰**——已实装：joinButton/leaveButton 按本端观看态互斥置灰（数据源=BE 同步的 WatchingPlayers，与右列 ▶ 标记同源）【S】
- [x] **B15 全屏退出按钮鼠标静止 3s 淡出**——已实装：控制条与退出按钮共用鼠标静止 3s 淡出显隐（controlsAlpha/fadeAlpha，淡出后命中失效）【S】

### 功能新增

> 本区仍有未完成项，上下文见 `TODO.md`。

- [x] **B 站视频/直播解析（第一批，已实装）**: util/BilibiliUrls 识别视频页（BV/av 含 ?p=）/直播间（live.bilibili.com/<房间号>）/b23.tv 短链 → VideoSourceResolver.resolveBilibili 绕开 MCEF 嗅探按类型分流——**直播间**交 WaterMedia 内置 BiliBiliPlatform（http_hls m3u8，实测可播）并置 bypassSync；**视频页**走 client/BilibiliApi 自研（view 取 cid → WBI 签名调 html5 播放接口取 durl 单流 mp4，失败回退内置解析）——内置视频解析产 DASH 分离流（音频 slave 被 CDN 终止握手 → 无声 + 画面卡缓冲），故不作首选；短链经 HttpUtil.resolveFinalUrl 重定向展开（含会话守卫）；服务端 ServerConfig.bilibiliCookie（SERVER 类型配置）经 BilibiliCookiePacket 登录下发 → 客户端写 BilibiliCredentials 与 WaterMediaConfig.platforms.biliBiliCookie（未登录 720P 上限）；队列标签对视频页取 BV/av 号；参考文档 reference/watermedia-wiki/en-us.md 已按实际 jar 重写
- [x] **B 站扫码登录与清晰度切换（第二批·已实装）**: 配置界面「B 站」分类：只读状态行（只报字符数，不回显明文）+「B 站 Cookie（留空=不修改）」+「扫码登录」（独立界面，zxing 编码二维码+每秒轮询）+「复制凭据」（只复制本机）+「清除本地登录」；播放器「线路」右侧「清晰度▾」按解析档位即时切换（仅本端生效、切完续播原进度）
- [x] **B 站解析改为服务端代理（第二批·架构调整）**: C→S BilibiliResolveRequestPacket + S→C BilibiliResolveResultPacket；服务端三查后用 ServerConfig.bilibiliCookie 调 common/bilibili/BilibiliApi（视频 html5 单流 mp4 / 直播 getRoomPlayInfo m3u8），只回传有时效地址与档位表——凭据不出服务端；客户端按 requestId 匹配在途请求，失败/20s 超时/未连接时用本机凭据回落自解析；BilibiliCookiePacket 与下发链路删除，凭据不下发/不回传/不回显
- [x] **B 站高清清晰度（DASH，已实装）**: 视频 1080P 及以上只有 DASH（音视频分离的两条流），单流接口硬顶 720P（fnval=0/1 × html5/pc/android/iphone × qn=80/116 共 16 组实测全部只回 720P 的 durl，无反例）；故 BilibiliApi.resolveVideo 改走 fnval=16 + platform=pc 取 DASH，档位表按 support_formats 构建（limit_watch_reason=1 即账号权益受限档，如大会员专属的 1080P60）+ dash.video[].id 集合标可用性，**一次请求即得全部档位**；音频独立成流，经 client/bilibili/KazumiBiliPlatform（PlatformAPI.register + 自定义媒体 URI kazumibili://）+ BiliDashRegistry 挂成 audioSlave（WaterMedia 的 FFMediaPlayer.initAudioSlave 会带 source headers 拉取音频）；CDN 候选按 backupUrl→baseUrl 排序（P2P *.mcdn.bilivideo.cn 压最后，实测约一成连接失败）并并行探测；有效期取 URL 的 deadline（签发后 2 小时）减 10 分钟；DASH 完全不可用时回落单流 720P。UI：档位下拉标注「（大会员）」、本账号拿不到的档位灰显且点选直接说明原因（不再静默降级），无缝交接的成功提示改报实际生效档位而非请求档位。实测结论：登录后 1080P 可用（非大会员），1080P60/1080P+ 需大会员【M-L】
- [x] **修复：队列始终显示 BV 号/房间号（用户反馈两次）**: 根因不在服务端——debug 日志实证 `Queue labels refreshed from metadata cache` 已执行、`BilibiliApi.fetchMeta` 也成功，语义化名称确实写进了 BE 的 Road identifier；**是客户端队列面板渲染时只用 URL 重新生成标签**（`makeLabel`），从未读 identifier。修复：`DirectLinkQueue.parseLabels` 新增读取入口，客户端面板 / `/kazumi queue list` / 切播提示全部改为「先取 identifier、缺失才回落 makeLabel」；另外补上切集路径的缺口——`PlaybackController.applyEpisodeSwitch` 会把已播前缀移出队列并重建 Road（标签退回 BV 号），现在与入队/move/remove 一样统一经新增的 `server/network/BilibiliMetaCache.applyTo` 回填（缓存类从 QueueRequestHandlers 内部提出来做成公共静态，供切集路径复用）【S】
- [x] **B 站弹幕（第四批·已实装）**: 视频片内时间轴弹幕 + 直播实时弹幕，与房间互发弹幕**共池混合渲染**（用户裁定）【M-L】
  - 取数（common `bilibili/`，**零新增第三方依赖**·JDK 内置足够）：视频 = view 取 cid → `x/v2/dm/web/seg.so` **裸 protobuf** 分段（每段 360s，段数由**窗口并发探测**决定——连续两窗空即停，末尾多花 4 个请求换「长静默段不误判为结尾」）→ `BilibiliProtobuf` 手写 wire 解析（proto3 默认值整个省略，progress=0 时字段不存在须按 0 兜底）→ 按 idStr 去重、过滤 pool=2 字幕池、按 progressMs 升序；越界段实测有 `200+0 字节` 与 `304` 两种表现，一律当空段不重试。
    **弃用 `x/v1/dm/list.so`**：实测硬上限（实得 ≈ min(总数, 0.6×计数)，大视频必丢低权重弹幕）且无间隔连发 30 次全部 HTTP 412 风控 ≥20 分钟；**匿名只回高权重子集**（某分P 匿名 814 条 vs 带 cookie 11085 条）→ 全量必须带 cookie（本地凭据，空则照常请求子集）。
  - 直播 = `room/v1/Room/get_info` 取**真实 room_id**（URL 可能是短号，实测 6→7734200）→ `getDanmuInfo` **必须 WBI 签名**（无签名恒 code -352）→ `java.net.http.WebSocket` 连 `wss://<host>:<wss_port>/sub`（回落 `broadcastlv.chat.bilibili.com:443`）→ 认证包 16 字节头 op=7 + JSON `{"uid":0,...,"protover":2}` → 30s 心跳 op=2 → op5 帧 zlib 解压后**再按 16 字节头切包**（一帧拼接多包，内层 protover=0）→ 只处理 `DANMU_MSG`（`info[1]` 文本、`info[2][1]` 昵称、`info[0][1]` 模式、`[3]` 颜色、`[2]` 字号；表情弹幕 `info[0][12]==1` 忽略；轮播房长时间 0 条属正常**不得判为连接失败**）；**token 必须与 uid 同源**（错配 1006 关闭且无 op8）故固定匿名链路；断线退避重连 3 次（3/6/12s）、close() 幂等可中断。
  - 接入（`client/danmaku/source/BilibiliDanmakuService`）：`cid`/`liveRoomId` 随解析结果下发（`BilibiliResolveResultPacket` 9→11 字段）；服务端代理成功路径与本端回落路径都按 `liveRoomId>0`/`cid>0` 分流 attach（同屏同 cid/房间幂等），换集/停播/断线/取消解析成对 detach；回调一律切主线程、在途代次校验丢弃迟到结果；`danmakuTimeOffsetMs` 在入队侧应用。
  - 渲染（`ClientDanmakuStore` + 世界/HUD 双层）：Store 改双通道（即时项下一帧必出队 + 片内项二分插入保序 + 头指针，pollDue 不再整池扫描；`danmakuMaxEntries` 超限丢时间轴靠后者）；补齐颜色、字号百分比（B 站 18/25/36→72/100/144，**按条目各自缩放**避免整层跳变）、SCROLL/TOP/BOTTOM（顶/底各三槽驻留 4500ms，不受速度倍率影响）、描边；三类来源与三模式独立开关；ROOM_CHAT 特殊样式 = 金色 0xFFD700 + 「昵称：内容」+ 矩形文字框（B 站弹幕不画框）；全屏层改传**真实播放位置**（原先传 0 导致片内弹幕在全屏永不出现）；两层各自持 |Δ|>1500ms 跳变清屏守卫。
  - 文档与证据：`reference/plans/f11-bilibili-danmaku-api-facts.md`（708 行实测）+ `reference/plans/f11-samples/`（样本）+ `reference/plans/f11-verification-report.md`（独立验证）
- [x] **解析状态上报（播放前确认其他人是否解析完成）**: 客户端 ResolveStatusPacket(C→S) 上报 解析中/完成/失败 → 服务端校验组成员聚合进 BE ResolveStates NBT（瞬态：只由 getUpdateTag 下发、saveAdditional 不写＝不落盘；客户端经 loadAdditional 读取——26.1.2 同步包与磁盘共用该入口，markDirty 自动同步全组）→ GUI 观看者列表按人显示状态后缀；等待可视化三处共用 client/ResolveHint——GUI 预览区第三行+无信号替代、全屏 HUD 同两处、世界屏幕面居中文字；清零点=新集起播（PlaybackController 三入口+队列 playItemAt）/leaveOwn/退服/整屏停止
- [x] **F5 规则热重载**: /kazumi rule reload + RuleWatcher 目录监视（WatchService 防抖 800ms+内部写回声抑制 1.5s，ruleHotReload 配置默认开）；三路广播统一 RuleSyncBroadcast（空列表也广播清客户端缓存）；reloadLive 解析失败保留内存现状不动盘
- [x] **F10 房间弹幕互发（已实现）**: 聊天栏监听发送（ServerChatEvent 查组广播，无命令/无输入框/不依赖画面时间同步，连发不丢弃——冷却已废止 v3.3）+ DanmakuBroadcastPacket 单包 + ClientDanmakuStore 统一入口 + 世界/HUD 双层渲染（Z180 坐标系补偿）+ 双删组点清池；DebugVerifyItem 含快速校验套件。规划/协议文档见 reference/plans/f10-*

## 已完成的实用物品（README 功能清单）

- [x] **屏幕遥控器**: Shift+右键绑定屏幕（CUSTOM_DATA 持久化），手持右键远程打开播放器 GUI，Shift+右键空中解绑；服务端校验（强制加载区块）——屏幕被破坏提示「未找到屏幕方块」。公共骨架抽为 AbstractScreenRemoteItem（RemoteOpenPacket/OpenRemoteGuiPacket）
- [x] **远程观影器**: 与屏幕遥控器同机制，右键直接进入全屏观影（纯观看无操作）；RemoteFullscreenPacket/OpenRemoteFullscreenPacket；全屏进入来源标记——GUI 进入退出回 GUI、观影器直接进入退出回世界
- [x] **规则管理器**: 右键打开规则管理界面（RuleManagerScreen）——远程仓库规则列表（index.json 与本地合并）、拉取/删除规则、连通性延迟测试（测/测试全部）、弃用红色标记；经 GuiProtocol 通用通道（rule_list/pull/delete/test），服务端处理并入 GuiRequestHandlers；common 经 RuleManagerOpener 钩子打开客户端界面
- [x] **创造模式物品栏标签页**: CreativeTabRegistration 聚合全模组物品（图标=视频屏幕方块）

## 功能增强

> 本区仍有未完成项，上下文见 `TODO.md`。

- [x] **配置功能完善**: Cloth Config 配置界面（可选依赖，未装 Cloth 时仅无界面），保存后即时应用
- [x] **KazumiLog 分类开关**: 13 个分类独立 logger 名（含 audio），配置文件/配置界面控制 DEBUG 级别并即时生效
- [x] **XPath 规则解析修复**: 对齐 Kazumi Dart 的 `//` 相对容器语义（Jsoup/JAXP 从文档根解析导致 7sefun 等站点解析失败），兼容 `/text()` 后缀与空文本过滤
- [x] **通用视频嗅探**: 网络层请求拦截（对齐 Kazumi `shouldInterceptRequest`）+ iframe 全注入——覆盖解析站嵌套播放页，无需逐站适配
- [x] **嗅探增强**: 网络层识别带 query 参数的 m3u8 与视频扩展名直链（mp4/mkv/webm 等）；onLoadStart 提前注入 fetch/XHR hook；嗅探失败自动重试 2 次（Cloudflare 概率挑战兜底）
- [x] **纹理尺寸自适应**: 按 `player.width()/height()` 动态重建读回纹理，修复非 1080p 源画面错乱/重影
- [x] **屏幕最大尺寸提升**: `/kazumi screen create` 宽高上限 20 → 128
- [x] **规则更新命令**: `/kazumi rule update [name]` 更新已安装规则（不填则更新全部）
- [x] **已弃用规则提示**: deprecated 规则在安装/搜索/播放/测试/列表时黄色警告
- [x] **统一消息系统**: 所有聊天提示带绿色 `[KazumiPlayer]` 前缀，按严重程度着色（成功绿/信息白/警告黄/错误红），搜索结果顶部加金色分隔线
- [x] ~~**GUI 播放线路选择**: 首次实现引入回归已撤回~~ → 已重做实装（见项目重构区「播放线路切换」）：旧回归根因为 payload 结构变更叠加已废弃的 HTTP 直取路径；新实现基于 playback/source 架构与 road 字段，多线路站点实测无回归
- [x] **播放器式 GUI**: 右键屏幕打开主界面（搜索流/视频实时预览/选集/番剧简介/进度条拖动/控制栏/直链输入），搜源按钮跳过 bgm 直接搜规则源，搜索状态会话内持久化，isPauseScreen=false 不暂停单人世界
- [x] **GUI 通用网络通道**: GuiActionPacket/GuiDataPacket + GuiProtocol/GuiPayloads，新增 GUI 操作零包成本；服务端 GuiRequestHandlers 与聊天命令共用会话缓存和 resultId
- [x] **播放多级容错**: HTTP 直取播放页解析直链优先（java 路径与浏览器路径对源站可达性互补）→ MCEF 常驻浏览器嗅探（UA 伪装+Cookie 保留+接管式并发+取消入口）→ 重试归因提示；带签名 query 的直链去参后直判快速播放
- [x] **Cookie 桥接**: ~~收割 document.cookie 按域名存储，直取/规则请求成对附加~~ → 审计确认收割端从未接线（saveFromBrowser 零调用者）且伪装 UA 与现嗅探 UA 池不配对，桥接整体不可用；已随深度审计修复整体移除（BrowserCookieStore 删除，RuleRequestEnhancer 钩子保留空转），如需 CF 直取能力须重新设计收割端+UA 配对
- [x] **停止屏幕全量通知**: GUI 停止屏幕向全部观看者发 PlayStopPacket + 聊天提示（原 /kazumi screen stop 不通知观看者端）
- [x] **全屏观影模式**: GUI 预览右下角「全屏」按钮 → 关闭 GUI 由 HUD 层接管画面（RenderGuiLayerEvent.Post@HOTBAR，盖住准心/血条、聊天层浮于其上——T 键原生可用）。覆盖度 fullscreenCoverage(1-100)/不透明度 fullscreenOpacity(10-100) 可配，底部进度条+时间；右上角「退出」按钮点击返回世界（鼠标全程可见可点，InputEvent.MouseButton.Pre 拦截按钮命中点击不下发世界）
- [x] **GUI 搜源流式化**: 逐规则搜索、每完成一个源立即推送增量（DATA_RULE_RESULTS_PARTIAL + searchId 代次防串轮），慢源/超时源不再阻塞快源结果的展示；状态栏渐进显示「搜源中 X/Y 个源…（已得 N 条）」；单源失败计为一次完成（空增量）保证计数收敛，超时仍由 searchTimeoutMs 兜底。关闭界面/清空搜索发 ACTION_CANCEL_SEARCH、发起新搜索自动作废旧代次（服务端 per-player 活跃代次登记），迟到增量双重过滤（服务端拦截 + 客户端仅接受更大 searchId）
- [x] **直链队列**: 队列复用 EpisodeData 存合成 Road（name="直链队列"，util/DirectLinkQueue），自动下一集/上一集/下一集零改动在队列内推进、无新增 NBT 字段与 S→C 包（BE markDirty 自动同步）。顶栏单按钮：空闲"播放"、播放中"排队"追加；队列面板行=[名称点击切播][插=移到下一个][删]；与规则剧集互斥（剧集中提交拒绝，点选集播放覆盖队列）；queue_add/jump/move/remove 经 GuiProtocol 通用通道，GUI 与 /kazumi play-url 共用 QueueRequestHandlers。顺带修复：旧 handlePlayUrl 不写 episodeData 导致直链播完误切到残留剧集下一集
- [x] **命令同步 GUI 能力**: 新增 /kazumi queue 一级子树（list 分页展示▶当前项+行可点击切播 / add / jump / move / remove）与 /kazumi screen viewers 观看玩家列表（组优先 NBT 兜底，在线显名离线短UUID）；命令层纯参数解析全部委托 QueueRequestHandlers，与 GUI 共用服务端唯一实现
- [x] **队列已播项自动出队**: 切集推进（自动/手动/GUI next）经 PlaybackController.applyEpisodeSwitch、队列内切换（jump/playNow/失败跳过）经 QueueRequestHandlers.playItemAt，两落点均把目标项之前的已播前缀移出队列（列表自新当前项起重写、episodeIndex 归 1）；规则剧集数据不受影响，保留完整列表供 prev/选集回跳

## BUG

> 本区仍有未完成项，上下文见 `TODO.md`。

- [x] **命令路径删除最后一个规则不清客户端缓存**: `RuleCommands.broadcastRuleSync` 对空规则列表早退，而 `GuiRequestHandlers` 版本行为正确（空列表也广播以清空客户端 RuleSync 缓存）——已随 F5 修复：三路广播统一收敛到 `RuleSyncBroadcast.broadcast()`（命令/GUI/热重载共用，空列表也广播）
- [x] **暂停/恢复后播放时间异常增大**: 同步架构重写改 MonoClock 时漏了读取侧调用方——命令/GUI/包处理器共 10 处以墙钟 `System.currentTimeMillis()` 减 MonoClock 基准的 `serverTimestamp`，得到 epoch 级 elapsed 写入权威位置并立即广播 → 客户端漂移兜底硬 seek 到天文位置 → FFmpeg seek 失败管道死亡 → 误判 ended 自动切集；已全部改用 MonoClock.millis()（ServerPacketHandlers/GuiRequestHandlers/PlayCommands/ScreenCommands），客户端硬 resync 增加"目标超出媒体时长即拒绝对随"防御
- [x] **手动切集后暂停卡死/时间调整无效（偶发，重新 join 才恢复）**: `handleEpisodeSwitch`（GUI/命令的手动 next/prev）只写 BE NBT 未重置权威同步组——周期广播仍携带旧集 URL → 客户端换片保护 `switchingEpisode` 恒为 true，吞掉之后所有暂停/seek 应用；切集前若处于暂停态则新集开局即暂停且无法恢复。已补 `onPlayStart` 重置组（对齐 auto-next 路径）。附带发现：上轮时钟修复在 PlayCommands 漏了 4 处墙钟混算（验证脚本 `-Path 'src\**\*.java'` 不支持 PowerShell 递归导致假阴性），已改用 Get-ChildItem -Recurse 复查清零
- [x] **拆屏/退主菜单后音频偶发继续播放（孤儿播放器）**: 异步解析完成的起播回调仅有 `screen.isRemoved()` 一个守卫——退主菜单时 `stopAllActive` 清空注册表但 BE 实例未 removed，play 照常执行产生无人引用的孤儿播放器；拆屏时 PlayStopPacket 先 stop、解析回调后 play 的队列竞态同样复活播放器。修复：beginPlayback 捕获 ScreenPlayer 会话引用，起播前三重校验（level==null / isRemoved / session.player 身份不符即弃播并回收）；stopAllActive 补 cancelAllResolves；handlePlayStop 由全局取消改按屏取消（消除多屏互扰）
- [x] **快速连续切集后音频泄漏（第二类孤儿播放器）**: `WaterMediaPlayer.play()` 内部的 MRL 加载是二段异步（独立线程轮询 + mc.execute 二段投递创建 FFMediaPlayer），会话守卫只护住了「解析完成→play()」段；若 stop 恰落在「play() 已调、MRL 加载中」窗口（player 字段尚为 null），stop 打在空气上，加载线程随后照常 createAndStart 产生孤儿。修复：WaterMediaPlayer 加 volatile closed 标志，MRL 线程在每次重试与创建前检查，stop() 置位即放弃起播（createAndStart 与 stop 同在主线程队列，检查无竞态）。附带修复：切集后假漂移——组时钟从切换瞬间起算而播放器要经解析+缓冲才出声，everPlayed=false 启动期不做漂移硬校正（此前会把新集 seek 到错误位置如 +11.6s）
- [x] **切集后假漂移连环打断（体感"播放很慢"）**: 组时钟从切换意图时刻起算流逝，而播放器要经解析+缓冲晚 N 秒出声，出画后漂移依旧巨大 → 反复 Hard resync → seek 清空缓冲 → Starvation 更久 → 恶性循环（实测 ep1 被 seek 到 +15.9s、重试的 ep3 撞上 87s/100s 假漂移）。根治：首帧锚定机制——新增 PositionReportPacket(C→S)，客户端首次实际出画时上报真实位置；SyncGroup.anchorEstablished 锚定前组时钟冻结（livePositionMillis/sendSyncState 双处），服务端仅接受每次切集后的第一次上报并立即广播对齐全组
- [x] **鼠标脱离准心**: 网页源嗅探/播放后鼠标指针脱离准心——GLFW 真实光标检测 + 嗅探完成立即强制抓回，已实机确认
- [x] **视频开头闪烁**: 播放器启动期被同步 seek 反复打断 + 无帧时填充占位色——保留上一帧 + 启动稳定期跳过漂移 seek
- [x] **配置重启重置**: Cloth 保存只改内存不写盘——保存时显式 `ModConfigSpec.save()` 持久化
- [x] **退出游戏音频残留**: `stopAllActive` 先删条目后 stopAll 导致播放器从未停止——`remove()` 内部先 stop + `ServerStoppedEvent` 兜底，已实机确认
- [x] **屏幕面被视锥剔除**: BE 包围盒默认仅方块本体，覆盖 `getRenderBoundingBox` 包含屏幕面，已实机确认
- [x] **同步 seek 反复重缓冲**: 漂移校正加 seek 后冷却 + 播放器时间接近目标时跳过重复 seek（防御性修复，待复现验证）
- [x] **search-rule 翻页失效**: 翻页命令硬编码"翻页"关键词导致"未找到结果"——新增 `RuleSearchSessionCache` 会话缓存，翻页从缓存读
- [x] **待机组误判播放中**: 屏幕未播放时 join 创建待机组，重复 join 走"加入现有组"分支写入空 URL/递增位置——按 `url.isEmpty()` 分支处理
- [x] **空 URL 标记播放中**: `setPlayback` 无条件设 `PLAYING`——空 URL 时保持 `IDLE` 并清空位置

## 后续大功能

- [x] ~~**MCEF 浏览器生命周期管理**: mcefLifecycle 配置项仍未接入~~ → 常驻共享浏览器策略已由 playback/source 包的 McefVideoSourceService 实现（等效 PERSISTENT：仅创建一次、间隙导航 about:blank），MCEFBrowserLifecycle 占位类与死配置 mcefLifecycle 已随旧链路删除
- [x] **弹幕支持**: 从 Kazumi App 移植弹幕渲染——已实装：房间互发（F10）+ B 站视频时间轴与直播实时（第四批），三层来源共池混合渲染
- [x] **画质选择**: WaterMedia 多 quality 选择——已实装：B 站按解析档位切换（视频 DASH 1080P/1080P60 与直播，走「重新解析 + 双播放器交接」，仅本端生效、切完续播）；WaterMedia 原生 variants 受 `MediaQuality.of(w,h)` 分档冲突限制未采用

## 项目重构

> 本区仍有未完成项，上下文见 `TODO.md`。

- [x] **GUI 三列布局与信息增强**: 显示区（预览+选集）宽度收窄，最右列新增队列（直链排队占位，未来实装）与观看玩家列表（WatchingPlayers UUID 经客户端 TabList 解析名字，每秒刷新）；左下状态区两行——「正在播放: 番剧 · 集 · 线路」（BE 新增 PlayingTitle 字段持久化，直链为空不显示）+ 醒目操作提示条（黑底黄字亮条，6s 自动隐藏）
- [x] **切集体验修复**: 进度条 seek 提交后 5s 反向同步豁免窗口（修复点击/拖动回弹——FFmpeg 跳转 HLS 重缓冲数秒）；无播放器时滑块清零（修复切集残留上一集位置）；换片 ≤1s 窗口内禁用兜底 seek 与暂停应用
- [x] **自动下一集修复**: 启动时 endedNotified=true 防加载期误触发，但从未清除导致自然播完永不触发 NextEpisodePacket——改为 player.isPlaying() 后解除保护，另加时长逼近 duration-300ms 兜底（live 型 HLS 无 EOF）
- [x] **GUI 操作通知补齐**: GUI/包路径的 seek/切集/暂停/选集播放/直链播放经 notifyOtherWatchers 通知其他观看者（此前仅聊天命令路径有通知），文案与命令路径一致
- [x] **播放线路切换**: GUI 右下新增线路下拉菜单（与选集/简介平级，ChaptersPayload 下发全部线路名），切线保持集数序号重新拉列表；play_episode/query_chapters 协议携带 road 字段；BE NBT 新增 RoadIndex，自动下一集/上一集在当前线路内切换；命令 /kazumi play 增加 [road] 可选参数
- [x] **服务端/客户端拆分**: 拆分为 `common`/`client`/`server` 三个 source set，构建输出 `kazumiplayer-server`/`kazumiplayer-client` 两份 jar。客户端类（WaterMedia/MCEF）不再出现在服务端 jar 中
- [x] **统一消息系统重构**: 新增 `KazumiMessages` 统一前缀与分级颜色，替换命令层/客户端全部散落消息（含 sendFailure/sendSystemMessage/客户端 chat），删除 ChatComponentUtil 未使用的 info/error 死代码路径
- [x] **网络包分派注册表化**: Server/ClientPacketHandlers 的 if-else instanceof 链改为 `Map<Class<?>, Handler>` 静态注册表，新增操作只需 static 块加一行
- [x] **重复逻辑抽取**: `JsonUtil.parseFirstRoad`（Road 解析）、`SyncGroup.watchingPlayersString()`（观看者序列化）、`KazumiMessages.formatMs`（时间格式化）、`ScreenCommands` 复用 `getTargetScreen`
- [x] **seek 权威状态同步**: handlePlaybackControl 的 seek 现经 `applySeek` 同步调用 `updateState` 更新 SyncGroupManager 权威位置并立即广播——修复 GUI 进度条拖动后被周期广播拉回的问题
- [x] **嗅探架构重写（对齐 Kazumi App）**: 新增 `playback/source` 包忠实移植 Kazumi video_source 架构（IVideoSourceService/McefVideoSourceService/McefSniffBrowser/SniffScripts/租约池/类型化异常/ResolveRequest 身份式取消），tick 播放入口切换至 `VideoSourceResolver.beginPlayback`，`maxConcurrentSniffs` 死配置由租约池启用；实测通过后旧链路已整体删除（PlaybackManager/VideoSniffer/MCEFBrowserLifecycle/dto/VideoSource/PlayStartPacket 死路径），BrowserCookieStore 保留供规则引擎 Cookie 桥接（UA 常量已内联）。测试状态：sorani 全流程通过，7sefun 超时待查（见 BUG 区）。遗留：规则的 `useLegacyParser` 字段→客户端嗅探调用接线（需经 NBT/包协议下发）
- [x] **同步架构重写（时钟同步 + 事件式对齐）**: 参考 AllMusic/MoeMusic 的机制思路（仅借鉴设计，实现为原创代码）——新增 MonoClock 单调毫秒源与 TimeSync/TimeSyncResponse 握手包，ClientClockSync 登录+每 30 秒按四时间戳中值法计算两端钟差；SyncStatePacket 时间戳改为服务器单调锚点，客户端锚点插值目标位置；取消周期性漂移 seek（墙钟偏差曾导致联机时每个广播周期硬 seek 一次的"重复同步"卡顿），仅漂移 >10s 兜底硬 seek。遗留：offset 无多次采样滤波（可取最小 RTT 样本）；切歌/seek 事件仍靠 5s 周期广播收敛（可改事件驱动）
- [x] **规则引擎对齐排查修复**: 对照 Kazumi lib/services/plugin 逐文件排查——修复 POST body 发不出去（usePost 规则搜索必挂）、API 模板变量不做 URL 编码（中文关键词破坏请求 URL）、空 JSONPath 把整棵子树当名称（roadNamePath/episodeNamePath 为空的规则名变 JSON 串）、URL 归一化放行 javascript: 等 opaque URI 且未编码 href 直接失败（404 症状）、antiCrawlerConfig 未解析（验证页被静默解析为空列表）。遗留见下两条
- [x] **RuleManager 重复实例**: `SyncGroupManager.onPlayerJoin` 曾每次玩家进服 `new RuleManager(...).loadAll()` 全量重读磁盘且产生第二实例——已改为 `SyncGroupManager.init(ruleManager)` 注入 KazumiPlayerServer 初始化的单例，进服广播直接读单例
- [x] **ClientDisconnectHandler 拆分**: 播放调度（原 onClientTick 约 130 行六段状态机）+ 生命周期清理已拆至 `ClientPlaybackScheduler`；顺带删除冗余的 `activeScreens` 双重跟踪结构（与 ScreenPlayerManager 表达同一事实、靠每秒对账维持，其孤儿清理段被 reconcileStaleEntries 的 screenGone 条件完全覆盖），渲染 BE 由两次遍历合并为一次，僵尸阈值移出循环，主菜单空转加守卫
- [x] **播放器启动逻辑去重**: handlePlayStart 死路径（PlayStartPacket 包+handler+NetworkManager 注册项）已随旧嗅探链路删除；tick 路径走 playback/source 包
- [x] **VideoSniffer handler 泄漏**: 旧 VideoSniffer 已随嗅探架构重写删除，问题不复存在
- [x] ~~**三份 dev mods.toml 去重**~~ → 已评估不做：实际存在 5 份（resources×3 + templates×2），templates 已是正式产物的占位符展开机制（单一来源）；dev resources 副本必须三处一致否则 JPMS split package 报 ResolutionException（文件头注释已说明约束）。若未来收敛，仅剩"dev 副本也由模板生成"一条路，锦上添花而已
- [x] **PlaybackControlPacket action 改枚举**: 新增 `PlaybackAction` enum（NEXT/PREV/PAUSE/RESUME/SEEK_FORWARD/SEEK_BACK/SEEK_GOTO），自定义 STREAM_CODEC 保持小写 snake_case wire 格式、非法值抛 DecoderException；packet/handler/GUI 发送端全部改用枚举
- [x] ~~**RuleEngine 策略接口**~~ → 已评估不做：实测仅 88 行、2 个方法、各 1 个 if 分支，抽接口不会更简单（过度设计）
