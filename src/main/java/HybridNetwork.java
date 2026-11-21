import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;

import java.net.InetSocketAddress;

public class HybridNetwork {

    public static void start(String ip, int javaPort, int bedrockPort,
                             String motd, String javaWorldName, String bedrockWorldName,
                             int maxPlayers, String gamemode,
                             int compressionThreshold, boolean onlineMode, boolean allVersions,
                             boolean allowJava, boolean allowBedrock) {

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        boolean skinsRestorer = Main.CONFIG.getBoolean("allow-skins-restorer", true);
        int internalBackendPort = javaPort + 1;

        if (allowJava) {
            Thread.ofVirtual().start(() -> {
                try {
                    ServerBootstrap b = new ServerBootstrap();
                    b.group(bossGroup, workerGroup)
                     .channel(NioServerSocketChannel.class)
                     .childHandler(new ChannelInitializer<SocketChannel>() {
                         @Override
                         protected void initChannel(SocketChannel ch) {
                             ch.pipeline().addLast("multiversion-router",
                                 new MultiVersionRouter(motd, javaWorldName, maxPlayers, allVersions));
                         }
                     });

                    b.bind(new InetSocketAddress(ip, javaPort)).sync();
                    System.out.println("[Hybrid-Network] Java Edition (TCP) ONLINE on " + ip + ":" + javaPort);
                } catch (Exception e) {
                    System.err.println("[Hybrid-Network] Failed to bind TCP port " + javaPort + ": " + e.getMessage());
                }
            });
        }

        if (allowBedrock) {
            Thread.ofVirtual().start(() -> {
                try {
                    BedrockPong pong = new BedrockPong()
                        .edition("MCPE")
                        .motd(motd)
                        .subMotd(bedrockWorldName)
                        .playerCount(0)
                        .maximumPlayerCount(maxPlayers)
                        .gameType(gamemode.substring(0, 1).toUpperCase() + gamemode.substring(1))
                        .protocolVersion(Bedrock_v944.CODEC.getProtocolVersion())
                        .version(Bedrock_v944.CODEC.getMinecraftVersion())
                        .ipv4Port(bedrockPort)
                        .ipv6Port(bedrockPort);

                    ServerBootstrap b = new ServerBootstrap();
                    b.group(bossGroup, workerGroup)
                     .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                     .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
                     .childHandler(new BedrockServerInitializer() {
                         @Override
                         protected void initSession(BedrockServerSession session) {
                             session.setCodec(Bedrock_v944.CODEC);
                             session.getPeer().getChannel().pipeline().addLast("hybrid-bridge",
                                 new BedrockToJavaBridge(session, internalBackendPort, onlineMode, skinsRestorer));
                         }
                     });

                    b.bind(new InetSocketAddress(ip, bedrockPort)).sync();
                    System.out.println("[Hybrid-Network] Bedrock Edition (Cloudburst RakNet 1.26.50) ONLINE on " + ip + ":" + bedrockPort);
                } catch (Exception e) {
                    System.err.println("[Hybrid-Network] Failed to bind Bedrock UDP port " + bedrockPort + ": " + e.getMessage());
                }
            });
        }
    }
}
