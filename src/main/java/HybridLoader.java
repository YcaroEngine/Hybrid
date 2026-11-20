import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class HybridLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Object> ACTIVE_PLUGINS = new ArrayList<>();
    private static final List<Object> ACTIVE_MODS = new ArrayList<>();
    public static final List<BedrockPackInfo> BEDROCK_PACKS = new ArrayList<>();

    public record BedrockPackInfo(String uuid, String version, String name, String category, boolean mandatoryForBedrock, File source, long sizeBytes, byte[] sha256) {}

    public static void initializeEnvironment() {
        organizeJavaPacksFolder();
        setupModLoaderCore();
        loadRealMods("java-packs/mods");
        loadRealPlugins("java-packs/plugins");
        loadAllBedrockPacks();
    }

    private static void organizeJavaPacksFolder() {
        try {
            Path modsTarget = Path.of("java-packs/mods");
            Path pluginsTarget = Path.of("java-packs/plugins");
            Files.createDirectories(modsTarget);
            Files.createDirectories(pluginsTarget);

            Path rootMods = Path.of("mods");
            if (Files.exists(rootMods) && Files.isDirectory(rootMods)) {
                try (Stream<Path> files = Files.list(rootMods)) {
                    files.filter(p -> p.toString().endsWith(".jar")).forEach(jar -> {
                        try {
                            Files.move(jar, modsTarget.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("[Hybrid-Core] Moved mod to java-packs/mods: " + jar.getFileName());
                        } catch (IOException ignored) {}
                    });
                }
                deleteIfEmptyDir(rootMods);
            }

            try (Stream<Path> rootFiles = Files.list(Path.of("."))) {
                rootFiles.filter(Files::isRegularFile)
                         .filter(p -> p.toString().endsWith(".jar"))
                         .filter(p -> !p.getFileName().toString().equalsIgnoreCase("server.jar"))
                         .forEach(jar -> {
                             try (JarFile jf = new JarFile(jar.toFile())) {
                                 boolean isPlugin = jf.getEntry("plugin.yml") != null || jf.getEntry("paper-plugin.yml") != null;
                                 boolean isMod = jf.getEntry("META-INF/neoforge.mods.toml") != null ||
                                                 jf.getEntry("META-INF/mods.toml") != null ||
                                                 jf.getEntry("fabric.mod.json") != null;
                                 if (isPlugin) {
                                     Files.move(jar, pluginsTarget.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                                     System.out.println("[Hybrid-Core] Moved loose plugin to java-packs/plugins: " + jar.getFileName());
                                 } else if (isMod) {
                                     Files.move(jar, modsTarget.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                                     System.out.println("[Hybrid-Core] Moved loose mod to java-packs/mods: " + jar.getFileName());
                                 }
                             } catch (Exception ignored) {}
                         });
            }
        } catch (IOException e) {
            System.err.println("[Hybrid-Core] Folder organization warning: " + e.getMessage());
        }
    }

    private static void setupModLoaderCore() {
        Path librariesDir = Path.of("libraries");
        try {
            if (!Files.exists(librariesDir)) {
                Files.createDirectories(librariesDir);
            }

            Path eulaPath = Path.of("eula.txt");
            if (!Files.exists(eulaPath) || !Files.readString(eulaPath).contains("eula=true")) {
                Files.writeString(eulaPath, "# Auto-accepted by Hybrid Engine\neula=true\n");
                System.out.println("[Hybrid-Core] Accepted eula.txt automatically.");
            }

            Optional<Path> unixArgs;
            try (Stream<Path> walk = Files.walk(librariesDir)) {
                unixArgs = walk
                    .filter(p -> p.getFileName().toString().equals("unix_args.txt") && p.toString().contains("neoforge"))
                    .findFirst();
            }

            if (unixArgs.isEmpty()) {
                System.out.println("[Hybrid-Core] Fetching latest NeoForge 1.21.11 from official Maven...");
                String host = "https://" + "maven.neoforged.net/releases/net/neoforged/neoforge/";
                String metadataUrl = host + "maven-metadata.xml";
                String xml = new String(URI.create(metadataUrl).toURL().openStream().readAllBytes(), StandardCharsets.UTF_8);

                String latestVersion = "21.11.38";
                Matcher m = Pattern.compile("<version>(21\\.11\\.[^<]+)</version>").matcher(xml);
                while (m.find()) {
                    latestVersion = m.group(1);
                }

                String installerUrl = host + latestVersion + "/neoforge-" + latestVersion + "-installer.jar";
                Path installerPath = Path.of("modloader-installer.jar");

                System.out.println("[Hybrid-Core] Downloading NeoForge " + latestVersion + "...");
                try (InputStream in = URI.create(installerUrl).toURL().openStream()) {
                    Files.copy(in, installerPath, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[Hybrid-Core] Installing NeoForge " + latestVersion + " (Generating world engine)...");
                    ProcessBuilder pb = new ProcessBuilder("java", "-jar", installerPath.toString(), "--installServer");
                    pb.inheritIO();
                    pb.start().waitFor();
                    Files.deleteIfExists(installerPath);
                    Files.deleteIfExists(Path.of("run.sh"));
                    Files.deleteIfExists(Path.of("run.bat"));
                    Files.deleteIfExists(Path.of("user_jvm_args.txt"));
                }

                organizeJavaPacksFolder();

                try (Stream<Path> walk = Files.walk(librariesDir)) {
                    unixArgs = walk
                        .filter(p -> p.getFileName().toString().equals("unix_args.txt") && p.toString().contains("neoforge"))
                        .findFirst();
                }
            }

            if (unixArgs.isPresent()) {
                Path argsFile = unixArgs.get();
                int internalBackendPort = Main.CONFIG.getInt("server-port", 25565) + 1;
                System.out.println("[Hybrid-Core] Starting NeoForge 1.21.11 World Engine on internal port " + internalBackendPort + "...");

                Thread.ofVirtual().start(() -> {
                    try {
                        ProcessBuilder serverPb = new ProcessBuilder(
                            "java",
                            "-Dfml.modsDir=java-packs/mods",
                            "@" + argsFile.toString(),
                            "--nogui",
                            "--port", String.valueOf(internalBackendPort)
                        );
                        serverPb.inheritIO();
                        serverPb.start().waitFor();
                    } catch (Exception runEx) {
                        System.err.println("[Hybrid-Core] World Engine stopped: " + runEx.getMessage());
                    }
                });

                deleteIfEmptyDir(Path.of("mods"));
            }
        } catch (Exception e) {
            System.err.println("[Hybrid-Core] Error: " + e.getMessage());
        }
    }

    private static void deleteIfEmptyDir(Path dir) {
        try {
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                try (Stream<Path> entries = Files.list(dir)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(dir);
                    }
                }
            }
        } catch (IOException ignored) {}
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
                boolean hasClientTextures = jarFile.getEntry("assets/") != null;

                if (isNeoForge || isForge) {
                    String type = isNeoForge ? "NeoForge" : "Forge";
                    String side = hasClientTextures ? "Client+Server (Textures detected)" : "Server-Side Only (No client texture required)";
                    List<String> modClasses = scanForgeModClasses(jarFile);
                    for (String className : modClasses) {
                        try {
                            Class<?> clazz = Class.forName(className, true, modLoader);
                            Object instance = clazz.getDeclaredConstructor().newInstance();
                            ACTIVE_MODS.add(instance);
                            System.out.println("[Hybrid-Mods] Loaded " + type + " @Mod: " + className + " | " + side);
                        } catch (Throwable t) {
                            System.out.println("[Hybrid-Mods] Queued " + type + " mod: " + jar.getName() + " | " + side);
                        }
                    }
                    if (modClasses.isEmpty()) {
                        System.out.println("[Hybrid-Mods] Queued " + type + " archive: " + jar.getName() + " | " + side);
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
                    boolean hasEmbeddedPack = jarFile.getEntry("assets/") != null || jarFile.getEntry("pack.mcmeta") != null;

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
                    String modeNote = hasEmbeddedPack ? "Has Embedded Texture Pack" : "Server-Side Only (No texture added)";
                    System.out.println("[Hybrid-Plugins] Enabled Plugin: " + pluginName + " | " + modeNote);
                }
            } catch (Exception e) {
                System.err.println("[Hybrid-Plugins] Failed to enable " + jar.getName() + ": " + e.getMessage());
            }
        }
    }

    private static void loadAllBedrockPacks() {
        BEDROCK_PACKS.clear();
        try {
            Files.createDirectories(Path.of("bedrock-packs/resource-packs"));
            Files.createDirectories(Path.of("bedrock-packs/behavior-packs"));
            Files.createDirectories(Path.of("bedrock-packs/raw-packs"));
        } catch (IOException e) {
            System.err.println("[Hybrid-BedrockPacks] Failed to create pack folders: " + e.getMessage());
        }

        boolean mandatory = Main.CONFIG.getBoolean("require-bedrock-packs", true);

        scanPackFolder(new File("bedrock-packs/resource-packs"), "RESOURCE", mandatory);
        scanPackFolder(new File("bedrock-packs/behavior-packs"), "BEHAVIOR", mandatory);
        scanPackFolder(new File("bedrock-packs/raw-packs"), "RAW_AUTO", mandatory);
        scanPackFolder(new File("bedrock-packs"), "ROOT_AUTO", mandatory);

        if (BEDROCK_PACKS.isEmpty()) {
            System.out.println("[Hybrid-BedrockPacks] No packs found. Skipping world_resource_packs.json / world_behavior_packs.json generation.");
        } else {
            System.out.println("[Hybrid-BedrockPacks] " + BEDROCK_PACKS.size() + " Bedrock-only pack(s) active. Mandatory download for Bedrock clients: " + mandatory);
            generateWorldPackBindings();
        }
    }

    private static void scanPackFolder(File folder, String forcedCategory, boolean mandatory) {
        File[] items = folder.listFiles();
        if (items == null) return;

        for (File item : items) {
            if (item.isDirectory()) {
                String dirName = item.getName();
                if (dirName.equals("resource-packs") || dirName.equals("behavior-packs") || dirName.equals("raw-packs")) {
                    continue;
                }
                File manifestFile = new File(item, "manifest.json");
                if (manifestFile.exists()) {
                    try {
                        String rawJson = Files.readString(manifestFile.toPath(), StandardCharsets.UTF_8);
                        parseAndRegisterPack(rawJson, item, forcedCategory, mandatory, item.getName().getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        System.err.println("[Hybrid-BedrockPacks] Error reading raw folder " + dirName + ": " + e.getMessage());
                    }
                }
            } else {
                String lower = item.getName().toLowerCase();
                if (lower.endsWith(".mcpack") || lower.endsWith(".mcaddon") || lower.endsWith(".zip")) {
                    try (JarFile zip = new JarFile(item)) {
                        JarEntry manifestEntry = zip.getJarEntry("manifest.json");
                        if (manifestEntry != null) {
                            String rawJson = new String(zip.getInputStream(manifestEntry).readAllBytes(), StandardCharsets.UTF_8);
                            parseAndRegisterPack(rawJson, item, forcedCategory, mandatory, Files.readAllBytes(item.toPath()));
                        } else {
                            Enumeration<JarEntry> entries = zip.entries();
                            while (entries.hasMoreElements()) {
                                JarEntry sub = entries.nextElement();
                                if (sub.getName().endsWith("manifest.json")) {
                                    String subJson = new String(zip.getInputStream(sub).readAllBytes(), StandardCharsets.UTF_8);
                                    parseAndRegisterPack(subJson, item, "RAW_AUTO", mandatory, Files.readAllBytes(item.toPath()));
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[Hybrid-BedrockPacks] Error reading archive " + item.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private static void parseAndRegisterPack(String rawJson, File source, String forcedCategory, boolean mandatory, byte[] bytesToHash) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String version = "1.0.0";
        String name = source.getName();
        String detectedType = forcedCategory;

        JsonObject manifest = GSON.fromJson(rawJson, JsonObject.class);
        if (manifest != null) {
            if (manifest.has("header")) {
                JsonObject header = manifest.getAsJsonObject("header");
                if (header.has("uuid")) uuid = header.get("uuid").getAsString();
                if (header.has("name")) name = header.get("name").getAsString();
                if (header.has("version")) {
                    JsonArray v = header.getAsJsonArray("version");
                    version = v.get(0).getAsInt() + "." + v.get(1).getAsInt() + "." + v.get(2).getAsInt();
                }
            }
            if ((forcedCategory.equals("RAW_AUTO") || forcedCategory.equals("ROOT_AUTO")) && manifest.has("modules")) {
                JsonArray modules = manifest.getAsJsonArray("modules");
                if (!modules.isEmpty()) {
                    String modType = modules.get(0).getAsJsonObject().get("type").getAsString();
                    detectedType = (modType.equalsIgnoreCase("data") || modType.equalsIgnoreCase("script")) ? "BEHAVIOR" : "RESOURCE";
                }
            }
        }

        if (detectedType.equals("RAW_AUTO") || detectedType.equals("ROOT_AUTO")) {
            detectedType = "RESOURCE";
        }

        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytesToHash);
        BEDROCK_PACKS.add(new BedrockPackInfo(uuid, version, name, detectedType, mandatory, source, source.length(), hash));
        System.out.println("[Hybrid-BedrockPacks] [" + detectedType + "] Registered for Bedrock clients ONLY: " + name + " (UUID: " + uuid + " | v" + version + ")");
    }

    private static void generateWorldPackBindings() {
        try {
            String levelName = Main.CONFIG.getString("level-name", "world");
            Path worldDir = Path.of(levelName);
            Files.createDirectories(worldDir);

            JsonArray resourceArray = new JsonArray();
            JsonArray behaviorArray = new JsonArray();

            for (BedrockPackInfo pack : BEDROCK_PACKS) {
                JsonObject entry = new JsonObject();
                entry.addProperty("pack_id", pack.uuid());
                JsonArray ver = new JsonArray();
                for (String part : pack.version().split("\\.")) {
                    ver.add(Integer.parseInt(part));
                }
                entry.add("version", ver);

                if (pack.category().equals("BEHAVIOR")) {
                    behaviorArray.add(entry);
                } else {
                    resourceArray.add(entry);
                }
            }

            if (!resourceArray.isEmpty()) {
                Files.writeString(worldDir.resolve("world_resource_packs.json"), GSON.toJson(resourceArray));
                System.out.println("[Hybrid-BedrockPacks] Generated /" + levelName + "/world_resource_packs.json (" + resourceArray.size() + " pack(s))");
            }
            if (!behaviorArray.isEmpty()) {
                Files.writeString(worldDir.resolve("world_behavior_packs.json"), GSON.toJson(behaviorArray));
                System.out.println("[Hybrid-BedrockPacks] Generated /" + levelName + "/world_behavior_packs.json (" + behaviorArray.size() + " pack(s))");
            }
        } catch (Exception e) {
            System.err.println("[Hybrid-BedrockPacks] Could not write world pack bindings: " + e.getMessage());
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
