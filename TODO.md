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
- [ ] **渲染性能优化**: 多屏幕同时播放时帧率优化

## BUG

- [ ] **多屏幕同时播放**: 未充分测试多屏幕同时播放的稳定性
- [ ] **音响重复连接**: 同一音响重复连接同一屏幕——目前无去重提示
- [x] **鼠标脱离准心**: 网页源嗅探/播放后鼠标指针脱离准心——GLFW 真实光标检测 + 嗅探完成立即强制抓回，已实机确认
- [x] **视频开头闪烁**: 播放器启动期被同步 seek 反复打断 + 无帧时填充占位色——保留上一帧 + 启动稳定期跳过漂移 seek
- [x] **配置重启重置**: Cloth 保存只改内存不写盘——保存时显式 `ModConfigSpec.save()` 持久化
- [x] **退出游戏音频残留**: `stopAllActive` 先删条目后 stopAll 导致播放器从未停止——`remove()` 内部先 stop + `ServerStoppedEvent` 兜底，已实机确认
- [x] **屏幕面被视锥剔除**: BE 包围盒默认仅方块本体，覆盖 `getRenderBoundingBox` 包含屏幕面，已实机确认
- [x] **同步 seek 反复重缓冲**: 漂移校正加 seek 后冷却 + 播放器时间接近目标时跳过重复 seek（防御性修复，待复现验证）
- [x] **search-rule 翻页失效**: 翻页命令硬编码"翻页"关键词导致"未找到结果"——新增 `RuleSearchSessionCache` 会话缓存，翻页从缓存读
- [x] **待机组误判播放中**: 屏幕未播放时 join 创建待机组，重复 join 走"加入现有组"分支写入空 URL/递增位置——按 `url.isEmpty()` 分支处理
- [x] **空 URL 标记播放中**: `setPlayback` 无条件设 `PLAYING`——空 URL 时保持 `IDLE` 并清空位置
- [ ] **视频源反爬 (tvtfun)**: tvtfun 规则已被官方标记 deprecated（站点加反爬，Kazumi App 同样解析失败）——依赖规则更新或站点放宽

## 后续大功能

- [~] **MCEF 浏览器生命周期管理**: 已有 `mcefLifecycle` 配置项（ON_DEMAND/PERSISTENT），`MCEFBrowserLifecycle` 仍为占位，未接入实际使用
- [ ] **弹幕支持**: 从 Kazumi App 移植弹幕渲染
- [ ] **画质选择**: WaterMedia 多 quality 选择

## 项目重构

- [x] **服务端/客户端拆分**: 拆分为 `common`/`client`/`server` 三个 source set，构建输出 `kazumiplayer-server`/`kazumiplayer-client` 两份 jar。客户端类（WaterMedia/MCEF）不再出现在服务端 jar 中
- [x] **统一消息系统重构**: 新增 `KazumiMessages` 统一前缀与分级颜色，替换命令层/客户端全部散落消息（含 sendFailure/sendSystemMessage/客户端 chat），删除 ChatComponentUtil 未使用的 info/error 死代码路径
- [x] **重复逻辑抽取**: `JsonUtil.parseFirstRoad`（Road 解析）、`SyncGroup.watchingPlayersString()`（观看者序列化）、`KazumiMessages.formatMs`（时间格式化）、`ScreenCommands` 复用 `getTargetScreen`
- [ ] **多版本支持**: 适配不同 Minecraft 版本
- [ ] **多加载器支持**: 除 NeoForge 外支持 Fabric/Quilt
