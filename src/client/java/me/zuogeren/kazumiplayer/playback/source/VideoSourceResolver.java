package me.zuogeren.kazumiplayer.playback.source;

import me.zuogeren.kazumiplayer.client.KazumiClientMessages;

import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.client.ScreenPlayerManager;
import me.zuogeren.kazumiplayer.bilibili.BilibiliApi;
import me.zuogeren.kazumiplayer.client.BilibiliCredentials;
import me.zuogeren.kazumiplayer.client.BilibiliQualityPrefs;
import me.zuogeren.kazumiplayer.util.BilibiliUrls;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.KazumiMessages;

import me.zuogeren.kazumiplayer.playback.WaterMediaPlayer;
import me.zuogeren.kazumiplayer.screen.VideoScreenBlockEntity;
import me.zuogeren.kazumiplayer.screen.VideoState;
import net.minecraft.client.Minecraft;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 视频源解析与播放编排——对齐 Kazumi lib/pages/video/video_controller.dart 的解析用法。
 *
 * 流程：直链直判 → 租约池获取解析器 → WebView/MCEF 解析 → WaterMedia 播放。
 * 与蓝本的差异仅一处：MC 无"重试"按钮，解析失败自动重试一次。
 */
public final class VideoSourceResolver {

    private static final VideoSourceResolver INSTANCE = new VideoSourceResolver();
    private static final int MAX_ATTEMPTS = 2;

    public static VideoSourceResolver getInstance() {
        return INSTANCE;
    }

    private final VideoSourceResolverPool pool = new VideoSourceResolverPool();
    private volatile boolean poolSized;

    /** 预解析结果缓存：每屏至多一条（下一集），切换到对应 URL 时直接消费免嗅探 */
    private final java.util.Map<String, PrefetchEntry> prefetches = new java.util.concurrent.ConcurrentHashMap<>();

    /** 预解析结果有效期：嗅探产物常含 CDN 时效令牌，超期不再复用 */
    private static final long PREFETCH_TTL_MS = 300_000L;

    private record PrefetchEntry(String episodeUrl, long createdAt,
                                 java.util.concurrent.CompletableFuture<VideoSource> future) {}

    private VideoSourceResolver() {}

    /**
     * 发起播放：同步返回播放器实例供调用方登记（启动快照 seek/pause 依赖立即可用的实例），
     * 解析与起播异步完成。直链直接起播，网页型 URL 走嗅探解析。
     */
    public WaterMediaPlayer beginPlayback(VideoScreenBlockEntity screen, String episodeUrl) {
        sizePoolOnce();
        screen.setVideoState(VideoState.LOADING);
        KazumiLog.sniff.info("[source] begin playback at {} url={}", screen.getBlockPos(), episodeUrl);
        WaterMediaPlayer player = new WaterMediaPlayer();
        // 会话身份：解析是异步的，完成时据此判断"这次启动是否仍然被需要"
        // （条目被 stopAll/remove 置空 player、或被新播放器实例顶替 → 本次结果作废）
        ScreenPlayerManager.ScreenPlayer session = ScreenPlayerManager.get(screen.getBlockPos());

        // B 站页面链接：WaterMedia 内置平台解析直接产出可播流（视频/直播），
        // 嗅探浏览器对 B 站 DASH 播放页拿不到可用直链，因此必须绕开嗅探
        if (BilibiliUrls.isBilibiliUrl(episodeUrl)) {
            KazumiLog.sniff.info("[source] bilibili URL, using built-in platform resolver");
            resolveBilibili(screen, episodeUrl, player, session);
            return player;
        }

        if (looksLikeDirectVideo(episodeUrl)) {
            KazumiLog.sniff.info("[source] direct video URL, playing without sniffing");
            // .m3u8/.m3u 播放列表直链按 HLS 直播流处理：置直连模式绕过服务端时钟同步
            // （live 无稳定时间轴，同步校正/暂停广播只会干扰缓冲，GUI 时间轴控制随之禁用）
            if (isLivePlaylistUrl(episodeUrl)) {
                session.bypassSync = true;
                KazumiLog.sniff.info("[source] live playlist URL, sync bypassed");
            }
            player.play(episodeUrl);
            screen.setVideoState(VideoState.PLAYING);
            reportResolveStatus(screen, me.zuogeren.kazumiplayer.network.packet.ResolveStatusPacket.STATUS_READY);
            return player;
        }

        reportResolveStatus(screen, me.zuogeren.kazumiplayer.network.packet.ResolveStatusPacket.STATUS_RESOLVING);
        resolveWithRetry(screen, episodeUrl, player, session, 0);
        return player;
    }

