import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class ServerConfig {
    private final Properties props = new Properties();
    private final Path file = Path.of("server.properties");

    public void loadOrCreate() throws IOException {
        if (!Files.exists(file)) {
            String defaultContent =
                "# Hybrid Engine Properties - 1.21.11 Multi-Version Core\n" +
                "server-name=Hybrid Server\n" +
                "motd=Hybrid Engine | Java & Bedrock | All Versions\n" +
                "server-ip=0.0.0.0\n" +
                "server-port=25565\n" +
                "online-mode=true\n" +
                "network-compression-threshold=256\n" +
                "max-players=50\n" +
                "view-distance=10\n" +
                "simulation-distance=10\n" +
                "gamemode=survival\n" +
                "difficulty=normal\n" +
                "hardcore=false\n" +
                "pvp=true\n" +
                "allow-flight=false\n" +
                "allow-nether=true\n" +
                "spawn-monsters=true\n" +
                "spawn-animals=true\n" +
                "spawn-npcs=true\n" +
                "spawn-protection=16\n" +
                "level-name=world\n" +
                "level-seed=\n" +
                "level-type=minecraft\\:normal\n" +
                "generate-structures=true\n" +
                "white-list=false\n" +
                "enforce-whitelist=false\n" +
                "enable-command-block=true\n" +
                "function-permission-level=2\n" +
                "op-permission-level=4\n" +
                "resource-pack=\n" +
                "resource-pack-prompt=\n" +
                "require-resource-pack=false\n" +
                "enable-query=false\n" +
                "query.port=25565\n" +
                "enable-rcon=false\n" +
                "rcon.port=25575\n" +
                "rcon.password=\n" +
                "sync-chunk-writes=true\n" +
                "max-tick-time=60000\n" +
                "rate-limit=0\n" +
                "# Hybrid Exclusive Settings\n" +
                "allow-java-server=true\n" +
                "allow-bedrock-server=true\n" +
                "java-edition-port=default\n" +
                "bedrock-edition-port=default\n" +
                "java-server-name=World\n" +
                "bedrock-server-name=World\n" +
                "accept-all-versions=true\n" +
                "allow-skins-restorer=true\n";

            Files.writeString(file, defaultContent);
            System.out.println("[Hybrid] Full server.properties generated successfully.");
        }

        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
    }

    public String getString(String key, String fallback) {
        return props.getProperty(key, fallback).trim();
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(fallback)).trim());
    }

    public int resolvePort(String key, int fallbackPort) {
        String value = getString(key, "default").toLowerCase();
        if (value.equals("default") || value.equals("0") || value.isEmpty()) {
            return fallbackPort;
        }
        try {
            int customPort = Integer.parseInt(value);
            return (customPort > 0 && customPort <= 65535) ? customPort : fallbackPort;
        } catch (NumberFormatException e) {
            return fallbackPort;
        }
    }
}
