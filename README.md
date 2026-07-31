# KazumiPlayer

Minecraft 视频屏幕 Mod，基于 NeoForge 26.1.2。在游戏世界内创建视频屏幕，搜索 bgm.tv 番剧数据库和 KazumiRules 社区番剧源，通过 WaterMedia (FFmpeg) 和 MCEF (Chromium) 实现多人同步观影。

## 功能

- **番剧搜索**：bgm.tv API 搜索番剧元数据 + KazumiRules 规则引擎查源站
- **视频播放**：WaterMedia V3 (FFmpeg) 解码，MCEF 浏览器嗅探提取视频直链（JS 嗅探 + 原生网络层拦截，支持 iframe 嵌套解析站，任意分辨率自适应）
- **多人同步**：NBT 驱动播放状态，服务端计时，自动同步进度/切集/暂停
- **剧集管理**：自动下一集、手动切集、时间快进/快退/跳转
- **进度条**：视频下方实时进度条
- **规则测试**：客户端 `/krule test` 直接测试规则连通性
- **配置系统**：Cloth Config 配置界面（可选），13 个日志分类 DEBUG 开关即时生效
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
| `/kazumi play <规则> <结果ID> <集数>` | 播放指定集 |
| `/kazumi play-url <URL>` | 直接播放 URL |
| `/kazumi join` | 加入同步播放 |
| `/kazumi play leave` | 离开同步播放 |

### 控制
| 命令 | 说明 |
|------|------|
| `/kazumi next` | 下一集 |
| `/kazumi prev` | 上一集 |
| `/kazumi pause` | 暂停 |
| `/kazumi resume` | 恢复 |
| `/kazumi time forward <秒>` | 快进 |
| `/kazumi time back <秒>` | 快退 |
| `/kazumi time goto <分:秒>` | 跳转到指定时间 |

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
