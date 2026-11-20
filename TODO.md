# TODO

> 只保留未完成项（`[ ]` 待办 / `[~]` 部分完成）与仍在生效的上下文说明（诊断结论、已评估不立项的原因等）。
> 已完成项统一归档在 [`DONE.md`](DONE.md)，保留原文、实测证据与结论。

## 前置 Mod 跟进（2026-10-06）

### Rinku 3.0.4：能捡的便宜与两个新坑

> 对照 Rinku 3.0.4（`rinku-neoforge:3.0.4-26.1.2`）sources jar 逐项核过。v3 变更里对我们有直接价值的有 4 项：①`--disable-web-security` 默认开启（legacy 模式往跨域 iframe 注入脚本不再被同源策略挡）——**升级后已自动生效**；②预加载浏览器池（默认透明/不透明各 1，`createBrowser` 直接取预热实例，省掉浏览器冷启动）——**已自动生效**；③Chromium 151（站点 JS/编解码兼容性整体上移）；④逐浏览器 `setAudioMuted` / `setWindowlessFrameRate`——**尚未接线**。仍未解决：`jcef_helper` 僵尸进程（IDE 强杀不跑 shutdown hook）、B 站直链依旧只能走 API 绕过。

- [ ] **嗅探浏览器静音（`setAudioMuted(true)`）**【S】: Rinku 硬编码 `--autoplay-policy=no-user-gesture-required`（`CefUtil.java:105`），而我们嗅探的就是播放页 → 页面 `<video>` 会自动播放；CEF 音频走系统声卡（不经 MC 的 OpenAL），于是解析期可能冒出网站声音，且同一视频被 CEF 与 WaterMedia **各解码一遍**。做法：`Rinku.createBrowser` 之后调 `browser.setAudioMuted(true)`（`org/cef/browser/CefBrowser_N.java:1336`，OSR 可用）。
- [ ] **`onRenderProcessTerminated` 空实现收尾**【S】: 现为空方法（`RinkuSniffBrowser.java:257-259`），渲染进程崩溃后只能白等到解析超时。改为 `parsedFuture.completeExceptionally(...)` + 重建浏览器（v3 的 4 参签名已带 errorCode/errorString）。
- [ ] **UA 诊断：JS 侧 UA 的 Chrome 记号被换成了 `Rinku/2`**【S】: `CefUtil.java:136-142` 在无自定义 UA 时设 `user_agent_product = "Rinku/2"`，而按 `CefSettings.java:159-165` 该字段**替换 UA 的产品记号**（即 `Chrome/151.x` 的位置）→ `navigator.userAgent` 缺 Chrome 版本号，站点播放器却常靠 UA 判浏览器能力；同时我们在资源层把 HTTP 头改写成 `UserAgents` 池的随机 Chrome UA（`RinkuSniffBrowser.java:232-237`）→ 两侧不一致且可被侦测。先加一行诊断（注入一段打印 `navigator.userAgent` 的脚本，走已有 `KAZUMI_LOG:` console 桥）确认实际值；确认后再定是否在 Rinku 初始化前 `Rinku.getSettings().setUserAgent(...)`（代价：写 Rinku 全局配置、需重启游戏、影响其他用 Rinku 的 mod、且不能再每实例随机 UA）。
- [ ] **`setWindowVisibility(false)` 省电实测**【S-M】: 注释明说 windowless 浏览器隐藏后**停止渲染与 paint 回调**（`org/cef/browser/CefBrowser.java:432-438`），配合静音通常还能让 Chromium 挂起后台视频解码 → 收益最大。风险：页面 visibility 转 hidden 会节流站点自身定时器（hls.js 类分片加载可能变慢）。做法：加开关，实测命中率与解析耗时后再定默认值。

### WaterMedia 3.0.2：影响评估（2026-11-21 升级，对照 3.0.0.23 反编译签名逐项核过）

