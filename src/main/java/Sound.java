import java.util.Map;
import java.util.UUID;

public class Sound {
    private static final Map<String, String> SOUND_MAP = Map.ofEntries(
        Map.entry("minecraft:block.stone.break", "dig.stone"),
        Map.entry("minecraft:block.stone.place", "use.stone"),
        Map.entry("minecraft:block.wood.break", "dig.wood"),
        Map.entry("minecraft:block.wood.place", "use.wood"),
        Map.entry("minecraft:block.grass.break", "dig.grass"),
        Map.entry("minecraft:entity.player.attack.strong", "game.player.attack.strong"),
        Map.entry("minecraft:entity.player.attack.weak", "game.player.attack.nodamage"),
        Map.entry("minecraft:entity.player.hurt", "game.player.hurt"),
        Map.entry("minecraft:entity.generic.explode", "random.explode"),
        Map.entry("minecraft:entity.experience_orb.pickup", "random.orb"),
        Map.entry("minecraft:block.chest.open", "random.chestopen"),
        Map.entry("minecraft:block.chest.close", "random.chestclose"),
        Map.entry("minecraft:entity.player.swim", "liquid.swim")
    );

    public static void playGlobalSound(String javaSound, double x, double y, double z, float volume, float pitch) {
        String bedrockSound = SOUND_MAP.getOrDefault(javaSound, javaSound.replace("minecraft:", ""));
        System.out.println("[Hybrid-Sound] Java: " + javaSound + " | Bedrock: " + bedrockSound + " at (" + (int)x + "," + (int)y + "," + (int)z + ")");
    }

    public static void playPrivateSound(UUID player, boolean isBedrock, String javaSound, float volume, float pitch) {
        String bedrockSound = SOUND_MAP.getOrDefault(javaSound, javaSound.replace("minecraft:", ""));
    }
}
