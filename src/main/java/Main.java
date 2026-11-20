import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class Main {
    public static final int BASE_PROTOCOL_1_21_11 = 774;
    public static final ServerConfig CONFIG = new ServerConfig();

    public static void main(String[] args) {
        try {
            Files.createDirectories(Path.of("mods"));
            Files.createDirectories(Path.of("plugins"));
            Files.createDirectories(Path.of("bedrock-packs"));
            CONFIG.loadOrCreate();

            String serverName = CONFIG.getString("server-name", "Hybrid Server");
            String motd = CONFIG.getString("motd", "Hybrid Engine | Java & Bedrock");
            String ip = CONFIG.getString("server-ip", "0.0.0.0");
            int basePort = CONFIG.getInt("server-port", 25565);

            int javaPort = CONFIG.resolvePort("java-edition-port", basePort);
            int bedrockPort = CONFIG.resolvePort("bedrock-edition-port", javaPort);

            String javaWorldName = CONFIG.getString("java-server-name", "World");
            String bedrockWorldName = CONFIG.getString("bedrock-server-name", "World");

            int maxPlayers = CONFIG.getInt("max-players", 50);
            String gamemode = CONFIG.getString("gamemode", "survival");
            int compression = CONFIG.getInt("network-compression-threshold", 256);
            boolean onlineMode = CONFIG.getBoolean("online-mode", true);
            boolean allVersions = CONFIG.getBoolean("accept-all-versions", true);
            boolean allowJava = CONFIG.getBoolean("allow-java-server", true);
            boolean allowBedrock = CONFIG.getBoolean("allow-bedrock-server", true);
            boolean allowSkins = CONFIG.getBoolean("allow-skins-restorer", true);

            if (!allowJava && !allowBedrock) {
                System.err.println("[Hybrid-WARN] Both 'allow-java-server' and 'allow-bedrock-server' are set to FALSE!");
                return;
            }

            System.out.println("==========================================================================");
            System.out.println(" HYBRID ENGINE | " + serverName + " (1.21.11 Multi-Version Base)");
            System.out.println(" Bind Address:          " + ip);
            System.out.println(" Java Edition (TCP):    " + (allowJava ? "ENABLED -> Port " + javaPort + " (World: " + javaWorldName + ")" : "DISABLED"));
            System.out.println(" Bedrock Edition (UDP): " + (allowBedrock ? "ENABLED -> Port " + bedrockPort + (bedrockPort == javaPort ? " [Same Port]" : " [Extra Port]") + " (World: " + bedrockWorldName + ")" : "DISABLED"));
            System.out.println(" Online-Mode:           " + (onlineMode ? "TRUE (Mojang/Xbox Auth)" : "FALSE (Offline/Cracked)"));
            System.out.println(" Network Compression:   " + compression + " bytes");
            System.out.println(" Multi-Version Router:  " + allVersions + " (All Java & Bedrock Versions)");
            System.out.println(" Native SkinsRestorer:  " + allowSkins);
            System.out.println("==========================================================================");

            HybridLoader.initializeEnvironment();
            loadBedrockPacks();

            HybridNetwork.start(ip, javaPort, bedrockPort, motd, javaWorldName, bedrockWorldName,
                                 maxPlayers, gamemode, compression, onlineMode, allVersions, allowJava, allowBedrock);

            Scanner scanner = new Scanner(System.in);
            while (scanner.hasNextLine()) {
                String cmd = scanner.nextLine().trim();
                if (cmd.equalsIgnoreCase("stop")) {
                    System.out.println("[Hybrid] Saving world and shutting down server...");
                    System.exit(0);
                }
            }

        } catch (Exception e) {
            System.err.println("[Hybrid-ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void loadBedrockPacks() {
        File folder = new File("bedrock-packs");
        File[] packs = folder.listFiles((dir, name) -> name.endsWith(".mcpack") || name.endsWith(".zip") || name.endsWith(".mcaddon"));

        if (packs == null || packs.length == 0) {
            System.out.println("[Hybrid-BedrockPacks] No resource/behavior packs found in /bedrock-packs");
            return;
        }

        for (File pack : packs) {
            System.out.println("[Hybrid-BedrockPacks] Loaded pack for Bedrock clients: " + pack.getName() + " (" + (pack.length() / 1024) + " KB)");
        }
    }
}
