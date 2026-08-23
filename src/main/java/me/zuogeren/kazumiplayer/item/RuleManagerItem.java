package me.zuogeren.kazumiplayer.item;

import me.zuogeren.kazumiplayer.rule.RuleManagerOpener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * 规则管理器：右键打开规则管理界面
 * （远程仓库规则列表 / 拉取 / 删除 / 连通性延迟测试 / 弃用检测）。
 */
public class RuleManagerItem extends Item {

    public RuleManagerItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            RuleManagerOpener.open("");
        }
        return InteractionResult.SUCCESS;
    }
}
