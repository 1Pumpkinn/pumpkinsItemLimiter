package net.saturn.itemlimiter;

import net.saturn.itemlimiter.command.ItemLimitCommand;
import net.saturn.itemlimiter.listeners.ItemLimitListener;
import net.saturn.itemlimiter.listeners.VillagerTradeListener;
import net.saturn.itemlimiter.managers.ItemLimitManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class ItemLimiter extends JavaPlugin {

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
