# TODO

## 音响系统（当前占位，待 WaterMedia v3 API 完善后实现）

- [ ] **音响发声**: WaterMedia v3 当前 `MediaAPI.createPlayer(mrl, gfx, sfx)` 不支持 video/audio 独立开关。`SpeakerBlockEntity.startAudio()` 为占位
- [ ] **连接时屏幕静音**: 有音响连接时屏幕 `player.mute(true)`，断开最后一个音响后恢复
- [ ] **漂移校正测试**: `SpeakerBlockEntity.clientTick()` 中的周期校正逻辑未经实测
- [ ] **音响自定义纹理**: 当前用 `minecraft:block/note_block` 占位
- [ ] **连接工具空手右键音响断开**: 当前只实现了连接，断开需挖掉音响或屏幕
- [ ] **音响方块合成配方**

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
- [ ] **GUI 播放线路选择**: 首次实现（ChaptersPayload 改多线路结构 + CycleButton 切换 + play_episode 带 roadIndex）引入回归——原可正常播放的站点失效，已整体撤回恢复单线路。重做前先定位回归根因（怀疑方向：payload 结构变更的序列化兼容、CycleButton 动态重建时机与 switchDetail 可见性交互、与 HTTP 直取路径的叠加影响），并准备一个多线路站点（如 DM84 三线路）+ 单线路站点的双重回归验证
- [x] **播放器式 GUI**: 右键屏幕打开主界面（搜索流/视频实时预览/选集/番剧简介/进度条拖动/控制栏/直链输入），搜源按钮跳过 bgm 直接搜规则源，搜索状态会话内持久化，isPauseScreen=false 不暂停单人世界
- [x] **GUI 通用网络通道**: GuiActionPacket/GuiDataPacket + GuiProtocol/GuiPayloads，新增 GUI 操作零包成本；服务端 GuiRequestHandlers 与聊天命令共用会话缓存和 resultId
- [x] **播放多级容错**: HTTP 直取播放页解析直链优先（java 路径与浏览器路径对源站可达性互补）→ MCEF 常驻浏览器嗅探（UA 伪装+Cookie 保留+接管式并发+取消入口）→ 重试归因提示；带签名 query 的直链去参后直判快速播放
- [x] **Cookie 桥接**: 嗅探期间收割 document.cookie 按 host 存入 BrowserCookieStore，RuleRequestEnhancer 钩子使直取/规则请求成对附加 Cookie+伪装 UA
- [x] **停止屏幕全量通知**: GUI 停止屏幕向全部观看者发 PlayStopPacket + 聊天提示（原 /kazumi screen stop 不通知观看者端）
- [ ] **渲染性能优化**: 多屏幕同时播放时帧率优化

## BUG

- [ ] **多屏幕同时播放**: 未充分测试多屏幕同时播放的稳定性
- [ ] **音响重复连接**: 同一音响重复连接同一屏幕——目前无去重提示
- [ ] **多人同时开 GUI 的状态陈旧**: 多人共享一块屏幕时，GUI 底部状态栏文字只反映本客户端触发过的操作，他人换集/暂停不会更新文字（预览画面/时间/暂停状态实时，数据层无冲突，最后操作者赢为预期语义）——可改进：播放标题写入 BE NBT 随同步广播，或从 Road JSON+EpisodeIndex 推断当前集名；如需防陌生人乱控可加"观看者/OP 可控"权限
- [ ] **视频源反爬 (tvtfun)**: tvtfun 规则已被官方标记 deprecated（站点加反爬，Kazumi App 同样解析失败）——依赖规则更新或站点放宽
- [ ] **嗅探健壮性收尾**: 已落地——停止/破坏屏幕取消在途嗅探（cancelActiveSniff）、带签名 query 的直链直判快速播放、接管式并发取代在途任务。剩余：接管/取消路径需实机回归；Cookie 桥接的 cf_clearance 时效与刷新策略未验证
- [~] **Cloudflare 挑战页卡死嗅探 (dmbus/DM84)**: 已落地——常驻共享浏览器（Cookie 跨嗅探保留）、UA 伪装（HTTP头+JS侧+mcef.properties 异常值强制重写，含空闲期）、HTTP 直取播放页解析直链优先（java 路径与浏览器路径可达性不同，互补）、Cookie 桥接（收割 document.cookie 按域名存储，直取/规则请求成对附加 Cookie+伪装 UA——clearance cookie 与签发 UA 绑定）、失败提示区分 CF 拦截、章节/直取请求补 Referer。**实测结论：dmbus 源站分钟级闪断(522)为站点侧问题，与客户端无关**。剩余候选：规则 antiCrawler 配置（验证页检测+自动点击+自定义 JS）
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

- [~] **MCEF 浏览器生命周期管理**: `mcefLifecycle` 配置项仍未接入；但 VideoSniffer 已实现常驻共享浏览器（等效 PERSISTENT：仅创建一次、间隙导航 about:blank、Cookie 跨嗅探保留）——MCEFBrowserLifecycle 占位类可删除或改造为该逻辑的封装
- [ ] **弹幕支持**: 从 Kazumi App 移植弹幕渲染
- [ ] **画质选择**: WaterMedia 多 quality 选择

## 项目重构

