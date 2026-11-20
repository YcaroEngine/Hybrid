import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class HybridNetwork {

    private static final byte[] RAKNET_MAGIC = new byte[]{
        0x00, (byte) 0xFF, (byte) 0xFF, 0x00,
        (byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE,
        (byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD,
        0x12, 0x34, 0x56, 0x78
    };

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
                    long serverGuid = Long.parseUnsignedLong("13253860892328930865");
                    String bedrockPing = String.join(";",
                        "MCPE",
                        motd,
                        "898",
                        "1.21.11",
                        "0",
                        String.valueOf(maxPlayers),
                        String.valueOf(serverGuid),
                        bedrockWorldName,
                        gamemode.substring(0, 1).toUpperCase() + gamemode.substring(1),
                        "1",
                        String.valueOf(bedrockPort),
                        String.valueOf(bedrockPort)
                    ) + ";";

                    Bootstrap b = new Bootstrap();
                    b.group(workerGroup)
                     .channel(NioDatagramChannel.class)
                     .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                         @Override
                         protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                             ByteBuf buf = packet.content();
                             if (!buf.isReadable()) return;

                             byte packetId = buf.readByte();

                             // 1. Unconnected Ping (0x01 / 0x02) -> Unconnected Pong (0x1C)
                             if ((packetId == 0x01 || packetId == 0x02) && buf.readableBytes() >= 8) {
                                 long clientTime = buf.readLong();
                                 byte[] motdBytes = bedrockPing.getBytes(StandardCharsets.UTF_8);

                                 ByteBuf pong = Unpooled.buffer(35 + motdBytes.length);
                                 pong.writeByte(0x1C);
                                 pong.writeLong(clientTime);
                                 pong.writeLong(serverGuid);
                                 pong.writeBytes(RAKNET_MAGIC);
                                 pong.writeShort(motdBytes.length);
                                 pong.writeBytes(motdBytes);

                                 ctx.writeAndFlush(new DatagramPacket(pong, packet.sender()));
                             }
                             // 2. Open Connection Request 1 (0x05) -> Open Connection Reply 1 (0x06)
                             else if (packetId == 0x05 && buf.readableBytes() >= 17) {
                                 buf.skipBytes(16); // Magic
                                 byte protocolVersion = buf.readByte();
                                 int mtuSize = packet.content().readableBytes() + 46;
                                 if (mtuSize > 1492) mtuSize = 1492;

                                 ByteBuf reply1 = Unpooled.buffer(28);
                                 reply1.writeByte(0x06);
                                 reply1.writeBytes(RAKNET_MAGIC);
                                 reply1.writeLong(serverGuid);
                                 reply1.writeBoolean(false); // No security
                                 reply1.writeShort(mtuSize);

                                 ctx.writeAndFlush(new DatagramPacket(reply1, packet.sender()));
                             }
                             // 3. Open Connection Request 2 (0x07) -> Open Connection Reply 2 (0x08)
                             else if (packetId == 0x07 && buf.readableBytes() >= 26) {
                                 buf.skipBytes(16); // Magic
                                 // Pula o endereço do servidor enviado pelo cliente
                                 byte ipVer = buf.readByte();
                                 if (ipVer == 4) buf.skipBytes(6);
                                 else buf.skipBytes(28);

                                 short mtuSize = buf.readShort();
                                 long clientGuid = buf.readLong();

                                 ByteBuf reply2 = Unpooled.buffer(35);
                                 reply2.writeByte(0x08);
                                 reply2.writeBytes(RAKNET_MAGIC);
                                 reply2.writeLong(serverGuid);
                                 // Escreve o endereço do cliente (IPv4)
                                 byte[] addrBytes = packet.sender().getAddress().getAddress();
                                 reply2.writeByte(4);
                                 for (byte ab : addrBytes) reply2.writeByte(~ab);
                                 reply2.writeShort(packet.sender().getPort());
                                 reply2.writeShort(mtuSize);
                                 reply2.writeBoolean(false); // No encryption

                                 ctx.writeAndFlush(new DatagramPacket(reply2, packet.sender()));
                                 System.out.println("[Hybrid-Bedrock] RakNet Handshake established with " + packet.sender() + " (GUID: " + clientGuid + ")");
                             }
                         }
                     });

                    b.bind(new InetSocketAddress(ip, bedrockPort)).sync();
                    System.out.println("[Hybrid-Network] Bedrock Edition (UDP/RakNet) ONLINE on " + ip + ":" + bedrockPort);
                } catch (Exception e) {
                    System.err.println("[Hybrid-Network] Failed to bind UDP port " + bedrockPort + ": " + e.getMessage());
                }
            });
        }
    }
}
