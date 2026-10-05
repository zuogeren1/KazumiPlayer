package me.zuogeren.kazumiplayer.client.bilibili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.zuogeren.kazumiplayer.ClientConfig;
import me.zuogeren.kazumiplayer.client.BilibiliCredentials;
import me.zuogeren.kazumiplayer.util.HttpUtil;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * B 站账号弹幕屏蔽词：用本机凭据拉取账号词表写回配置，并提供弹幕入队前的过滤判据。
 *
 * <p>接口为实测结论（见 {@code reference/plans/f11-block-words-api.md}）：
 * {@code GET https://api.bilibili.com/x/dm/filter/user}，必须带 cookie（匿名 {@code code=-101 账号未登录}），
 * 不需要 WBI 签名、不校验 Referer 与 User-Agent；成功时 {@code data.rule[]} 每项含
 * {@code id/mid/type/filter/comment/ctime/mtime}。实测 {@code type}：0=关键词（纯文本）、1=正则、2=屏蔽用户。
 *
 * <p>落盘约定：账号词只写 {@code danmakuAccountBlockWords}（由本流程维护），用户手工词永远在
 * {@code danmakuBlockWords}，两者互不覆盖；过滤时取并集。正则词以 {@code re:} 前缀标记，
 * 解析失败时回落为字面包含并记一次 DEBUG（不打印词原文）。
 *
 * <p>{@code type=2}（屏蔽用户，8 位 uid 哈希）本批跳过：视频片内弹幕条目模型没有稳定 UID 字段可判定。
 */
public final class BilibiliBlockWords {

    /** 账号屏蔽词列表接口（实测：GET + cookie；匿名返回 code=-101） */
    private static final String ACCOUNT_API = "https://api.bilibili.com/x/dm/filter/user";
    private static final String REFERER = "https://www.bilibili.com/";

    /** 正则词前缀（账号侧 type=1） */
    private static final String REGEX_PREFIX = "re:";

    /** 账号侧 rule[].type 实测取值 */
    private static final int TYPE_KEYWORD = 0;
    private static final int TYPE_REGEX = 1;
    private static final int TYPE_USER = 2;

    /** 同步成功后的冷却（毫秒）：写盘会触发配置重载事件，冷却避免反复回环请求 */
    private static final long AUTO_SYNC_COOLDOWN_MS = 5 * 60 * 1000L;

    /** 失败后的最快重试间隔（毫秒）：失败时保持挂起，等下一次触发（配置重载/弹幕装载）再试 */
    private static final long AUTO_SYNC_RETRY_MS = 30 * 1000L;

    /** 解析后的词表快照 */
    private record Rules(List<String> keywords, List<Pattern> regexes) {}

    private static final Rules EMPTY = new Rules(List.of(), List.of());

    /** 当前生效的词表快照与生成它的两个原始配置串（任一变化才重新编译） */
    private static volatile Rules snapshot = EMPTY;
    private static volatile String snapshotManual = null;
    private static volatile String snapshotAccount = null;

    /** 正则编译失败已告警的词（只记一次，不打印词原文） */
    private static final Set<String> regexWarned = ConcurrentHashMap.newKeySet();

    private static final AtomicBoolean AUTO_SYNC_PENDING = new AtomicBoolean();
    private static final AtomicBoolean SYNC_IN_FLIGHT = new AtomicBoolean();
    private static volatile long lastAttemptMono = Long.MIN_VALUE / 2;
    private static volatile long lastSuccessMono = Long.MIN_VALUE / 2;

    private BilibiliBlockWords() {}

    // ---- 过滤判据 ----

    /** 该文本是否命中屏蔽词（手工词 + 账号词并集；关键词为大小写不敏感包含，正则词按正则在文本中查找） */
    public static boolean blocked(String text) {
        return blocked(text, configValue(true), configValue(false));
    }

    /**
     * 判据本体：显式传入两个词表串（把配置读取与匹配解耦，便于离线验证同一份代码路径）。
     *
     * @param manual  手工键原文（换行或逗号分隔）
     * @param account 账号键原文（每行一条，正则以 re: 前缀标记）
     */
    public static boolean blocked(String text, String manual, String account) {
        if (text == null || text.isEmpty()) return false;
        Rules rules = rules(manual, account);
        if (!rules.keywords().isEmpty()) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String keyword : rules.keywords()) {
                if (lower.contains(keyword)) return true;
            }
        }
        for (Pattern pattern : rules.regexes()) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
    }

    /** 词表条数（关键词 + 正则），审计与验证用 */
    public static int ruleCount() {
        return ruleCount(configValue(true), configValue(false));
    }

    /** 词表条数（显式词表串版本） */
    public static int ruleCount(String manual, String account) {
        Rules rules = rules(manual, account);
        return rules.keywords().size() + rules.regexes().size();
    }

    /** 取两个词表串的并集并编译；入参未变化时直接复用快照（直播弹幕按条判定，避免逐条重编译） */
    private static Rules rules(String manual, String account) {
        if (manual.equals(snapshotManual) && account.equals(snapshotAccount)) return snapshot;
        Rules built = compile(manual, account);
        snapshot = built;
        snapshotManual = manual;
        snapshotAccount = account;
        return built;
    }

    /**
     * 编译词表。
     *
     * @param manual  手工键的值：注释约定「换行或逗号分隔」，故两种分隔符都切
     * @param account 账号键的值：约定「每行一条」，只按换行切——正则里出现逗号（如 {@code a{1,2}}）时不会被切断
     */
    private static Rules compile(String manual, String account) {
        List<String> keywords = new ArrayList<>();
        Map<String, Pattern> regexes = new LinkedHashMap<>();
        for (String line : lines(manual, true)) addRule(line, keywords, regexes);
        for (String line : lines(account, false)) addRule(line, keywords, regexes);
        return new Rules(List.copyOf(keywords), List.copyOf(regexes.values()));
    }

    /** 单条词：re: 前缀按正则编译，编译失败回落字面包含（回落用的是去掉前缀后的原文）；其余按关键词 */
    private static void addRule(String line, List<String> keywords, Map<String, Pattern> regexes) {
        String literal = line;
        if (line.regionMatches(true, 0, REGEX_PREFIX, 0, REGEX_PREFIX.length())) {
            String body = line.substring(REGEX_PREFIX.length()).trim();
            if (body.length() > 2 && body.startsWith("/") && body.endsWith("/")) {
                body = body.substring(1, body.length() - 1);
            }
            if (body.isEmpty()) return;
            literal = body;
            try {
                regexes.putIfAbsent(body, Pattern.compile(body,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                return;
            } catch (PatternSyntaxException e) {
                // 只记位置与描述：异常 message 会内嵌词原文，不能整条打印
                if (regexWarned.add(body)) {
                    KazumiLog.danmaku.debug("Block word regex invalid (len={}, index={}): {}",
                        body.length(), e.getIndex(), e.getDescription());
                }
            }
        }
        String keyword = literal.toLowerCase(Locale.ROOT);
        if (!keyword.isBlank() && !keywords.contains(keyword)) keywords.add(keyword);
    }

    /** 按行/逗号切词：忽略空白行（splitComma 仅对手工键开启） */
    private static List<String> lines(String raw, boolean splitComma) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(splitComma ? "[,\\r\\n]+" : "[\\r\\n]+")) {
            String line = part.trim();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    private static String configValue(boolean manual) {
        try {
            String value = manual
                ? ClientConfig.CONFIG.danmakuBlockWords.get()
                : ClientConfig.CONFIG.danmakuAccountBlockWords.get();
            return value == null ? "" : value;
        } catch (Throwable t) {
            return ""; // 配置尚未加载：按无词处理
        }
    }

    // ---- 账号同步 ----

    /**
     * 拉取账号屏蔽词并写回配置（本机凭据；未配置凭据直接失败，不发请求）。
     *
     * @return 写入的条目数（关键词 + 正则）；失败时以异常完成，由调用方按不打扰用户的方式记录
     */
    public static CompletableFuture<Integer> syncFromAccount() {
        String cookie = BilibiliCredentials.get();
        if (cookie.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("未配置本机 B 站凭据"));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", REFERER);
        headers.put("Cookie", cookie);
        // 凭据只作为请求头，任何日志只报条数
        return HttpUtil.fetch(ACCOUNT_API, "GET", headers, Map.of())
            .thenApply(BilibiliBlockWords::parseAndStore);
    }

    /** 账号词解析结果：待写入文本与分类计数 */
    private record AccountWords(String text, int keywords, int regexes, int skipped) {}

    /** 解析响应并写回账号词配置（网络线程；只有内容变化才落盘，避免与配置重载互相触发） */
    private static int parseAndStore(String body) {
        AccountWords parsed = parseResponse(body);
        String current = configValue(false);
        if (!parsed.text().equals(current)) store(parsed.text());
        KazumiLog.danmaku.info(
            "Bilibili account block words synced: {} entries ({} keywords, {} regex, {} rules skipped, changed={})",
            parsed.keywords() + parsed.regexes(), parsed.keywords(), parsed.regexes(), parsed.skipped(),
            !parsed.text().equals(current));
        return parsed.keywords() + parsed.regexes();
    }

    /** 响应体 → 待写入文本（纯函数：不触碰配置与线程，便于离线核对写入格式） */
    private static AccountWords parseResponse(String body) {
        if (body == null || body.isBlank()) throw new IllegalStateException("账号屏蔽词响应为空");
        JsonObject root = JsonUtil.GSON.fromJson(body, JsonObject.class);
        if (root == null || !root.has("code")) throw new IllegalStateException("账号屏蔽词响应无法解析");
        int code = root.get("code").getAsInt();
        if (code != 0) {
            String message = root.has("message") ? root.get("message").getAsString() : "";
            throw new IllegalStateException("账号屏蔽词接口返回 code=" + code + " message=" + message);
        }
        JsonObject data = root.getAsJsonObject("data");
        JsonArray rule = data == null ? null : data.getAsJsonArray("rule");
        if (rule == null) throw new IllegalStateException("账号屏蔽词响应缺少 data.rule");

        Set<String> rules = new LinkedHashSet<>();
        int keywords = 0;
        int regexes = 0;
        int skipped = 0;
        for (JsonElement element : rule) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String filter = item.has("filter") ? item.get("filter").getAsString().trim() : "";
            if (filter.isEmpty()) continue;
            int type = item.has("type") ? item.get("type").getAsInt() : TYPE_KEYWORD;
            switch (type) {
                case TYPE_KEYWORD -> {
                    if (rules.add(filter)) keywords++;
                }
                case TYPE_REGEX -> {
                    if (rules.add(REGEX_PREFIX + filter)) regexes++;
                }
                case TYPE_USER -> skipped++;
                default -> skipped++;
            }
        }
        return new AccountWords(String.join("\n", rules), keywords, regexes, skipped);
    }

    /** 写回账号词配置并落盘（主线程执行，避开配置写入与重载的竞争） */
    private static void store(String text) {
        Runnable task = () -> {
            try {
                ClientConfig.CONFIG.danmakuAccountBlockWords.set(text);
                ClientConfig.SPEC.save();
            } catch (Throwable t) {
                KazumiLog.danmaku.debug("Store account block words failed: {}", String.valueOf(t.getMessage()));
            }
        };
        try {
            Minecraft.getInstance().execute(task);
        } catch (Throwable t) {
            task.run();
        }
    }

    // ---- 自动同步接线 ----

    /** 启动/配置加载后请求一次自动同步（总开关关闭或未登录时直接跳过） */
    public static void requestAutoSync() {
        AUTO_SYNC_PENDING.set(true);
        retryPendingAutoSync();
    }

    /**
     * 待同步仍挂起时补一次：配置事件触发时公共配置可能尚未加载（{@link HttpUtil} 依赖它），
     * 此时同步会失败但**保持挂起**，由弹幕装载路径（那时配置必定已就绪）再补一次。
     */
    public static void retryPendingAutoSync() {
        if (!AUTO_SYNC_PENDING.get()) return;
        if (!autoSyncEnabled()) return;
        long now = MonoClock.millis();
        if (now - lastSuccessMono < AUTO_SYNC_COOLDOWN_MS) {
            AUTO_SYNC_PENDING.set(false);
            return;
        }
        if (now - lastAttemptMono < AUTO_SYNC_RETRY_MS) return;
        if (!SYNC_IN_FLIGHT.compareAndSet(false, true)) return;
        lastAttemptMono = now;
        syncFromAccount().whenComplete((count, error) -> {
            SYNC_IN_FLIGHT.set(false);
            if (error != null) {
                KazumiLog.danmaku.debug("Account block words sync failed: {}", String.valueOf(error.getMessage()));
                return;
            }
            AUTO_SYNC_PENDING.set(false);
            lastSuccessMono = MonoClock.millis();
        });
    }

    private static boolean autoSyncEnabled() {
        try {
            return ClientConfig.CONFIG.danmakuAutoSyncBlockWords.get() && BilibiliCredentials.present();
        } catch (Throwable t) {
            AUTO_SYNC_PENDING.set(true); // 配置尚未加载：保留挂起，稍后补
            return false;
        }
    }
}
