package net.saturn.itemlimiter.util;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Small text helpers shared across the plugin: turning a Material like
 * {@code NETHERITE_SWORD} into a readable "Netherite Sword", and translating
 * {@code &}-based color codes for chat messages.
 * <p>
 * This used to be copy-pasted (with minor variations) in
 * ItemLimitListener, VillagerTradeListener, and ItemLimitCommand.
 */
public final class ItemNameFormatter {

    private ItemNameFormatter() {
        // Utility class - not instantiable.
    }

    /** e.g. Material.NETHERITE_SWORD -> "Netherite Sword" */
    public static String format(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();

        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }

        return out.toString();
    }

    /** Translates {@code &}-based color codes (e.g. "&cHello") into chat colors. */
    public static String colorize(String message) {
        return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', message);
    }
}
