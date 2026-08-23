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
    /** {rule(空=全部), keyword} 规则源搜索（keyword 为番剧名），结果流式增量回推 */
    public static final String ACTION_SEARCH_RULE = "search_rule";
    /** {} 取消本玩家在途的流式搜源（关闭界面/清空搜索时由客户端发送） */
    public static final String ACTION_CANCEL_SEARCH = "cancel_search";
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
    /** {urls} 提交直链：屏幕空闲则立即起播，队列播放中则追加到队尾，规则剧集中拒绝 */
    public static final String ACTION_QUEUE_ADD = "queue_add";
    /** {offsetX,offsetY,offsetZ,facing,width,height} 设置屏幕几何属性（朝向/大小/偏移），服务端 clamp 后回发权威值 */
    public static final String ACTION_SCREEN_PROPS = "screen_props";
    /** {index} 立即切播队列第 index 项（1-based） */
    public static final String ACTION_QUEUE_JUMP = "queue_jump";
    /** {index} 把队列第 index 项移到当前项之后（下一个就播它），仅待播项有效 */
    public static final String ACTION_QUEUE_MOVE = "queue_move";
    /** {index} 从队列移除第 index 项（当前项不可移除） */
    public static final String ACTION_QUEUE_REMOVE = "queue_remove";

    // ---- 规则管理器（RuleManagerScreen）----
    /** {} 获取远程仓库规则列表 + 本地安装状态合并 */
    public static final String ACTION_RULE_LIST = "rule_list";
    /** {name} 从远程拉取并安装规则 */
    public static final String ACTION_RULE_PULL = "rule_pull";
    /** {name} 删除本地已安装规则 */
    public static final String ACTION_RULE_DELETE = "rule_delete";
    /** {name} 测试规则连通性（服务端计时） */
    public static final String ACTION_RULE_TEST = "rule_test";

    // ---- S→C dataTypes ----
    /** [BangumiResultItem] */
    public static final String DATA_BANGUMI_RESULTS = "bangumi_results";
    /**
     * RuleSearchPartialPayload {searchId, rule, items, completedRules, totalRules}
     * 流式规则搜源：每完成一个源推一条增量；completedRules==totalRules 表示全部结束。
     * searchId 为本轮搜索代次，客户端据此区分新旧一轮并重置结果列表。
     */
    public static final String DATA_RULE_RESULTS_PARTIAL = "rule_results_partial";
    /** ChaptersPayload */
    public static final String DATA_CHAPTERS = "chapters";
    /** ScreenPropsPayload 服务端 clamp 后的权威属性值（设置面板以此刷新显示） */
    public static final String DATA_SCREEN_PROPS = "screen_props";
    /** PlayOkPayload */
    public static final String DATA_PLAY_OK = "play_ok";
    /** ErrorPayload */
    public static final String DATA_ERROR = "error";

    // ---- 规则管理器 ----
    /** [RuleListEntryPayload] 远程目录+本地安装状态的合并列表 */
    public static final String DATA_RULE_LIST = "rule_list";
    /** RuleTestResultPayload 单条规则的连通性测试结果（异步逐条回推） */
    public static final String DATA_RULE_TEST_RESULT = "rule_test_result";
}
