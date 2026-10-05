# KazumiPlayer

Minecraft 视频屏幕 Mod，基于 NeoForge 26.1.2。在游戏世界内创建视频屏幕，搜索 bgm.tv 番剧数据库和 KazumiRules 社区番剧源，通过 WaterMedia (FFmpeg) 和 MCEF (Chromium) 实现多人同步观影。

## 功能

- **播放器式 GUI**：右键屏幕方块打开——bgm.tv 搜索、规则源搜索（「来源▾」下拉限定单源；**流式增量显示**：每个源搜完立即上列表，慢源不阻塞快源）、选集、播放线路切换、视频实时预览、番剧简介、进度条拖动、播放控制、直链输入、观看玩家列表，打开不暂停游戏世界
- **番剧搜索**：bgm.tv API 搜索番剧元数据 + KazumiRules 规则引擎查源站（`search-rule` 支持会话翻页）
- **视频播放**：WaterMedia V3 (FFmpeg) 解码；直链三级容错——java HTTP 直取播放页 → MCEF 浏览器嗅探（JS 嗅探 + 原生网络层拦截，支持 iframe 嵌套解析站）→ 自动重试；常驻浏览器保留 Cookie 跨集直通，UA 伪装过 Cloudflare 防护，任意分辨率自适应；**m3u8 直链**按直播流处理（绕过时钟同步直连播放，播放失败自动跳过队列下一项），粘贴 IPTV 频道目录 m3u8 自动解析为频道列表（每条可「加」入队列或「切」立即换台）
- **B 站支持**：粘贴 B 站视频（BV/av，含 ?p= 分 P）或直播间链接即可播放——**解析由服务端代理执行**（B 站凭据只留在服务端配置里，不下发到客户端），服务端把有时效的视频直链/直播 m3u8 下发给客户端播放；服务端未配置或解析失败时，客户端可用本机凭据回落自解析。视频取 html5 单流 mp4（音视频合一，自动绕开网页嗅探），直播间按直播流处理（无时间轴、不参与同步）；支持 b23.tv 短链自动展开；**清晰度切换**：播放器「线路」右侧下拉按解析出的档位即时切换（仅本端生效、切完回到原进度）；**扫码登录**（本机凭据，仅回落解析用）：配置界面「B 站」分类一键打开二维码，另有手工粘贴、「复制凭据」、「清除本地登录」
- **多人同步**：NBT 驱动播放状态，服务端单调钟计时 + 客户端钟差校准 + **首帧锚定**（以首个出画观看者的真实位置校准组时钟），自动同步进度/切集/暂停；**解析状态上报**——观看者嗅探进度经服务端聚合同步全组，GUI 观看者列表按人标注「解析中/就绪/失败」，预览区、全屏 HUD 与世界屏幕面同步显示「解析中 · 已就绪 n/m」，播放前即可确认其他人是否就绪
- **剧集管理**：自动下一集、手动切集、时间快进/快退/跳转
- **屏幕属性调整**：GUI 预览区左上角「设置」进入独立界面——朝向、宽高、XYZ 偏移步进或直接输入，即时生效且背景透明可对照世界中的屏幕实际位置；未播放时世界内显示实心预览框标记屏幕面，播放中可开启边框叠加
- **画面适配**：屏幕比例与视频不一致时可配置「拉伸填满」或「等比缩放居中」
- **直链队列**：播放中粘贴直链自动排队（顶栏按钮「播放/排队」智能切换），队列面板支持切播/插队/删除，播完自动连播
- **全屏观影**：GUI 预览右下角一键进入——画面铺满（覆盖度/不透明度可配），底部悬浮控制条（暂停/±10s/可拖动进度条/每屏音量）+ 快捷键（空格暂停、←→±10s、↑↓音量、M 静音），重缓冲冻结提示，聊天键 T 原生可用，控制条与退出按钮鼠标静止 3s 淡出
- **进度条**：视频下方实时进度条
- **实用物品**（创造物品栏「KazumiPlayer」标签页）：
  - **屏幕遥控器**：Shift+右键绑定屏幕，右键远程打开播放器 GUI
  - **远程观影器**：右键跳过 GUI 直接全屏观看绑定屏幕（纯观看无操作）
  - **规则管理器**：右键打开规则管理界面——远程仓库规则列表、拉取/删除规则、连通性延迟测试、弃用检测
- **规则测试**：客户端 `/krule test` 直接测试规则连通性
- **规则管理**：安装/更新/删除/列表，已弃用（deprecated）规则在使用/列表时黄色警告
- **配置系统**：Cloth Config 配置界面（可选），13 个日志分类 DEBUG 开关即时生效
- **统一消息**：所有聊天提示带绿色 `[KazumiPlayer]` 前缀，按严重程度着色（成功绿/信息白/警告黄/错误红）
- **多语言 (i18n)**：GUI 与全部命令反馈走语言文件（zh_cn / en_us 各 300 键），服务端发送翻译键由客户端按玩家语言渲染
- **GPL-3.0 开源**

## 安装

**客户端**需安装以下前置 Mod，**服务端无需任何前置**：

| 前置 | 用途 |
|------|------|
| MCEF 2.2.0 | Chromium 视频嗅探 |
| WaterMedia 3.0.0.22 | FFmpeg 解码 + GL 纹理 |
| Cloth Config 26.1.154（可选） | 游戏内配置界面，不装不影响功能 |

## 配置

