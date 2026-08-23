# TODO

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
- [x] **Cookie 桥接**: 嗅探期间收割 document.cookie 按 host 存入 BrowserCookieStore，RuleRequestEnhancer 钩子使直取/规则请求成对附加 Cookie+伪装 UA
- [x] **停止屏幕全量通知**: GUI 停止屏幕向全部观看者发 PlayStopPacket + 聊天提示（原 /kazumi screen stop 不通知观看者端）
- [x] **全屏观影模式**: GUI 预览右下角「全屏」按钮 → 关闭 GUI 由 HUD 层接管画面（RenderGuiLayerEvent.Post@HOTBAR，盖住准心/血条、聊天层浮于其上——T 键原生可用）。覆盖度 fullscreenCoverage(1-100)/不透明度 fullscreenOpacity(10-100) 可配，底部进度条+时间；右上角「退出」按钮点击返回世界（鼠标全程可见可点，InputEvent.MouseButton.Pre 拦截按钮命中点击不下发世界）
- [ ] **渲染性能优化**: 多屏幕同时播放时帧率优化
- [x] **直链队列**: 队列复用 EpisodeData 存合成 Road（name="直链队列"，util/DirectLinkQueue），自动下一集/上一集/下一集零改动在队列内推进、无新增 NBT 字段与 S→C 包（BE markDirty 自动同步）。顶栏单按钮：空闲"播放"、播放中"排队"追加；队列面板行=[名称点击切播][插=移到下一个][删]；与规则剧集互斥（剧集中提交拒绝，点选集播放覆盖队列）；queue_add/jump/move/remove 经 GuiProtocol 通用通道，GUI 与 /kazumi play-url 共用 QueueRequestHandlers。顺带修复：旧 handlePlayUrl 不写 episodeData 导致直链播完误切到残留剧集下一集

## BUG

- [ ] **多屏幕同时播放**: 未充分测试多屏幕同时播放的稳定性
- [ ] **音响重复连接**: 同一音响重复连接同一屏幕——目前无去重提示
- [ ] **多人同时开 GUI 的状态陈旧**: 多人共享一块屏幕时，GUI 底部状态栏文字只反映本客户端触发过的操作，他人换集/暂停不会更新文字（预览画面/时间/暂停状态实时，数据层无冲突，最后操作者赢为预期语义）——可改进：播放标题写入 BE NBT 随同步广播，或从 Road JSON+EpisodeIndex 推断当前集名；如需防陌生人乱控可加"观看者/OP 可控"权限
- [ ] **嗅探健壮性收尾**: 已落地——停止/破坏屏幕取消在途嗅探（cancelActiveSniff）、带签名 query 的直链直判快速播放、接管式并发取代在途任务。剩余：接管/取消路径需实机回归；Cookie 桥接的 cf_clearance 时效与刷新策略未验证
- [~] **Cloudflare 挑战页卡死嗅探 (dmbus/DM84)**: 已落地——常驻共享浏览器（Cookie 跨嗅探保留）、UA 伪装（HTTP头+JS侧+mcef.properties 异常值强制重写，含空闲期）、HTTP 直取播放页解析直链优先（java 路径与浏览器路径可达性不同，互补）、Cookie 桥接（收割 document.cookie 按域名存储，直取/规则请求成对附加 Cookie+伪装 UA——clearance cookie 与签发 UA 绑定）、失败提示区分 CF 拦截、章节/直取请求补 Referer。**实测结论：dmbus 源站分钟级闪断(522)为站点侧问题，与客户端无关**。剩余候选：规则 antiCrawler 配置（验证页检测+自动点击+自定义 JS）
- [x] **暂停/恢复后播放时间异常增大**: 同步架构重写改 MonoClock 时漏了读取侧调用方——命令/GUI/包处理器共 10 处以墙钟 `System.currentTimeMillis()` 减 MonoClock 基准的 `serverTimestamp`，得到 epoch 级 elapsed 写入权威位置并立即广播 → 客户端漂移兜底硬 seek 到天文位置 → FFmpeg seek 失败管道死亡 → 误判 ended 自动切集；已全部改用 MonoClock.millis()（ServerPacketHandlers/GuiRequestHandlers/PlayCommands/ScreenCommands），客户端硬 resync 增加"目标超出媒体时长即拒绝对随"防御
- [x] **手动切集后暂停卡死/时间调整无效（偶发，重新 join 才恢复）**: `handleEpisodeSwitch`（GUI/命令的手动 next/prev）只写 BE NBT 未重置权威同步组——周期广播仍携带旧集 URL → 客户端换片保护 `switchingEpisode` 恒为 true，吞掉之后所有暂停/seek 应用；切集前若处于暂停态则新集开局即暂停且无法恢复。已补 `onPlayStart` 重置组（对齐 auto-next 路径）。附带发现：上轮时钟修复在 PlayCommands 漏了 4 处墙钟混算（验证脚本 `-Path 'src\**\*.java'` 不支持 PowerShell 递归导致假阴性），已改用 Get-ChildItem -Recurse 复查清零
- [x] **拆屏/退主菜单后音频偶发继续播放（孤儿播放器）**: 异步解析完成的起播回调仅有 `screen.isRemoved()` 一个守卫——退主菜单时 `stopAllActive` 清空注册表但 BE 实例未 removed，play 照常执行产生无人引用的孤儿播放器；拆屏时 PlayStopPacket 先 stop、解析回调后 play 的队列竞态同样复活播放器。修复：beginPlayback 捕获 ScreenPlayer 会话引用，起播前三重校验（level==null / isRemoved / session.player 身份不符即弃播并回收）；stopAllActive 补 cancelAllResolves；handlePlayStop 由全局取消改按屏取消（消除多屏互扰）
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
- [ ] **PlayCommands 拆分**: 现 490 行含 10+ 子命令+辅助函数，stop/leave 重复、切集逻辑与 ServerPacketHandlers 重复——抽 EpisodeSwitcher 服务消重即可，不必机械拆命令类【P2·攒批】
- [x] **搜索缓存抽象 TimedCache<T>**: 三个缓存类的 Map+TTL+清理线程同构逻辑收敛到 `TimedCache<V>` 泛型基类（创建时间由包装层管理，Session/Entry 去掉冗余 createdAt/sessionId 字段；showRulePage 改传参取 sessionId）；shutdown() 仍无外部调用方但守护线程不阻塞 JVM 退出
- [x] **BangumiApi HttpClient 复用**: HttpClient/Gson 提升为类级 static final 字段（HttpClient 自带连接池，不再每次 search 重建）
- [x] ~~**onClientTick 魔法数字**~~: 核验后仅剩 4 处（20/2/3000/300，其余已在此前重构消化），提为 ClientDisconnectHandler 类内命名常量
- [x] ~~**SNIFF_SCRIPT 外部化**~~ → 已评估不做：SniffScripts.java 已是嗅探 JS 的单一来源，resources 化只会失去编译期检查与 IDE 高亮
- [ ] **extractRenderState 跨包引用**: screen 包完全限定名调用 client.ScreenPlayerManager——同属 client source set 内部耦合，不影响 common 纯净性；解耦收益存疑【低·可弃】
- [ ] **多版本支持**: 适配不同 Minecraft 版本【暂缓——功能快速迭代期聚焦单版本，避免按版本分支/抽象层的双重维护成本；待功能面稳定后再评估】
- [ ] **多加载器支持**: 除 NeoForge 外支持 Fabric/Quilt【暂缓——理由同上，且前置依赖 MCEF/WaterMedia 生态以 NeoForge 为主】
