package net.saturn.itemlimiter.command;

import net.saturn.itemlimiter.ItemLimiter;
import net.saturn.itemlimiter.managers.ItemLimitManager;
import net.saturn.itemlimiter.util.ItemNameFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ItemLimitCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS =
            List.of("add", "remove", "list", "check", "clear", "containers", "info", "reload");

    // Built once instead of on every tab-complete keystroke.
    private static final List<String> ITEM_NAMES = Arrays.stream(Material.values())
            .filter(m -> !m.isLegacy() && m.isItem() && !m.isAir())
            .map(Material::name)
            .sorted()
            .toList();

    private final ItemLimiter plugin;
    private final ItemLimitManager itemLimitManager;

    public ItemLimitCommand(ItemLimiter plugin, ItemLimitManager itemLimitManager) {
        this.plugin = plugin;
        this.itemLimitManager = itemLimitManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("itemlimiter.admin")) {
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
            case "containers":
            case "nested": // old name, kept so existing muscle memory/scripts still work
                return handleContainers(sender, args);
            case "reload":
                return handleReload(sender);
            case "info":
                return handleInfo(sender, args);
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

        // The quantity, if given, is always the last argument. Everything between
        // "add" and the quantity (or the end, if no quantity) is the item name,
        // so multi-word names like "golden apple" work without underscores.
        int itemEnd = args.length;
        Integer parsedQuantity = null;
        if (args.length >= 3) {
            try {
                parsedQuantity = Integer.parseInt(args[args.length - 1]);
                itemEnd = args.length - 1;
            } catch (NumberFormatException ignored) {
                // Last token isn't a number, so treat the whole remainder as the item name.
            }
        }

        String rawName = String.join(" ", Arrays.copyOfRange(args, 1, itemEnd));
        Material material = parseMaterial(rawName);

        if (material == null) {
            sender.sendMessage(colorize("&cInvalid item: &e" + rawName));
            sender.sendMessage(colorize("&7Use tab completion or check the Minecraft wiki for valid item names."));
            return true;
        }

        if (material == Material.AIR) {
            sender.sendMessage(colorize("&cYou cannot limit AIR!"));
            return true;
        }

        // Default to 0 (banned) if no quantity specified
        int maxQuantity = parsedQuantity != null ? parsedQuantity : 0;

        if (parsedQuantity != null) {
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

        String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Material material = parseMaterial(rawName);

        if (material == null) {
            sender.sendMessage(colorize("&cInvalid item: &e" + rawName));
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
        entries.sort(Map.Entry.comparingByKey(Comparator.comparing(Material::name)));

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

        String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Material material = parseMaterial(rawName);

        if (material == null) {
            sender.sendMessage(colorize("&cInvalid item: &e" + rawName));
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

    private boolean handleContainers(CommandSender sender, String[] args) {
        if (args.length < 2) {
            boolean current = itemLimitManager.isCountingItemsInContainers();
            sender.sendMessage(colorize("&cUsage: /itemlimit containers <on|off>"));
            sender.sendMessage(colorize("&7Currently: " + (current
                    ? "&aON &7(items in bundles/shulker boxes count)"
                    : "&cOFF &7(only items directly in the inventory count)")));
            return true;
        }

        boolean value;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable" -> value = true;
            case "off", "false", "disable" -> value = false;
            default -> {
                sender.sendMessage(colorize("&cUsage: /itemlimit containers <on|off>"));
                return true;
            }
        }

        itemLimitManager.setCountItemsInContainers(value);

        if (value) {
            sender.sendMessage(colorize("&aItems inside bundles and shulker boxes now count toward limits."));
        } else {
            sender.sendMessage(colorize("&eItems inside bundles and shulker boxes no longer count toward limits."));
            sender.sendMessage(colorize("&7Only items sitting directly in a player's inventory/armor/off-hand are limited."));
        }

        return true;
    }

    private boolean handleReload(CommandSender sender) {
        plugin.reloadConfig();
        itemLimitManager.load();
        sender.sendMessage(colorize("&aReloaded config.yml and limited-items.yml!"));
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(colorize("&cUsage: /itemlimit info <player>"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(colorize("&cPlayer not found or not online: &e" + args[1]));
            return true;
        }

        Map<Material, Integer> limitedItems = itemLimitManager.getLimitedItems();
        if (limitedItems.isEmpty()) {
            sender.sendMessage(colorize("&eNo items are currently limited."));
            return true;
        }

        sender.sendMessage(colorize("&6&m----------&r &e&lLimits for " + target.getName() + " &6&m----------"));
        if (itemLimitManager.isExempt(target)) {
            sender.sendMessage(colorize("&b" + target.getName() + " is EXEMPT from limits &7(itemlimiter.off)"));
        }

        boolean any = false;
        List<Material> sorted = new ArrayList<>(limitedItems.keySet());
        sorted.sort(Comparator.comparing(Material::name));

        for (Material material : sorted) {
            int count = itemLimitManager.countItemInInventory(target, material);
            if (count <= 0) continue;

            any = true;
            int limit = limitedItems.get(material);
            String status = limit == 0 ? "&c[BANNED]" : (count >= limit ? "&c[AT MAX]" : "&a[OK]");
            sender.sendMessage(colorize("&8• &e" + formatMaterialName(material) + " &7- " + count + "/" + limit + " " + status));
        }

        if (!any) {
            sender.sendMessage(colorize("&7" + target.getName() + " has none of the limited items."));
        }

        sender.sendMessage(colorize("&6&m---------------------------------------"));
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
        sender.sendMessage(colorize("&e/itemlimit containers <on|off> &7- Toggle counting items inside bundles/shulker boxes"));
        sender.sendMessage(colorize("&e/itemlimit info <player> &7- Show a player's current counts of limited items"));
        sender.sendMessage(colorize("&e/itemlimit reload &7- Reload config.yml and limited-items.yml"));
        sender.sendMessage(colorize("&6&m---------------------------------------"));
    }

    /**
     * Resolves user-typed item names to a Material, accepting friendly forms
     * like "golden apple" or "golden-apple" in addition to "GOLDEN_APPLE".
     */
    private Material parseMaterial(String rawName) {
        String normalized = rawName.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(normalized);
        // Rejects legacy materials and non-items (WATER, CAVE_AIR, ...).
        return material != null && !material.isLegacy() && material.isItem() ? material : null;
    }

    private String formatMaterialName(Material material) {
        return ItemNameFormatter.format(material);
    }

    private String colorize(String message) {
        return ItemNameFormatter.colorize(message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(SUB_COMMANDS);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "add", "check" -> completions.addAll(ITEM_NAMES);
                case "remove" -> completions.addAll(itemLimitManager.getLimitedItemNames());
                case "list" -> {
                    int totalPages = (int) Math.ceil(itemLimitManager.getLimitedItemCount() / 10.0);
                    for (int i = 1; i <= Math.min(totalPages, 5); i++) {
                        completions.add(String.valueOf(i));
                    }
                }
                case "containers", "nested" -> completions.addAll(List.of("on", "off"));
                case "info" -> Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
                default -> { }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            completions.addAll(List.of("0", "1", "8", "16", "32", "64"));
        }

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return completions.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}