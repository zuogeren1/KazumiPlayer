# TODO

## 音响系统（当前占位，待 WaterMedia v3 API 完善后实现）

- [ ] **音响发声**: WaterMedia v3 当前 `MediaAPI.createPlayer(mrl, gfx, sfx)` 不支持 video/audio 独立开关。`SpeakerBlockEntity.startAudio()` 为占位
- [ ] **连接时屏幕静音**: 有音响连接时屏幕 `player.mute(true)`，断开最后一个音响后恢复
- [ ] **漂移校正测试**: `SpeakerBlockEntity.clientTick()` 中的周期校正逻辑未经实测
- [ ] **音响自定义纹理**: 当前用 `minecraft:block/note_block` 占位
- [ ] **连接工具空手右键音响断开**: 当前只实现了连接，断开需挖掉音响或屏幕
- [ ] **音响方块合成配方**

## 功能增强

- [ ] **配置功能完善**
- [ ] **KazumiLog 分类开关**: 配置文件控制各分类日志级别
- [ ] **渲染性能优化**: 多屏幕同时播放时帧率优化

## BUG

- [ ] **多屏幕同时播放**: 未充分测试多屏幕同时播放的稳定性
- [ ] **音响重复连接**: 同一音响重复连接同一屏幕——目前无去重提示

## 后续大功能

- [ ] **MCEF 浏览器生命周期管理**: `MCEFBrowserLifecycle` 为 Phase 0 占位
- [ ] **弹幕支持**: 从 Kazumi App 移植弹幕渲染
- [ ] **画质选择**: WaterMedia 多 quality 选择

## 项目重构

- [x] **服务端/客户端拆分**: 拆分为 `common`/`client`/`server` 三个 source set，构建输出 `kazumiplayer-server`/`kazumiplayer-client` 两份 jar。客户端类（WaterMedia/MCEF）不再出现在服务端 jar 中
- [ ] **多版本支持**: 适配不同 Minecraft 版本
- [ ] **多加载器支持**: 除 NeoForge 外支持 Fabric/Quilt
