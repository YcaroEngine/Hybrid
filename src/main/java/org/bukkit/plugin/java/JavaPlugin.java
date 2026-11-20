package org.bukkit.plugin.java;

import java.io.File;
import java.util.logging.Logger;

public abstract class JavaPlugin {
    private final Logger logger = Logger.getLogger(this.getClass().getName());
    private boolean enabled = false;

    public void onLoad() {}
    public void onEnable() {}
    public void onDisable() {}

    public final void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            if (enabled) onEnable();
            else onDisable();
        }
    }

    public final boolean isEnabled() {
        return this.enabled;
    }

    public Logger getLogger() {
        return this.logger;
    }

     public File getDataFolder() {
     File folder = new File("java-packs/plugins", this.getClass().getSimpleName());
    if (!folder.exists()) folder.mkdirs();
       return folder;
    }
}
