package net.saturn.itemlimiter.listeners;

import net.saturn.itemlimiter.ItemLimiter;
import net.saturn.itemlimiter.managers.ItemLimitManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;

public class VillagerTradeListener implements Listener {

    private final ItemLimiter plugin;
    private final ItemLimitManager itemLimitManager;

    public VillagerTradeListener(ItemLimiter plugin, ItemLimitManager itemLimitManager) {
        this.plugin = plugin;
        this.itemLimitManager = itemLimitManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerTrade(InventoryClickEvent event) {
        // Only handle merchant (villager/wandering trader) inventories
        if (!(event.getInventory() instanceof MerchantInventory)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();

        // Check if clicking the result slot (slot 2 in merchant inventory)
        if (event.getRawSlot() != 2) {
            return;
        }

        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }

        Material material = result.getType();

        if (!itemLimitManager.isItemLimited(material)) {
            return;
        }

        Integer limit = itemLimitManager.getLimit(material);

        // If completely banned
        if (limit == 0) {
            event.setCancelled(true);

            String message = plugin.getConfig().getString(
                    "messages.item-blocked-trade-banned",
                    "&cYou cannot trade for &e{item}&c - it is banned!"
            ).replace("{item}", formatItemName(result));
            player.sendMessage(colorize(message));
            return;
        }

        // Check if trading would exceed limit
        int currentCount = itemLimitManager.countItemInInventory(player, material);
        int tradeAmount = result.getAmount();

        if (currentCount >= limit) {
            // Already at or over limit
            event.setCancelled(true);

            String message = plugin.getConfig().getString(
                            "messages.item-blocked-trade-limit",
                            "&cYou cannot trade for &e{item}&c - you already have the maximum ({limit})!"
                    ).replace("{item}", formatItemName(result))
                    .replace("{limit}", String.valueOf(limit));
            player.sendMessage(colorize(message));
            return;
        } else if (currentCount + tradeAmount > limit) {
            // Trade would exceed limit
            event.setCancelled(true);

            int canTrade = limit - currentCount;
            String message = plugin.getConfig().getString(
                            "messages.item-blocked-trade-partial",
                            "&cThis trade would exceed your limit! You can only have &6{remaining} &cmore &e{item}&c (limit: {limit})"
                    ).replace("{item}", formatItemName(result))
                    .replace("{remaining}", String.valueOf(canTrade))
                    .replace("{limit}", String.valueOf(limit));
            player.sendMessage(colorize(message));
            return;
        }

        // Trade is within limits - allow it to proceed
    }

    private String formatItemName(ItemStack item) {
        return formatMaterialName(item.getType());
    }

    private String formatMaterialName(Material material) {
        String name = material.name().replace("_", " ");
        String[] words = name.split(" ");
        StringBuilder formatted = new StringBuilder();

        for (String word : words) {
            if (formatted.length() > 0) {
                formatted.append(" ");
            }
            formatted.append(word.substring(0, 1).toUpperCase())
                    .append(word.substring(1).toLowerCase());
        }

        return formatted.toString();
    }

    private String colorize(String message) {
        return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', message);
    }
}