现状：`build.gradle` 钉 **API `maven.modrinth:G922NeHS:3.0.2`** + **binaries `maven.modrinth:4997XcoK:3.0.2`**，两者互为 required 依赖（binaries 3.0.2 要求 API `[3.0.1],[3.0.2,3.1)`）。升级动因：旧钉版 `FdCZ5Rxq`（3.0.0.23）与 `fYWsOuBz`（binaries 3.0.0.6）**已从 Modrinth Maven 下架**（404，CDN 文件仍在但 Maven 端点没了）→ 构建直接失败；且 3.0.1 起 API 包结构大改，**不是 Z 级兼容升级**（wiki 的「Z 升级绝不破坏」承诺与实际字节码不符）。完整破坏性变更清单见 `CLAUDE.md`「WaterMedia v3 关键」；参考源码 `reference/watermedia-src/302`、官方中文 wiki `reference/watermedia-wiki/zh-CN`。

- [x] **迁移到 3.0.2 API**【M】（2026-11-21 完成）: `MediaAPI`→`PlayerAPI`、`api.media.*`→`api.player.*`、`IPlatform`→`Platform`（`getData`→`resolve`，`PlatformData`→`Platform.Resolution`）、`DataSource`→`Platform.Source`、`DataQuality`→`Platform.Quality`、`Slave`→`Track`、`RequestHeaders`→`HttpHeaders`、`WaterMediaConfig.media.ffmpeg`→`codecs.ffmpeg`、`cacheMaxSize`→`cacheMaxResponse`、`liveSource()`→`live()`、`MRL.uri` 字段→`uri()`。同时按 wiki「AI 智能体」硬性要求补齐返回值检查：`start()` 被拒即当起播失败并释放引擎、`pause()/resume()/seek()` 被拒时不再误通知监听器（音响会跟着"暂停"一个仍在出声的屏幕）、`applyPendingSeek` 被拒时保留意图下 tick 重试（否则同步锚点永久错位）、构造/起播抛异常时释放已创建的引擎（防原生资源泄漏）、`SpeakerClientAudio` 补 `getPlayer()` 判空。产物校验：`gradlew clean build` BUILD SUCCESSFUL，jar 内 `mods.toml` 依赖范围已改 `[3.0.2,)`。
- [ ] **机会（暂缓，需单独立项）：用内置同步子系统替换自研同步层**【L】: 3.0.2 把联机同步做进了 `MediaPlayer`——`sync(PlayerState)`/`sync(ByteBuffer)`（27 字节大端快照，可承载任意字节通道，能挂在我们现有包上）、`following()/authority()/authorityTime()/drift()/tolerance(ms)`、`PlayerAPI.serverPlayer()`（`ServerMediaPlayer` 权威时钟：`syncDuration`/`revision()`/`snapshot()`，无真实媒体）、revision 单调计数与乱序丢弃、ENDED 态 seek 落 PAUSED、跟随者每 50ms 校正（`seekQuick` 限频、加载/缓冲时跳过、重复播放走短路径）。它与我们的 `SyncGroupManager` + `SyncStatePacket` + `ClientClockSync` + 漂移兜底属同一层能力，收益：①跟随者自动校正省掉自研漂移数学；②控制请求走上行、两侧 API 对称；③快照格式固定 27 字节，比我们的 JSON 包更省。风险：权威侧只有 `ServerMediaPlayer`（无真实媒体）与真实媒体播放器两类，与「每屏一个权威 + 每人本地播放器跟随」的映射需实测；**音量/静音/缩放/空间音频恒为本地生效**（正好符合我们每屏独立音量，但 `sync` 的 VOLUME 能力若启用会覆盖 → 不启用）；`sync()` 解码发生在任意线程、内部 50ms ticker；还要与「线路/清晰度双播放器交接」「直链队列」重新对齐。结论：先记录，等其他批次稳定后再评估。
- [ ] **站点白/黑名单（3.0.2 新增）**【S】: `WaterMediaConfig.player.whitelist`/`blacklist`（`enable`/`sites`/`trustLocal`），默认全关。我们自己的嗅探链路不走 WaterMedia 平台层，但**直链播放会经过** `MediaPlayer.checkSite`——若用户开了白名单而没把 B 站/CDN 域名列进去，直链会被判 `BLOCKED`。做法：评估是否需要在配置界面暴露这两个开关，或在文档里说明「开了白名单要自己加域名」。
- [ ] **`NetworkAPI.serverEnabled()`（3.0.2 新增）**【S】: 文件服务器是否就绪的即时查询（本机立刻返回，远端等 `remoteUrl` 应答）。我们没用文件服务器，仅记录。
- [ ] **错误码 `(wm_err:NNNN)`（3.0.2 新增）**【S】: 每条异常消息与错误日志都带 `WaterMedia.ERR_*` 常量码，官方 Upload Logs 会列出找到的码。做法：排查用户日志时按码定位比按文本快，可考虑在 `KazumiLog` 侧做一次提取。

