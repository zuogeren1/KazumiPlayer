package me.zuogeren.kazumiplayer.network.gui;

/**
 * GUI 通用通道协议常量。
 * C→S GuiActionPacket.action 与 S→C GuiDataPacket.dataType 的取值集中在此，
 * payload 均为 Gson JSON（DTO 见 GuiPayloads）。
 */
public final class GuiProtocol {

    private GuiProtocol() {}

    // ---- C→S actions ----
    /** {keyword} bgm.tv 搜索 */
    public static final String ACTION_SEARCH_BANGUMI = "search_bangumi";
    /** {rule(空=全部), keyword} 规则源搜索（keyword 为番剧名） */
    public static final String ACTION_SEARCH_RULE = "search_rule";
    /** {rule, id} 查询集数列表 */
    public static final String ACTION_QUERY_CHAPTERS = "query_chapters";
    /** {rule, id, episode} 在绑定屏幕播放指定集 */
    public static final String ACTION_PLAY_EPISODE = "play_episode";
    /** {} 加入绑定屏幕的同步 */
    public static final String ACTION_JOIN = "join";
    /** {} 离开同步并停止本地播放 */
    public static final String ACTION_LEAVE = "leave";
    /** {} 停止绑定屏幕的播放（清除 NBT + 通知所有观看者） */
    public static final String ACTION_STOP_SCREEN = "stop_screen";

    // ---- S→C dataTypes ----
    /** [BangumiResultItem] */
    public static final String DATA_BANGUMI_RESULTS = "bangumi_results";
    /** [RuleResultItem] */
    public static final String DATA_RULE_RESULTS = "rule_results";
    /** ChaptersPayload */
    public static final String DATA_CHAPTERS = "chapters";
    /** PlayOkPayload */
    public static final String DATA_PLAY_OK = "play_ok";
    /** ErrorPayload */
    public static final String DATA_ERROR = "error";
}
