import org.bukkit.plugin.java.JavaPlugin;

public class HybridBootstrapPlugin extends JavaPlugin {
    private static boolean started = false;

    public HybridBootstrapPlugin() {
        bootHybrid();
    }

    @Override
    public void onLoad() {
        bootHybrid();
    }

    @Override
    public void onEnable() {
        bootHybrid();
    }

    public void onInitializeServer() {
        bootHybrid();
    }

    public void onInitialize() {
        bootHybrid();
    }

    public static synchronized void bootHybrid() {
        if (started) return;
        started = true;
        Thread.ofVirtual().start(() -> Main.main(new String[]));
    }
}