    /**
     * B 站链接起播：向服务端请求代理解析（凭据只留在服务端，一次解析全组可复用），
     * 服务端失败/超时/未连接时用本地凭据回落自解析。短链先跟随重定向展开。
     * 直播间置 bypassSync：直播无稳定时间轴，时钟同步与时间轴控制不适用。
     */
    private void resolveBilibili(VideoScreenBlockEntity screen, String episodeUrl,
            WaterMediaPlayer player, ScreenPlayerManager.ScreenPlayer session) {
        reportResolveStatus(screen, me.zuogeren.kazumiplayer.network.packet.ResolveStatusPacket.STATUS_RESOLVING);
        if (!BilibiliUrls.isShortLink(episodeUrl)) {
            requestServerResolve(screen, episodeUrl, player, session);
            return;
        }
        KazumiLog.sniff.info("[source] expanding bilibili short link: {}", episodeUrl);
        HttpUtil.resolveFinalUrl(episodeUrl)
            .thenAccept(finalUrl -> {
                KazumiLog.sniff.info("[source] bilibili short link resolved to {}", finalUrl);
                // 展开回调晚于调度器登记，可继续走解析流程
                Minecraft.getInstance().execute(() -> {
                    if (session.player != player || screen.isRemoved()) return;
                    requestServerResolve(screen, finalUrl, player, session);
                });
            })
            .exceptionally(t -> {
                failPlayback(screen, unwrap(t));
                return null;
            });
    }

    /** 在途的服务端解析请求（每屏唯一：新请求顶替旧请求，迟到结果按 requestId 丢弃） */
    private record PendingResolve(long requestId, String pageUrl, VideoScreenBlockEntity screen,
                                  WaterMediaPlayer player, ScreenPlayerManager.ScreenPlayer session,
                                  boolean live) {}

    private final java.util.Map<String, PendingResolve> pendingResolves = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong resolveSeq = new java.util.concurrent.atomic.AtomicLong();

