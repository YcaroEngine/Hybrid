import java.io.*;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class ServerConfig {
    private final Properties props = new Properties();
    private final Path file = Path.of("server.properties");

    public void loadOrCreate() throws IOException {
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
        }

        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("server-name", "Hybrid Server");
        defaults.put("motd", "Hybrid Engine | Java & Bedrock | All Versions");
        defaults.put("server-ip", "0.0.0.0");
        defaults.put("server-port", "25565");
        defaults.put("online-mode", "true");
        defaults.put("network-compression-threshold", "256");
        defaults.put("max-players", "50");
        defaults.put("view-distance", "10");
        defaults.put("simulation-distance", "10");
        defaults.put("gamemode", "survival");
        defaults.put("difficulty", "normal");
        defaults.put("hardcore", "false");
        defaults.put("pvp", "true");
        defaults.put("allow-flight", "false");
        defaults.put("allow-nether", "true");
        defaults.put("spawn-monsters", "true");
        defaults.put("spawn-animals", "true");
        defaults.put("spawn-npcs", "true");
        defaults.put("spawn-protection", "16");
        defaults.put("level-name", "world");
        defaults.put("level-seed", "");
        defaults.put("level-type", "minecraft\\:normal");
        defaults.put("generate-structures", "true");
        defaults.put("white-list", "false");
        defaults.put("enforce-whitelist", "false");
        defaults.put("enable-command-block", "true");
        defaults.put("function-permission-level", "2");
        defaults.put("op-permission-level", "4");
        defaults.put("resource-pack", "");
        defaults.put("resource-pack-prompt", "");
        defaults.put("require-resource-pack", "false");
        defaults.put("enable-query", "false");
        defaults.put("query.port", "25565");
        defaults.put("enable-rcon", "false");
        defaults.put("rcon.port", "25575");
        defaults.put("rcon.password", "");
        defaults.put("sync-chunk-writes", "true");
        defaults.put("max-tick-time", "60000");
        defaults.put("rate-limit", "0");
        // Configuracoes Exclusivas Hybrid
        defaults.put("allow-java-server", "true");
        defaults.put("allow-bedrock-server", "true");
        defaults.put("java-edition-port", "default");
        defaults.put("bedrock-edition-port", "default");
        defaults.put("java-server-name", "World");
        defaults.put("bedrock-server-name", "World");
        defaults.put("accept-all-versions", "true");
        defaults.put("allow-skins-restorer", "true");
        defaults.put("require-bedrock-packs", "true");

        boolean updated = !Files.exists(file);
        StringBuilder sb = new StringBuilder();
        if (Files.exists(file)) {
            sb.append(Files.readString(file));
            if (!sb.toString().endsWith("\n")) sb.append("\n");
        } else {
            sb.append("# Hybrid Engine Properties - 1.21.11 Multi-Version Core\n");
        }

        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            if (!props.containsKey(entry.getKey())) {
                props.setProperty(entry.getKey(), entry.getValue());
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                updated = true;
            }
        }

        if (updated) {
            Files.writeString(file, sb.toString());
            System.out.println("[Hybrid] server.properties synced with all Hybrid & Vanilla keys.");
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
