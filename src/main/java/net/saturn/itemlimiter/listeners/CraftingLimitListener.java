package net.saturn.itemlimiter.listeners;

import net.saturn.itemlimiter.ItemLimiter;
import net.saturn.itemlimiter.enforcement.ExcessEnforcer;
import net.saturn.itemlimiter.managers.ItemLimitManager;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Crafting/smithing rules:
 * <ul>
 *   <li>Banned items (limit 0) can't be crafted at all.</li>
 *   <li>Limited items can always be crafted. Anything over the limit is
 *       dropped on the ground right after the craft, so ingredients are
 *       never blocked or wasted by the recipe itself.</li>
 * </ul>
 */
public class CraftingLimitListener implements Listener {

    private final ItemLimiter plugin;
    private final ItemLimitManager itemLimitManager;
    private final ExcessEnforcer excessEnforcer;

    public CraftingLimitListener(ItemLimiter plugin, ItemLimitManager itemLimitManager, ExcessEnforcer excessEnforcer) {
        this.plugin = plugin;
        this.itemLimitManager = itemLimitManager;
        this.excessEnforcer = excessEnforcer;
    }

    @EventHandler
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (event.getRecipe() == null) return;
        ItemStack result = event.getRecipe().getResult();

        // A Crafter block isn't a player, so there's no inventory to drop
        // excess from - keep blocking anything limited.
        if (itemLimitManager.isItemLimited(result)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null) return;
        if (isExempt(event.getView().getPlayer())) return;

        if (isBannedResult(event.getRecipe().getResult())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        if (isExempt(event.getView().getPlayer())) return;
        if (isBannedResult(event.getResult())) {
            event.setResult(null);
        }
    }

    /** After a craft goes through, drop whatever now exceeds the limit. */
    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRecipe() == null) return;
        if (!itemLimitManager.isItemLimited(event.getRecipe().getResult())) return;

        // Next tick, once the crafted items have actually landed in the
        // inventory (shift-click crafts can produce many stacks at once).
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) excessEnforcer.checkAndDropAllExcess(player);
        });
    }

    private boolean isExempt(HumanEntity human) {
        return human instanceof Player player && itemLimitManager.isExempt(player);
    }

    private boolean isBannedResult(ItemStack result) {
        return result != null && result.getType() != Material.AIR && itemLimitManager.isItemBanned(result);
    }
}