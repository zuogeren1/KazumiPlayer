# TODO

## 前置 Mod 跟进（2026-10-06）

### Rinku 3.0.4：能捡的便宜与两个新坑

> 对照 Rinku 3.0.4（`rinku-neoforge:3.0.4-26.1.2`）sources jar 逐项核过。v3 变更里对我们有直接价值的有 4 项：①`--disable-web-security` 默认开启（legacy 模式往跨域 iframe 注入脚本不再被同源策略挡）——**升级后已自动生效**；②预加载浏览器池（默认透明/不透明各 1，`createBrowser` 直接取预热实例，省掉浏览器冷启动）——**已自动生效**；③Chromium 151（站点 JS/编解码兼容性整体上移）；④逐浏览器 `setAudioMuted` / `setWindowlessFrameRate`——**尚未接线**。仍未解决：`jcef_helper` 僵尸进程（IDE 强杀不跑 shutdown hook）、B 站直链依旧只能走 API 绕过。

- [ ] **嗅探浏览器静音（`setAudioMuted(true)`）**【S】: Rinku 硬编码 `--autoplay-policy=no-user-gesture-required`（`CefUtil.java:105`），而我们嗅探的就是播放页 → 页面 `<video>` 会自动播放；CEF 音频走系统声卡（不经 MC 的 OpenAL），于是解析期可能冒出网站声音，且同一视频被 CEF 与 WaterMedia **各解码一遍**。做法：`Rinku.createBrowser` 之后调 `browser.setAudioMuted(true)`（`org/cef/browser/CefBrowser_N.java:1336`，OSR 可用）。
- [x] **嗅探浏览器降帧（`setWindowlessFrameRate(30)` 定值）**【S】: 嗅探浏览器**从不显示**（全项目无任何 `getTextureIdentifier`/纹理绘制点），但 OSR 每帧都走 `RinkuBrowser.onPaint`：非渲染线程路径会把整块 dirty buffer `memAlloc+memcpy` 塞进 mailbox（`MAX_PENDING_PAINT_STREAMS=2`）再上传纹理 → v3 默认 60fps（MCEF 时代 30）× 最多 5 个 worker = 每秒最多 300 次「没人看」的拷贝+上传。做法：创建后调 `setWindowlessFrameRate(n)`（`CefBrowser_N.java:1318`，**小于 1 抛 `IllegalArgumentException`**；`CefBrowserSettings` 里的 0 只在创建期表示退回 30fps）。定值 30、暂不做配置项（`RinkuSniffBrowser.SNIFF_FRAME_RATE`）：嗅探的一秒轮询是 Java 侧 `executeJavaScript`，与绘制无关，降帧不影响命中率。
- [ ] **`onRenderProcessTerminated` 空实现收尾**【S】: 现为空方法（`RinkuSniffBrowser.java:257-259`），渲染进程崩溃后只能白等到解析超时。改为 `parsedFuture.completeExceptionally(...)` + 重建浏览器（v3 的 4 参签名已带 errorCode/errorString）。
- [ ] **UA 诊断：JS 侧 UA 的 Chrome 记号被换成了 `Rinku/2`**【S】: `CefUtil.java:136-142` 在无自定义 UA 时设 `user_agent_product = "Rinku/2"`，而按 `CefSettings.java:159-165` 该字段**替换 UA 的产品记号**（即 `Chrome/151.x` 的位置）→ `navigator.userAgent` 缺 Chrome 版本号，站点播放器却常靠 UA 判浏览器能力；同时我们在资源层把 HTTP 头改写成 `UserAgents` 池的随机 Chrome UA（`RinkuSniffBrowser.java:232-237`）→ 两侧不一致且可被侦测。先加一行诊断（注入一段打印 `navigator.userAgent` 的脚本，走已有 `KAZUMI_LOG:` console 桥）确认实际值；确认后再定是否在 Rinku 初始化前 `Rinku.getSettings().setUserAgent(...)`（代价：写 Rinku 全局配置、需重启游戏、影响其他用 Rinku 的 mod、且不能再每实例随机 UA）。
- [ ] **`setWindowVisibility(false)` 省电实测**【S-M】: 注释明说 windowless 浏览器隐藏后**停止渲染与 paint 回调**（`org/cef/browser/CefBrowser.java:432-438`），配合静音通常还能让 Chromium 挂起后台视频解码 → 收益最大。风险：页面 visibility 转 hidden 会节流站点自身定时器（hls.js 类分片加载可能变慢）。做法：加开关，实测命中率与解析耗时后再定默认值。

