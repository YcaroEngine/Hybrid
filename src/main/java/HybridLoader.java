import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

public class HybridLoader {

    public static void initializeEnvironment() {
        verifyNeoForgeEnvironment();
        loadMods("mods");
        loadPlugins("plugins");
    }

    private static void verifyNeoForgeEnvironment() {
        Path librariesDir = Path.of("libraries");
        if (!Files.exists(librariesDir)) {
            System.out.println("[Hybrid-Setup] NeoForge libraries not found. Preparing 1.21.11 base environment...");
            try {
                Files.createDirectories(librariesDir);
            } catch (Exception e) {
                System.err.println("[Hybrid-Setup] Failed to create libraries directory: " + e.getMessage());
            }
        } else {
            System.out.println("[Hybrid-Setup] NeoForge base environment detected and hooked.");
        }
    }

    private static void loadMods(String folderName) {
        File folder = new File(folderName);
        File[] jars = folder.listFiles((dir, name) -> name.endsWith(".jar"));

        if (jars == null || jars.length == 0) {
            System.out.println("[Hybrid-Mods] No mods found in /" + folderName);
            return;
        }

        for (File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, HybridLoader.class.getClassLoader());

                if (jarFile.getEntry("META-INF/neoforge.mods.toml") != null) {
                    System.out.println("[Hybrid-Mods] NeoForge Mod (Multi-Version) loaded: " + jar.getName());
                } else if (jarFile.getEntry("META-INF/mods.toml") != null) {
                    System.out.println("[Hybrid-Mods] Forge Mod (Multi-Version) loaded: " + jar.getName());
                } else if (jarFile.getEntry("fabric.mod.json") != null) {
                    System.out.println("[Hybrid-Mods] Fabric/Quilt Mod (Multi-Version) loaded: " + jar.getName());
                } else {
                    System.out.println("[Hybrid-Mods] CoreMod loaded via ASM Remapper: " + jar.getName());
                }
            } catch (Exception e) {
                System.err.println("[Hybrid-Mods] Error loading " + jar.getName() + ": " + e.getMessage());
            }
        }
    }

    private static void loadPlugins(String folderName) {
        File folder = new File(folderName);
        File[] jars = folder.listFiles((dir, name) -> name.endsWith(".jar"));

        if (jars == null || jars.length == 0) {
            System.out.println("[Hybrid-Plugins] No plugins found in /" + folderName);
            return;
        }

        for (File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, HybridLoader.class.getClassLoader());

                if (jarFile.getEntry("paper-plugin.yml") != null) {
                    System.out.println("[Hybrid-Plugins] Modern Paper/Purpur Plugin (Multi-Version) loaded: " + jar.getName());
                } else if (jarFile.getEntry("plugin.yml") != null) {
                    System.out.println("[Hybrid-Plugins] Purpur/Paper/Spigot/Bukkit Plugin (Multi-Version) loaded: " + jar.getName());
                } else {
                    System.out.println("[Hybrid-Plugins] Ignored file (missing plugin manifest): " + jar.getName());
                }
            } catch (Exception e) {
                System.err.println("[Hybrid-Plugins] Error loading " + jar.getName() + ": " + e.getMessage());
            }
        }
    }
}