## B 站解析健壮性（2026-10-06 生产日志核对后登记）

- [ ] **未开播直播间把 JsonNull 当对象强转，异常名直接甩给用户**【S】: `BilibiliApi:430` 的 `data.has("playurl_info") ? data.getAsJsonObject("playurl_info")…` —— Gson 的 `has()` 对 JSON null 也返回 true，未开播/无流时返回 `"playurl_info": null` 即抛 `ClassCastException: com.google.gson.JsonNull cannot be cast to class com.google.gson.JsonObject`；`dataOf` 的 `root.getAsJsonObject("data")`（其后那句 `data == null` 检查永远走不到）与 `dash`/`wbi_img` 两处同写法。生产日志实测：房间 1921300321 未开播期间连续 10 次（17:59–18:10）服务端代理与本端回落双双抛同一 CCE，聊天栏显示整串 Java 异常名，且僵尸回收每 75 秒重试一次。做法：加 null 安全的取值器（缺字段与 JSON null 一律当缺席），未开播归为可读原因「直播间未开播或未返回播放流」。
- [ ] **DASH 有效期减 10 分钟余量可能落到过去**【S】: `VideoSourceResolver.expiresAtOf` 返回 `deadline - 600`，若 CDN 给的 deadline 距现在不足 10 分钟就会早于当前时刻，而 `KazumiBiliPlatform.getData` 对「已过期」是**直接抛异常拒绝服务**（不是触发重新解析）→ 表现为「视频加载失败：Bili DASH link expired at …」。做法：扣完余量仍早于「现在 + 最小宽限」时按未知有效期处理（传 null，让 FFmpeg 自己撞 403 走既有失败路径），或改为发起一次重新解析。
- [ ] **服务端代理解析在「请求者不在组里」时静默丢弃**【S】: `ServerPacketHandlers.handleBilibiliResolve` 的三查都在日志行之前，组校验失败即 return，客户端只能干等 20 秒超时才回落（dev 日志 01:07:27 实例：请求发出后服务端一行日志都没有）。做法：组校验失败记一条 WARN 并回一个明确失败，让客户端立刻走本端回落。
- [ ] **失败跳跃的 3 秒冷却为全局值**【S】: `ClientPlaybackScheduler.lastSkipSentAt` 跨屏共享，多块屏同时失败时后到的那次跳过请求会被静默丢弃（该屏只能等 60s 冷却到期）。做法：改为按屏的 Map。

## 待裁决（拍板后才动）

- [ ] **扫码登录成功后立即同步屏蔽词**: `BilibiliLoginScreen` 登录成功处加一行拉取 `x/dm/filter/user`（一行改动，取舍在「登录即联网」的体验）【S】
- [ ] **屏蔽用户 uid 规则**: B 站账号屏蔽词实测 429 条里 342 条是 type=2 用户规则（按 uid 屏蔽发送者），当前整体跳过【M】
- [ ] **高级弹幕完整还原**: 现为降级滚动或直接跳过（mode 7/8 跳过、9 降级），完整还原需实现 B 站高级弹幕脚本语义【L】

