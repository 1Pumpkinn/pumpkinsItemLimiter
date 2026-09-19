package net.saturn.itemlimiter.enforcement;

import net.saturn.itemlimiter.ItemLimiter;
import net.saturn.itemlimiter.managers.ItemLimitManager;
import net.saturn.itemlimiter.util.ItemNameFormatter;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Map;

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
     * Scans the inventory once, drops whatever is over its limit, and sends a
     * single summary message if anything was dropped.
     */
    public void checkAndDropAllExcess(Player player) {
        if (!itemLimitManager.hasLimitedItems()) return;

        Map<Material, Integer> counts = itemLimitManager.countLimitedItems(player);
        if (counts.isEmpty()) return;

        int totalDropped = 0;
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            totalDropped += itemLimitManager.dropExcess(player, entry.getKey(), entry.getValue());
        }

        if (totalDropped > 0) {
            String message = plugin.getConfig().getString(
                    "messages.items-dropped-excess-all",
                    "&eDropped &6{count} &eexcess limited items!"
            ).replace("{count}", String.valueOf(totalDropped));

            player.sendMessage(ItemNameFormatter.colorize(message));
            player.updateInventory();
        }
    }
}