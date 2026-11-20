import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class HybridNetwork {

    public static void start(String ip, int javaPort, int bedrockPort,
                             String motd, String javaWorldName, String bedrockWorldName,
                             int maxPlayers, String gamemode,
                             int compressionThreshold, boolean onlineMode, boolean allVersions,
                             boolean allowJava, boolean allowBedrock) {

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        if (allowJava) {
            Thread.ofVirtual().start(() -> {
                try {
                    ServerBootstrap b = new ServerBootstrap();
                    b.group(bossGroup, workerGroup)
                     .channel(NioServerSocketChannel.class)
                     .childHandler(new ChannelInitializer<SocketChannel>() {
                         @Override
                         protected void initChannel(SocketChannel ch) {
                         }
                     });

                    b.bind(new InetSocketAddress(ip, javaPort)).sync();
                    System.out.println("[Hybrid-Network] Java Edition (TCP) ONLINE on " + ip + ":" + javaPort + " | World: " + javaWorldName + " | Compression: " + compressionThreshold);
                } catch (Exception e) {
                    System.err.println("[Hybrid-Network] Failed to bind TCP port " + javaPort + ": " + e.getMessage());
                }
            });
        }

        if (allowBedrock) {
            Thread.ofVirtual().start(() -> {
                try {
                    String bedrockPing = String.join(";",
                        "MCPE",
                        motd,
                        "898",
                        "1.21.11",
                        "0",
                        String.valueOf(maxPlayers),
                        "13253860892328930865",
                        bedrockWorldName,
                        gamemode.substring(0, 1).toUpperCase() + gamemode.substring(1),
                        "1",
                        String.valueOf(bedrockPort),
                        String.valueOf(bedrockPort)
                    ) + ";";

                    ServerBootstrap b = new ServerBootstrap();
                    b.group(workerGroup)
                     .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                     .option(RakChannelOption.RAK_ADVERTISEMENT, bedrockPing.getBytes(StandardCharsets.UTF_8))
                     .childHandler(new ChannelInitializer<>() {
                         @Override
                         protected void initChannel(io.netty.channel.Channel ch) {
                         }
                     });

                    b.bind(new InetSocketAddress(ip, bedrockPort)).sync();
                    System.out.println("[Hybrid-Network] Bedrock Edition (UDP/RakNet) ONLINE on " + ip + ":" + bedrockPort + " | World: " + bedrockWorldName);
                } catch (Exception e) {
                    System.err.println("[Hybrid-Network] Failed to bind UDP port " + bedrockPort + ": " + e.getMessage());
                }
            });
        }
    }
}
