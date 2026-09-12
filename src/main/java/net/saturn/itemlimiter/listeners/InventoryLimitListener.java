package net.saturn.itemlimiter.listeners;

import net.saturn.itemlimiter.ItemLimiter;
import net.saturn.itemlimiter.enforcement.ExcessEnforcer;
import net.saturn.itemlimiter.managers.ItemLimitManager;
import net.saturn.itemlimiter.util.LimitMessenger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Enforces item limits across every way an item can land in a player's
 * inventory while it's open: clicking, dragging, shift-clicking, hand
 * swapping, closing the inventory with an item on the cursor, and the
 * drop key.
 */
public class InventoryLimitListener implements Listener {

    private final ItemLimiter plugin;
    private final ItemLimitManager itemLimitManager;
    private final LimitMessenger messenger;
    private final ExcessEnforcer excessEnforcer;

    public InventoryLimitListener(ItemLimiter plugin, ItemLimitManager itemLimitManager,
                                  LimitMessenger messenger, ExcessEnforcer excessEnforcer) {
        this.plugin = plugin;
        this.itemLimitManager = itemLimitManager;
        this.messenger = messenger;
        this.excessEnforcer = excessEnforcer;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHandSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack offHandItem = event.getOffHandItem();
        ItemStack mainHandItem = event.getMainHandItem();

        Material offHandMat = offHandItem != null ? offHandItem.getType() : Material.AIR;
        Material mainHandMat = mainHandItem != null ? mainHandItem.getType() : Material.AIR;

        boolean offHandLimited = offHandMat != Material.AIR && itemLimitManager.isItemLimited(offHandMat);
        boolean mainHandLimited = mainHandMat != Material.AIR && itemLimitManager.isItemLimited(mainHandMat);

        if (!offHandLimited && !mainHandLimited) return;

        // A hand swap doesn't change the total count, so only a fully
        // banned item (limit 0) needs to block it.
        if (offHandLimited && itemLimitManager.getLimit(offHandMat) == 0) {
            event.setCancelled(true);
            messenger.sendBlocked(player, offHandMat, 0);
            return;
        }

        if (mainHandLimited && itemLimitManager.getLimit(mainHandMat) == 0) {
            event.setCancelled(true);
            messenger.sendBlocked(player, mainHandMat, 0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory().getType() == InventoryType.CREATIVE) return;

        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;

        Inventory playerInv = player.getInventory();
        InventoryAction action = event.getAction();
        int slot = event.getSlot();

        ItemStack moving = null;
        boolean fromContainer = clicked != playerInv;
        boolean isOffhandSlot = (clicked == playerInv && slot == 40);

        switch (action) {
            case PLACE_ALL:
            case PLACE_ONE:
            case PLACE_SOME:
            case SWAP_WITH_CURSOR:
            case COLLECT_TO_CURSOR:
                moving = event.getCursor();
                fromContainer = false;
                break;

            // MOVE_TO_OTHER_INVENTORY (shift-click) is intentionally not
            // handled here - it's fully owned by onShiftClick below, which
            // never cancels it (cancelling a shift-click transfer out of a
            // crafting/container inventory is unreliable and can leave the
            // item stuck in the source slot, exploitable via Escape). If
            // this handler also tried to cancel/partial-transfer the same
            // click, it would reintroduce that exact bug.

            case HOTBAR_SWAP:
            case HOTBAR_MOVE_AND_READD:
                if (isOffhandSlot) {
                    int hotbarButton = event.getHotbarButton();
                    if (hotbarButton >= 0 && hotbarButton < 9) {
                        ItemStack hotbarItem = playerInv.getItem(hotbarButton);
                        if (hotbarItem != null && hotbarItem.getType() != Material.AIR) {
                            moving = hotbarItem;
                            fromContainer = false;
                        }
                    }
                } else if (fromContainer) {
                    // Pressing a number key while hovering a container slot
                    // (crafting result, chest, furnace/anvil output, trade
                    // result, etc.) swaps that slot straight into the hotbar,
                    // completely bypassing PLACE_ALL/MOVE_TO_OTHER_INVENTORY.
                    // Treat it the same as any other container -> player transfer.
                    moving = event.getCurrentItem();
                }
                break;

            default:
                return;
        }

        if (moving == null || moving.getType() == Material.AIR) return;

        Material material = moving.getType();
        if (!itemLimitManager.isItemLimited(material)) return;

        if (isOffhandSlot) {
            handleOffhandClick(event, player, material, moving);
            return;
        }

        if (!isAddingToPlayer(action, clicked, playerInv)) return;

        int limit = itemLimitManager.getLimit(material);
        int current = itemLimitManager.countItemInInventory(player, material);

        if (limit == 0) {
            event.setCancelled(true);
            player.updateInventory();
            messenger.sendBlocked(player, material, limit);
            return;
        }

        if (current >= limit) {
            event.setCancelled(true);
            player.updateInventory();
            if (!fromContainer) {
                dropCursorSafe(player);
            }
            messenger.sendBlocked(player, material, limit);
            return;
        }

        int amountToAdd = moving.getAmount();
        if (current + amountToAdd > limit) {
            event.setCancelled(true);
            player.updateInventory();

            int canAdd = limit - current;
            if (fromContainer) {
                handlePartialTransferFromContainer(player, event, material, canAdd);
            } else {
                handlePartialTransferFromCursor(player, moving, material, canAdd);
            }
        }
    }

    /** Items moving into the off-hand slot specifically (not covered by isAddingToPlayer). */
    private void handleOffhandClick(InventoryClickEvent event, Player player, Material material, ItemStack moving) {
        int limit = itemLimitManager.getLimit(material);

        if (limit == 0) {
            event.setCancelled(true);
            player.updateInventory();
            messenger.sendBlocked(player, material, limit);
            return;
        }

        ItemStack currentOffhand = player.getInventory().getItemInOffHand();
        boolean isSwap = currentOffhand != null && currentOffhand.getType() == material;
        if (isSwap) return; // Same material swapping hands doesn't change the total count.

        int current = itemLimitManager.countItemInInventory(player, material);
        if (current + moving.getAmount() > limit) {
            event.setCancelled(true);
            player.updateInventory();
            messenger.sendBlocked(player, material, limit);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack draggedItem = event.getOldCursor();
        if (draggedItem == null || draggedItem.getType() == Material.AIR) return;

        Material material = draggedItem.getType();
        if (!itemLimitManager.isItemLimited(material)) return;

        int limit = itemLimitManager.getLimit(material);
        if (limit == 0) {
            event.setCancelled(true);
            messenger.sendBlocked(player, material, limit);
            return;
        }

        Inventory playerInv = player.getInventory();
        int current = itemLimitManager.countItemInInventory(player, material);

        boolean draggingToPlayerInv = false;
        for (int slot : event.getRawSlots()) {
            if (slot < playerInv.getSize() || slot == 40) {
                draggingToPlayerInv = true;
                break;
            }
        }
        if (!draggingToPlayerInv) return;

        int totalDragAmount = 0;
        for (ItemStack stack : event.getNewItems().values()) {
            if (stack != null && stack.getType() == material) {
                totalDragAmount += stack.getAmount();
            }
        }

        if (current >= limit) {
            event.setCancelled(true);
            messenger.sendBlocked(player, material, limit);
            return;
        }

        if (current + totalDragAmount > limit) {
            event.setCancelled(true);
            messenger.sendBlocked(player, material, limit);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShiftClick(InventoryClickEvent event) {
        if (event.getClick() != ClickType.SHIFT_LEFT && event.getClick() != ClickType.SHIFT_RIGHT) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        Material material = clicked.getType();
        if (!itemLimitManager.isItemLimited(material)) return;

        Inventory clickedInv = event.getClickedInventory();
        Inventory playerInv = player.getInventory();
        if (clickedInv == playerInv) return; // Only handle container -> player transfers.

        int limit = itemLimitManager.getLimit(material);

        // Shift-clicking a crafting result is special: vanilla "craft all"
        // can perform many crafts in a single click (moving far more than
        // the single result stack visible in getCurrentItem()), and
        // cancelling InventoryClickEvent is known to not reliably undo a
        // crafting-inventory shift-click transfer - the ingredient can stay
        // consumed with the result stuck in the output slot, which is
        // itself a dupe vector (pressing Escape walks away with it). So
        // instead of trying to cancel or predict the amount up front, always
        // let the click fully resolve, then correct it a tick later by
        // dropping anything banned or over the limit - the same safety net
        // used for /give and other bypasses.
        boolean isCraftingResult = clickedInv.getType() == InventoryType.CRAFTING
                || clickedInv.getType() == InventoryType.WORKBENCH;

        if (isCraftingResult) {
            sweepExcessNextTick(player);
            return;
        }

        if (limit == 0) {
            event.setCancelled(true);
            messenger.sendBlocked(player, material, limit);
            return;
        }

        int current = itemLimitManager.countItemInInventory(player, material);
        if (current >= limit) {
            event.setCancelled(true);
            messenger.sendBlocked(player, material, limit);
            return;
        }

        int amountToTransfer = clicked.getAmount();
        if (current + amountToTransfer > limit) {
            event.setCancelled(true);
            int canTransfer = limit - current;

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) return;

                    clicked.setAmount(clicked.getAmount() - canTransfer);

                    ItemStack toAdd = clicked.clone();
                    toAdd.setAmount(canTransfer);
                    playerInv.addItem(toAdd);

                    player.updateInventory();
                    messenger.sendPartial(player, material, canTransfer, limit);
                }
            }.runTask(plugin);
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        ItemStack cursor = player.getItemOnCursor();
        Material material = (cursor == null || cursor.getType() == Material.AIR) ? null : cursor.getType();

        if (material != null && itemLimitManager.isItemLimited(material)) {
            int limit = itemLimitManager.getLimit(material);
            int current = itemLimitManager.countItemInInventory(player, material);

            if (limit == 0 || current >= limit) {
                dropCursorSafe(player);
                messenger.sendBlocked(player, material, limit);
            } else if (current + cursor.getAmount() > limit) {
                // Cursor item doesn't push you over on its own, but merging
                // it with what's already in your inventory would - keep only
                // as many as fit under the limit and drop the exact excess.
                int canKeep = limit - current;
                int excess = cursor.getAmount() - canKeep;

                ItemStack keep = cursor.clone();
                keep.setAmount(canKeep);
                player.setItemOnCursor(keep);

                ItemStack drop = cursor.clone();
                drop.setAmount(excess);
                player.getWorld().dropItemNaturally(player.getLocation(), drop);

                messenger.sendPartial(player, material, canKeep, limit);
            }
        }

        // Also sweep for excess items that may have been added (e.g. /give)
        // while this inventory was open.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                excessEnforcer.checkAndDropAllExcess(player);
            }
        }.runTask(plugin);
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;

        Material material = cursor.getType();
        if (!itemLimitManager.isItemLimited(material)) return;

        int limit = itemLimitManager.getLimit(material);
        int current = itemLimitManager.countItemInInventory(player, material);

        if (limit == 0 || current >= limit) {
            event.setCancelled(true);
            dropCursorSafe(player);
            messenger.sendBlocked(player, material, limit);
        }
    }