    /** 服务端代理解析超时（超时后回落本端解析，避免网络异常时永久等待） */
    private static final long SERVER_RESOLVE_TIMEOUT_MS = 20_000L;
    private static final java.util.concurrent.ScheduledExecutorService RESOLVE_TIMEOUT =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KazumiPlayer-BiliResolve-Timeout");
            t.setDaemon(true);
            return t;
        });

    /** 发起服务端代理解析；直播链接在此置 bypassSync（早于起播生效） */
    private void requestServerResolve(VideoScreenBlockEntity screen, String pageUrl,
            WaterMediaPlayer player, ScreenPlayerManager.ScreenPlayer session) {
        net.minecraft.core.BlockPos pos = screen.getBlockPos();
        boolean live = BilibiliApi.liveRoomId(pageUrl) > 0;
        if (live) {
            session.bypassSync = true;
            KazumiLog.sniff.info("[source] bilibili live room, sync bypassed");
        }
        int qn = BilibiliQualityPrefs.preferredQn(pos);
        long requestId = resolveSeq.incrementAndGet();
        PendingResolve pending = new PendingResolve(requestId, pageUrl, screen, player, session, live);
        pendingResolves.put(pos.toString(), pending);

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            pendingResolves.remove(pos.toString());
            failPlayback(screen, new IllegalStateException("未连接服务端"));
            return;
        }
        mc.getConnection().send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
            new me.zuogeren.kazumiplayer.network.packet.BilibiliResolveRequestPacket(
                pos, screen.getScreenId(), requestId, pageUrl, qn)));
        KazumiLog.sniff.info("[source] server-side bilibili resolve requested: {} (qn={}, live={})",
            pageUrl, qn, live);
        RESOLVE_TIMEOUT.schedule(() -> Minecraft.getInstance().execute(() -> {
            PendingResolve current = pendingResolves.get(pos.toString());
            if (current == null || current.requestId() != requestId) return;
            pendingResolves.remove(pos.toString());
            KazumiLog.sniff.warn("[source] server-side bilibili resolve timed out ({}ms), falling back",
                SERVER_RESOLVE_TIMEOUT_MS);
            fallbackLocalResolve(pending, "服务端解析超时");
        }), SERVER_RESOLVE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 服务端解析结果（ClientPacketHandlers 转发）；与在途请求不匹配（换集/停止/迟到包）直接丢弃 */
    public void onServerResolveResult(me.zuogeren.kazumiplayer.network.packet.BilibiliResolveResultPacket packet) {
        String key = packet.screenPos().toString();
        PendingResolve pending = pendingResolves.get(key);
        if (pending == null || pending.requestId() != packet.requestId()) return;
        pendingResolves.remove(key);
        if (!pending.pageUrl().equals(packet.pageUrl())) return;
        if (packet.ok()) {
            applyStream(pending, packet.url(), packet.qualities(), packet.currentQn(), "server");
        } else {
            KazumiLog.sniff.warn("[source] server-side bilibili resolve failed ({}), falling back",
                packet.error());
            fallbackLocalResolve(pending, packet.error());
        }
    }

    /** 本端回落解析：用本地凭据（可能为空 = 匿名，清晰度上限 720P）直接调 B 站接口 */
    private void fallbackLocalResolve(PendingResolve pending, String reason) {
        net.minecraft.core.BlockPos pos = pending.screen().getBlockPos();
        int qn = BilibiliQualityPrefs.preferredQn(pos);
        String cookie = BilibiliCredentials.get();
        KazumiLog.sniff.warn("[source] local bilibili fallback ({}), credentials={}",
            reason, cookie.isEmpty() ? "anonymous" : "local");
        var future = pending.live()
            ? BilibiliApi.resolveLive(pending.pageUrl(), qn, cookie)
            : BilibiliApi.resolveVideo(pending.pageUrl(), qn, cookie);
        future.whenComplete((stream, t) -> {
            if (t != null) {
                failPlayback(pending.screen(), unwrap(t));
                return;
            }
            applyStream(pending, stream.url(), BilibiliApi.encodeQualities(stream.qualities()),
                stream.currentQn(), "local");
        });
    }

    /** 记录档位表并受守卫起播（服务端与本端两条来源共用） */
    private void applyStream(PendingResolve pending, String url, String encodedQualities,
            int currentQn, String origin) {
        BilibiliQualityPrefs.setInfo(pending.screen().getBlockPos(),
            new BilibiliQualityPrefs.Info(BilibiliApi.decodeQualities(encodedQualities), currentQn));
        KazumiLog.sniff.info("[source] bilibili stream resolved via {} (qn={}): {}", origin, currentQn, url);
        startWhenValid(pending.screen(), pending.session(), pending.player(), url);
    }

    /** 停止/拆屏时取消全部在途解析（含服务端代理解析请求） */
    public void cancelAllResolves() {
        pool.cancelAll();
        pendingResolves.clear();
    }

    /** 取消指定屏幕的在途解析并回收其租约（URL 切换停旧播放器时调用）。
     * 预解析缓存条目保留：集间切换的主路径恰好在 URL 变更时消费它（在途租约由 pool.cancel 回收，
     * 被取消的 future 留存于条目中，消费端按「已取消→回落常规解析」处理）。 */
    public void cancelResolve(net.minecraft.core.BlockPos pos) {
        pool.cancel(pos.toString());
        pendingResolves.remove(pos.toString());
    }

    /**
     * 预解析下一集：当前集稳定播放时由调度器调用，借用空闲租约提前完成嗅探。
     * 结果缓存于 {@link #prefetches}（每屏一条、按 URL 匹配消费）；租约不足时静默放弃。
     * 直链与直播播放列表不预解析——前者无需嗅探，后者时间轴语义不同。
     */
    public void prefetchNext(net.minecraft.core.BlockPos pos, String episodeUrl) {
        if (episodeUrl == null || episodeUrl.isBlank()
                || looksLikeDirectVideo(episodeUrl) || isLivePlaylistUrl(episodeUrl)) {
            return;
        }
        String key = pos.toString();
        PrefetchEntry existing = prefetches.get(key);
        if (existing != null && existing.episodeUrl().equals(episodeUrl)) return;
        sizePoolOnce();
        VideoSourceResolverPool.Lease lease = pool.tryAcquire(key);
        if (lease == null) return; // 无空闲租约：静默放弃，切换时走常规解析
        long startedAt = System.currentTimeMillis();
        Duration timeout = Duration.ofSeconds(ClientConfig.CONFIG.sniffTimeoutSeconds.get());
        var future = lease.resolve(episodeUrl, false, timeout)
            .whenComplete((v, t) -> pool.release(lease));
        prefetches.put(key, new PrefetchEntry(episodeUrl, startedAt, future));
        future.whenComplete((v, t) -> {
            if (t != null) {
                KazumiLog.sniff.debug("[source] prefetch failed for {} ({}), will resolve on demand",
                    key, unwrap(t).getMessage());
            } else {
                KazumiLog.sniff.info("[source] prefetched next episode for {} in {}ms",
                    key, System.currentTimeMillis() - startedAt);
            }
        });
    }

    private void resolveWithRetry(VideoScreenBlockEntity screen, String episodeUrl,
            WaterMediaPlayer player, ScreenPlayerManager.ScreenPlayer session, int attempt) {
        String key = screen.getBlockPos().toString();

        // 命中预解析缓存：URL 匹配且未过期（嗅探产物常含 CDN 时效令牌）才复用；
        // 不匹配/过期的陈旧条目已随 remove 丢弃，继续走常规解析。
        // 消费的是「现在就要起播」的 URL，因此取消态同样回落常规解析（重新嗅探）而非放弃
        PrefetchEntry pref = prefetches.remove(key);
        if (pref != null && attempt == 0 && pref.episodeUrl().equals(episodeUrl)
                && System.currentTimeMillis() - pref.createdAt() <= PREFETCH_TTL_MS) {
            KazumiLog.sniff.info("[source] using prefetched resolution at {} for {}", key, episodeUrl);
            pref.future()
                .thenAccept(source -> startWhenValid(screen, session, player, source.url()))
                .exceptionally(t -> {
                    // 预解析结果不可用或已被取消：回落常规解析链路（含重试）
                    KazumiLog.sniff.debug("[source] prefetch unusable ({}), resolving on demand",
                        unwrap(t).getMessage());
                    resolveWithRetry(screen, episodeUrl, player, session, 0);
                    return null;
                });
            return;
        }

        // 换集/重播场景：先取消该屏在途解析再取租约
        pool.cancel(key);
        VideoSourceResolverPool.Lease lease = pool.tryAcquire(key);
        if (lease == null) {
            KazumiLog.sniff.warn("[source] resolver pool exhausted, giving up screen {}", key);
            Minecraft.getInstance().execute(() -> {
                screen.setVideoState(VideoState.STOPPED);
                KazumiClientMessages.chatWarn("已达最大同时嗅探数，无法解析该屏幕视频");
            });
            return;
        }

        long startedAt = System.currentTimeMillis();
        Duration timeout = Duration.ofSeconds(ClientConfig.CONFIG.sniffTimeoutSeconds.get());
        KazumiLog.sniff.info("[source] resolving (attempt {}/{}, timeout={}s, key={})",
            attempt + 1, MAX_ATTEMPTS, timeout.toSeconds(), key);
        // useLegacyParser 接线待办：按来源规则的 useLegacyParser 字段传入（需经 NBT/包协议下发）
        lease.resolve(episodeUrl, false, timeout)
            .whenComplete((v, t) -> pool.release(lease))
            .thenAccept(source -> {
                KazumiLog.sniff.info("[source] resolved video URL: {} ({}ms)",
                    source.url(), System.currentTimeMillis() - startedAt);
                startWhenValid(screen, session, player, source.url());
            })
            .exceptionally(t -> {
                Throwable cause = unwrap(t);
                if (cause instanceof VideoSourceResolveException.Cancelled) {
                    KazumiLog.sniff.debug("[source] resolution cancelled for {}", key);
                    return null;
                }
                if (attempt < MAX_ATTEMPTS - 1 && !lease.isCancelled()) {
                    KazumiLog.sniff.warn("[source] resolve failed ({}), retrying...",
                        String.valueOf(cause.getMessage()));
                    resolveWithRetry(screen, episodeUrl, player, session, attempt + 1);
                    return null;
                }
                failPlayback(screen, cause);
                return null;
            });
    }

    /**
     * 异步解析完成的受守卫起播。调用前提：解析回调晚于调用方登记 player（仅嗅探回调与短链展开
     * 满足）——同步起播路径禁止调用：彼时 session.player 尚未赋值，而 Minecraft.execute 在渲染
     * 线程同线程重入执行，校验会先于登记发生并把结果误判为陈旧。
     * 解析期间以下任一情况发生即丢弃结果并回收播放器，
     * 否则会产生无人引用的孤儿播放器（音频持续外漏，只能重启游戏才能停掉）：
     * <ul>
     *   <li>已离开世界（level==null，退主菜单场景）</li>
     *   <li>屏幕方块已拆除（isRemoved）</li>
     *   <li>会话失效：注册表条目被 stopAll/remove 置空、或 player 已被新实例顶替（换片/停止后重启）——
     *       同时天然封死"PlayStopPacket 先 stop、解析回调后 play 复活"的队列竞态窗口</li>
     * </ul>
     */
    private void startWhenValid(VideoScreenBlockEntity screen,
            ScreenPlayerManager.ScreenPlayer session, WaterMediaPlayer player, String resolvedUrl) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().level == null || screen.isRemoved() || session.player != player) {
                KazumiLog.sniff.debug("[source] discard stale resolution for {} (session no longer valid)",
                        screen.getBlockPos());
                player.stop();
                return;
            }
            player.play(resolvedUrl);
            screen.setVideoState(VideoState.PLAYING);
            reportResolveStatus(screen, me.zuogeren.kazumiplayer.network.packet.ResolveStatusPacket.STATUS_READY);
        });
    }

    private void failPlayback(VideoScreenBlockEntity screen, Throwable cause) {
        String reason;
        if (cause instanceof VideoSourceResolveException.Timeout t) {
            reason = "超时（" + t.getMessage() + "）";
        } else if (cause instanceof VideoSourceResolveException.NotFound) {
            reason = "未能从页面中找到视频源";
        } else {
            reason = String.valueOf(cause.getMessage());
        }
        KazumiLog.sniff.warn("[source] resolution failed: {}", reason);
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().level == null) return; // 已离开世界，聊天提示无意义
            screen.setVideoState(VideoState.STOPPED);
            reportResolveStatus(screen, me.zuogeren.kazumiplayer.network.packet.ResolveStatusPacket.STATUS_FAILED);
            KazumiClientMessages.chatError("视频源解析失败：" + reason);
        });
    }

    /** 解析状态上报：经服务端聚合进 BE NBT 同步全组（GUI 观看者列表展示谁未就绪） */
    private static void reportResolveStatus(VideoScreenBlockEntity screen, int status) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
            new me.zuogeren.kazumiplayer.network.packet.ResolveStatusPacket(
                screen.getBlockPos(), screen.getScreenId(), status)));
    }

    private void sizePoolOnce() {
        if (!poolSized) {
            poolSized = true;
            pool.resize(ClientConfig.CONFIG.maxConcurrentSniffs.get());
            KazumiLog.sniff.debug("[source] resolver pool resized to {}",
                ClientConfig.CONFIG.maxConcurrentSniffs.get());
        }
    }

    /**
     * URL 是否可能为可直接播放的视频（file://、盘符、去 query/fragment 后的视频扩展名）。
     * 移植自旧 PlaybackManager.looksLikeDirectVideo；"/" 开头的网页相对路径不算本地文件。
     */
    private static boolean looksLikeDirectVideo(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase();
        boolean hasDrive = url.length() > 2 && (url.charAt(1) == ':' || url.charAt(1) == '：');
        String path = lower;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        boolean hasVideoExt = path.endsWith(".mp4") || path.endsWith(".mkv")
            || path.endsWith(".m3u8") || path.endsWith(".avi")
            || path.endsWith(".webm") || path.endsWith(".mov");
        return lower.startsWith("file://") || hasDrive || hasVideoExt;
    }

    /**
     * URL 去 query/fragment 后以 .m3u8/.m3u 结尾 → 按 HLS 直播流处理
     * （置 {@link ScreenPlayerManager.ScreenPlayer#bypassSync}，绕过同步与自动切集）。
     */
    private static boolean isLivePlaylistUrl(String url) {
        if (url == null) return false;
        return pointsToPlaylist(url.trim());
    }

    private static boolean pointsToPlaylist(String line) {
        String lower = line.toLowerCase();
        int cut = lower.indexOf('?');
        if (cut >= 0) lower = lower.substring(0, cut);
        cut = lower.indexOf('#');
        if (cut >= 0) lower = lower.substring(0, cut);
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException || t instanceof ExecutionException) {
            if (t.getCause() == null) break;
            t = t.getCause();
        }
        return t;
    }
}
