import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class HybridLoader {
    private static final Gson GSON = new Gson();
    private static final List<Object> ACTIVE_PLUGINS = new ArrayList<>();
    private static Process coreWorldProcess;
    private static Process viaEngineProcess;
    private static Process bedrockEngineProcess;

    private static final Path HIDDEN_ROOT = Path.of(".hybrid", ".sys");
    private static final Path HIDDEN_VIA_DIR = HIDDEN_ROOT.resolve(".v1");
    private static final Path HIDDEN_BEDROCK_DIR = HIDDEN_ROOT.resolve(".b1");
    private static final Path USER_BEDROCK_PACKS = Path.of("bedrock-packs");

    public static int detectHostPort(int fallback) {
        try {
            String envPort = System.getenv("SERVER_PORT");
            if (envPort != null && !envPort.isBlank()) {
                return Integer.parseInt(envPort.trim());
            }
            Path props = Path.of("server.properties");
            if (Files.exists(props)) {
                for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                    if (line.startsWith("server-port=")) {
                        int p = Integer.parseInt(line.substring("server-port=".length()).trim());
                        if (p > 0) return p;
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    public static String detectHostMotd(String fallback) {
        try {
            Path props = Path.of("server.properties");
            if (Files.exists(props)) {
                for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                    if (line.startsWith("motd=")) {
                        String m = line.substring("motd=".length()).trim();
                        if (!m.isEmpty()) return m;
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    public static String detectHostIp(String fallback) {
        try {
            String envIp = System.getenv("SERVER_IP");
            if (envIp != null && !envIp.isBlank() && !envIp.equals("0.0.0.0")) {
                return envIp.trim();
            }
            Path props = Path.of("server.properties");
            if (Files.exists(props)) {
                for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                    if (line.startsWith("server-ip=")) {
                        String ip = line.substring("server-ip=".length()).trim();
                        if (!ip.isEmpty() && !ip.equals("0.0.0.0")) return ip;
                    }
                }
            }
            HttpURLConnection conn = (HttpURLConnection) URI.create("https://" + "api.ipify.org").toURL().openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            String publicIp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!publicIp.isEmpty()) return publicIp;
        } catch (Exception ignored) {}
        return fallback;
    }

    public static void initializeEnvironment(String hostIp, int hostPort, String hostMotd) {
        try {
            Files.createDirectories(Path.of("plugins"));
            Files.createDirectories(Path.of("mods"));
            Files.createDirectories(USER_BEDROCK_PACKS);
            Files.createDirectories(HIDDEN_VIA_DIR);
            Files.createDirectories(HIDDEN_BEDROCK_DIR.resolve("packs"));
        } catch (IOException ignored) {}

        ensureCoreProperties(hostMotd);
        generateFloodgateKeyAndConfig();
        setupModLoaderCore(hostPort);
        startBedrockPacksSyncWatcher();
        setupHiddenMultiVersionEngine(hostPort);
        setupHiddenBedrockEngine(hostIp, hostPort, hostMotd);
        loadRealPlugins("plugins");
    }

    private static void ensureCoreProperties(String hostMotd) {
        try {
            Path eulaPath = Path.of("eula.txt");
            if (!Files.exists(eulaPath) || !Files.readString(eulaPath).contains("eula=true")) {
                Files.writeString(eulaPath, "# Auto-accepted by Hybrid Engine\neula=true\n");
            }

            Path propsPath = Path.of("server.properties");
            List<String> lines = Files.exists(propsPath)
                    ? new ArrayList<>(Files.readAllLines(propsPath, StandardCharsets.UTF_8))
                    : new ArrayList<>();

            setOrAddProperty(lines, "online-mode", "false");
            setOrAddProperty(lines, "enforce-secure-profile", "false");
            setOrAddProperty(lines, "motd", hostMotd);
            setOrAddProperty(lines, "max-players", String.valueOf(Main.CONFIG.getInt("max-players", 50)));
            setOrAddProperty(lines, "gamemode", Main.CONFIG.getString("gamemode", "survival"));
            setOrAddProperty(lines, "level-name", Main.CONFIG.getString("level-name", "world"));

            Files.write(propsPath, lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static void setOrAddProperty(List<String> lines, String key, String value) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                return;
            }
        }
        lines.add(key + "=" + value);
    }
    private static void generateFloodgateKeyAndConfig() {
        try {
            Path keyPath = HIDDEN_BEDROCK_DIR.resolve("key.pem");
            if (!Files.exists(keyPath)) {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair kp = kpg.generateKeyPair();
                String pem = "-----BEGIN PRIVATE KEY-----\n" +
                        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(kp.getPrivate().getEncoded()) +
                        "\n-----END PRIVATE KEY-----\n";
                Files.writeString(keyPath, pem, StandardCharsets.UTF_8);
            }

            String floodgateConfig =
                "key-file-name: key.pem\n" +
                "username-prefix: \"\"\n" +
                "replace-spaces: true\n" +
                "default-locale: pt_BR\n" +
                "disconnect:\n" +
                "  invalid-key: \"Invalid authentication key.\"\n" +
                "  invalid-arguments-length: \"Invalid login packet.\"\n" +
                "data-linking:\n" +
                "  enable: true\n" +
                "  allow-linking: true\n" +
                "  type: sqlite\n" +
                "metrics:\n" +
                "  enabled: false\n" +
                "debug: false\n" +
                "config-version: 3\n";

            Files.writeString(HIDDEN_BEDROCK_DIR.resolve("floodgate-config.yml"), floodgateConfig, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static void setupModLoaderCore(int publicPort) {
        Path librariesDir = Path.of("libraries");
        try {
            Files.createDirectories(librariesDir);

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

                String latestVersion = "21.11.48";
                Matcher m = Pattern.compile("<version>(21\\.11\\.[^<]+)</version>").matcher(xml);
                while (m.find()) {
                    latestVersion = m.group(1);
                }

                String installerUrl = host + latestVersion + "/neoforge-" + latestVersion + "-installer.jar";
                Path installerPath = HIDDEN_ROOT.resolve("installer.dat");

                System.out.println("[Hybrid-Core] Installing NeoForge " + latestVersion + "...");
                try (InputStream in = URI.create(installerUrl).toURL().openStream()) {
                    Files.copy(in, installerPath, StandardCopyOption.REPLACE_EXISTING);
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
                int internalCorePort = publicPort + 2;
                System.out.println("[Hybrid-Core] Starting NeoForge 1.21.11 World Engine...");

                Thread.ofVirtual().start(() -> {
                    try {
                        ProcessBuilder serverPb = new ProcessBuilder(
                            "java",
                            "@" + argsFile.toString(),
                            "--nogui",
                            "--port", String.valueOf(internalCorePort)
                        );
                        serverPb.inheritIO();
                        coreWorldProcess = serverPb.start();
                        coreWorldProcess.waitFor();
                    } catch (Exception runEx) {
                        System.err.println("[Hybrid-Core] World Engine stopped: " + runEx.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("[Hybrid-Core] Error: " + e.getMessage());
        }
    }

    private static void setupHiddenMultiVersionEngine(int publicPort) {
        if (!Main.CONFIG.getBoolean("allow-java-server", true)) return;

        Thread.ofVirtual().start(() -> {
            try {
                Path hiddenJar = HIDDEN_VIA_DIR.resolve("net-v1.dat");
                if (!Files.exists(hiddenJar) || Files.size(hiddenJar) < 1024 * 1024) {
                    System.out.println("[Hybrid-Network] Initializing ViaVersion & ViaBackwards engine...");
                    String apiUrl = "https://" + "://github.com";
                    HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
                    conn.setRequestProperty("User-Agent", "HybridEngine");
                    String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                    JsonObject release = GSON.fromJson(json, JsonObject.class);
                    JsonArray assets = release.getAsJsonArray("assets");
                    String downloadUrl = null;
                    for (JsonElement el : assets) {
                        JsonObject asset = el.getAsJsonObject();
                        String name = asset.get("name").getAsString();
                        if (name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc")) {
                            downloadUrl = asset.get("browser_download_url").getAsString();
                            break;
                        }
                    }

                    if (downloadUrl != null) {
                        try (InputStream in = URI.create(downloadUrl).toURL().openStream()) {
                            Files.copy(in, hiddenJar, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }

                if (Files.exists(hiddenJar)) {
                    int internalCorePort = publicPort + 2;

                    String vpConfig =
                        "bind-address: 0.0.0.0:" + publicPort + "\n" +
                        "target-address: 127.0.0.1:" + internalCorePort + "\n" +
                        "target-version: 1.21.11\n" +
                        "proxy-online-mode: false\n" +
                        "auth-method: NONE\n" +
                        "passthrough-motd: true\n" +
                        "passthrough-ping: true\n";

                    Files.writeString(HIDDEN_VIA_DIR.resolve("viaproxy.yml"), vpConfig, StandardCharsets.UTF_8);

                    Thread.sleep(2000);
                    ProcessBuilder pb = new ProcessBuilder(
                        "java", "-Xms128M", "-Xmx384M",
                        "-jar", "net-v1.dat",
                        "cli",
                        "--config", "viaproxy.yml"
                    );
                    pb.directory(HIDDEN_VIA_DIR.toFile());
                    pb.redirectErrorStream(true);
                    viaEngineProcess = pb.start();
                    System.out.println("[Hybrid-Network] Java Multi-Version (ViaVersion + ViaBackwards) ONLINE on port " + publicPort);
                }
            } catch (Exception e) {
                System.err.println("[Hybrid-Network] Multi-version engine notice: " + e.getMessage());
            }
        });
    }
    private static void setupHiddenBedrockEngine(String hostIp, int publicPort, String hostMotd) {
        if (!Main.CONFIG.getBoolean("allow-bedrock-server", true)) return;

        Thread.ofVirtual().start(() -> {
            try {
                Path hiddenJar = HIDDEN_BEDROCK_DIR.resolve("net-v2.dat");
                if (!Files.exists(hiddenJar) || Files.size(hiddenJar) < 1024 * 1024) {
                    System.out.println("[Hybrid-Network] Initializing Bedrock 26.60 & NetherNet + Floodgate engine...");
                    String url = "https://" + "download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/standalone";
                    try (InputStream in = URI.create(url).toURL().openStream()) {
                        Files.copy(in, hiddenJar, StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                syncBedrockPacksOnce();

                int internalCorePort = publicPort + 2;
                String cleanMotd = hostMotd.replace("\"", "").replaceAll("§.", "");
                int maxPlayers = Main.CONFIG.getInt("max-players", 50);
                boolean requirePacks = Main.CONFIG.getBoolean("require-bedrock-packs", true);

                String configYaml =
                    "bedrock:\n" +
                    "  address: 0.0.0.0\n" +
                    "  port: " + publicPort + "\n" +
                    "  clone-remote-port: false\n" +
                    "  motd1: \"" + cleanMotd + "\"\n" +
                    "  motd2: \"" + cleanMotd + "\"\n" +
                    "  server-name: \"" + cleanMotd + "\"\n" +
                    "  enable-proxy-protocol: false\n" +
                    "remote:\n" +
                    "  address: 127.0.0.1\n" +
                    "  port: " + internalCorePort + "\n" +
                    "  auth-type: floodgate\n" +
                    "  allow-password-authentication: true\n" +
                    "  use-proxy-protocol: false\n" +
                    "  forward-hostname: false\n" +
                    "floodgate-key-file: key.pem\n" +
                    "command-suggestions: true\n" +
                    "passthrough-motd: true\n" +
                    "passthrough-protocol-name: false\n" +
                    "passthrough-player-counts: true\n" +
                    "max-players: " + maxPlayers + "\n" +
                    "debug-mode: false\n" +
                    "show-cooldown: title\n" +
                    "show-coordinates: true\n" +
                    "disable-bedrock-scaffolding: false\n" +
                    "emote-offhand-workaround: disabled\n" +
                    "cache-images: 0\n" +
                    "allow-custom-skulls: true\n" +
                    "add-non-bedrock-items: true\n" +
                    "force-resource-packs: " + requirePacks + "\n" +
                    "xbox-achievements-enabled: false\n" +
                    "log-player-ip-addresses: true\n" +
                    "notify-update: false\n" +
                    "config-version: 4\n";

                Files.writeString(HIDDEN_BEDROCK_DIR.resolve("config.yml"), configYaml, StandardCharsets.UTF_8);

                Thread.sleep(3500);
                ProcessBuilder pb = new ProcessBuilder("java", "-Xms256M", "-Xmx512M", "-jar", "net-v2.dat", "nogui");
                pb.directory(HIDDEN_BEDROCK_DIR.toFile());
                pb.redirectErrorStream(true);
                bedrockEngineProcess = pb.start();
                System.out.println("[Hybrid-Network] Bedrock 26.60 & NetherNet + Floodgate ONLINE on " + hostIp + ":" + publicPort);

                try (Scanner sc = new Scanner(bedrockEngineProcess.getInputStream())) {
                    while (sc.hasNextLine()) {
                        String line = sc.nextLine();
                        if (line.contains("joined") || line.contains("disconnected") || line.contains("Started")) {
                            String clean = line.replaceAll("(?i)geyser", "Hybrid-Bedrock").replaceAll("^\\[[^\\]]+\\]\\s*", "");
                            System.out.println("[Hybrid-Bedrock] " + clean);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[Hybrid-Network] Bedrock engine error: " + e.getMessage());
            }
        });
    }
    private static void startBedrockPacksSyncWatcher() {
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    syncBedrockPacksOnce();
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        });
    }

    private static void syncBedrockPacksOnce() {
        try {
            Path targetPacksDir = HIDDEN_BEDROCK_DIR.resolve("packs");
            Files.createDirectories(USER_BEDROCK_PACKS);
            Files.createDirectories(targetPacksDir);

            Set<String> validNames = new HashSet<>();

            try (Stream<Path> userItems = Files.list(USER_BEDROCK_PACKS)) {
                userItems.forEach(source -> {
                    String fileName = source.getFileName().toString();
                    validNames.add(fileName);
                    Path dest = targetPacksDir.resolve(fileName);
                    try {
                        if (Files.isDirectory(source)) {
                            copyDirectorySync(source, dest);
                        } else {
                            if (!Files.exists(dest) || Files.size(source) != Files.size(dest)) {
                                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                                System.out.println("[Hybrid-Packs] Synced Bedrock pack: " + fileName);
                            }
                        }
                    } catch (IOException ignored) {}
                });
            }

            try (Stream<Path> targetItems = Files.list(targetPacksDir)) {
                targetItems.forEach(dest -> {
                    String fileName = dest.getFileName().toString();
                    if (!validNames.contains(fileName)) {
                        try {
                            deleteRecursively(dest);
                            System.out.println("[Hybrid-Packs] Removed deleted Bedrock pack: " + fileName);
                        } catch (IOException ignored) {}
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private static void copyDirectorySync(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                if (!Files.exists(targetDir)) Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                if (!Files.exists(targetFile) || Files.size(file) != Files.size(targetFile)) {
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
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

                    try { clazz.getMethod("onLoad").invoke(instance); } catch (Throwable ignored) {}
                    try { clazz.getMethod("setEnabled", boolean.class).invoke(instance, true); } catch (Throwable ignored) {}
                    try { clazz.getMethod("onEnable").invoke(instance); } catch (Throwable ignored) {}

                    ACTIVE_PLUGINS.add(instance);
                    System.out.println("[Hybrid-Plugins] Enabled Paper/Spigot Plugin: " + pluginName);
                }
            } catch (Exception e) {
                System.err.println("[Hybrid-Plugins] Failed to enable " + jar.getName() + ": " + e.getMessage());
            }
        }
    }

    public static void shutdownAll() {
        if (bedrockEngineProcess != null && bedrockEngineProcess.isAlive()) bedrockEngineProcess.destroy();
        if (viaEngineProcess != null && viaEngineProcess.isAlive()) viaEngineProcess.destroy();
        if (coreWorldProcess != null && coreWorldProcess.isAlive()) coreWorldProcess.destroy();

        for (Object plugin : ACTIVE_PLUGINS) {
            try { plugin.getClass().getMethod("setEnabled", boolean.class).invoke(plugin, false); } catch (Throwable ignored) {}
            try { plugin.getClass().getMethod("onDisable").invoke(plugin); } catch (Throwable ignored) {}
        }
    }
}
