import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class Main {
    public static final int BASE_PROTOCOL_1_21_11 = 774;
    public static final ServerConfig CONFIG = new ServerConfig();

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        try {
            Files.createDirectories(Path.of(".hybrid"));
            Files.createDirectories(Path.of("java-packs/mods"));
            Files.createDirectories(Path.of("java-packs/plugins"));
            Files.createDirectories(Path.of("bedrock-packs/resource-packs"));
            Files.createDirectories(Path.of("bedrock-packs/behavior-packs"));
            Files.createDirectories(Path.of("bedrock-packs/raw-packs"));
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

            HybridNetwork.start(ip, javaPort, bedrockPort, motd, javaWorldName, bedrockWorldName,
                                 maxPlayers, gamemode, compression, onlineMode, allVersions, allowJava, allowBedrock);

            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            System.out.printf("Done (%.3fs)! For help, type \"help\"%n", elapsed);

            Scanner scanner = new Scanner(System.in);
            while (scanner.hasNextLine()) {
                String cmd = scanner.nextLine().trim();
                if (cmd.equalsIgnoreCase("stop")) {
                    System.out.println("[Hybrid] Saving world and shutting down server...");
                    HybridLoader.shutdownAll();
                    System.exit(0);
                } else if (cmd.equalsIgnoreCase("help")) {
                    System.out.println("[Hybrid] Commands: stop, help");
                }
            }

        } catch (Exception e) {
            System.err.println("[Hybrid-ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }
}