    // HELPERS
    private void sweepExcessNextTick(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                excessEnforcer.checkAndDropAllExcess(player);
            }
        }.runTask(plugin);
    }

    private boolean isAddingToPlayer(InventoryAction action, Inventory clicked, Inventory playerInv) {
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return clicked != playerInv;
        }
        if (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            // A hotbar-swap that stays within the player's own inventory
            // doesn't change their total count; only count it when the
            // clicked slot belongs to an external container.
            return clicked != playerInv;
        }
        return clicked == playerInv;
    }

    private void handlePartialTransferFromContainer(Player player, InventoryClickEvent event, Material material, int canAdd) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                ItemStack source = event.getCurrentItem();
                if (source == null) return;

                source.setAmount(source.getAmount() - canAdd);

                ItemStack toAdd = source.clone();
                toAdd.setAmount(canAdd);
                player.getInventory().addItem(toAdd);

                player.updateInventory();
                messenger.sendPartial(player, material, canAdd, itemLimitManager.getLimit(material));
            }
        }.runTask(plugin);
    }

    private void handlePartialTransferFromCursor(Player player, ItemStack cursor, Material material, int canAdd) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                ItemStack toAdd = cursor.clone();
                toAdd.setAmount(canAdd);
                player.getInventory().addItem(toAdd);

                ItemStack toDrop = cursor.clone();
                toDrop.setAmount(cursor.getAmount() - canAdd);
                player.getWorld().dropItemNaturally(player.getLocation(), toDrop);

                player.setItemOnCursor(null);
                player.updateInventory();

                messenger.sendPartial(player, material, canAdd, itemLimitManager.getLimit(material));
            }
        }.runTask(plugin);
    }

    private void dropCursorSafe(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;

        ItemStack drop = cursor.clone();
        player.setItemOnCursor(null);

        new BukkitRunnable() {
            @Override
            public void run() {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }.runTask(plugin);
    }
}