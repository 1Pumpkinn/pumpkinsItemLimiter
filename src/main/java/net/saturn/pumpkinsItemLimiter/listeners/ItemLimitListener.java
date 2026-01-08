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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class ItemLimitListener implements Listener {

    private final PumpkinsItemLimiter plugin;
    private final ItemLimitManager itemLimitManager;

    public ItemLimitListener(PumpkinsItemLimiter plugin, ItemLimitManager itemLimitManager) {
        this.plugin = plugin;
        this.itemLimitManager = itemLimitManager;
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
        int current = itemLimitManager.countItemInInventory(player, material);

        // If banned, cancel pickup
        if (limit == 0) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
            return;
        }

        // If at or over limit, cancel pickup
        if (current >= limit) {
            event.setCancelled(true);
            sendBlockedMessage(player, material, limit);
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

        ItemStack moving = null;
        boolean fromContainer = clicked != playerInv;

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

            default:
                return;
        }

        if (moving == null || moving.getType() == Material.AIR) return;

        Material material = moving.getType();
        if (!itemLimitManager.isItemLimited(material)) return;

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

        // Check if any dragged slot is in player inventory
        boolean draggingToPlayerInv = false;
        for (int slot : event.getRawSlots()) {
            if (slot < playerInv.getSize()) {
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
        if (cursor == null || cursor.getType() == Material.AIR) return;

        Material material = cursor.getType();
        if (!itemLimitManager.isItemLimited(material)) return;

        int limit = itemLimitManager.getLimit(material);
        int current = itemLimitManager.countItemInInventory(player, material);

        // If banned or at/over limit, drop the item
        if (limit == 0 || current >= limit) {
            dropCursorSafe(player);
            sendBlockedMessage(player, material, limit);
        }
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