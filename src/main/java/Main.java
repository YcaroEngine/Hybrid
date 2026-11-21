import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class Main {
    public static final int BASE_PROTOCOL_1_21_11 = 774;
    public static final ServerConfig CONFIG = new ServerConfig();

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        try {
            Files.createDirectories(Path.of(".hybrid", ".sys"));
            Files.createDirectories(Path.of("mods"));
            Files.createDirectories(Path.of("plugins"));
            Files.createDirectories(Path.of("bedrock-packs"));
            CONFIG.loadOrCreate();

            int hostPort = HybridLoader.detectHostPort(CONFIG.getInt("server-port", 25565));
            String hostMotd = HybridLoader.detectHostMotd(CONFIG.getString("motd", "Hybrid Engine | Java & Bedrock"));
            String hostIp = HybridLoader.detectHostIp(CONFIG.getString("server-ip", "0.0.0.0"));

            String serverName = CONFIG.getString("server-name", "Hybrid Server");
            int javaPort = hostPort;
            int bedrockPort = hostPort;

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
            System.out.println(" HYBRID ENGINE | " + serverName + " (NeoForge 1.21.11 + Paper + Bedrock 26.60)");
            System.out.println(" Host Address:          " + hostIp + ":" + hostPort);
            System.out.println(" Java Edition (TCP):    " + (allowJava ? "ENABLED -> Port " + javaPort + " (ViaVersion + ViaBackwards)" : "DISABLED"));
            System.out.println(" Bedrock Edition (UDP): " + (allowBedrock ? "ENABLED -> Port " + bedrockPort + " [Same Port / NetherNet + Floodgate]" : "DISABLED"));
            System.out.println(" Bedrock Prefix:        NONE (No dot '.' prefix)");
            System.out.println(" Native SkinsRestorer:  " + allowSkins);
            System.out.println("==========================================================================");

            HybridLoader.initializeEnvironment(hostIp, hostPort, hostMotd);

            HybridNetwork.start("0.0.0.0", javaPort, bedrockPort, hostMotd, javaWorldName, bedrockWorldName,
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
