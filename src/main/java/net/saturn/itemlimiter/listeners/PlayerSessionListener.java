package net.saturn.itemlimiter.listeners;

import net.saturn.itemlimiter.ItemLimiter;
import net.saturn.itemlimiter.enforcement.ExcessEnforcer;
import net.saturn.itemlimiter.util.LimitMessenger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Handles per-session bookkeeping: an excess sweep shortly after join,
 * cooldown cleanup on quit, and a periodic server-wide sweep that catches
 * items added outside normal inventory events (/give, other plugins, etc).
 */
public class PlayerSessionListener implements Listener {

    private static final long PERIODIC_CHECK_INTERVAL_TICKS = 100L; // 5 seconds
    private static final long JOIN_CHECK_DELAY_TICKS = 20L; // 1 second

    private final ItemLimiter plugin;
    private final ExcessEnforcer excessEnforcer;
    private final LimitMessenger messenger;
    private BukkitRunnable periodicCheckTask;

    public PlayerSessionListener(ItemLimiter plugin, ExcessEnforcer excessEnforcer, LimitMessenger messenger) {
        this.plugin = plugin;
        this.excessEnforcer = excessEnforcer;
        this.messenger = messenger;
        startPeriodicCheck();
    }

    /**
     * Starts a periodic task that checks all online players for excess items.
     * This catches items added via /give or other means that might bypass
     * the normal inventory events.
     */
    private void startPeriodicCheck() {
        periodicCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    return;
                }
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.isOnline()) {
                        excessEnforcer.checkAndDropAllExcess(player);
                    }
                }
            }
        };
        periodicCheckTask.runTaskTimer(plugin, PERIODIC_CHECK_INTERVAL_TICKS, PERIODIC_CHECK_INTERVAL_TICKS);
    }

    /** Stops the periodic sweep. Called from the plugin's onDisable(). */
    public void stopPeriodicCheck() {
        if (periodicCheckTask != null && !periodicCheckTask.isCancelled()) {
            periodicCheckTask.cancel();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                excessEnforcer.checkAndDropAllExcess(player);
            }
        }.runTaskLater(plugin, JOIN_CHECK_DELAY_TICKS);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        messenger.clearCooldowns(event.getPlayer().getUniqueId());
    }
}