配置文件位于 `.minecraft/config/`（单人/服务器共用同一份）：

| 文件 | 内容 |
|------|------|
| `kazumiplayer.toml` | 服务端通用配置 + 日志分类 DEBUG 开关（`log.debugGeneral` 等 13 项） |
| `kazumiplayer-client.toml` | 客户端播放/嗅探配置（音量、并发数、超时、同步等） |

**日志开关**：默认全部关闭，开启后对应分类输出 DEBUG 诊断日志并即时生效，无需重启。

安装了 Cloth Config 后，可在 **Mods 列表 → KazumiPlayer → Config** 打开配置界面修改并保存（保存即写盘，重启保留）。

## 使用

1. 放置视频屏幕方块（或 `/kazumi screen create` 创建大屏）
2. **右键屏幕方块打开播放器 GUI**：搜索番剧 → 点击结果在全部规则源中搜索 → 点条目出现选集 → 点集数播放；顶部输入框可直接粘贴视频直链（播放中粘贴自动排队）
3. 其他玩家对同一屏幕操作即可同步观看（或 `/kazumi join` 加入同步组）
4. 底部控制栏：暂停/切集/±10s/进度条拖动/加入同步/离开/停止屏幕
5. **实用物品**（创造模式「KazumiPlayer」标签页或 `/give` 获取）：
   - **屏幕遥控器**：Shift+右键屏幕绑定 → 右键任意处远程打开该屏幕的播放器 GUI（屏幕被破坏会提示）
   - **远程观影器**：同上绑定，右键直接进入全屏观影（纯观看，退出直接回世界）
   - **规则管理器**：右键打开规则管理界面（列表/拉取/删除/测延迟/弃用检测）

所有聊天命令保留可用（见下表），GUI 与命令共用搜索会话与结果缓存。

## 命令

### 搜索
| 命令 | 说明 |
|------|------|
| `/kazumi search <关键词>` | bgm.tv 搜索番剧 |
| `/kazumi search-rule <规则> <番剧名>` | 指定规则搜索源站 |
| `/kazumi search-rule all <番剧名>` | 所有规则搜索源站 |

### 播放
| 命令 | 说明 |
|------|------|
| `/kazumi episodes <规则> <结果ID>` | 查看集数列表 |
| `/kazumi play <规则> <结果ID> <集数> [线路]` | 播放指定集 |
| `/kazumi play url <URL>` | 直接播放 URL |
| `/kazumi play join` | 加入同步播放 |
| `/kazumi play leave` / `stop` | 离开同步 / 停止本端播放 |

### 控制
| 命令 | 说明 |
|------|------|
| `/kazumi control next` | 下一集 |
| `/kazumi control prev` | 上一集 |
| `/kazumi control pause` | 暂停 |
| `/kazumi control resume` | 恢复 |
| `/kazumi control time forward <秒>` | 快进 |
| `/kazumi control time back <秒>` | 快退 |
| `/kazumi control time goto <分:秒>` | 跳转到指定时间 |

> 兼容别名：`/kazumi next` `prev` `pause` `resume` `time …` 与 `/kazumi play-url` `/kazumi join` 仍可用（推荐逐步迁移到上表新写法）。

### 屏幕
| 命令 | 说明 |
|------|------|
| `/kazumi screen create <坐标> <宽> <高> <朝向>` | 创建屏幕（宽高 0.5~128） |
| `/kazumi screen stop` | 停止屏幕 |

### 规则管理
| 命令 | 说明 |
|------|------|
| `/kazumi rule pull <规则名>` | 安装规则 |
| `/kazumi rule pull-all` | 安装全部规则 |
| `/kazumi rule update [规则名]` | 更新已安装规则（不填则更新全部） |
| `/kazumi rule list [page]` | 列出规则 |
| `/kazumi rule delete <规则名>` | 删除规则 |
| `/kazumi rule test <规则名>` | 服务端测试规则 |
| `/krule test <规则名>` | 客户端测试规则 |

## 构建

```bash
./gradlew serverJar   # 构建服务端 jar → build/libs/kazumiplayer-server-0.1-alpha.jar
./gradlew clientJar   # 构建客户端 jar → build/libs/kazumiplayer-client-0.1-alpha.jar
./gradlew assemble    # 同时产出服务端 + 客户端两个正式 jar
./gradlew runClient # 启动客户端
./gradlew runServer # 启动服务端
```

**服务端**安装 `kazumiplayer-server-0.1-alpha.jar`（无需 MCEF/WaterMedia 前置）；
**客户端**安装 `kazumiplayer-client-0.1-alpha.jar`（需 MCEF 2.2.0 + WaterMedia 3.0.0.22 前置）。

## 免责声明

- 本项目仅供学习交流使用，请勿用于商业用途
- 视频内容来源于第三方网站，本项目不对其合法性、准确性、完整性负责
- 使用者应遵守当地法律法规及内容提供方的服务条款
- 本项目不存储、缓存、分发任何视频内容，仅提供 URL 转发播放功能

## 致谢

- [Kazumi](https://github.com/Predidit/Kazumi) — Flutter 番剧客户端，搜索/规则逻辑移植自此
- [KazumiRules](https://github.com/Predidit/KazumiRules) — 社区维护的番剧源规则
- [Bangumi](https://bgm.tv/) — 番剧数据库开放 API

## 许可证

GPL-3.0-only，衍生自 [Kazumi](https://github.com/Predidit/Kazumi) (GPLv3)。
