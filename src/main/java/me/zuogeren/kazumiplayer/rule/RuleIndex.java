package me.zuogeren.kazumiplayer.rule;

/**
 * KazumiRules index.json 目录条目
 */
public class RuleIndex {
    String name;
    String version;
    boolean useNativePlayer;
    boolean antiCrawlerEnabled;
    String author;
    long lastUpdate;

    public String getName() { return name; }
    public String getVersion() { return version; }
    public boolean isUseNativePlayer() { return useNativePlayer; }
    public boolean isAntiCrawlerEnabled() { return antiCrawlerEnabled; }
    public String getAuthor() { return author; }
    public long getLastUpdate() { return lastUpdate; }

    @Override
    public String toString() {
        return "RuleIndex{name='" + name + "', version='" + version + "'}";
    }
}
