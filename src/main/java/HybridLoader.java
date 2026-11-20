import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    public static final List<BedrockPackInfo> BEDROCK_PACKS = new ArrayList<>();

    public record BedrockPackInfo(String uuid, String version, String name, String category, boolean mandatoryForBedrock, File source, long sizeBytes, byte[] sha256) {}

    public static void initializeEnvironment() {
        try {
            Files.createDirectories(Path.of("plugins"));
            Files.createDirectories(Path.of("mods"));
        } catch (IOException ignored) {}

        setupModLoaderCore();
        loadRealPlugins("plugins");
        loadAllBedrockPacks();
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
                String host = "https://neoforged.net";
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
            }
        } catch (Exception e) {
            System.err.println("[Hybrid-Core] Error: " + e.getMessage());
        }
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
                    System.out.println("[Hybrid-Plugins] Enabled Plugin: " + pluginName);
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

        if (!BEDROCK_PACKS.isEmpty()) {
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
                    } catch (Exception ignored) {}
                }
            } else {
                String lower = item.getName().toLowerCase();
                if (lower.endsWith(".mcpack") || lower.endsWith(".mcaddon") || lower.endsWith(".zip")) {
                    try (JarFile zip = new JarFile(item)) {
                        JarEntry manifestEntry = zip.getJarEntry("manifest.json");
                        if (manifestEntry != null) {
                            String rawJson = new String(zip.getInputStream(manifestEntry).readAllBytes(), StandardCharsets.UTF_8);
                            parseAndRegisterPack(rawJson, item, forcedCategory, mandatory, Files.readAllBytes(item.toPath()));
                        }
                    } catch (Exception ignored) {}
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
            }
            if (!behaviorArray.isEmpty()) {
                Files.writeString(worldDir.resolve("world_behavior_packs.json"), GSON.toJson(behaviorArray));
            }
        } catch (Exception ignored) {}
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