## 体验与功能待办（2026-08-24 使用体验分析筛选，任务看板同名卡片）

### GUI 体验
- [ ] **B2 等待与失败可视化包**: 起播阶段文案（解析中→加载流→缓冲）+等待秒数+取消按钮；连续失败冷却+可点击「重试/换来源」；错误文案 i18n【M】——已落地：**八档起播阶段 + 已等秒数 + 量化细节（第 N/M 次尝试 · 档位 · 直播或点播 · 位置 · 取址轮询次数 · 失败原因）**、组内进度「已出画 x/y · 加载中 n · 失败 m」、观看者列表四态后缀、**起播看门狗与 60s 失败冷却（阶段行显示倒计时）**、取消按钮（可见期＝尚未出画）、失败横幅 20s 窗口、错误文案 i18n；剩余：可点击的「重试/换来源」入口（当前重新加入同步/重新提交直链会清掉冷却并立刻重试，但界面里没有专门的按钮）
- [ ] **B7 破坏性操作防误触**: 停止二次确认/撤销窗、队列删格首次点变"确认？"【S】
- [ ] **B16 bgm 搜索结果列表可滚动**（替代翻页方案）【S-M】

### 功能新增
- [ ] **F2 倍速播放**: 单机直链先行（WaterMedia speed 待验证）；联机需 SyncGroup 加 speed 字段防循环硬 seek【M-L】
- [ ] **F4 每周放送时间表**: bgm calendar 免鉴权 API+TimedCache；新增"放送时刻表"物品右键打开星期分栏 GUI【S-M】
- [ ] **F10-R2 dandanplay 片内弹幕（搁置 backlog）**: 三步对接设计完毕（room-protocol v3.3 §10 + render-design v1.7），TIMELINE 时间轴通道留接口可复用现成 Store/渲染管线；阻塞项=dandanplay 开放平台凭据申请（实测匿名裸调三端点全 403，见 plans/f10-dandanplay-auth-check.md），凭据到位后解冻

### 其他
- [ ] **合成配方添加**: 已完成 4 个——视频屏幕 / 远程观影器 / 屏幕遥控器 / 规则管理器（`data/kazumiplayer/recipe/*.json`，配方表见看板卡片）；剩余：**音响**、**连接工具**（音响配方与挂起区条目合并处理）

### 已评估不立项（含原因备查）
B6 多人误触观感风险｜B9 规则/线路数量通常不多｜B11 失败有报错即可｜B12/B17/F7/F8 需更多信息｜F1/F3 用处不大且 GUI 无位置｜F6 当前嗅探问题少｜F9 无用

## 音响系统（⛔ 整体挂起——先决条件：前置 mod WaterMedia v3 完善 audio 能力（video/audio 独立开关）；就绪前不动此区）

- [ ] **音响发声**: WaterMedia v3 当前 `PlayerAPI.player(mrl, gfx, sfx)` 不支持 video/audio 独立开关（3.0.2 仍未提供；`MediaPlayer` 有 `video()`/`audio()` 只读查询与 `sinks()/addSink()` 多输出端，但引擎 supplier 决定一切）。`SpeakerBlockEntity.startAudio()` 为占位
- [ ] **连接时屏幕静音**: 有音响连接时屏幕 `player.mute(true)`，断开最后一个音响后恢复
- [ ] **漂移校正测试**: `SpeakerBlockEntity.clientTick()` 中的周期校正逻辑未经实测
- [ ] **音响自定义纹理**: 当前用 `minecraft:block/note_block` 占位
- [ ] **连接工具空手右键音响断开**: 当前只实现了连接，断开需挖掉音响或屏幕
- [ ] **音响方块合成配方**

## 功能增强

