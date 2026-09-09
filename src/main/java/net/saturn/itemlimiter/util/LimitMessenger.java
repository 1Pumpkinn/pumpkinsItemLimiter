package net.saturn.itemlimiter.util;

import net.saturn.itemlimiter.ItemLimiter;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends the player-facing "blocked" / "partial" chat messages for item
 * limits. Each message type has its own per-player cooldown so a burst of
 * events (e.g. a hopper spamming a full inventory) doesn't flood chat.
 */
public class LimitMessenger {

    private static final long COOLDOWN_MS = 60_000L;

    private final ItemLimiter plugin;
    private final Map<UUID, Long> pickupCooldowns = new HashMap<>();
    private final Map<UUID, Long> blockedCooldowns = new HashMap<>();
    private final Map<UUID, Long> partialCooldowns = new HashMap<>();

    public LimitMessenger(ItemLimiter plugin) {
        this.plugin = plugin;
    }

    /** Sent when a ground-item pickup is blocked (banned or already at limit). */
    public void sendPickupBlocked(Player player, Material material, int limit) {
        if (!offCooldown(pickupCooldowns, player.getUniqueId())) return;

        String key = limit == 0 ? "messages.item-blocked-pickup-banned" : "messages.item-blocked-pickup-limit";
        String def = limit == 0
                ? "&cCannot pick up &e{item}&c - it's banned!"
                : "&cCannot pick up &e{item}&c - at max ({limit})!";
        send(player, key, def, material, limit, -1);
    }

    /** Sent when an inventory action (click/drag/swap/close/drop) is blocked. */
    public void sendBlocked(Player player, Material material, int limit) {
        if (!offCooldown(blockedCooldowns, player.getUniqueId())) return;

        String key = limit == 0 ? "messages.item-blocked-place-banned" : "messages.item-blocked-place-limit";
        send(player, key, "&cYou cannot have &e{item}&c!", material, limit, -1);
    }

    /** Sent when only part of a stack could be added because the rest would exceed the limit. */
    public void sendPartial(Player player, Material material, int added, int limit) {
        sendPartial(player, material, added, limit, false);
    }

    public void sendPartial(Player player, Material material, int added, int limit, boolean isPickup) {
        if (!offCooldown(partialCooldowns, player.getUniqueId())) return;

        String key = isPickup ? "messages.item-partial-pickup" : "messages.item-blocked-take-partial";
        String def = isPickup
                ? "&ePickup limited to &6{amount} &e{item} &7(max: {limit})"
                : "&cCan only take &6{amount} &cmore &e{item} &7(max: {limit})";
        send(player, key, def, material, limit, added);
    }

    /** Clears all cooldowns for a player - call this when they leave the server. */
    public void clearCooldowns(UUID playerId) {
        pickupCooldowns.remove(playerId);
        blockedCooldowns.remove(playerId);
        partialCooldowns.remove(playerId);
    }

    private boolean offCooldown(Map<UUID, Long> cooldowns, UUID playerId) {
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(playerId, 0L);
        if (now - last < COOLDOWN_MS) return false;
        cooldowns.put(playerId, now);
        return true;
    }

    private void send(Player player, String key, String def, Material material, int limit, int amount) {
        String message = plugin.getConfig().getString(key, def)
                .replace("{item}", ItemNameFormatter.format(material))
                .replace("{limit}", String.valueOf(limit));

        if (amount >= 0) {
            message = message.replace("{amount}", String.valueOf(amount));
        }

        player.sendMessage(ItemNameFormatter.colorize(message));
    }
}
