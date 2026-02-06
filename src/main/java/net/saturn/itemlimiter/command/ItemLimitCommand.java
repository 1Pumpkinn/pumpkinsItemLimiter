package net.saturn.itemlimiter.command;

import net.saturn.itemlimiter.ItemLimiter;
import net.saturn.itemlimiter.managers.ItemLimitManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ItemLimitCommand implements CommandExecutor, TabCompleter {

    private final ItemLimiter plugin;
    private final ItemLimitManager itemLimitManager;

    public ItemLimitCommand(ItemLimiter plugin, ItemLimitManager itemLimitManager) {
        this.plugin = plugin;
        this.itemLimitManager = itemLimitManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("BetterCombatLogging.admin")) {
            sender.sendMessage(colorize("&cYou don't have permission to use this command!"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "add":
                return handleAdd(sender, args);
            case "remove":
                return handleRemove(sender, args);
            case "list":
                return handleList(sender, args);
            case "clear":
                return handleClear(sender);
            case "check":
                return handleCheck(sender, args);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleAdd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(colorize("&cUsage: /itemlimit add <item> [maxQuantity]"));
            sender.sendMessage(colorize("&7Example: /itemlimit add GOLDEN_APPLE 64"));
            sender.sendMessage(colorize("&7Example: /itemlimit add TOTEM_OF_UNDYING 1"));
            sender.sendMessage(colorize("&7Example: /itemlimit add NETHERITE_SWORD 0 &8(completely ban)"));
            return true;
        }

        String itemName = args[1].toUpperCase();
        Material material;

        try {
            material = Material.valueOf(itemName);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(colorize("&cInvalid item: &e" + args[1]));
            sender.sendMessage(colorize("&7Use tab completion or check the Minecraft wiki for valid item names."));
            return true;
        }

        if (material == Material.AIR) {
            sender.sendMessage(colorize("&cYou cannot limit AIR!"));
            return true;
        }

        // Default to 0 (banned) if no quantity specified
        int maxQuantity = 0;

        if (args.length >= 3) {
            try {
                maxQuantity = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(colorize("&cInvalid quantity! Must be a number."));
                return true;
            }

            if (maxQuantity < 0) {
                sender.sendMessage(colorize("&cQuantity cannot be negative!"));
                return true;
            }

            if (maxQuantity > 2304) { // 36 stacks (full inventory)
                sender.sendMessage(colorize("&cQuantity cannot exceed 2304 (36 stacks)!"));
                return true;
            }
        }

        itemLimitManager.addItem(material, maxQuantity);

        if (maxQuantity == 0) {
            sender.sendMessage(colorize("&aCompletely banned &e" + formatMaterialName(material) + "&a!"));
            sender.sendMessage(colorize("&7Players cannot obtain this item at all."));

            String message = plugin.getConfig().getString(
                    "messages.item-banned",
                    "&e{item} &chas been completely banned!"
            ).replace("{item}", formatMaterialName(material));
            Bukkit.broadcastMessage(colorize(message));
        } else {
            sender.sendMessage(colorize("&aLimited &e" + formatMaterialName(material) + " &ato &6" + maxQuantity + " &aitems!"));
            sender.sendMessage(colorize("&7Players can have a maximum of " + maxQuantity + " of this item."));

            String message = plugin.getConfig().getString(
                            "messages.item-limited-quantity",
                            "&e{item} &chas been limited to &6{quantity} &citems!"
                    ).replace("{item}", formatMaterialName(material))
                    .replace("{quantity}", String.valueOf(maxQuantity));
            Bukkit.broadcastMessage(colorize(message));
        }

        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(colorize("&cUsage: /itemlimit remove <item>"));
            sender.sendMessage(colorize("&7Example: /itemlimit remove GOLDEN_APPLE"));
            return true;
        }

        String itemName = args[1].toUpperCase();
        Material material;

        try {
            material = Material.valueOf(itemName);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(colorize("&cInvalid item: &e" + args[1]));
            return true;
        }

        if (!itemLimitManager.isItemLimited(material)) {
            sender.sendMessage(colorize("&e" + formatMaterialName(material) + " &cis not currently limited!"));
            return true;
        }

        itemLimitManager.removeItem(material);
        sender.sendMessage(colorize("&aRemoved limit on &e" + formatMaterialName(material) + "&a!"));

        String message = plugin.getConfig().getString(
                "messages.item-limit-removed",
                "&e{item} &ais no longer limited!"
        ).replace("{item}", formatMaterialName(material));
        Bukkit.broadcastMessage(colorize(message));

        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        Map<Material, Integer> limitedItems = itemLimitManager.getLimitedItems();

        if (limitedItems.isEmpty()) {
            sender.sendMessage(colorize("&eNo items are currently limited."));
            sender.sendMessage(colorize("&7Use &e/itemlimit add <item> [quantity] &7to limit an item."));
            return true;
        }

        int page = 1;
        int itemsPerPage = 10;

        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(colorize("&cInvalid page number!"));
                return true;
            }
        }

        List<Map.Entry<Material, Integer>> entries = new ArrayList<>(limitedItems.entrySet());
        entries.sort(Map.Entry.comparingByKey((m1, m2) -> m1.name().compareTo(m2.name())));

        int totalPages = (int) Math.ceil((double) entries.size() / itemsPerPage);

        if (page < 1 || page > totalPages) {
            sender.sendMessage(colorize("&cInvalid page! Valid pages: 1-" + totalPages));
            return true;
        }

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, entries.size());

        sender.sendMessage(colorize("&6&m----------&r &e&lLimited Items &7(Page " + page + "/" + totalPages + ") &6&m----------"));
        sender.sendMessage(colorize("&7Total: &e" + entries.size()));
        sender.sendMessage("");

        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<Material, Integer> entry = entries.get(i);
            String itemName = formatMaterialName(entry.getKey());
            int limit = entry.getValue();

            if (limit == 0) {
                sender.sendMessage(colorize("&8• &e" + itemName + " &c[BANNED]"));
            } else {
                sender.sendMessage(colorize("&8• &e" + itemName + " &7- Max: &6" + limit));
            }
        }

        if (page < totalPages) {
            sender.sendMessage("");
            sender.sendMessage(colorize("&7Use &e/itemlimit list " + (page + 1) + " &7for the next page."));
        }

        sender.sendMessage(colorize("&6&m---------------------------------------"));

        return true;
    }

    private boolean handleClear(CommandSender sender) {
        int count = itemLimitManager.getLimitedItemCount();

        if (count == 0) {
            sender.sendMessage(colorize("&cNo items are currently limited!"));
            return true;
        }

        itemLimitManager.clearItems();
        sender.sendMessage(colorize("&aCleared all &e" + count + " &alimited items!"));

        String message = plugin.getConfig().getString(
                "messages.all-item-limits-removed",
                "&aAll item limitations have been removed!"
        );
        Bukkit.broadcastMessage(colorize(message));

        return true;
    }

    private boolean handleCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(colorize("&cUsage: /itemlimit check <item>"));
            sender.sendMessage(colorize("&7Example: /itemlimit check GOLDEN_APPLE"));
            return true;
        }

        String itemName = args[1].toUpperCase();
        Material material;

        try {
            material = Material.valueOf(itemName);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(colorize("&cInvalid item: &e" + args[1]));
            return true;
        }

        if (itemLimitManager.isItemLimited(material)) {
            Integer limit = itemLimitManager.getLimit(material);
            if (limit == 0) {
                sender.sendMessage(colorize("&e" + formatMaterialName(material) + " &cis completely BANNED!"));
                sender.sendMessage(colorize("&7Players cannot obtain this item at all."));
            } else {
                sender.sendMessage(colorize("&e" + formatMaterialName(material) + " &cis limited to &6" + limit + " &citems!"));
                sender.sendMessage(colorize("&7Players can have a maximum of " + limit + " of this item."));
            }
        } else {
            sender.sendMessage(colorize("&e" + formatMaterialName(material) + " &ais not limited."));
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(colorize("&6&m----------&r &e&lItem Limiter &6&m----------"));
        sender.sendMessage(colorize("&e/itemlimit add <item> [quantity] &7- Limit an item"));
        sender.sendMessage(colorize("  &8• &7No quantity = completely banned"));
        sender.sendMessage(colorize("  &8• &7Quantity 0 = completely banned"));
        sender.sendMessage(colorize("  &8• &7Quantity 1-2304 = max amount allowed"));
        sender.sendMessage(colorize("&e/itemlimit remove <item> &7- Remove item limit"));
        sender.sendMessage(colorize("&e/itemlimit list [page] &7- List all limited items"));
        sender.sendMessage(colorize("&e/itemlimit check <item> &7- Check if item is limited"));
        sender.sendMessage(colorize("&e/itemlimit clear &7- Clear all limited items"));
        sender.sendMessage(colorize("&6&m---------------------------------------"));
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("add", "remove", "list", "check", "clear"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("check")) {
                completions.addAll(Arrays.stream(Material.values())
                        .filter(Material::isItem)
                        .filter(m -> m != Material.AIR)
                        .map(Material::name)
                        .collect(Collectors.toList()));
            } else if (args[0].equalsIgnoreCase("remove")) {
                completions.addAll(itemLimitManager.getLimitedItemNames());
            } else if (args[0].equalsIgnoreCase("list")) {
                int totalPages = (int) Math.ceil((double) itemLimitManager.getLimitedItemCount() / 10);
                for (int i = 1; i <= Math.min(totalPages, 5); i++) {
                    completions.add(String.valueOf(i));
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            // Suggest common quantities
            completions.addAll(Arrays.asList("0", "1", "8", "16", "32", "64"));
        }

        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .collect(Collectors.toList());
    }
}