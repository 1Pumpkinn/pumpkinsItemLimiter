package net.saturn.itemlimiter.managers;

import net.saturn.itemlimiter.ItemLimiter;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ItemLimitManager {

    private final ItemLimiter plugin;
    private final Map<Material, Integer> limitedItems; // Material -> max quantity (0 = completely banned)
    private File dataFile;
    private FileConfiguration data;

    public ItemLimitManager(ItemLimiter plugin) {
        this.plugin = plugin;
        this.limitedItems = new HashMap<>();
        this.dataFile = new File(plugin.getDataFolder(), "limited-items.yml");
    }

    public void load() {
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create limited-items.yml: " + e.getMessage());
            }
        }

        data = YamlConfiguration.loadConfiguration(dataFile);

        // Load limited items
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
            // Clear existing data
            data.set("limited-items", null);

            // Save limited items with their limits
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
     * Counts how many of a specific material a player has in their inventory
     */
    public int countItemInInventory(org.bukkit.entity.Player player, Material material) {
        int count = 0;

        // Count in main inventory (slots 0-35: hotbar + main inventory)
        // Note: getStorageContents() returns only storage slots (0-35), excluding armor and offhand
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }

        // Count in armor slots
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }

        // Count in off-hand
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() == material) {
            count += offHand.getAmount();
        }

        return count;
    }

    /**
     * Drops excess items from player's inventory to enforce limits
     * Returns the number of items dropped
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

        // Drop from main inventory (slots 0-35: hotbar + main inventory, excluding slot 40 which is offhand)
        for (int i = 0; i < 36 && dropped < toDrop; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == material) {
                int amount = item.getAmount();
                if (amount <= toDrop - dropped) {
                    player.getInventory().setItem(i, null);
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                    dropped += amount;
                } else {
                    int amountToDrop = toDrop - dropped;
                    ItemStack dropStack = item.clone();
                    dropStack.setAmount(amountToDrop);
                    item.setAmount(amount - amountToDrop);
                    player.getWorld().dropItemNaturally(player.getLocation(), dropStack);
                    dropped = toDrop;
                }
            }
        }

        // Drop from armor slots if needed
        if (dropped < toDrop) {
            ItemStack[] armor = player.getInventory().getArmorContents();
            for (int i = 0; i < armor.length && dropped < toDrop; i++) {
                if (armor[i] != null && armor[i].getType() == material) {
                    int amount = armor[i].getAmount();
                    if (amount <= toDrop - dropped) {
                        player.getWorld().dropItemNaturally(player.getLocation(), armor[i]);
                        armor[i] = null;
                        dropped += amount;
                    } else {
                        int amountToDrop = toDrop - dropped;
                        ItemStack dropStack = armor[i].clone();
                        dropStack.setAmount(amountToDrop);
                        armor[i].setAmount(amount - amountToDrop);
                        player.getWorld().dropItemNaturally(player.getLocation(), dropStack);
                        dropped = toDrop;
                    }
                }
            }
            player.getInventory().setArmorContents(armor);
        }

        // Drop from off-hand if needed
        if (dropped < toDrop) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand != null && offHand.getType() == material) {
                int amount = offHand.getAmount();
                if (amount <= toDrop - dropped) {
                    player.getWorld().dropItemNaturally(player.getLocation(), offHand);
                    player.getInventory().setItemInOffHand(null);
                    dropped += amount;
                } else {
                    int amountToDrop = toDrop - dropped;
                    ItemStack dropStack = offHand.clone();
                    dropStack.setAmount(amountToDrop);
                    offHand.setAmount(amount - amountToDrop);
                    player.getWorld().dropItemNaturally(player.getLocation(), dropStack);
                    dropped = toDrop;
                }
            }
        }
        return dropped;
    }
}