- [x] **服务端/客户端拆分**: 拆分为 `common`/`client`/`server` 三个 source set，构建输出 `kazumiplayer-server`/`kazumiplayer-client` 两份 jar。客户端类（WaterMedia/MCEF）不再出现在服务端 jar 中
- [x] **统一消息系统重构**: 新增 `KazumiMessages` 统一前缀与分级颜色，替换命令层/客户端全部散落消息（含 sendFailure/sendSystemMessage/客户端 chat），删除 ChatComponentUtil 未使用的 info/error 死代码路径
- [x] **网络包分派注册表化**: Server/ClientPacketHandlers 的 if-else instanceof 链改为 `Map<Class<?>, Handler>` 静态注册表，新增操作只需 static 块加一行
- [x] **重复逻辑抽取**: `JsonUtil.parseFirstRoad`（Road 解析）、`SyncGroup.watchingPlayersString()`（观看者序列化）、`KazumiMessages.formatMs`（时间格式化）、`ScreenCommands` 复用 `getTargetScreen`
- [x] **seek 权威状态同步**: handlePlaybackControl 的 seek 现经 `applySeek` 同步调用 `updateState` 更新 SyncGroupManager 权威位置并立即广播——修复 GUI 进度条拖动后被周期广播拉回的问题
- [ ] **RuleManager 重复实例**: `SyncGroupManager.onPlayerJoin` 每次玩家进服 `new RuleManager().loadAll()`，绕过 KazumiPlayerServer 已初始化实例——应注入复用单例
- [ ] **ClientDisconnectHandler 拆分（god class）**: 259 行混杂鼠标恢复/命令注册/生命周期/核心调度四大职责——拆为 MouseGrabRestorer / ClientCommandRegistration / ClientLifecycleHandler / ClientPlaybackScheduler
- [ ] **播放器启动逻辑去重**: `onClientTick` 与 `handlePlayStart` 两处各自 `new PlaybackManager()` 启动流程重复；`handlePlayStart` 服务端从不发 PlayStartPacket（死路径）——提取 `startPlayback()` 统一入口
- [ ] **删除死代码**: `MCEFBrowserLifecycle`、`dto/VideoSource` 无任何调用方；`PlayStateListener` 接口+WaterMediaPlayer listener 列表从未注册——确认后删除
- [x] **VideoSniffer handler 泄漏**: 常驻浏览器重构后 handler 仅在浏览器创建时注册一次且永不移除（回调路由到 activeFuture），原 add/remove 引用不对称问题不复存在
- [ ] **三份 dev mods.toml 去重**: main/server/client resources 下 `META-INF/neoforge.mods.toml` 内容一字不差——保留一份或 processResources 动态生成
- [ ] **build.gradle configurations.all 篡改**: 全局强制 Usage=JAVA_RUNTIME 破坏变体感知解析——排查 moddev universalJar 变体冲突根因，改为 configuration 级 attributes
- [ ] **PlaybackControlPacket action 改枚举**: 用 String 表示 action（next/prev/seek_forward 等）无编译期检查——定义 `PlaybackAction` enum + STRING_UTF8 映射 codec
- [ ] **RuleEngine 策略接口**: RuleEngine if-else 分派 + 两策略参数类型不统一（RuleExecutionConfig vs Rule）——抽取 `RuleStrategy` 接口统一参数
- [ ] **setPlayback/setPlaybackFull 重叠**: 两个方法职责重叠，调用方易误用——合并或加明确 Javadoc
- [ ] **getScreenId 延迟副作用**: getter 首次调用生成 UUID 并 markDirty 触发网络同步——提供 ensureScreenId() 显式初始化
- [ ] **ConnectedSpeakers 编解码去重**: BlockPos 列表编码在 saveAdditional/loadAdditional/getUpdateTag 三处重复——提取 encode/decode 工具
- [ ] **BE 双轨序列化统一**: load/saveAdditional 用 ValueOutput，getUpdateTag 用旧式 CompoundTag——统一或至少抽字段名常量
- [ ] **SpeakerBlockEntity chunk 加载误清连接**: loadAdditional 中 `getLinkedScreen()==null` 自动 clearLink，屏幕 chunk 未加载时误清——推迟到服务端 tick 验证
- [ ] **SpeakerConnectPacket UUID 序列化**: STRING_UTF8 存 36 字符膨胀——改 mostSignificantBits+leastSignificantBits VAR_LONG
- [ ] **HttpUtil.fetch 双重异步**: runAsync 再包一层 CompletableFuture——直接返回 supplyAsync 结果简化异常链
- [ ] **PlayCommands 拆分**: 515 行含 10+ 子命令+辅助函数，stop/leave 重复、切集逻辑与 ServerPacketHandlers 重复——拆命令类+EpisodeSwitcher 服务
- [ ] **搜索缓存抽象**: SearchResultCache / SearchSessionCache / RuleSearchSessionCache 结构重复（Map+定时清理）——提取 `TimedCache<T>` 泛型基类；shutdown() 从未调用
- [ ] **BangumiApi HttpClient 复用**: 每次 search() new HttpClient/Gson——提升为类级 final 字段
- [ ] **SNIFF_SCRIPT 外部化**: 70 行 JS 内联字符串——提取为 resources 资源文件
- [ ] **extractRenderState 跨包引用**: screen 包完全限定名调用 client.ScreenPlayerManager——解耦（注入播放器或独立查询机制）
- [ ] **onClientTick 魔法数字**: 3000/8000/5000/800/500/100 散落——提为 TimingConstants 命名常量
- [ ] **多版本支持**: 适配不同 Minecraft 版本
- [ ] **多加载器支持**: 除 NeoForge 外支持 Fabric/Quilt
