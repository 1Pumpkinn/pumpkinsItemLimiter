package net.saturn.itemlimiter.managers;

import net.saturn.itemlimiter.ItemLimiter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ItemLimitManager {

    /** Players with this permission are never affected by item limits. */
    public static final String PERM_OFF = "itemlimiter.off";
    /** Players with this permission are affected by item limits (granted by default). */
    public static final String PERM_ON = "itemlimiter.on";

    private static final String LIMITS_PATH = "limited-items";
    private static final String CONTAINER_SETTING = "settings.count-items-in-containers";
    /** Pre-rename key; migrated to {@link #CONTAINER_SETTING} on load. */
    private static final String LEGACY_CONTAINER_SETTING = "settings.limit-nested-containers";

    /** Player inventory slots: 0-35 storage/hotbar, 36-39 armor, 40 off-hand. */
    private static final int LAST_PLAYER_SLOT = 40;

    // Built once - avoids String.endsWith() on every item of every scan.
    private static final Set<Material> BUNDLES = EnumSet.noneOf(Material.class);
    private static final Set<Material> SHULKER_BOXES = EnumSet.noneOf(Material.class);

    static {
        for (Material material : Material.values()) {
            if (material.isLegacy()) continue;
            String name = material.name();
            if (name.equals("BUNDLE") || name.endsWith("_BUNDLE")) BUNDLES.add(material);
            if (name.equals("SHULKER_BOX") || name.endsWith("_SHULKER_BOX")) SHULKER_BOXES.add(material);
        }
    }

    private final ItemLimiter plugin;
    private final Map<Material, Integer> limitedItems = new EnumMap<>(Material.class); // 0 = completely banned
    private final Map<Material, Integer> limitedItemsView = Collections.unmodifiableMap(limitedItems);
    private final File dataFile;
    private FileConfiguration data;

    // Cached so hot paths (every click/pickup) don't hit the config map.
    private boolean countItemsInContainers = true;

    public ItemLimitManager(ItemLimiter plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "limited-items.yml");
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public void load() {
        migrateLegacySettings();
        countItemsInContainers = plugin.getConfig().getBoolean(CONTAINER_SETTING, true);

        limitedItems.clear();
        data = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection section = data.getConfigurationSection(LIMITS_PATH);
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || material.isLegacy()) {
                plugin.getLogger().warning("Invalid material in limited-items.yml: " + key);
                continue;
            }
            limitedItems.put(material, Math.max(0, section.getInt(key)));
        }
        plugin.getLogger().info("Loaded " + limitedItems.size() + " limited items");
    }

    public void save() {
        if (data == null) return;
        try {
            data.set(LIMITS_PATH, null);
            for (Map.Entry<Material, Integer> entry : limitedItems.entrySet()) {
                data.set(LIMITS_PATH + "." + entry.getKey().name(), entry.getValue());
            }
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save limited items: " + e.getMessage());
        }
    }

    private void migrateLegacySettings() {
        FileConfiguration config = plugin.getConfig();
        if (config.contains(LEGACY_CONTAINER_SETTING, true) && !config.contains(CONTAINER_SETTING, true)) {
            config.set(CONTAINER_SETTING, config.getBoolean(LEGACY_CONTAINER_SETTING, true));
            config.set(LEGACY_CONTAINER_SETTING, null);
            plugin.saveConfig();
        }
    }

    // ------------------------------------------------------------------
    // Limit management
    // ------------------------------------------------------------------

    public boolean addItem(Material material, int maxQuantity) {
        if (material == null || material.isAir() || maxQuantity < 0) {
            return false;
        }
        limitedItems.put(material, maxQuantity);
        save();
        return true;
    }

    public boolean removeItem(Material material) {
        boolean removed = limitedItems.remove(material) != null;
        if (removed) save();
        return removed;
    }

    public void clearItems() {
        limitedItems.clear();
        save();
    }

    public boolean isItemLimited(Material material) {
        return limitedItems.containsKey(material);
    }

    public boolean isItemLimited(ItemStack item) {
        return item != null && limitedItems.containsKey(item.getType());
    }

    public boolean isItemBanned(Material material) {
        Integer limit = limitedItems.get(material);
        return limit != null && limit == 0;
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

    /** Read-only live view. Copy it before modifying limits while iterating. */
    public Map<Material, Integer> getLimitedItems() {
        return limitedItemsView;
    }

    public List<String> getLimitedItemNames() {
        return limitedItems.keySet().stream().map(Material::name).sorted().toList();
    }

    public int getLimitedItemCount() {
        return limitedItems.size();
    }

    public boolean hasLimitedItems() {
        return !limitedItems.isEmpty();
    }

    // ------------------------------------------------------------------
    // Per-player exemption (LuckPerms groups etc.)
    // ------------------------------------------------------------------

    /**
     * Whether item limits are ignored for this player.
     * <ul>
     *   <li>{@code itemlimiter.off} -> exempt. This always wins, so a staff group
     *       can set it even if it inherits {@code itemlimiter.on} from "default".</li>
     *   <li>{@code itemlimiter.on} (true by default) set to false -> exempt.</li>
     *   <li>Otherwise limits apply. Being op does NOT exempt anyone by itself.</li>
     * </ul>
     */
    public boolean isExempt(Player player) {
        return player.hasPermission(PERM_OFF) || !player.hasPermission(PERM_ON);
    }

    // ------------------------------------------------------------------
    // Container setting
    // ------------------------------------------------------------------

    /**
     * Whether items inside bundles/shulker boxes count toward limits.
     * Toggle live via /itemlimit containers.
     */
    public boolean isCountingItemsInContainers() {
        return countItemsInContainers;
    }

    public void setCountItemsInContainers(boolean value) {
        countItemsInContainers = value;
        plugin.getConfig().set(CONTAINER_SETTING, value);
        plugin.saveConfig();
    }

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    /** Counts one material across the whole player inventory (armor/off-hand included). */
    public int countItemInInventory(Player player, Material material) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        scan(player, material, counts);
        return counts.getOrDefault(material, 0);
    }

    /**
     * Counts every limited material in a single pass over the inventory.
     * Much cheaper than one scan per material, since unpacking shulker boxes
     * is the expensive part.
     */
    public Map<Material, Integer> countLimitedItems(Player player) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        if (!limitedItems.isEmpty()) {
            scan(player, null, counts);
        }
        return counts;
    }

    private void scan(Player player, Material only, Map<Material, Integer> counts) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot <= LAST_PLAYER_SLOT; slot++) {
            countInto(inventory.getItem(slot), only, counts);
        }
    }

    /** @param only a single material to count, or null for every limited material. */
    private void countInto(ItemStack item, Material only, Map<Material, Integer> counts) {
        if (item == null || item.getType().isAir()) return;

        Material type = item.getType();
        if (only != null ? type == only : limitedItems.containsKey(type)) {
            counts.merge(type, item.getAmount(), Integer::sum);
        }

        if (!countItemsInContainers) return;

        if (BUNDLES.contains(type)) {
            if (item.getItemMeta() instanceof BundleMeta meta && meta.hasItems()) {
                for (ItemStack bundled : meta.getItems()) {
                    countInto(bundled, only, counts);
                }
            }
        } else if (SHULKER_BOXES.contains(type)) {
            if (item.getItemMeta() instanceof BlockStateMeta meta
                    && meta.getBlockState() instanceof ShulkerBox box) {
                for (ItemStack contained : box.getInventory().getContents()) {
                    countInto(contained, only, counts);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Enforcement
    // ------------------------------------------------------------------

    /** Drops whatever is over the limit for one material. Returns the amount dropped. */
    public int dropExcess(Player player, Material material) {
        return dropExcess(player, material, countItemInInventory(player, material));
    }

    /**
     * Drops whatever is over the limit, given a count the caller already has
     * (avoids re-scanning). Loose stacks go first; items inside bundles and
     * shulker boxes are only touched if that isn't enough.
     */
    public int dropExcess(Player player, Material material, int currentCount) {
        Integer limit = limitedItems.get(material);
        if (limit == null) return 0;

        int toDrop = currentCount - limit;
        if (toDrop <= 0) return 0;

        PlayerInventory inventory = player.getInventory();
        World world = player.getWorld();
        Location location = player.getLocation();
        int dropped = 0;

        // Pass 1: loose stacks.
        for (int slot = 0; slot <= LAST_PLAYER_SLOT && dropped < toDrop; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != material) continue;

            int take = Math.min(item.getAmount(), toDrop - dropped);
            ItemStack out = item.clone();
            out.setAmount(take);

            if (take >= item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - take);
                inventory.setItem(slot, item);
            }
            world.dropItemNaturally(location, out);
            dropped += take;
        }

        // Pass 2: inside containers.
        if (dropped < toDrop && countItemsInContainers) {
            for (int slot = 0; slot <= LAST_PLAYER_SLOT && dropped < toDrop; slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null) continue;

                int removed = 0;
                if (BUNDLES.contains(item.getType())) {
                    removed = dropFromBundle(item, material, toDrop - dropped, world, location);
                } else if (SHULKER_BOXES.contains(item.getType())) {
                    removed = dropFromShulker(item, material, toDrop - dropped, world, location);
                }

                if (removed > 0) {
                    inventory.setItem(slot, item);
                    dropped += removed;
                }
            }
        }

        return dropped;
    }

    /** Mutates the bundle's meta in place. Returns the amount dropped. */
    private int dropFromBundle(ItemStack bundleItem, Material material, int want, World world, Location location) {
        if (want <= 0 || !(bundleItem.getItemMeta() instanceof BundleMeta meta) || !meta.hasItems()) {
            return 0;
        }

        List<ItemStack> contents = new ArrayList<>(meta.getItems());
        int removed = extract(contents, material, want, world, location);

        if (removed > 0) {
            contents.removeIf(Objects::isNull);
            meta.setItems(contents);
            bundleItem.setItemMeta(meta);
        }
        return removed;
    }

    /** Mutates the shulker box's stored contents in place. Returns the amount dropped. */
    private int dropFromShulker(ItemStack shulkerItem, Material material, int want, World world, Location location) {
        if (want <= 0
                || !(shulkerItem.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            return 0;
        }

        List<ItemStack> contents = new ArrayList<>(Arrays.asList(box.getInventory().getContents()));
        int removed = extract(contents, material, want, world, location);

        if (removed > 0) {
            box.getInventory().setContents(contents.toArray(new ItemStack[0]));
            meta.setBlockState(box);
            shulkerItem.setItemMeta(meta);
        }
        return removed;
    }

    /**
     * Shared by bundles and shulker boxes: drops up to {@code want} of the
     * material out of {@code contents}, recursing into nested containers.
     * Fully removed entries are set to null (callers compact if needed);
     * dropped stacks are clones, so enchants/names/etc. are preserved.
     */
    private int extract(List<ItemStack> contents, Material material, int want, World world, Location location) {
        int removed = 0;

        for (int i = contents.size() - 1; i >= 0 && removed < want; i--) {
            ItemStack inner = contents.get(i);
            if (inner == null || inner.getType().isAir()) continue;

            Material type = inner.getType();
            if (type == material) {
                int take = Math.min(inner.getAmount(), want - removed);
                ItemStack out = inner.clone();
                out.setAmount(take);
                world.dropItemNaturally(location, out);

                if (take >= inner.getAmount()) {
                    contents.set(i, null);
                } else {
                    inner.setAmount(inner.getAmount() - take);
                }
                removed += take;
            } else if (BUNDLES.contains(type)) {
                removed += dropFromBundle(inner, material, want - removed, world, location);
            } else if (SHULKER_BOXES.contains(type)) {
                removed += dropFromShulker(inner, material, want - removed, world, location);
            }
        }
        return removed;
    }
}