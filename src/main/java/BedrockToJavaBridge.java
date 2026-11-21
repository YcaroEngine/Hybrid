import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.util.OptionalBoolean;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;

public class BedrockToJavaBridge implements BedrockPacketHandler {
    private static final Gson GSON = new Gson();
    private final BedrockServerSession session;
    private final int targetJavaPort;
    private final boolean onlineMode;
    private final boolean skinsRestorer;

    private Channel javaBackendChannel;
    private String bedrockUsername = "BedrockPlayer";
    private UUID playerUuid;
    private boolean spawned = false;

    public BedrockToJavaBridge(BedrockServerSession session, int targetJavaPort, boolean onlineMode, boolean skinsRestorer) {
        this.session = session;
        this.targetJavaPort = targetJavaPort;
        this.onlineMode = onlineMode;
        this.skinsRestorer = skinsRestorer;
        System.out.println("[Hybrid-Bedrock] Incoming Bedrock session from " + session.getSocketAddress());
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        PacketCompressionAlgorithm algorithm = PacketCompressionAlgorithm.ZLIB;

        NetworkSettingsPacket response = new NetworkSettingsPacket();
        response.setCompressionThreshold(1);
        response.setCompressionAlgorithm(algorithm);
        session.sendPacketImmediately(response);

        session.setCompression(algorithm);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        try {
            if (packet.getAuthPayload() != null) {
                extractUsernameFromJwt(packet.getAuthPayload().toString());
            }
        } catch (Exception ignored) {}

        this.playerUuid = UUID.nameUUIDFromBytes(("Bedrock:" + bedrockUsername).getBytes(StandardCharsets.UTF_8));
        System.out.println("[Hybrid-Bedrock] Player logged in: " + bedrockUsername + " (" + playerUuid + ")");

        PlayStatusPacket status = new PlayStatusPacket();
        status.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
        session.sendPacket(status);

        ResourcePacksInfoPacket packsInfo = new ResourcePacksInfoPacket();
        packsInfo.setForcedToAccept(false);
        packsInfo.setHasAddonPacks(false);
        packsInfo.setScriptingEnabled(false);
        session.sendPacket(packsInfo);

        connectToJavaBackend();
        return PacketSignal.HANDLED;
    }
    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        if (packet.getStatus() == ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS) {
            ResourcePackStackPacket stack = new ResourcePackStackPacket();
            stack.setForcedToAccept(false);
            stack.setGameVersion("*");
            session.sendPacket(stack);
        } else if (packet.getStatus() == ResourcePackClientResponsePacket.Status.COMPLETED) {
            System.out.println("[Hybrid-Bedrock] Spawning " + bedrockUsername + " into Hybrid world...");
            spawnPlayerInWorld();
        }
        return PacketSignal.HANDLED;
    }

    private void spawnPlayerInWorld() {
        if (spawned) return;
        spawned = true;

        StartGamePacket startGame = new StartGamePacket();
        startGame.setUniqueEntityId(1L);
        startGame.setRuntimeEntityId(1L);
        startGame.setPlayerGameType(GameType.SURVIVAL);
        startGame.setPlayerPosition(Vector3f.from(0, 80, 0));
        startGame.setRotation(Vector2f.from(0, 0));

        startGame.setSeed(0L);
        startGame.setSpawnBiomeType(SpawnBiomeType.DEFAULT);
        startGame.setCustomBiomeName("");
        startGame.setDimensionId(0);
        startGame.setGeneratorId(1);
        startGame.setLevelGameType(GameType.SURVIVAL);
        startGame.setDifficulty(1);
        startGame.setDefaultSpawn(Vector3i.from(0, 80, 0));
        startGame.setAchievementsDisabled(true);
        startGame.setDayCycleStopTime(0);
        startGame.setEduEditionOffers(0);
        startGame.setEduFeaturesEnabled(false);
        startGame.setEduProductionId("");
        startGame.setRainLevel(0f);
        startGame.setLightningLevel(0f);
        startGame.setPlatformLockedContentConfirmed(false);
        startGame.setMultiplayerGame(true);
        startGame.setBroadcastingToLan(true);
        startGame.setXblBroadcastMode(GamePublishSetting.PUBLIC);
        startGame.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
        startGame.setCommandsEnabled(true);
        startGame.setTexturePacksRequired(false);
        startGame.setBonusChestEnabled(false);
        startGame.setStartingWithMap(false);
        startGame.setTrustingPlayers(true);
        startGame.setDefaultPlayerPermission(PlayerPermission.MEMBER);
        startGame.setServerChunkTickRange(4);
        startGame.setBehaviorPackLocked(false);
        startGame.setResourcePackLocked(false);
        startGame.setFromLockedWorldTemplate(false);
        startGame.setUsingMsaGamertagsOnly(false);
        startGame.setFromWorldTemplate(false);
        startGame.setWorldTemplateOptionLocked(false);
        startGame.setOnlySpawningV1Villagers(false);
        startGame.setDisablingPersonas(false);
        startGame.setDisablingCustomSkins(false);
        startGame.setEmoteChatMuted(false);
        startGame.setVanillaVersion("*");
        startGame.setLimitedWorldWidth(16);
        startGame.setLimitedWorldHeight(16);
        startGame.setNetherType(false);
        startGame.setForceExperimentalGameplay(OptionalBoolean.empty());
        startGame.setChatRestrictionLevel(ChatRestrictionLevel.NONE);
        startGame.setDisablingPlayerInteractions(false);

        startGame.setLevelId("HybridWorld");
        startGame.setLevelName("Hybrid World");
        startGame.setPremiumWorldTemplateId("00000000-0000-0000-0000-000000000000");
        startGame.setTrial(false);
        startGame.setAuthoritativeMovementMode(AuthoritativeMovementMode.CLIENT);
        startGame.setRewindHistorySize(0);
        startGame.setServerAuthoritativeBlockBreaking(false);
        startGame.setCurrentTick(0L);
        startGame.setEnchantmentSeed(0);
        startGame.setMultiplayerCorrelationId("");
        startGame.setServerEngine("Hybrid Engine");
        startGame.setPlayerPropertyData(NbtMap.EMPTY);
        startGame.setWorldTemplateId(UUID.randomUUID());
        startGame.setBlockPaletteChecksum(0L);

        session.sendPacket(startGame);

        PlayStatusPacket playStatus = new PlayStatusPacket();
        playStatus.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        session.sendPacket(playStatus);
    }

    @Override
    public PacketSignal handle(MovePlayerPacket packet) {
        if (javaBackendChannel != null && javaBackendChannel.isActive()) {
            ByteBuf moveBuf = Unpooled.buffer();
            MultiVersionRouter.writeVarInt(moveBuf, 0x1A);
            moveBuf.writeDouble(packet.getPosition().getX());
            moveBuf.writeDouble(packet.getPosition().getY() - 1.62);
            moveBuf.writeDouble(packet.getPosition().getZ());
            moveBuf.writeBoolean(packet.isOnGround());
            sendFramedToJava(moveBuf);
        }
        return PacketSignal.HANDLED;
    }

    private void connectToJavaBackend() {
        Bootstrap b = new Bootstrap();
        b.group(new NioEventLoopGroup(1))
         .channel(NioSocketChannel.class)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                     @Override
                     public void channelRead(ChannelHandlerContext javaCtx, Object msg) {
                         if (msg instanceof ByteBuf javaBuf) {
                             javaBuf.release();
                         }
                     }
                 });
             }
         });

        b.connect("127.0.0.1", targetJavaPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                this.javaBackendChannel = future.channel();
                Bridge.registerPlayer(playerUuid, bedrockUsername, true, skinsRestorer);
                sendJavaHandshakeAndLogin();
            } else {
                System.err.println("[Hybrid-Bedrock] Failed to bridge to Java core on port " + targetJavaPort);
            }
        });
    }

    private void sendJavaHandshakeAndLogin() {
        if (javaBackendChannel == null || !javaBackendChannel.isActive()) return;

        ByteBuf handshake = Unpooled.buffer();
        MultiVersionRouter.writeVarInt(handshake, 0x00);
        MultiVersionRouter.writeVarInt(handshake, 769);
        MultiVersionRouter.writeString(handshake, "127.0.0.1");
        handshake.writeShort(targetJavaPort);
        MultiVersionRouter.writeVarInt(handshake, 2);
        sendFramedToJava(handshake);

        ByteBuf loginStart = Unpooled.buffer();
        MultiVersionRouter.writeVarInt(loginStart, 0x00);
        MultiVersionRouter.writeString(loginStart, bedrockUsername);
        loginStart.writeLong(playerUuid.getMostSignificantBits());
        loginStart.writeLong(playerUuid.getLeastSignificantBits());
        sendFramedToJava(loginStart);
    }

    private void sendFramedToJava(ByteBuf payload) {
        ByteBuf framed = Unpooled.buffer();
        MultiVersionRouter.writeVarInt(framed, payload.readableBytes());
        framed.writeBytes(payload);
        payload.release();
        javaBackendChannel.writeAndFlush(framed);
    }

    private void extractUsernameFromJwt(String raw) {
        for (String part : raw.split("\\.")) {
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(part);
                String jsonStr = new String(decoded, StandardCharsets.UTF_8);
                if (jsonStr.contains("displayName")) {
                    JsonObject obj = GSON.fromJson(jsonStr, JsonObject.class);
                    if (obj.has("extraData")) {
                        JsonObject extra = obj.getAsJsonObject("extraData");
                        if (extra.has("displayName")) {
                            this.bedrockUsername = extra.get("displayName").getAsString();
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        System.out.println("[Hybrid-Bedrock] Disconnected (" + bedrockUsername + "): " + reason);
        if (javaBackendChannel != null) {
            javaBackendChannel.close();
        }
    }
}