### WaterMedia 3.0.0.23：影响评估（对照 3.0.0.22 反编译签名逐项核过）

现状（2026-10-06 已升级）：`build.gradle` 钉 **API `FdCZ5Rxq`（= 3.0.0.23）** + **binaries `fYWsOuBz`（= 3.0.0.6）**，两者互为 required 依赖且同支持 MC 26.1.2。升级动因：`start()`/`startPaused()` 由 `void` 改 `boolean` 属二进制不兼容（编译于 .22 的字节码在 .23 上会 `NoSuchMethodError`）；binaries 3.0.0.6 的 changelog 为「Fixed jar crashes the game asking for multirelease folder」。

- [x] **升 API 3.0.0.23 并重新编译**【S】: `start()` / `startPaused()` 由 `void` 改 `boolean`（`MediaPlayer`：`public abstract void start()` → `public boolean start()`），**二进制不兼容**——编译于 .22 的字节码引用 `MediaPlayer.start()V`，在 .23 上运行即 `NoSuchMethodError`；我们的 `mods.toml` 依赖范围是 `[3.0.0,)`，用户装 .23 就会命中。源码侧无需改动（`player.start();` 照常编译），但**必须重新编译**（全项目唯一 `start()` 调用点＝`WaterMediaPlayer.java:140`）。
- [x] **binaries 同批升到 3.0.0.6**【S】: 与 API 同日发布且属崩溃修复；上游 `neoforge.mods.toml` 未声明 API↔binaries 版本约束，不会自动拦住旧 binaries。
- [ ] **顺手：用上 `start()` 的返回值**【S】: 预热播放器起播失败时可记日志/回退（现在忽略返回值）。
- [x] **文档债（已修）**：`reference/watermedia-wiki/en-us.md:111` 仍写 `void start(); void startPaused();`，需改为 `boolean`（该文件自称「对照本项目实际构建的产物核实」）。
- [x] **不需动（签名 diff 已核）**：我们在用的 `MediaAPI.mrl/glEngine/alEngine/createPlayer(mrl,gfx,sfx)`、`MRL`、`MediaPlayer` 的 `volume/width/height/time/duration/ended/isPlaying/mute/seekQuick/stop`、`platform` 包 6 个类（`IPlatform/PlatformAPI/PlatformData/DataSource/PlatformException/DataQuality`）、`util` 包 4 个类（`MediaType/Metadata/RequestHeaders/Slave`）、`WaterMediaConfig` —— **签名全部一致**；新增 API 类只有 `players/sync/*`（`Bridge/Packet/Sync/Config/Watch/Unwatch/Report/Control`）与 `MediaPlayer$Role`、`ServerMediaPlayer$Watcher`，**无任何删除**。CodecsAPI 那批修复（PNG zTXt 死循环、渐进式 JPEG、SVG 二次方、DDS 溢出…）我们不用该 API；`speed(NaN)` 拒绝我们也不调 `speed`。
- [ ] **机会（暂缓，需单独立项）：用内置同步子系统替换自研同步层**【L】: .23 把联机同步做进了 `MediaPlayer`——`sync.Bridge`（单方法 `send(ByteBuffer)`，可承载任意字节通道，能挂在我们现有包上）、`Config.Capability`（LOCKSTEP / CONTROLS / VOLUME）、`role()/authority()/drift()/tolerance(ms)`、`ServerMediaPlayer`（权威：注册观众 + 变更广播 + 约 5s 心跳 + 处理控制请求 + 清理失联）、revision 单调计数与乱序丢弃、`syncDuration` 首胜、ENDED 态 seek 落 PAUSED。它与我们的 `SyncGroupManager` + `SyncStatePacket` + `ClientClockSync` + 漂移兜底属同一层能力，收益：①LOCKSTEP（有人缓冲时全体冻结、失败者忽略、中途加入不打断他人）正对我们「缓冲饥饿期反复戳播放器」「集间过渡」两个老痛点；②CONTROLS 让控制请求走上行、两侧 API 对称；③省掉自研协议与节流数学。风险：权威侧只有 `ServerMediaPlayer`（无真实媒体）与真实媒体播放器两类，与「每屏一个权威 + 每人本地播放器跟随」的映射需实测；**VOLUME 能力会覆盖我们每屏本端音量 → 不能启用**；`sync()` 解码发生在任意线程、内部 50ms ticker；还要与「线路/清晰度双播放器交接」「直链队列」重新对齐。结论：先记录，等其他批次稳定后再评估。

