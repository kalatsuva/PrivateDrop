package kalatsuva.privatedrop;

import org.bukkit.plugin.java.JavaPlugin;

public class PrivateDropPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new DroppedItemListener(this), this);
        getLogger().info("プラグインを有効にしました。");
    }
}
