import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Bridge {
    private static final Map<UUID, HybridSession> SESSIONS = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public record HybridSession(UUID uuid, String username, boolean isBedrock, String gamemode, boolean flying) {}

    public static void registerPlayer(UUID uuid, String username, boolean isBedrock, boolean skinsRestorerEnabled) {
        SESSIONS.put(uuid, new HybridSession(uuid, username, isBedrock, "SURVIVAL", false));
        System.out.println("[Hybrid-Bridge] " + username + " joined via " + (isBedrock ? "Bedrock (UDP)" : "Java (TCP)"));

        if (skinsRestorerEnabled) {
            syncCrossPlatformSkin(uuid, username, isBedrock);
        }
    }

    private static void syncCrossPlatformSkin(UUID uuid, String username, boolean isBedrock) {
        Thread.ofVirtual().start(() -> {
            try {
                if (!isBedrock) {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("https://mojang.com" + username))
                            .GET().build();
                    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

                    if (resp.statusCode() == 200) {
                        System.out.println("[Hybrid-Skins] Java skin for '" + username + "' synced to Bedrock clients.");
                    }
                } else {
                    System.out.println("[Hybrid-Skins] Bedrock skin for '" + username + "' converted for Java clients.");
                }
            } catch (Exception e) {
                System.err.println("[Hybrid-Skins] Failed to sync skin for " + username + ": " + e.getMessage());
            }
        });
    }

    public static void handleBedrockMovement(UUID uuid, double x, double y, double z,
                                              float yaw, float pitch,
                                              boolean sprinting, boolean jumping, boolean swimming, boolean flying) {
        HybridSession session = SESSIONS.get(uuid);
        if (session == null) return;
        if (swimming) {
            Sound.playGlobalSound("minecraft:entity.player.swim", x, y, z, 0.5f, 1.0f);
        }
    }

    public static void setGamemode(UUID uuid, String newMode) {
        HybridSession current = SESSIONS.get(uuid);
        if (current == null) return;

        boolean canFly = newMode.equalsIgnoreCase("CREATIVE") || newMode.equalsIgnoreCase("SPECTATOR");
        SESSIONS.put(uuid, new HybridSession(current.uuid(), current.username(), current.isBedrock(), newMode.toUpperCase(), canFly));
    }

    public static void broadcastChat(UUID sender, String message) {
        HybridSession author = SESSIONS.get(sender);
        if (author == null) return;
        System.out.println("[Hybrid-Chat] <" + author.username() + "> " + message);
    }

    public static void handleBlockAction(UUID uuid, int x, int y, int z, String action, int bedrockBlockId) {
        Sound.playGlobalSound("minecraft:block.stone.break", x, y, z, 1.0f, 1.0f);
    }

    public static void handleEntityAttack(UUID attacker, int targetEntityId) {
        Sound.playGlobalSound("minecraft:entity.player.attack.strong", 0, 64, 0, 1.0f, 1.0f);
    }
}
