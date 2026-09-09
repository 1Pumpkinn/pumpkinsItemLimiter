package net.saturn.itemlimiter.listeners;

import net.saturn.itemlimiter.managers.ItemLimitManager;
import net.saturn.itemlimiter.util.LimitMessenger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Enforces item limits when a player picks an item up off the ground,
 * including partial pickups that top a stack up to exactly the limit.
 */
public class PickupLimitListener implements Listener {

    private final ItemLimitManager itemLimitManager;
    private final LimitMessenger messenger;

    public PickupLimitListener(ItemLimitManager itemLimitManager, LimitMessenger messenger) {
        this.itemLimitManager = itemLimitManager;
        this.messenger = messenger;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack stack = event.getItem().getItemStack();
        Material material = stack.getType();

        if (!itemLimitManager.isItemLimited(material)) return;

        int limit = itemLimitManager.getLimit(material);

        if (limit == 0) {
            event.setCancelled(true);
            messenger.sendPickupBlocked(player, material, limit);
            return;
        }

        int current = itemLimitManager.countItemInInventory(player, material);
        int totalAfterPickup = current + stack.getAmount();

        if (current >= limit) {
            event.setCancelled(true);
            messenger.sendPickupBlocked(player, material, limit);
            return;
        }

        if (totalAfterPickup <= limit) {
            return; // Fully within the limit - allow it as-is.
        }

        int canAdd = limit - current;
        if (canAdd <= 0) {
            event.setCancelled(true);
            messenger.sendPickupBlocked(player, material, limit);
            return;
        }

        event.setCancelled(true);

        ItemStack toAdd = stack.clone();
        toAdd.setAmount(canAdd);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(toAdd);

        int leftoverAmount = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        int actuallyAdded = canAdd - leftoverAmount;

        if (actuallyAdded <= 0) {
            messenger.sendPickupBlocked(player, material, limit);
            return;
        }

        int remaining = stack.getAmount() - actuallyAdded;
        if (remaining <= 0) {
            event.getItem().remove();
        } else {
            ItemStack newStack = stack.clone();
            newStack.setAmount(remaining);
            event.getItem().setItemStack(newStack);
        }

        player.updateInventory();
        messenger.sendPartial(player, material, actuallyAdded, limit, true);
    }
}