- [ ] **渲染性能优化**: 多屏幕同时播放时帧率优化——每屏每帧一次全量 GPU→CPU→GPU 往返（`VideoScreenTexture.updateFrame`：`glGetTexImage` 读回 `NativeImage` 再 `DynamicTexture.upload`，1080p 即 8 MB×2），且读回会强制同步等待 GPU，是最大成本项；免读回需要把 WaterMedia 的 GL 纹理包成 blaze3d `GpuTexture`（26.1 未暴露"接管既有纹理 id"的入口，需评估）

## BUG

- [ ] **多屏幕同时播放**: 未充分测试多屏幕同时播放的稳定性
- [ ] **音响重复连接**: 同一音响重复连接同一屏幕——目前无去重提示
- [ ] **多人同时开 GUI 的状态陈旧**: 多人共享一块屏幕时，GUI 底部状态栏文字只反映本客户端触发过的操作，他人换集/暂停不会更新文字（预览画面/时间/暂停状态实时，数据层无冲突，最后操作者赢为预期语义）——可改进：播放标题写入 BE NBT 随同步广播，或从 Road JSON+EpisodeIndex 推断当前集名；如需防陌生人乱控可加"观看者/OP 可控"权限
- [ ] **嗅探健壮性收尾**: 已落地——停止/破坏屏幕取消在途嗅探（cancelActiveSniff）、带签名 query 的直链直判快速播放、接管式并发取代在途任务。剩余：接管/取消路径需实机回归；Cookie 桥接的 cf_clearance 时效与刷新策略未验证
- [~] **Cloudflare 挑战页卡死嗅探 (dmbus/DM84)**: 已落地——常驻共享浏览器（Cookie 跨嗅探保留）、UA 伪装（HTTP头+JS侧+mcef.properties 异常值强制重写，含空闲期）、HTTP 直取播放页解析直链优先（java 路径与浏览器路径可达性不同，互补）、Cookie 桥接（收割 document.cookie 按域名存储，直取/规则请求成对附加 Cookie+伪装 UA——clearance cookie 与签发 UA 绑定）、失败提示区分 CF 拦截、章节/直取请求补 Referer。**实测结论：dmbus 源站分钟级闪断(522)为站点侧问题，与客户端无关**。剩余候选：规则 antiCrawler 配置（验证页检测+自动点击+自定义 JS）

## 项目重构

- [ ] **权限系统**: GUI/命令操作分级（OP / 屏幕创建者 / 观看者）——停止屏幕、屏幕属性修改（朝向/大小/偏移）、队列管理等当前全员开放，联机信任场景可用；引入创建者归属后可细化【P3】
- [ ] **API 章节 delimited 格式**: ApiRuleStrategy 仅实现 nested，delimited（分隔符聚合格式）未实现——最新社区规则暂无使用【按需：出现首个依赖规则时再补】
- [ ] **反爬 captcha webview 全流程**: 已做 text/regex 验证页检测报错；Kazumi 完整能力含 xpath 检测、captchaImage/Input/Button 定位、captchaScript JS 注入自动验证（WebView 加载+Cookie 保存+重试），需 MCEF 配合【按需：出现实际站点需求再立项】
- [ ] **API 模式 POST body 模板**: Rule.ApiRequestConfig 缺 body 字段（json/form 模板 + @var 渲染），带 body 的 API 规则无法正确发请求【按需：同上】
- [~] **PlayStateListener 死代码（改判：决策项而非清理项）**: 已确认 `addListener` 全项目零调用点、listener 从未注册；但 `SpeakerClientAudio implements PlayStateListener`（音响跟随骨架）——直接删会连带删掉音响跟随设计。处置随音响区一同挂起【挂起·待 WaterMedia v3 audio】
- [ ] **build.gradle configurations.all 篡改**: 全局强制 Usage=JAVA_RUNTIME 破坏变体感知解析（当年为修 moddev universalJar 变体冲突所加）——构建目前可用则不动，待真撞上变体解析报错时带复现排查，改为 configuration 级 attributes【观望】
