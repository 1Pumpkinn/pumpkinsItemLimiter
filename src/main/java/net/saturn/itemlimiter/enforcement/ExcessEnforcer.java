package net.saturn.itemlimiter.enforcement;

import net.saturn.itemlimiter.ItemLimiter;
import net.saturn.itemlimiter.managers.ItemLimitManager;
import net.saturn.itemlimiter.util.ItemNameFormatter;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Sweeps a player's inventory for limited materials over their configured
 * limit and drops the excess. Used both reactively (e.g. after closing an
 * inventory) and periodically, to catch items added via /give, other
 * plugins, or anything else that bypasses the normal inventory events.
 */
public class ExcessEnforcer {

    private final ItemLimiter plugin;
    private final ItemLimitManager itemLimitManager;

    public ExcessEnforcer(ItemLimiter plugin, ItemLimitManager itemLimitManager) {
        this.plugin = plugin;
        this.itemLimitManager = itemLimitManager;
    }

    /**
     * Checks every limited material in the player's inventory, drops
     * whatever is over its limit, and sends a single summary message
     * if anything was dropped.
     */
    public void checkAndDropAllExcess(Player player) {
        int totalDropped = 0;
        for (Material material : itemLimitManager.getLimitedItems().keySet()) {
            totalDropped += itemLimitManager.dropExcess(player, material);
        }

        if (totalDropped > 0) {
            player.sendMessage(ItemNameFormatter.colorize(
                    plugin.getConfig().getString(
                            "messages.items-dropped-excess-all",
                            "&eDropped &6{count} &eexcess limited items!"
                    ).replace("{count}", String.valueOf(totalDropped))
            ));
            player.updateInventory();
        }
    }
}