### 待裁决（拍板后才动）

- [ ] **扫码登录成功后立即同步屏蔽词**: `BilibiliLoginScreen` 登录成功处加一行拉取 `x/dm/filter/user`（一行改动，取舍在「登录即联网」的体验）【S】
- [ ] **屏蔽用户 uid 规则**: B 站账号屏蔽词实测 429 条里 342 条是 type=2 用户规则（按 uid 屏蔽发送者），当前整体跳过【M】
- [ ] **高级弹幕完整还原**: 现为降级滚动或直接跳过（mode 7/8 跳过、9 降级），完整还原需实现 B 站高级弹幕脚本语义【L】

### 待实机回归

- [ ] **屏幕前方深度分层**：3 层的方向（`DanmakuDepthLayers.OBSERVER_Z_SIGN` 若相反只翻这一个常量）/层次观感/帧率影响
- [ ] **全屏描边观感**：改用 `Style` 阴影单次绘制后（原 9 次绘制有重影），实机确认与 B 站观感一致
- [ ] **全屏弹幕快捷开关**：手感、与进度条的挤压关系、直播屏底条表现

## 深度审计修复（2026-08-24）

- [x] **P1 RuleManagerScreen 成功回包 NPE**: 服务端 PlayOkPayload 只填 key（title=null），客户端 `Component.literal(null)` 必崩 → 改用 `p.toComponent()`
- [x] **P1 HttpUtil SSRF 重定向绕过**: Redirect.NEVER + 手动循环逐跳 checkSsrf（≤5 跳）；CGNAT/ULA 拦截配置化（`ssrfBlockCgnat` 默认 true / `ssrfBlockUlaIpv6` 默认 false 兼容 TUN 代理）；白名单归一化；单次 getAllByName 全地址校验
- [x] **P2 服务端六项**: 通知组件 getString 扁平化（专用服显示原始 key）/ Component 拼接 toString 调试串 / translatable.getString 系统性违规 12+ 处 / 整屏停止四处收敛 PlaybackController.stopScreen（含通知死代码与 WatchingPlayers 清理）/ RuleManager 损坏文件容错+原子写盘 / playFromSearch 统一播放入口+集数钳制落盘
- [x] **P2 客户端三项**: BrowserCookieStore 移除（收割端从未接线+UA 不配对，文档债同步修正）/ 死配置处理（maxConcurrentPlays 接入调度闸门、videoVolume 总系数接线；mcefLifecycle、autoJoinSync 移除）/ GUI static 会话换屏重置
- [x] **P2 公共层五项**: DNS 单次解析全地址校验 / 内网段字节级判定（CGNAT/ULA/组播/240/4）/ EpisodeUrlNormalizer 孤立 % 容错 / 两处 BE loadAdditional UUID 容错 / common 四文件 net.minecraft.client 引用解耦（ClientPacketSender 钩子 + KazumiClientMessages 迁移）
- [x] **P3 必修两条**（复核确认）: krule 异步回调 mc.execute 投递（CME 路径）/ ScreenPropsScreen Locale.ROOT
- [x] **配套**: 语言键全量比对补缺 8 个（status_done/speaker_connected/disconnected/ok.joined/played_episode_n/cmd.rule.testing/play_limit/item.*×7）；调试物品 debug_verify（外壳永久保留，验证区提交前清空）

## 体验与功能待办（2026-08-24 使用体验分析筛选，任务看板同名卡片）

