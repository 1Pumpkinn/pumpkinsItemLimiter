package net.saturn.itemlimiter.managers;

import net.saturn.itemlimiter.ItemLimiter;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ItemLimitManager {

    private final ItemLimiter plugin;
    private final Map<Material, Integer> limitedItems; // Material -> max quantity (0 = completely banned)
    private final File dataFile;
    private FileConfiguration data;

    public ItemLimitManager(ItemLimiter plugin) {
        this.plugin = plugin;
        this.limitedItems = new HashMap<>();
        this.dataFile = new File(plugin.getDataFolder(), "limited-items.yml");
    }

    public void load() {
        limitedItems.clear();

        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create limited-items.yml: " + e.getMessage());
            }
        }

        data = YamlConfiguration.loadConfiguration(dataFile);

        if (data.contains("limited-items")) {
            for (String key : data.getConfigurationSection("limited-items").getKeys(false)) {
                try {
                    Material material = Material.valueOf(key.toUpperCase());
                    int limit = data.getInt("limited-items." + key);
                    limitedItems.put(material, limit);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid material in limited-items.yml: " + key);
                }
            }
            plugin.getLogger().info("Loaded " + limitedItems.size() + " limited items");
        }
    }

    public void save() {
        try {
            data.set("limited-items", null);

            if (!limitedItems.isEmpty()) {
                for (Map.Entry<Material, Integer> entry : limitedItems.entrySet()) {
                    data.set("limited-items." + entry.getKey().name(), entry.getValue());
                }
            }

            data.save(dataFile);
            plugin.getLogger().info("Saved limited items");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save limited items: " + e.getMessage());
        }
    }

    public boolean addItem(Material material, int maxQuantity) {
        if (material == null || material == Material.AIR) {
            return false;
        }

        if (maxQuantity < 0) {
            return false;
        }

        limitedItems.put(material, maxQuantity);
        save();
        return true;
    }

    public boolean removeItem(Material material) {
        boolean removed = limitedItems.remove(material) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public boolean isItemLimited(Material material) {
        return limitedItems.containsKey(material);
    }

    public boolean isItemLimited(ItemStack item) {
        return item != null && limitedItems.containsKey(item.getType());
    }

    public boolean isItemBanned(Material material) {
        return limitedItems.containsKey(material) && limitedItems.get(material) == 0;
    }

    public boolean isItemBanned(ItemStack item) {
        return item != null && isItemBanned(item.getType());
    }

    public Integer getLimit(Material material) {
        return limitedItems.get(material);
    }

    public Integer getLimit(ItemStack item) {
        return item != null ? limitedItems.get(item.getType()) : null;
    }

    public Map<Material, Integer> getLimitedItems() {
        return new HashMap<>(limitedItems);
    }

    public List<String> getLimitedItemNames() {
        return limitedItems.keySet().stream()
                .map(Material::name)
                .sorted()
                .collect(Collectors.toList());
    }

    public int getLimitedItemCount() {
        return limitedItems.size();
    }

    public void clearItems() {
        limitedItems.clear();
        save();
    }

    public boolean hasLimitedItems() {
        return !limitedItems.isEmpty();
    }

    /**
     * Whether items nested inside bundles/shulker boxes count toward limits.
     * Backed by config.yml so it can be toggled live via /itemlimit nested.
     */
    public boolean isLimitNestedContainers() {
        return plugin.getConfig().getBoolean("settings.limit-nested-containers", true);
    }

    public void setLimitNestedContainers(boolean value) {
        plugin.getConfig().set("settings.limit-nested-containers", value);
        plugin.saveConfig();
    }

    /**
     * Counts how many of a specific material a player has across their entire
     * inventory, including items nested inside bundles and shulker boxes
     * (unless nested-container limiting has been disabled).
     */
    public int countItemInInventory(org.bukkit.entity.Player player, Material material) {
        boolean nested = isLimitNestedContainers();
        int count = 0;

        for (ItemStack item : player.getInventory().getStorageContents()) {
            count += countItemInStack(item, material, nested);
        }

        for (ItemStack item : player.getInventory().getArmorContents()) {
            count += countItemInStack(item, material, nested);
        }

        count += countItemInStack(player.getInventory().getItemInOffHand(), material, nested);

        return count;
    }

    /**
     * Recursively counts how many of a specific material are in an ItemStack,
     * looking inside bundles and shulker boxes when nested is true.
     */
    private int countItemInStack(ItemStack item, Material material, boolean nested) {
        if (item == null || item.getType() == Material.AIR) return 0;

        Material type = item.getType();
        int count = 0;

        if (type == material) {
            count += item.getAmount();
        }

        if (!nested) return count;

        if (isBundle(type) && item.getItemMeta() instanceof BundleMeta bundleMeta) {
            if (bundleMeta.hasItems()) {
                for (ItemStack bundled : bundleMeta.getItems()) {
                    count += countItemInStack(bundled, material, true);
                }
            }
        }

        if (isShulkerBox(type) && item.getItemMeta() instanceof BlockStateMeta blockMeta) {
            if (blockMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
                for (ItemStack contained : shulkerBox.getInventory().getContents()) {
                    count += countItemInStack(contained, material, true);
                }
            }
        }

        return count;
    }

    /**
     * Matches the plain BUNDLE as well as every dyed variant
     * (e.g. RED_BUNDLE, BLACK_BUNDLE, ...), since each color is its own Material.
     */
    private boolean isBundle(Material material) {
        return material == Material.BUNDLE || material.name().endsWith("_BUNDLE");
    }

    private boolean isShulkerBox(Material material) {
        return material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX");
    }

    /**
     * Drops excess items from player's inventory to enforce limits.
     * Strips items from bundles and shulker boxes as needed.
     * Returns the number of items dropped.
     */
    public int dropExcess(org.bukkit.entity.Player player, Material material) {
        Integer limit = limitedItems.get(material);
        if (limit == null) {
            return 0;
        }

        int currentCount = countItemInInventory(player, material);

        if (currentCount <= limit) {
            return 0; // Within limit
        }

        int toDrop = currentCount - limit;
        int dropped = 0;
        boolean nested = isLimitNestedContainers();

        // Main inventory (slots 0-35: hotbar + storage)
        for (int i = 0; i < 36 && dropped < toDrop; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;

            if (item.getType() == material) {
                int amount = item.getAmount();
                int canDrop = Math.min(amount, toDrop - dropped);
                if (canDrop >= amount) {
                    player.getInventory().setItem(i, null);
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                } else {
                    ItemStack dropStack = item.clone();
                    dropStack.setAmount(canDrop);
                    item.setAmount(amount - canDrop);
                    player.getWorld().dropItemNaturally(player.getLocation(), dropStack);
                }
                dropped += canDrop;
                continue;
            }

            if (nested && isBundle(item.getType())) {
                int canDrop = dropFromBundle(item, material, toDrop - dropped, player);
                if (canDrop > 0) {
                    player.getInventory().setItem(i, item);
                    dropped += canDrop;
                }
                continue;
            }

            if (nested && isShulkerBox(item.getType())) {
                int canDrop = dropFromShulker(item, material, toDrop - dropped, player);
                if (canDrop > 0) {
                    player.getInventory().setItem(i, item);
                    dropped += canDrop;
                }
            }
        }

        if (dropped < toDrop) {
            ItemStack[] armor = player.getInventory().getArmorContents();
            for (int i = 0; i < armor.length && dropped < toDrop; i++) {
                if (armor[i] == null || armor[i].getType() != material) continue;
                int amount = armor[i].getAmount();
                int canDrop = Math.min(amount, toDrop - dropped);
                if (canDrop >= amount) {
                    player.getWorld().dropItemNaturally(player.getLocation(), armor[i]);
                    armor[i] = null;
                } else {
                    ItemStack dropStack = armor[i].clone();
                    dropStack.setAmount(canDrop);
                    armor[i].setAmount(amount - canDrop);
                    player.getWorld().dropItemNaturally(player.getLocation(), dropStack);
                }
                dropped += canDrop;
            }
            player.getInventory().setArmorContents(armor);
        }

        if (dropped < toDrop) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand != null && offHand.getType() == material) {
                int amount = offHand.getAmount();
                int canDrop = Math.min(amount, toDrop - dropped);
                if (canDrop >= amount) {
                    player.getWorld().dropItemNaturally(player.getLocation(), offHand);
                    player.getInventory().setItemInOffHand(null);
                } else {
                    ItemStack dropStack = offHand.clone();
                    dropStack.setAmount(canDrop);
                    offHand.setAmount(amount - canDrop);
                    player.getWorld().dropItemNaturally(player.getLocation(), dropStack);
                }
                dropped += canDrop;
            }
        }

        return dropped;
    }

    /**
     * Recursively drops matching items out of a bundle's contents, descending
     * into any bundles or shulker boxes nested inside it. Mutates the given
     * bundle ItemStack's meta in place. Returns the number of items dropped.
     */
    private int dropFromBundle(ItemStack bundleItem, Material material, int toDrop, org.bukkit.entity.Player player) {
        if (toDrop <= 0 || !(bundleItem.getItemMeta() instanceof BundleMeta bundleMeta) || !bundleMeta.hasItems()) {
            return 0;
        }

        List<ItemStack> contents = new ArrayList<>(bundleMeta.getItems());
        int dropped = 0;
        boolean changed = false;

        for (int j = contents.size() - 1; j >= 0 && dropped < toDrop; j--) {
            ItemStack inner = contents.get(j);
            if (inner == null || inner.getType() == Material.AIR) continue;

            if (inner.getType() == material) {
                int amount = inner.getAmount();
                int canDrop = Math.min(amount, toDrop - dropped);
                if (canDrop >= amount) {
                    contents.remove(j);
                } else {
                    inner.setAmount(amount - canDrop);
                    contents.set(j, inner);
                }
                player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(material, canDrop));
                dropped += canDrop;
                changed = true;
                continue;
            }

            if (isBundle(inner.getType())) {
                int canDrop = dropFromBundle(inner, material, toDrop - dropped, player);
                if (canDrop > 0) {
                    contents.set(j, inner);
                    dropped += canDrop;
                    changed = true;
                }
            } else if (isShulkerBox(inner.getType())) {
                int canDrop = dropFromShulker(inner, material, toDrop - dropped, player);
                if (canDrop > 0) {
                    contents.set(j, inner);
                    dropped += canDrop;
                    changed = true;
                }
            }
        }

        if (changed) {
            bundleMeta.setItems(contents);
            bundleItem.setItemMeta(bundleMeta);
        }

        return dropped;
    }

    /**
     * Recursively drops matching items out of a shulker box's contents,
     * descending into any bundles or shulker boxes nested inside it. Mutates
     * the given shulker box ItemStack's meta in place. Returns the number of
     * items dropped.
     */
    private int dropFromShulker(ItemStack shulkerItem, Material material, int toDrop, org.bukkit.entity.Player player) {
        if (toDrop <= 0 || !(shulkerItem.getItemMeta() instanceof BlockStateMeta blockMeta)) {
            return 0;
        }
        if (!(blockMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return 0;
        }

        ItemStack[] contents = shulkerBox.getInventory().getContents();
        int dropped = 0;
        boolean changed = false;

        for (int j = 0; j < contents.length && dropped < toDrop; j++) {
            ItemStack inner = contents[j];
            if (inner == null || inner.getType() == Material.AIR) continue;

            if (inner.getType() == material) {
                int amount = inner.getAmount();
                int canDrop = Math.min(amount, toDrop - dropped);
                if (canDrop >= amount) {
                    contents[j] = null;
                } else {
                    inner.setAmount(amount - canDrop);
                    contents[j] = inner;
                }
                player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(material, canDrop));
                dropped += canDrop;
                changed = true;
                continue;
            }

            if (isBundle(inner.getType())) {
                int canDrop = dropFromBundle(inner, material, toDrop - dropped, player);
                if (canDrop > 0) {
                    contents[j] = inner;
                    dropped += canDrop;
                    changed = true;
                }
            } else if (isShulkerBox(inner.getType())) {
                int canDrop = dropFromShulker(inner, material, toDrop - dropped, player);
                if (canDrop > 0) {
                    contents[j] = inner;
                    dropped += canDrop;
                    changed = true;
                }
            }
        }

        if (changed) {
            shulkerBox.getInventory().setContents(contents);
            blockMeta.setBlockState(shulkerBox);
            shulkerItem.setItemMeta(blockMeta);
        }

        return dropped;
    }
}