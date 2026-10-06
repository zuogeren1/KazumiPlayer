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

### WaterMedia 3.0.0.23：影响评估（对照 3.0.0.22 反编译签名逐项核过）

现状（2026-10-06 已升级）：`build.gradle` 钉 **API `FdCZ5Rxq`（= 3.0.0.23）** + **binaries `fYWsOuBz`（= 3.0.0.6）**，两者互为 required 依赖且同支持 MC 26.1.2。升级动因：`start()`/`startPaused()` 由 `void` 改 `boolean` 属二进制不兼容（编译于 .22 的字节码在 .23 上会 `NoSuchMethodError`）；binaries 3.0.0.6 的 changelog 为「Fixed jar crashes the game asking for multirelease folder」。

- [ ] **顺手：用上 `start()` 的返回值**【S】: 预热播放器起播失败时可记日志/回退（现在忽略返回值）。
- [ ] **机会（暂缓，需单独立项）：用内置同步子系统替换自研同步层**【L】: .23 把联机同步做进了 `MediaPlayer`——`sync.Bridge`（单方法 `send(ByteBuffer)`，可承载任意字节通道，能挂在我们现有包上）、`Config.Capability`（LOCKSTEP / CONTROLS / VOLUME）、`role()/authority()/drift()/tolerance(ms)`、`ServerMediaPlayer`（权威：注册观众 + 变更广播 + 约 5s 心跳 + 处理控制请求 + 清理失联）、revision 单调计数与乱序丢弃、`syncDuration` 首胜、ENDED 态 seek 落 PAUSED。它与我们的 `SyncGroupManager` + `SyncStatePacket` + `ClientClockSync` + 漂移兜底属同一层能力，收益：①LOCKSTEP（有人缓冲时全体冻结、失败者忽略、中途加入不打断他人）正对我们「缓冲饥饿期反复戳播放器」「集间过渡」两个老痛点；②CONTROLS 让控制请求走上行、两侧 API 对称；③省掉自研协议与节流数学。风险：权威侧只有 `ServerMediaPlayer`（无真实媒体）与真实媒体播放器两类，与「每屏一个权威 + 每人本地播放器跟随」的映射需实测；**VOLUME 能力会覆盖我们每屏本端音量 → 不能启用**；`sync()` 解码发生在任意线程、内部 50ms ticker；还要与「线路/清晰度双播放器交接」「直链队列」重新对齐。结论：先记录，等其他批次稳定后再评估。

## 待裁决（拍板后才动）

- [ ] **扫码登录成功后立即同步屏蔽词**: `BilibiliLoginScreen` 登录成功处加一行拉取 `x/dm/filter/user`（一行改动，取舍在「登录即联网」的体验）【S】
- [ ] **屏蔽用户 uid 规则**: B 站账号屏蔽词实测 429 条里 342 条是 type=2 用户规则（按 uid 屏蔽发送者），当前整体跳过【M】
- [ ] **高级弹幕完整还原**: 现为降级滚动或直接跳过（mode 7/8 跳过、9 降级），完整还原需实现 B 站高级弹幕脚本语义【L】

## 体验与功能待办（2026-08-24 使用体验分析筛选，任务看板同名卡片）

### GUI 体验
- [ ] **B2 等待与失败可视化包**: 起播阶段文案（解析中→加载流→缓冲）+等待秒数+取消按钮；连续失败冷却+可点击「重试/换来源」；错误文案 i18n【M】——已落地：起播等待文案+秒数+取消按钮、解析状态上报三处等待可视化（ResolveHint）、失败横幅 20s 窗口与错误文案 i18n；剩余：连续失败冷却与可点击「重试/换来源」入口
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

- [ ] **音响发声**: WaterMedia v3 当前 `MediaAPI.createPlayer(mrl, gfx, sfx)` 不支持 video/audio 独立开关。`SpeakerBlockEntity.startAudio()` 为占位
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