### 功能性 Bug
- [x] **A1 ±10s 与小幅度 seek 无效**: 无本地回显，靠 handleSyncState 阈值 drift>10000ms 严格回灌永不触发，落盘位置恒差 ~10s——已由「seek 定向指令」解决：SyncStatePacket 新增 forceSeek 标志，GUI ±10s 与命令时间调整绕过漂移保护全组立即跳转（applyForcedSeek，仅保留天文位置拒绝）【S】
- [x] **A2 断线重进从 0 重播**: joinScreen 异常恢复分支 setPlayback(url,0) 覆盖已落盘进度——已修：异常恢复沿用落盘 SyncPositionMs（clamp ≥0），不再从 0 重播【S】
- [x] **A3 非队列播放失败整屏连坐**: 一人失败全场被踢且进度清空——已修：无队列上下文降级为仅发起者本端停播并退出同步（QueueRequestHandlers.stopLocalOnly → PlaybackController.leaveOwn），其他观看者不受影响【S】

### GUI 体验
- [x] **B1 全屏观影控制条+快捷键**: 底部悬浮控制条（暂停/±10s/拖动seek/音量）+空格/方向键，进度条可拖——已实装：控制条含暂停/±10s/可拖动进度条+时间，空格暂停、←→±10s、↑↓本屏音量、M 静音（OSD 提示）；直播直连屏停用控制条与时间轴类快捷键，音量类保留【M】
- [ ] **B2 等待与失败可视化包**: 起播阶段文案（解析中→加载流→缓冲）+等待秒数+取消按钮；连续失败冷却+可点击「重试/换来源」；错误文案 i18n【M】——已落地：起播等待文案+秒数+取消按钮、解析状态上报三处等待可视化（ResolveHint）、失败横幅 20s 窗口与错误文案 i18n；剩余：连续失败冷却与可点击「重试/换来源」入口
- [x] **B4 seek/HLS 重缓冲冻结指示**: 时间戳冻结 >1.5s 角落缓冲动画——已实装（全屏 HUD）：ScreenPlayerManager.isBufferingFrozen（阈值 BUFFER_FREEZE_MS=1500，位置推进即刷新时刻）→ 进度条上方琥珀文字，独立于控制条淡出恒可见【S-M】
- [x] **B5 音量体系升级**: GUI/全屏音量滑块+每屏独立音量+静音快捷键（现全服共享 RECORDS 滑块）——已实装：有效音量=RECORDS×videoVolume×volumeScale（静音=0），每屏状态存 ScreenPlayerManager；GUI 进度条右侧滑块+静音钮、全屏 ↑↓/M、全局 M 键静音准心屏幕【M】
- [ ] **B7 破坏性操作防误触**: 停止二次确认/撤销窗、队列删格首次点变"确认？"【S】
- [x] **B8 番剧单击改选中**: 单击只显示简介，双击/按钮才搜源（现误点即全源搜源+清选集）——已实装：搜索结果行改 addDoubleClickableRow，单击只 selectSubject（选中+显简介，零网络请求），双击才 searchSubject【S】
- [x] **B10 列表行悬停 tooltip 显全文**: 现 SimpleList 剪字无省略号、全局零 tooltip——已实装：悬停且文本被截断时 setTooltipForNextFrame 显示全文（自动换行 200px）【S】
- [x] **B13 设置数值框失焦/Enter 提交**: 现 setResponder 逐键发包致画面抖动+包风暴——已实装：ScreenPropsScreen 数值框改失焦/关闭时提交（propCommits 登记 + commitBox，等值输入早退），逐键发包与画面抖动消除【S】
- [x] **B14 加入/离开按钮按观看态置灰**——已实装：joinButton/leaveButton 按本端观看态互斥置灰（数据源=BE 同步的 WatchingPlayers，与右列 ▶ 标记同源）【S】
- [x] **B15 全屏退出按钮鼠标静止 3s 淡出**——已实装：控制条与退出按钮共用鼠标静止 3s 淡出显隐（controlsAlpha/fadeAlpha，淡出后命中失效）【S】
- [ ] **B16 bgm 搜索结果列表可滚动**（替代翻页方案）【S-M】

