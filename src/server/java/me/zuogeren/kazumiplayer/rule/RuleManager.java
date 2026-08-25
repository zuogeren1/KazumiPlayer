package me.zuogeren.kazumiplayer.rule;
import me.zuogeren.kazumiplayer.util.KazumiLog;
import me.zuogeren.kazumiplayer.util.MonoClock;

import com.google.gson.reflect.TypeToken;
import me.zuogeren.kazumiplayer.util.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端规则管理器: 安装/删除/列出规则
 */
public class RuleManager {

    /** 整表可原子替换（热重载时构建新 Map 整体切换引用，避免并发搜索看到中间态） */
    private volatile Map<String, Rule> rules = new ConcurrentHashMap<>();
    private final Path rulesDir;
    private final RuleDownloader downloader = new RuleDownloader();
    private final RuleEngine engine = new RuleEngine();
    /** 最近一次内部写入 plugins.json 的单调时间戳（MonoClock ms）：供目录监视抑制自写回环 */
    private volatile long lastInternalWriteMs;

    public RuleManager(Path configDir) {
        this.rulesDir = configDir.resolve("kazumiplayer").resolve("rules");
    }

    /** 规则目录路径（目录监视器注册用） */
    public Path rulesDirectory() {
        return rulesDir;
    }

    /** 最近一次内部写入时间戳，从未写过返回 0 */
    public long lastInternalWriteAt() {
        return lastInternalWriteMs;
    }

    /**
     * 从 rules 目录加载已安装的规则。
     * 损坏的 plugins.json 不阻断启动：备份为 plugins.json.corrupted 后以空表继续。
     */
    public void loadAll() {
        Path pluginsFile = rulesDir.resolve("plugins.json");
        if (!Files.exists(pluginsFile)) return;

        try {
            String json = Files.readString(pluginsFile);
            List<Rule> loaded = JsonUtil.GSON.fromJson(json,
                    new TypeToken<List<Rule>>() {}.getType());
            if (loaded != null) {
                for (Rule rule : loaded) {
                    // 规则名缺失的条目无法寻址，跳过而非让 ConcurrentHashMap 拒绝 null 键
                    if (rule == null || rule.getName() == null) continue;
                    rules.put(rule.getName(), rule);
                }
                KazumiLog.rule.info("Loaded {} rules", loaded.size());
            }
        } catch (Exception e) {
            // 解析异常若外抛会沿 @Mod 构造器中断 mod 加载（服务器无法启动）
            Path backup = backupCorruptedFile(pluginsFile);
            KazumiLog.rule.error("""
                    规则缓存文件已损坏，本次启动将以空规则表继续。
                    损坏文件: {}
                    解析详情: {}
                    已备份至: {}（原文件已移走，避免下次启动重复报错）
                    处理方式: 修正 JSON 后将备份改回 plugins.json 即可恢复；或删除备份文件，用 /kazumi rule pull 重新安装规则。
                    """,
                    pluginsFile.toAbsolutePath(), rootMessage(e),
                    backup == null ? "<备份失败，请手动处理>" : backup.toAbsolutePath(), e);
        }
    }

    /** 解包 CompletionException 包装链，取最内层异常的消息（Gson 的语法错误含行号/列号/JSON 路径） */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage();
        return msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    /** 损坏文件改名留档，便于用户取回手工修复；返回备份路径（失败返回 null） */
    private Path backupCorruptedFile(Path pluginsFile) {
        try {
            Path backup = rulesDir.resolve("plugins.json.corrupted");
            Files.move(pluginsFile, backup,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            KazumiLog.rule.error("Failed to back up corrupted plugins.json", e);
            return null;
        }
    }

    /**
     * 保存所有规则到 plugins.json（临时文件 + 原子替换，避免断电截断产生损坏文件）
     */
    public void saveAll() {
        try {
            Files.createDirectories(rulesDir);
            String json = JsonUtil.GSON_PRETTY.toJson(new ArrayList<>(rules.values()));
            Path target = rulesDir.resolve("plugins.json");
            Path tmp = rulesDir.resolve("plugins.json.tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            lastInternalWriteMs = MonoClock.millis();
        } catch (IOException e) {
            KazumiLog.rule.error("Failed to save rules", e);
        }
    }

    /**
     * 运行期从磁盘热重载规则表（plugins.json），供 /kazumi rule reload 与目录监视调用。
     * 与启动路径 {@link #loadAll} 的差异：文件不存在视为清空；解析失败保留内存现状且不动盘
     * （自动重载不搬运用户文件，半写文件不会丢运行中的规则）。
     *
     * @return 成功返回重载后的规则数，解析失败返回 -1
     */
    public int reloadLive() {
        Path pluginsFile = rulesDir.resolve("plugins.json");
        if (!Files.exists(pluginsFile)) {
            this.rules = new ConcurrentHashMap<>();
            KazumiLog.rule.info("Rules hot-reloaded: plugins.json absent, table cleared");
            return 0;
        }
        try {
            String json = Files.readString(pluginsFile);
            List<Rule> loaded = JsonUtil.GSON.fromJson(json,
                    new TypeToken<List<Rule>>() {}.getType());
            Map<String, Rule> fresh = new ConcurrentHashMap<>();
            if (loaded != null) {
                for (Rule rule : loaded) {
                    // 规则名缺失的条目无法寻址，跳过（与 loadAll 一致）
                    if (rule == null || rule.getName() == null) continue;
                    fresh.put(rule.getName(), rule);
                }
            }
            this.rules = fresh;
            KazumiLog.rule.info("Rules hot-reloaded from disk: {} entries", fresh.size());
            return fresh.size();
        } catch (Exception e) {
            KazumiLog.rule.error("Failed to hot-reload rules, keeping {} in-memory entries: {}",
                    this.rules.size(), rootMessage(e));
            return -1;
        }
    }

    /**
     * 从 GitHub 下载并安装规则
     */
    public void install(Rule rule) {
        rules.put(rule.getName(), rule);
        saveAll();
    }

    /**
     * 删除规则
     */
    public boolean delete(String name) {
        if (rules.remove(name) != null) {
            saveAll();
            return true;
        }
        return false;
    }

    /**
     * 获取规则
     */
    public Rule get(String name) {
        return rules.get(name);
    }

    /**
     * 列出所有已安装的规则名称
     */
    public List<String> listAll() {
        List<String> names = new ArrayList<>(rules.keySet());
        Collections.sort(names);
        return names;
    }

    /**
     * 已安装规则数
     */
    public int count() {
        return rules.size();
    }

    public RuleDownloader getDownloader() { return downloader; }
    public RuleEngine getEngine() { return engine; }
    public Map<String, Rule> getRules() { return Collections.unmodifiableMap(rules); }
}
