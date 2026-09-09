package net.saturn.itemlimiter.listeners;

import net.saturn.itemlimiter.managers.ItemLimitManager;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Blocks crafting/smithing results that are banned or already at their
 * limit, including automated crafting via the Crafter block (1.21+).
 */
public class CraftingLimitListener implements Listener {

    private final ItemLimitManager itemLimitManager;

    public CraftingLimitListener(ItemLimitManager itemLimitManager) {
        this.itemLimitManager = itemLimitManager;
    }

    @EventHandler
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (event.getRecipe() == null) return;
        ItemStack result = event.getRecipe().getResult();

        // Crafters enforce the limit on the block itself: if the
        // result is banned or limited, cancel the craft event.
        if (itemLimitManager.isItemLimited(result)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null) return;
        ItemStack result = event.getRecipe().getResult();

        if (shouldBlockCreation(event.getView().getPlayer(), result)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();

        if (shouldBlockCreation(event.getView().getPlayer(), result)) {
            event.setResult(null);
        }
    }

    private boolean shouldBlockCreation(HumanEntity human, ItemStack result) {
        if (result == null || result.getType() == Material.AIR) return false;
        if (!(human instanceof Player player)) return false;

        if (itemLimitManager.isItemBanned(result)) return true;

        if (itemLimitManager.isItemLimited(result)) {
            int limit = itemLimitManager.getLimit(result);
            int current = itemLimitManager.countItemInInventory(player, result.getType());
            return current >= limit;
        }
        return false;
    }
}