### 功能新增
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
- [ ] **F2 倍速播放**: 单机直链先行（WaterMedia speed 待验证）；联机需 SyncGroup 加 speed 字段防循环硬 seek【M-L】
- [ ] **F4 每周放送时间表**: bgm calendar 免鉴权 API+TimedCache；新增"放送时刻表"物品右键打开星期分栏 GUI【S-M】
- [x] **F5 规则热重载**: /kazumi rule reload + RuleWatcher 目录监视（WatchService 防抖 800ms+内部写回声抑制 1.5s，ruleHotReload 配置默认开）；三路广播统一 RuleSyncBroadcast（空列表也广播清客户端缓存）；reloadLive 解析失败保留内存现状不动盘
- [x] **F10 房间弹幕互发（已实现）**: 聊天栏监听发送（ServerChatEvent 查组广播，无命令/无输入框/不依赖画面时间同步，连发不丢弃——冷却已废止 v3.3）+ DanmakuBroadcastPacket 单包 + ClientDanmakuStore 统一入口 + 世界/HUD 双层渲染（Z180 坐标系补偿）+ 双删组点清池；DebugVerifyItem 含快速校验套件。规划/协议文档见 reference/plans/f10-*
- [ ] **F10-R2 dandanplay 片内弹幕（搁置 backlog）**: 三步对接设计完毕（room-protocol v3.3 §10 + render-design v1.7），TIMELINE 时间轴通道留接口可复用现成 Store/渲染管线；阻塞项=dandanplay 开放平台凭据申请（实测匿名裸调三端点全 403，见 plans/f10-dandanplay-auth-check.md），凭据到位后解冻

### 其他
- [ ] **合成配方添加**: 已完成 4 个——视频屏幕 / 远程观影器 / 屏幕遥控器 / 规则管理器（`data/kazumiplayer/recipe/*.json`，配方表见看板卡片）；剩余：**音响**、**连接工具**（音响配方与挂起区条目合并处理）

### 已评估不立项（含原因备查）
B6 多人误触观感风险｜B9 规则/线路数量通常不多｜B11 失败有报错即可｜B12/B17/F7/F8 需更多信息｜F1/F3 用处不大且 GUI 无位置｜F6 当前嗅探问题少｜F9 无用

## 音响系统（⛔ 整体挂起——先决条件：前置 mod WaterMedia v3 完善 audio 能力（video/audio 独立开关）；就绪前不动此区）

- [ ] **音响发声**: WaterMedia v3 当前 `MediaAPI.createPlayer(mrl, gfx, sfx)` 不支持 video/audio 独立开关。`SpeakerBlockEntity.startAudio()` 为占位
- [ ] **连接时屏幕静音**: 有音响连接时屏幕 `player.mute(true)`，断开最后一个音响后恢复
- [ ] **漂移校正测试**: `SpeakerBlockEntity.clientTick()` 中的周期校正逻辑未经实测
- [ ] **音响自定义纹理**: 当前用 `minecraft:block/note_block` 占位
- [ ] **连接工具空手右键音响断开**: 当前只实现了连接，断开需挖掉音响或屏幕
- [ ] **音响方块合成配方**

## 已完成的实用物品（README 功能清单）

- [x] **屏幕遥控器**: Shift+右键绑定屏幕（CUSTOM_DATA 持久化），手持右键远程打开播放器 GUI，Shift+右键空中解绑；服务端校验（强制加载区块）——屏幕被破坏提示「未找到屏幕方块」。公共骨架抽为 AbstractScreenRemoteItem（RemoteOpenPacket/OpenRemoteGuiPacket）
- [x] **远程观影器**: 与屏幕遥控器同机制，右键直接进入全屏观影（纯观看无操作）；RemoteFullscreenPacket/OpenRemoteFullscreenPacket；全屏进入来源标记——GUI 进入退出回 GUI、观影器直接进入退出回世界
- [x] **规则管理器**: 右键打开规则管理界面（RuleManagerScreen）——远程仓库规则列表（index.json 与本地合并）、拉取/删除规则、连通性延迟测试（测/测试全部）、弃用红色标记；经 GuiProtocol 通用通道（rule_list/pull/delete/test），服务端处理并入 GuiRequestHandlers；common 经 RuleManagerOpener 钩子打开客户端界面
- [x] **创造模式物品栏标签页**: CreativeTabRegistration 聚合全模组物品（图标=视频屏幕方块）

