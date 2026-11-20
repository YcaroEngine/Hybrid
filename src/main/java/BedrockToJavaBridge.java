import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
                String chainJson = packet.getAuthPayload().toString();
                extractUsernameFromJwt(chainJson);
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
