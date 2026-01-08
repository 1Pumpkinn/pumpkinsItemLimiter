package net.saturn.pumpkinsItemLimiter;

import net.saturn.pumpkinsItemLimiter.command.ItemLimitCommand;
import net.saturn.pumpkinsItemLimiter.listeners.ItemLimitListener;
import net.saturn.pumpkinsItemLimiter.listeners.VillagerTradeListener;
import net.saturn.pumpkinsItemLimiter.managers.ItemLimitManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class PumpkinsItemLimiter extends JavaPlugin {

    private ItemLimitManager itemLimitManager;


    @Override
    public void onEnable() {
        // Plugin startup logic

        itemLimitManager = new ItemLimitManager(this);
        itemLimitManager.load();

        getServer().getPluginManager().registerEvents(new ItemLimitListener(this, itemLimitManager), this);
        getServer().getPluginManager().registerEvents(new VillagerTradeListener(this, itemLimitManager), this);

        getCommand("itemlimit").setExecutor(new ItemLimitCommand(this, itemLimitManager));


    }


    @Override
    public void onDisable() {
        // Plugin shutdown logic

        // Save item limits
        if (itemLimitManager != null) {
            itemLimitManager.save();
        }

    }

    public ItemLimitManager getItemLimitManager() {
        return itemLimitManager;
    }
}