## 功能增强

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
- [ ] **渲染性能优化**: 多屏幕同时播放时帧率优化
- [x] **GUI 搜源流式化**: 逐规则搜索、每完成一个源立即推送增量（DATA_RULE_RESULTS_PARTIAL + searchId 代次防串轮），慢源/超时源不再阻塞快源结果的展示；状态栏渐进显示「搜源中 X/Y 个源…（已得 N 条）」；单源失败计为一次完成（空增量）保证计数收敛，超时仍由 searchTimeoutMs 兜底。关闭界面/清空搜索发 ACTION_CANCEL_SEARCH、发起新搜索自动作废旧代次（服务端 per-player 活跃代次登记），迟到增量双重过滤（服务端拦截 + 客户端仅接受更大 searchId）
- [x] **直链队列**: 队列复用 EpisodeData 存合成 Road（name="直链队列"，util/DirectLinkQueue），自动下一集/上一集/下一集零改动在队列内推进、无新增 NBT 字段与 S→C 包（BE markDirty 自动同步）。顶栏单按钮：空闲"播放"、播放中"排队"追加；队列面板行=[名称点击切播][插=移到下一个][删]；与规则剧集互斥（剧集中提交拒绝，点选集播放覆盖队列）；queue_add/jump/move/remove 经 GuiProtocol 通用通道，GUI 与 /kazumi play-url 共用 QueueRequestHandlers。顺带修复：旧 handlePlayUrl 不写 episodeData 导致直链播完误切到残留剧集下一集
- [x] **命令同步 GUI 能力**: 新增 /kazumi queue 一级子树（list 分页展示▶当前项+行可点击切播 / add / jump / move / remove）与 /kazumi screen viewers 观看玩家列表（组优先 NBT 兜底，在线显名离线短UUID）；命令层纯参数解析全部委托 QueueRequestHandlers，与 GUI 共用服务端唯一实现
- [x] **队列已播项自动出队**: 切集推进（自动/手动/GUI next）经 PlaybackController.applyEpisodeSwitch、队列内切换（jump/playNow/失败跳过）经 QueueRequestHandlers.playItemAt，两落点均把目标项之前的已播前缀移出队列（列表自新当前项起重写、episodeIndex 归 1）；规则剧集数据不受影响，保留完整列表供 prev/选集回跳

## BUG

- [x] **命令路径删除最后一个规则不清客户端缓存**: `RuleCommands.broadcastRuleSync` 对空规则列表早退，而 `GuiRequestHandlers` 版本行为正确（空列表也广播以清空客户端 RuleSync 缓存）——已随 F5 修复：三路广播统一收敛到 `RuleSyncBroadcast.broadcast()`（命令/GUI/热重载共用，空列表也广播）
- [ ] **多屏幕同时播放**: 未充分测试多屏幕同时播放的稳定性
- [ ] **音响重复连接**: 同一音响重复连接同一屏幕——目前无去重提示
- [ ] **多人同时开 GUI 的状态陈旧**: 多人共享一块屏幕时，GUI 底部状态栏文字只反映本客户端触发过的操作，他人换集/暂停不会更新文字（预览画面/时间/暂停状态实时，数据层无冲突，最后操作者赢为预期语义）——可改进：播放标题写入 BE NBT 随同步广播，或从 Road JSON+EpisodeIndex 推断当前集名；如需防陌生人乱控可加"观看者/OP 可控"权限
- [ ] **嗅探健壮性收尾**: 已落地——停止/破坏屏幕取消在途嗅探（cancelActiveSniff）、带签名 query 的直链直判快速播放、接管式并发取代在途任务。剩余：接管/取消路径需实机回归；Cookie 桥接的 cf_clearance 时效与刷新策略未验证
- [~] **Cloudflare 挑战页卡死嗅探 (dmbus/DM84)**: 已落地——常驻共享浏览器（Cookie 跨嗅探保留）、UA 伪装（HTTP头+JS侧+mcef.properties 异常值强制重写，含空闲期）、HTTP 直取播放页解析直链优先（java 路径与浏览器路径可达性不同，互补）、Cookie 桥接（收割 document.cookie 按域名存储，直取/规则请求成对附加 Cookie+伪装 UA——clearance cookie 与签发 UA 绑定）、失败提示区分 CF 拦截、章节/直取请求补 Referer。**实测结论：dmbus 源站分钟级闪断(522)为站点侧问题，与客户端无关**。剩余候选：规则 antiCrawler 配置（验证页检测+自动点击+自定义 JS）
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
- [ ] **弹幕支持**: 从 Kazumi App 移植弹幕渲染
- [ ] **画质选择**: WaterMedia 多 quality 选择

