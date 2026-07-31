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
- [x] **KazumiLog 分类开关**: 12 个分类独立 logger 名，配置文件/配置界面控制 DEBUG 级别并即时生效
- [ ] **渲染性能优化**: 多屏幕同时播放时帧率优化

## BUG

- [ ] **多屏幕同时播放**: 未充分测试多屏幕同时播放的稳定性
- [ ] **音响重复连接**: 同一音响重复连接同一屏幕——目前无去重提示
- [x] **鼠标脱离准心**: 网页源嗅探/播放后鼠标指针脱离准心——已加自动恢复逻辑（需实机确认，诊断日志默认关闭，开启 general 分类 DEBUG 可观察）

## 后续大功能

- [~] **MCEF 浏览器生命周期管理**: 已有 `mcefLifecycle` 配置项（ON_DEMAND/PERSISTENT），`MCEFBrowserLifecycle` 仍为占位，未接入实际使用
- [ ] **弹幕支持**: 从 Kazumi App 移植弹幕渲染
- [ ] **画质选择**: WaterMedia 多 quality 选择

## 项目重构

- [x] **服务端/客户端拆分**: 拆分为 `common`/`client`/`server` 三个 source set，构建输出 `kazumiplayer-server`/`kazumiplayer-client` 两份 jar。客户端类（WaterMedia/MCEF）不再出现在服务端 jar 中
- [ ] **多版本支持**: 适配不同 Minecraft 版本
- [ ] **多加载器支持**: 除 NeoForge 外支持 Fabric/Quilt
