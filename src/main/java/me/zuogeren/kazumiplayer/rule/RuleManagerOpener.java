package me.zuogeren.kazumiplayer.rule;

import java.util.function.Consumer;

/**
 * 规则管理器 GUI 打开钩子：common 物品经此打开客户端界面（与 ScreenGuiOpeners 同模式，
 * 由客户端附加 mod 注入实现，避免 common 引用 client 类）。
 */
public class RuleManagerOpener {

    private static Consumer<String> opener = name -> {};

    /** 注入打开实现（参数为选中规则名或空串=不预选） */
    public static void set(Consumer<String> impl) {
        opener = impl != null ? impl : name -> {};
    }

    public static void open(String selectedRule) {
        opener.accept(selectedRule == null ? "" : selectedRule);
    }
}