## 项目重构

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
- [ ] **权限系统**: GUI/命令操作分级（OP / 屏幕创建者 / 观看者）——停止屏幕、屏幕属性修改（朝向/大小/偏移）、队列管理等当前全员开放，联机信任场景可用；引入创建者归属后可细化【P3】
- [ ] **API 章节 delimited 格式**: ApiRuleStrategy 仅实现 nested，delimited（分隔符聚合格式）未实现——最新社区规则暂无使用【按需：出现首个依赖规则时再补】
- [ ] **反爬 captcha webview 全流程**: 已做 text/regex 验证页检测报错；Kazumi 完整能力含 xpath 检测、captchaImage/Input/Button 定位、captchaScript JS 注入自动验证（WebView 加载+Cookie 保存+重试），需 MCEF 配合【按需：出现实际站点需求再立项】
- [ ] **API 模式 POST body 模板**: Rule.ApiRequestConfig 缺 body 字段（json/form 模板 + @var 渲染），带 body 的 API 规则无法正确发请求【按需：同上】
- [x] **RuleManager 重复实例**: `SyncGroupManager.onPlayerJoin` 曾每次玩家进服 `new RuleManager(...).loadAll()` 全量重读磁盘且产生第二实例——已改为 `SyncGroupManager.init(ruleManager)` 注入 KazumiPlayerServer 初始化的单例，进服广播直接读单例
- [x] **ClientDisconnectHandler 拆分**: 播放调度（原 onClientTick 约 130 行六段状态机）+ 生命周期清理已拆至 `ClientPlaybackScheduler`；顺带删除冗余的 `activeScreens` 双重跟踪结构（与 ScreenPlayerManager 表达同一事实、靠每秒对账维持，其孤儿清理段被 reconcileStaleEntries 的 screenGone 条件完全覆盖），渲染 BE 由两次遍历合并为一次，僵尸阈值移出循环，主菜单空转加守卫
- [x] **播放器启动逻辑去重**: handlePlayStart 死路径（PlayStartPacket 包+handler+NetworkManager 注册项）已随旧嗅探链路删除；tick 路径走 playback/source 包
- [~] **PlayStateListener 死代码（改判：决策项而非清理项）**: 已确认 `addListener` 全项目零调用点、listener 从未注册；但 `SpeakerClientAudio implements PlayStateListener`（音响跟随骨架）——直接删会连带删掉音响跟随设计。处置随音响区一同挂起【挂起·待 WaterMedia v3 audio】
- [x] **VideoSniffer handler 泄漏**: 旧 VideoSniffer 已随嗅探架构重写删除，问题不复存在
- [x] ~~**三份 dev mods.toml 去重**~~ → 已评估不做：实际存在 5 份（resources×3 + templates×2），templates 已是正式产物的占位符展开机制（单一来源）；dev resources 副本必须三处一致否则 JPMS split package 报 ResolutionException（文件头注释已说明约束）。若未来收敛，仅剩"dev 副本也由模板生成"一条路，锦上添花而已
- [ ] **build.gradle configurations.all 篡改**: 全局强制 Usage=JAVA_RUNTIME 破坏变体感知解析（当年为修 moddev universalJar 变体冲突所加）——构建目前可用则不动，待真撞上变体解析报错时带复现排查，改为 configuration 级 attributes【观望】
- [x] **PlaybackControlPacket action 改枚举**: 新增 `PlaybackAction` enum（NEXT/PREV/PAUSE/RESUME/SEEK_FORWARD/SEEK_BACK/SEEK_GOTO），自定义 STREAM_CODEC 保持小写 snake_case wire 格式、非法值抛 DecoderException；packet/handler/GUI 发送端全部改用枚举
- [x] ~~**RuleEngine 策略接口**~~ → 已评估不做：实测仅 88 行、2 个方法、各 1 个 if 分支，抽接口不会更简单（过度设计）
- [x] **setPlayback/setPlaybackFull 边界文档化**: Javadoc 写清分工——setPlayback 不写 Road 数据（无剧集上下文场景），setPlaybackFull 完整写入（规则剧集/直链队列，自动连播依赖）
- [x] **getScreenId 显式初始化入口**: 新增 `ensureScreenId()`（生成即持久化）；getScreenId() 转发保持 lazy 历史行为不变（30 处服务端调用点依赖该语义，改纯 getter 会引入 NPE 面），新代码建议创建屏幕后显式调 ensureScreenId()
- [x] **ConnectedSpeakers 编解码去重**: 三处重复逻辑收敛为 `encodeConnectedSpeakers`/`decodeConnectedSpeakers` 静态工具（磁盘 NBT 与同步 getUpdateTag 共用）
- [x] ~~**BE 双轨序列化统一**~~ → 按"至少抽字段名常量"落地：ValueOutput（磁盘）与 CompoundTag（同步包）双轨保留，字段名统一走 KEY_* 常量消除裸字符串双份维护
- [x] **SpeakerBlockEntity chunk 加载误清连接**: loadAdditional 曾在服务端 `getLinkedScreen()==null` 时自动 clearLink——但屏幕 chunk 未加载时 getBlockEntity 返回 null，启动加载顺序不定会误删有效连接并写盘；已删除该防御块（所有合法失效路径均已有清理：屏幕破坏 setRemoved 遍历 ConnectedSpeakers / 音响挖除反向通知 / 手动断开双向清理）
- [ ] **SpeakerConnectPacket UUID 序列化**: STRING_UTF8 存 36 字符膨胀——改 mostSignificantBits+leastSignificantBits VAR_LONG；该包仅在手动连接音响时发送，每包省 ~20 字节收益趋近零【低·可弃】
- [x] ~~**HttpUtil.fetch 双重异步**~~ → 已评估不改：`new CompletableFuture` + runAsync 是受检异常场景的标准手动桥接（仅一层异步提交，非双重异步）；改 supplyAsync 直返会让所有异常被 CompletionException 包裹，而 `e.getMessage()` 在 GUI/命令层有 10+ 处用户可见文案消费点（GuiRequestHandlers/PlayCommands/RuleEngine errors 等），文案将变成 "java.util.concurrent.CompletionException: ..." 噪音，逐处剥壳得不偿失
- [x] **PlayCommands 拆分**: 新增 `sync/PlaybackController` 作为播放控制唯一权威实现——切集四件套（setPlaybackFull+onPlayStart+WatchingPlayers+立即广播）原子化为 applyEpisodeSwitch，暂停/恢复/seek/joinScreen/leaveOwn 全收敛；`SyncGroup.livePositionMillis()` 单点化 elapsed 计算（曾因复制传播扩散出 14 处、其中 10 处墙钟混算）。PlayCommands 瘦身 537→332 行纯参数解析层，ServerPacketHandlers 283→197 行。命令树重组：控制类迁入 `/kazumi control next|prev|pause|resume|time …`，play-url/join 迁入 `/kazumi play url|join`（旧一级写法保留透明别名）
- [x] **搜索缓存抽象 TimedCache<T>**: 三个缓存类的 Map+TTL+清理线程同构逻辑收敛到 `TimedCache<V>` 泛型基类（创建时间由包装层管理，Session/Entry 去掉冗余 createdAt/sessionId 字段；showRulePage 改传参取 sessionId）；shutdown() 仍无外部调用方但守护线程不阻塞 JVM 退出
- [x] **BangumiApi HttpClient 复用**: HttpClient/Gson 提升为类级 static final 字段（HttpClient 自带连接池，不再每次 search 重建）
- [x] ~~**onClientTick 魔法数字**~~: 核验后仅剩 4 处（20/2/3000/300，其余已在此前重构消化），提为 ClientDisconnectHandler 类内命名常量
- [x] ~~**SNIFF_SCRIPT 外部化**~~ → 已评估不做：SniffScripts.java 已是嗅探 JS 的单一来源，resources 化只会失去编译期检查与 IDE 高亮
- [ ] **extractRenderState 跨包引用**: screen 包完全限定名调用 client.ScreenPlayerManager——同属 client source set 内部耦合，不影响 common 纯净性；解耦收益存疑【低·可弃】
- [ ] **多版本支持**: 适配不同 Minecraft 版本【暂缓——功能快速迭代期聚焦单版本，避免按版本分支/抽象层的双重维护成本；待功能面稳定后再评估】
- [ ] **多加载器支持**: 除 NeoForge 外支持 Fabric/Quilt【暂缓——理由同上，且前置依赖 MCEF/WaterMedia 生态以 NeoForge 为主】
