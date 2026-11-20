package me.zuogeren.kazumiplayer.client.bilibili;

import me.zuogeren.kazumiplayer.util.KazumiLog;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.Track;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WaterMedia 平台扩展：让播放引擎认识 kazumibili:// 这类自定义 MRL。
 *
 * B 站 1080P 以上只给 DASH，音视频分成两条 m4s，而 WaterMedia 的 MRL 只能从 URI 解析单一媒体源；
 * 平台扩展点正好补上这一环——URI 里只带一个注册序号，真正的流地址在
 * {@link BiliStreamRegistry} 里，本类把序号还原成带 audioTracks 的 {@link Platform.Resolution}。
 * 自定义 scheme 意味着只有我们自己会用这种方式起播，播放其他 URL 时本平台一律不接手。
 */
public final class KazumiBiliPlatform implements Platform {

    /** 自定义 URI 的 scheme，与 {@link BiliStreamRegistry#SCHEME} 保持一致 */
    public static final String SCHEME = "kazumibili";

    /** B 站 CDN 会对非浏览器 UA 与缺失 Referer 的请求做防盗链拒绝 */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 防盗链 Referer（点播：视频页/DASH 流） */
    private static final String REFERER = "https://www.bilibili.com/";

    /** 直播防盗链 Referer：直播 CDN（FLV 节点）只认直播站来源 */
    public static final String LIVE_REFERER = "https://live.bilibili.com/";

    /** 平台名，日志与排查用 */
    private static final String NAME = "KazumiPlayer-Bili";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private KazumiBiliPlatform() {}

    /** 幂等注册：重复调用只生效一次，避免平台表里出现多个同源实例 */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        PlatformAPI.register(new KazumiBiliPlatform());
        KazumiLog.playback.info("Registered WaterMedia platform {} for scheme {}", NAME, SCHEME);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Platform.Resolution resolve(URI uri) throws PlatformException {
        if (uri == null || uri.getScheme() == null || !SCHEME.equalsIgnoreCase(uri.getScheme())) {
            return null; // 非本平台的 scheme：不处理，交给后续平台继续探测
        }
        String id = uri.getAuthority();
        BiliStreamRegistry.Snapshot snapshot = id == null ? null : BiliStreamRegistry.find(id);
        if (snapshot == null) {
            throw new PlatformException(NAME, "Unknown Bili DASH id: " + uri);
        }
        // CDN 地址带签名有效期：过期后再拿它起播只会卡在握手失败，不如直接给出可读原因
        if (snapshot.expiresAtEpochSec() > 0
                && snapshot.expiresAtEpochSec() < Instant.now().getEpochSecond()) {
            throw new PlatformException(NAME,
                "Bili DASH link expired at " + snapshot.expiresAtEpochSec() + ": " + uri);
        }
        // 音视频两条流都要带同一套头：B 站 CDN 按 UA 与 Referer 校验防盗链，
        // 缺任一项都会在握手阶段被拒（表现为无音轨或直接断流）
        String referer = snapshot.referer() == null || snapshot.referer().isBlank()
                ? REFERER : snapshot.referer();
        HttpHeaders headers = new HttpHeaders()
                .set("User-Agent", USER_AGENT)
                .set("Referer", referer)
                .set("Accept", "*/*");
        // qualities 只放当前解析到的这一档：WaterMedia 的 MediaQuality.of(w,h) 按短边归入档位，
        // 1080P 与 1080P60 会落到同一个 key，多档并列会互相覆盖，最终取到的可能不是本次解析的 URI
        List<Platform.Quality> qualities = List.of(
                new Platform.Quality(URI.create(snapshot.videoUri()), snapshot.width(), snapshot.height()));
        // audioTracks 承载 DASH 分离出来的音轨——MRL 本身表达不了第二条 URI，只能走独立音轨；
        // 单流注册（直播）没有伴随音轨，音轨列表留空
        List<Track> audioTracks = snapshot.audioUri() == null || snapshot.audioUri().isBlank()
                ? List.of()
                : List.of(new Track("audio", null, URI.create(snapshot.audioUri())));
        // 元数据字段全部给安全空值：null 会让 record 生成的 toString/hashCode 抛 NPE，
        // 出问题时连日志都打不出来，故用空串与零时长
        Metadata metadata = new Metadata("", "", Instant.EPOCH, 0L, "");
        URI video = URI.create(snapshot.videoUri());
        // thumbnail 用 null：DASH 解析结果里没有封面图，引擎允许为 null
        Platform.Source source = new Platform.Source(MediaType.VIDEO, null,
                metadata, headers, qualities, audioTracks, List.of());
        // 有效期 0 = 未知：MRL.status() 每被查询一次就把 expiresAt 早于当前时刻的条目标成 EXPIRED，
        // 而 MRL.get() 见到 EXPIRED 会 reload（清空 sources 并重载）→ source 永远为 null，
        // 表现为"MRL 每 500ms 重载一次直到装载超时"。故未知有效期必须留 null（= 永不过期），不得写成纪元 0。
        Instant expires = snapshot.expiresAtEpochSec() > 0
                ? Instant.ofEpochSecond(snapshot.expiresAtEpochSec()) : null;
        return new Platform.Resolution(expires, source);
    }
}
