package net.saturn.pumpkinsItemLimiter.listeners;

import net.saturn.pumpkinsItemLimiter.PumpkinsItemLimiter;
import net.saturn.pumpkinsItemLimiter.managers.ItemLimitManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class ItemLimitListener implements Listener {

    private final PumpkinsItemLimiter plugin;
    private final ItemLimitManager itemLimitManager;
    private BukkitRunnable periodicCheckTask;

    public ItemLimitListener(PumpkinsItemLimiter plugin, ItemLimitManager itemLimitManager) {
        this.plugin = plugin;
        this.itemLimitManager = itemLimitManager;
        startPeriodicCheck();
    }

    /**
     * Starts a periodic task that checks all online players for excess items
     * This catches items added via /give or other means that might bypass normal events
     */
    private void startPeriodicCheck() {
        // Run every 5 seconds (100 ticks) to check all players for excess items
        periodicCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    return;
                }

                // Check all online players for excess items
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player != null && player.isOnline()) {
                        checkAndDropAllExcess(player);
                    }
                }
            }
        };
        periodicCheckTask.runTaskTimer(plugin, 100L, 100L); // Start after 5 seconds, then every 5 seconds
    }

    /**
     * Stops the periodic check task (useful for cleanup)
     */
    public void stopPeriodicCheck() {
        if (periodicCheckTask != null && !periodicCheckTask.isCancelled()) {
            periodicCheckTask.cancel();
        }
    }

    /* ============================================================
       PICKUP (GROUND → INVENTORY)
       ============================================================ */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack stack = event.getItem().getItemStack();
        Material material = stack.getType();

        if (!itemLimitManager.isItemLimited(material)) return;

        int limit = itemLimitManager.getLimit(material);

        // If banned, cancel pickup
        if (limit == 0) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
            return;
        }

        int current = itemLimitManager.countItemInInventory(player, material);
        int amountPickingUp = stack.getAmount();
        int totalAfterPickup = current + amountPickingUp;

        // If already at or over limit, cancel the pickup
        if (current >= limit) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
            return;
        }

        // If picking up would exceed limit, cancel the pickup
        if (totalAfterPickup > limit) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
        }
        // If totalAfterPickup <= limit, allow the pickup (don't cancel event)
    }

    /* ============================================================
       HAND SWAP (F KEY)
       ============================================================ */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHandSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack offHandItem = event.getOffHandItem();
        ItemStack mainHandItem = event.getMainHandItem();

        // Check both items being swapped
        Material offHandMat = offHandItem != null ? offHandItem.getType() : Material.AIR;
        Material mainHandMat = mainHandItem != null ? mainHandItem.getType() : Material.AIR;

        // Only check limited items
        boolean offHandLimited = offHandMat != Material.AIR && itemLimitManager.isItemLimited(offHandMat);
        boolean mainHandLimited = mainHandMat != Material.AIR && itemLimitManager.isItemLimited(mainHandMat);

        if (!offHandLimited && !mainHandLimited) return;

        // For hand swaps, we don't need to cancel as the total count stays the same
        // Just verify neither item is banned
        if (offHandLimited) {
            int limit = itemLimitManager.getLimit(offHandMat);
            if (limit == 0) {
                event.setCancelled(true);
                sendBlockedMessage(player, offHandMat, limit);
                return;
            }
        }

        if (mainHandLimited) {
            int limit = itemLimitManager.getLimit(mainHandMat);
            if (limit == 0) {
                event.setCancelled(true);
                sendBlockedMessage(player, mainHandMat, limit);
            }
        }
    }

    /* ============================================================
       INVENTORY CLICK
       ============================================================ */
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

        // Check if clicking offhand slot (slot 40 in player inventory)
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

            case MOVE_TO_OTHER_INVENTORY:
                moving = event.getCurrentItem();
                break;

            case HOTBAR_SWAP:
            case HOTBAR_MOVE_AND_READD:
                // Handle hotbar swaps to/from offhand
                if (isOffhandSlot) {
                    int hotbarButton = event.getHotbarButton();
                    if (hotbarButton >= 0 && hotbarButton < 9) {
                        ItemStack hotbarItem = playerInv.getItem(hotbarButton);
                        if (hotbarItem != null && hotbarItem.getType() != Material.AIR) {
                            moving = hotbarItem;
                            fromContainer = false;
                        }
                    }
                }
                break;

            default:
                return;
        }

        if (moving == null || moving.getType() == Material.AIR) return;

        Material material = moving.getType();
        if (!itemLimitManager.isItemLimited(material)) return;

        // Special handling for offhand slot - items moving there should be allowed within limit
        if (isOffhandSlot) {
            int limit = itemLimitManager.getLimit(material);

            // If banned, always cancel
            if (limit == 0) {
                event.setCancelled(true);
                player.updateInventory();
                sendBlockedMessage(player, material, limit);
                return;
            }

            // Check if this is a swap (offhand already has an item of the same material)
            ItemStack currentOffhand = player.getInventory().getItemInOffHand();
            boolean isSwap = currentOffhand != null && currentOffhand.getType() == material;

            if (isSwap) {
                // If swapping same material, total count doesn't change, so allow it
                return;
            }

            // For adding to offhand, check if it would exceed limit
            int current = itemLimitManager.countItemInInventory(player, material);
            int amountToAdd = moving.getAmount();

            if (current + amountToAdd > limit) {
                event.setCancelled(true);
                player.updateInventory();
                sendBlockedMessage(player, material, limit);
                return;
            }

            // Allow the move if within limit
            return;
        }

        if (!isAddingToPlayer(action, clicked, playerInv)) return;

        int limit = itemLimitManager.getLimit(material);
        int current = itemLimitManager.countItemInInventory(player, material);

        // If banned, always cancel
        if (limit == 0) {
            event.setCancelled(true);
            player.updateInventory();
            sendBlockedMessage(player, material, limit);
            return;
        }

        // If at or over limit, cancel the action
        if (current >= limit) {
            event.setCancelled(true);
            player.updateInventory();

            // If from cursor (not container), drop it
            if (!fromContainer) {
                dropCursorSafe(player);
            }

            sendBlockedMessage(player, material, limit);
            return;
        }

        // Check if adding would exceed limit
        int amountToAdd = moving.getAmount();
        if (current + amountToAdd > limit) {
            event.setCancelled(true);
            player.updateInventory();

            // Calculate how much can be added
            int canAdd = limit - current;

            if (fromContainer) {
                // From container - add what we can, leave rest in container
                handlePartialTransferFromContainer(player, event, material, canAdd);
            } else {
                // From cursor - add what we can, drop the rest
                handlePartialTransferFromCursor(player, moving, material, canAdd);
            }

            return;
        }
    }

    /* ============================================================
       DRAG EVENT - CRITICAL FOR CHEST/SHULKER DRAGGING
       ============================================================ */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack draggedItem = event.getOldCursor();
        if (draggedItem == null || draggedItem.getType() == Material.AIR) return;

        Material material = draggedItem.getType();
        if (!itemLimitManager.isItemLimited(material)) return;

        int limit = itemLimitManager.getLimit(material);

        // If banned, always cancel
        if (limit == 0) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
            return;
        }

        Inventory playerInv = player.getInventory();
        int current = itemLimitManager.countItemInInventory(player, material);

        // Check if any dragged slot is in player inventory (including offhand slot 40)
        boolean draggingToPlayerInv = false;
        for (int slot : event.getRawSlots()) {
            if (slot < playerInv.getSize() || slot == 40) {
                draggingToPlayerInv = true;
                break;
            }
        }

        if (!draggingToPlayerInv) return;

        // Calculate total amount being dragged
        int totalDragAmount = 0;
        for (ItemStack stack : event.getNewItems().values()) {
            if (stack != null && stack.getType() == material) {
                totalDragAmount += stack.getAmount();
            }
        }

        // If at or over limit, cancel
        if (current >= limit) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
            return;
        }

        // If dragging would exceed limit, cancel
        if (current + totalDragAmount > limit) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
        }
    }

    /* ============================================================
       SHIFT CLICK - HANDLES MASS TRANSFER
       ============================================================ */
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

        // Only handle shift-clicking FROM a container TO player inventory
        if (clickedInv == playerInv) return;

        int limit = itemLimitManager.getLimit(material);

        // If banned, cancel
        if (limit == 0) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
            return;
        }

        int current = itemLimitManager.countItemInInventory(player, material);

        // If at limit, cancel
        if (current >= limit) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
            return;
        }

        int amountToTransfer = clicked.getAmount();

        // If transfer would exceed limit, handle partial transfer
        if (current + amountToTransfer > limit) {
            event.setCancelled(true);

            int canTransfer = limit - current;

            // Schedule the partial transfer for next tick
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) return;

                    // Remove from source
                    clicked.setAmount(clicked.getAmount() - canTransfer);

                    // Add to player inventory
                    ItemStack toAdd = clicked.clone();
                    toAdd.setAmount(canTransfer);
                    playerInv.addItem(toAdd);

                    player.updateInventory();
                    sendPartialMessage(player, material, canTransfer, limit);
                }
            }.runTask(plugin);
        }
    }

    /* ============================================================
       INVENTORY CLOSE (ESC)
       ============================================================ */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR) {
            // No item on cursor, but check for excess items in inventory
            // This catches items added via /give or other means
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) return;
                    checkAndDropAllExcess(player);
                }
            }.runTask(plugin);
            return;
        }

        Material material = cursor.getType();
        if (!itemLimitManager.isItemLimited(material)) {
            // Check for other excess items even if cursor item isn't limited
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) return;
                    checkAndDropAllExcess(player);
                }
            }.runTask(plugin);
            return;
        }

        int limit = itemLimitManager.getLimit(material);
        int current = itemLimitManager.countItemInInventory(player, material);

        // If banned or at/over limit, drop the item
        if (limit == 0 || current >= limit) {
            dropCursorSafe(player);
            sendBlockedMessage(player, material, limit);
        }

        // Also check for excess items in inventory after handling cursor
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                checkAndDropAllExcess(player);
            }
        }.runTask(plugin);
    }

    /* ============================================================
       DROP KEY (Q)
       ============================================================ */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;

        Material material = cursor.getType();
        if (!itemLimitManager.isItemLimited(material)) return;

        int limit = itemLimitManager.getLimit(material);
        int current = itemLimitManager.countItemInInventory(player, material);

        // If banned or at/over limit, allow drop but clear cursor
        if (limit == 0 || current >= limit) {
            event.setCancelled(true);
            dropCursorSafe(player);
            sendBlockedMessage(player, material, limit);
        }
    }

    /* ============================================================
       LOGIN ENFORCEMENT
       ============================================================ */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                int dropped = 0;
                for (Material mat : itemLimitManager.getLimitedItems().keySet()) {
                    dropped += itemLimitManager.dropExcess(player, mat);
                }

                if (dropped > 0) {
                    player.sendMessage(colorize(
                            plugin.getConfig().getString(
                                    "messages.items-dropped-login",
                                    "&eDropped &6{count} &elimited items at your feet!"
                            ).replace("{count}", String.valueOf(dropped))
                    ));
                }
            }
        }.runTaskLater(plugin, 20L);
    }

    /* ============================================================
       HELPERS
       ============================================================ */
    private boolean isAddingToPlayer(InventoryAction action, Inventory clicked, Inventory playerInv) {
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
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

                // Take what we can from the source
                int remaining = source.getAmount() - canAdd;
                source.setAmount(remaining);

                // Add to player inventory
                ItemStack toAdd = source.clone();
                toAdd.setAmount(canAdd);
                player.getInventory().addItem(toAdd);

                player.updateInventory();
                sendPartialMessage(player, material, canAdd, itemLimitManager.getLimit(material));
            }
        }.runTask(plugin);
    }

    private void handlePartialTransferFromCursor(Player player, ItemStack cursor, Material material, int canAdd) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                // Add what we can to inventory
                ItemStack toAdd = cursor.clone();
                toAdd.setAmount(canAdd);
                player.getInventory().addItem(toAdd);

                // Drop the rest
                ItemStack toDrop = cursor.clone();
                toDrop.setAmount(cursor.getAmount() - canAdd);
                player.getWorld().dropItemNaturally(player.getLocation(), toDrop);

                // Clear cursor
                player.setItemOnCursor(null);
                player.updateInventory();

                sendPartialMessage(player, material, canAdd, itemLimitManager.getLimit(material));
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

    /**
     * Checks and drops excess items for a specific material
     * Sends a message to the player if items were dropped
     */
    private void checkAndDropExcess(Player player, Material material) {
        if (!itemLimitManager.isItemLimited(material)) return;

        int dropped = itemLimitManager.dropExcess(player, material);
        if (dropped > 0) {
            int limit = itemLimitManager.getLimit(material);
            player.sendMessage(colorize(
                    plugin.getConfig().getString(
                                    "messages.items-dropped-excess",
                                    "&eDropped &6{amount} &e{item} &7(limit: {limit})"
                            )
                            .replace("{amount}", String.valueOf(dropped))
                            .replace("{item}", format(material))
                            .replace("{limit}", String.valueOf(limit))
            ));
            player.updateInventory();
        }
    }

    /**
     * Checks and drops excess items for all limited materials in player's inventory
     * This is useful for catching items added via /give or other means
     */
    private void checkAndDropAllExcess(Player player) {
        int totalDropped = 0;
        for (Material material : itemLimitManager.getLimitedItems().keySet()) {
            totalDropped += itemLimitManager.dropExcess(player, material);
        }

        if (totalDropped > 0) {
            player.sendMessage(colorize(
                    plugin.getConfig().getString(
                            "messages.items-dropped-excess-all",
                            "&eDropped &6{count} &eexcess limited items at your feet!"
                    ).replace("{count}", String.valueOf(totalDropped))
            ));
            player.updateInventory();
        }
    }

    private void sendBlockedMessage(Player player, Material material, int limit) {
        player.sendMessage(colorize(
                plugin.getConfig().getString(
                                limit == 0
                                        ? "messages.item-blocked-place-banned"
                                        : "messages.item-blocked-place-limit",
                                "&cYou cannot have &e{item}&c!"
                        )
                        .replace("{item}", format(material))
                        .replace("{limit}", String.valueOf(limit))
        ));
    }

    private void sendPartialMessage(Player player, Material material, int added, int limit) {
        player.sendMessage(colorize(
                plugin.getConfig().getString(
                                "messages.item-partial-pickup",
                                "&eAdded &6{amount} &e{item} &7(max: {limit})"
                        )
                        .replace("{item}", format(material))
                        .replace("{amount}", String.valueOf(added))
                        .replace("{limit}", String.valueOf(limit))
        ));
    }

    private String format(Material material) {
        String[] parts = material.name().toLowerCase().split("_");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            out.append(Character.toUpperCase(p.charAt(0)))
                    .append(p.substring(1))
                    .append(" ");
        }
        return out.toString().trim();
    }

    private String colorize(String msg) {
        return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', msg);
    }
}