package net.saturn.itemlimiter;

import net.saturn.itemlimiter.command.ItemLimitCommand;
import net.saturn.itemlimiter.enforcement.ExcessEnforcer;
import net.saturn.itemlimiter.listeners.CraftingLimitListener;
import net.saturn.itemlimiter.listeners.InventoryLimitListener;
import net.saturn.itemlimiter.listeners.PickupLimitListener;
import net.saturn.itemlimiter.listeners.PlayerSessionListener;
import net.saturn.itemlimiter.listeners.VillagerTradeListener;
import net.saturn.itemlimiter.managers.ItemLimitManager;
import net.saturn.itemlimiter.util.LimitMessenger;
import org.bukkit.plugin.java.JavaPlugin;

public final class ItemLimiter extends JavaPlugin {

    private ItemLimitManager itemLimitManager;
    private PlayerSessionListener sessionListener;

    @Override
    public void onEnable() {
        itemLimitManager = new ItemLimitManager(this);
        itemLimitManager.load();

        LimitMessenger messenger = new LimitMessenger(this);
        ExcessEnforcer excessEnforcer = new ExcessEnforcer(this, itemLimitManager);
        sessionListener = new PlayerSessionListener(this, excessEnforcer, messenger);

        getServer().getPluginManager().registerEvents(new CraftingLimitListener(itemLimitManager), this);
        getServer().getPluginManager().registerEvents(new PickupLimitListener(itemLimitManager, messenger), this);
        getServer().getPluginManager().registerEvents(
                new InventoryLimitListener(this, itemLimitManager, messenger, excessEnforcer), this);
        getServer().getPluginManager().registerEvents(sessionListener, this);
        getServer().getPluginManager().registerEvents(new VillagerTradeListener(this, itemLimitManager), this);

        getCommand("itemlimit").setExecutor(new ItemLimitCommand(this, itemLimitManager));
    }

    @Override
    public void onDisable() {
        if (sessionListener != null) {
            sessionListener.stopPeriodicCheck();
        }
        if (itemLimitManager != null) {
            itemLimitManager.save();
        }
    }

    public ItemLimitManager getItemLimitManager() {
        return itemLimitManager;
    }
}
