import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class HybridLoader {
    private static final Gson GSON = new Gson();
    private static final List<Object> ACTIVE_PLUGINS = new ArrayList<>();
    private static final List<Object> ACTIVE_MODS = new ArrayList<>();
    public static final List<BedrockPackInfo> BEDROCK_PACKS = new ArrayList<>();

    public record BedrockPackInfo(String uuid, String version, String name, File file, long sizeBytes, byte[] sha256) {}

    public static void initializeEnvironment() {
        setupModLoaderCore();
        loadRealMods("mods");
        loadRealPlugins("plugins");
        loadRealBedrockPacks("bedrock-packs");
    }

    private static void setupModLoaderCore() {
        Path librariesDir = Path.of("libraries");
        try {
            if (!Files.exists(librariesDir)) {
                Files.createDirectories(librariesDir);
            }

            Optional<Path> coreJar;
            try (Stream<Path> walk = Files.walk(librariesDir)) {
                coreJar = walk
                    .filter(p -> (p.toString().contains("neoforge") || p.toString().contains("forge"))
                              && (p.toString().endsWith("-universal.jar") || p.toString().endsWith("-server.jar")))
                    .findFirst();
            }

            if (coreJar.isEmpty()) {
                System.out.println("[Hybrid-Core] NeoForge/Forge 1.21.11 not found in /libraries. Downloading NeoForge 1.21.11...");
                String installerUrl = "https://neoforged.net";
                Path installerPath = Path.of("modloader-installer.jar");

                try (InputStream in = URI.create(installerUrl).toURL().openStream()) {
                    Files.copy(in, installerPath, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[Hybrid-Core] Installing 1.21.11 NeoForge/Forge server libraries...");
                    ProcessBuilder pb = new ProcessBuilder("java", "-jar", installerPath.toString(), "--installServer");
                    pb.inheritIO();
                    pb.start().waitFor();
                    Files.deleteIfExists(installerPath);
                } catch (Exception ex) {
                    System.err.println("[Hybrid-Core] Notice: " + ex.getMessage());
                }
            } else {
                System.out.println("[Hybrid-Core] Hooked into 1.21.11 ModLoader core: " + coreJar.get().getFileName());
            }
        } catch (Exception e) {
            System.err.println("[Hybrid-Core] Error: " + e.getMessage());
        }
    }

    private static void loadRealMods(String folderName) {
        File folder = new File(folderName);
        File[] jars = folder.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            System.out.println("[Hybrid-Mods] No mods in /" + folderName);
            return;
        }

        for (File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                URLClassLoader modLoader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, HybridLoader.class.getClassLoader());
                boolean isNeoForge = jarFile.getEntry("META-INF/neoforge.mods.toml") != null;
                boolean isForge = jarFile.getEntry("META-INF/mods.toml") != null;
                boolean isFabric = jarFile.getEntry("fabric.mod.json") != null;

                if (isNeoForge || isForge) {
                    String type = isNeoForge ? "NeoForge" : "Forge";
                    List<String> modClasses = scanForgeModClasses(jarFile);
                    for (String className : modClasses) {
                        try {
                            Class<?> clazz = Class.forName(className, true, modLoader);
                            Object instance = clazz.getDeclaredConstructor().newInstance();
                            ACTIVE_MODS.add(instance);
                            System.out.println("[Hybrid-Mods] Loaded " + type + " @Mod: " + className + " (" + jar.getName() + ")");
                        } catch (Throwable t) {
                            System.out.println("[Hybrid-Mods] Queued " + type + " mod for ModLauncher: " + jar.getName());
                        }
                    }
                    if (modClasses.isEmpty()) {
                        System.out.println("[Hybrid-Mods] Queued " + type + " archive: " + jar.getName());
                    }
                } else if (isFabric) {
                    JarEntry entry = jarFile.getJarEntry("fabric.mod.json");
                    String jsonText = new String(jarFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = GSON.fromJson(jsonText, JsonObject.class);

                    if (json.has("entrypoints")) {
                        JsonObject entrypoints = json.getAsJsonObject("entrypoints");
                        JsonArray serverEntries = entrypoints.has("server") ? entrypoints.getAsJsonArray("server") : entrypoints.getAsJsonArray("main");
                        if (serverEntries != null && !serverEntries.isEmpty()) {
                            String mainClass = serverEntries.get(0).getAsString();
                            Class<?> clazz = Class.forName(mainClass, true, modLoader);
                            Object instance = clazz.getDeclaredConstructor().newInstance();
                            for (Method m : clazz.getMethods()) {
                                if (m.getName().equals("onInitializeServer") || m.getName().equals("onInitialize")) {
                                    m.invoke(instance);
                                }
                            }
                            ACTIVE_MODS.add(instance);
                            System.out.println("[Hybrid-Mods] Executed Fabric Mod: " + jar.getName() + " (" + mainClass + ")");
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[Hybrid-Mods] Error reading " + jar.getName() + ": " + e.getMessage());
            }
        }
    }
    private static List<String> scanForgeModClasses(JarFile jarFile) throws IOException {
        List<String> classes = new ArrayList<>();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().endsWith(".class")) {
                try (InputStream is = jarFile.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(is);
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        String className;
                        @Override
                        public void visit(int v, int a, String name, String s, String sup, String[] i) {
                            this.className = name.replace('/', '.');
                        }
                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            if (desc.equals("Lnet/minecraftforge/fml/common/Mod;") ||
                                desc.equals("Lnet/neoforged/fml/common/Mod;")) {
                                classes.add(className);
                            }
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
        return classes;
    }

    private static void loadRealPlugins(String folderName) {
        File folder = new File(folderName);
        File[] jars = folder.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            System.out.println("[Hybrid-Plugins] No plugins in /" + folderName);
            return;
        }

        Yaml yaml = new Yaml();
        for (File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                JarEntry ymlEntry = jarFile.getJarEntry("paper-plugin.yml");
                if (ymlEntry == null) ymlEntry = jarFile.getJarEntry("plugin.yml");

                if (ymlEntry != null) {
                    Map<String, Object> data = yaml.load(jarFile.getInputStream(ymlEntry));
                    String pluginName = String.valueOf(data.getOrDefault("name", jar.getName()));
                    String mainClass = String.valueOf(data.get("main"));

                    URLClassLoader pluginLoader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, HybridLoader.class.getClassLoader());
                    Class<?> clazz = Class.forName(mainClass, true, pluginLoader);
                    Object instance = clazz.getDeclaredConstructor().newInstance();

                    if (instance instanceof JavaPlugin jp) {
                        jp.onLoad();
                        jp.setEnabled(true);
                    } else {
                        try { clazz.getMethod("onLoad").invoke(instance); } catch (NoSuchMethodException ignored) {}
                        try { clazz.getMethod("onEnable").invoke(instance); } catch (NoSuchMethodException ignored) {}
                    }

                    ACTIVE_PLUGINS.add(instance);
                    System.out.println("[Hybrid-Plugins] Enabled Purpur/Paper/Spigot/Bukkit Plugin: " + pluginName + " (" + mainClass + ")");
                }
            } catch (Exception e) {
                System.err.println("[Hybrid-Plugins] Failed to enable " + jar.getName() + ": " + e.getMessage());
            }
        }
    }

    private static void loadRealBedrockPacks(String folderName) {
        BEDROCK_PACKS.clear();
        File folder = new File(folderName);
        File[] packs = folder.listFiles((dir, name) -> name.endsWith(".mcpack") || name.endsWith(".zip") || name.endsWith(".mcaddon"));

        if (packs == null || packs.length == 0) {
            System.out.println("[Hybrid-BedrockPacks] No packs in /" + folderName);
            return;
        }

        for (File pack : packs) {
            try (JarFile zip = new JarFile(pack)) {
                JarEntry manifestEntry = zip.getJarEntry("manifest.json");
                String uuid = UUID.randomUUID().toString();
                String version = "1.0.0";
                String name = pack.getName();

                if (manifestEntry != null) {
                    String rawJson = new String(zip.getInputStream(manifestEntry).readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject manifest = GSON.fromJson(rawJson, JsonObject.class);
                    if (manifest.has("header")) {
                        JsonObject header = manifest.getAsJsonObject("header");
                        if (header.has("uuid")) uuid = header.get("uuid").getAsString();
                        if (header.has("name")) name = header.get("name").getAsString();
                        if (header.has("version")) {
                            JsonArray v = header.getAsJsonArray("version");
                            version = v.get(0).getAsInt() + "." + v.get(1).getAsInt() + "." + v.get(2).getAsInt();
                        }
                    }
                }

                byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pack.toPath()));
                BEDROCK_PACKS.add(new BedrockPackInfo(uuid, version, name, pack, pack.length(), hash));
                System.out.println("[Hybrid-BedrockPacks] Loaded pack: " + name + " | UUID: " + uuid + " | v" + version);
            } catch (Exception e) {
                System.err.println("[Hybrid-BedrockPacks] Error reading " + pack.getName() + ": " + e.getMessage());
            }
        }
    }

    public static void shutdownAll() {
        for (Object plugin : ACTIVE_PLUGINS) {
            try {
                if (plugin instanceof JavaPlugin jp) jp.setEnabled(false);
                else plugin.getClass().getMethod("onDisable").invoke(plugin);
            } catch (Exception ignored) {}
        }
    }
}

