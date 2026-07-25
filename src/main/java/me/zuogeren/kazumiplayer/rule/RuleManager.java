package me.zuogeren.kazumiplayer.rule;

import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import me.zuogeren.kazumiplayer.util.JsonUtil;
import org.slf4j.Logger;

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
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, Rule> rules = new ConcurrentHashMap<>();
    private final Path rulesDir;
    private final RuleDownloader downloader = new RuleDownloader();
    private final RuleEngine engine = new RuleEngine();

    public RuleManager(Path configDir) {
        this.rulesDir = configDir.resolve("kazumiplayer").resolve("rules");
    }

    /**
     * 从 rules 目录加载已安装的规则
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
                    rules.put(rule.getName(), rule);
                }
                LOGGER.info("Loaded {} rules", loaded.size());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load rules", e);
        }
    }

    /**
     * 保存所有规则到 plugins.json
     */
    public void saveAll() {
        try {
            Files.createDirectories(rulesDir);
            String json = JsonUtil.GSON_PRETTY.toJson(new ArrayList<>(rules.values()));
            Files.writeString(rulesDir.resolve("plugins.json"), json);
        } catch (IOException e) {
            LOGGER.error("Failed to save rules", e);
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
