import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class MultiVersionRouter extends ChannelInboundHandlerAdapter {
    private final String motd;
    private final String worldName;
    private final int maxPlayers;
    private final boolean acceptAllVersions;
    private int state = 0;
    private int clientProtocol = 774;

    private static final Map<Integer, String> PROTOCOL_NAMES = Map.ofEntries(
        Map.entry(47, "1.8.x"),
        Map.entry(340, "1.12.2"),
        Map.entry(754, "1.16.5"),
        Map.entry(763, "1.20.1"),
        Map.entry(765, "1.20.4"),
        Map.entry(767, "1.21.1"),
        Map.entry(769, "1.21.4"),
        Map.entry(774, "1.21.11"),
        Map.entry(777, "26.3")
    );

    public MultiVersionRouter(String motd, String worldName, int maxPlayers, boolean acceptAllVersions) {
        this.motd = motd;
        this.worldName = worldName;
        this.maxPlayers = maxPlayers;
        this.acceptAllVersions = acceptAllVersions;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) return;

        try {
            while (buf.isReadable()) {
                int length = readVarInt(buf);
                if (buf.readableBytes() < length) {
                    buf.resetReaderIndex();
                    return;
                }

                int packetId = readVarInt(buf);

                if (state == 0 && packetId == 0x00) {
                    this.clientProtocol = readVarInt(buf);
                    String serverAddress = readString(buf);
                    int serverPort = buf.readUnsignedShort();
                    int nextState = readVarInt(buf);

                    String versionLabel = PROTOCOL_NAMES.getOrDefault(clientProtocol, "Protocol-" + clientProtocol);
                    if (!acceptAllVersions && clientProtocol != 774) {
                        System.out.println("[Hybrid-MultiVersion] Blocked non-1.21.11 client (" + versionLabel + ")");
                        ctx.close();
                        return;
                    }

                    if (nextState == 2) {
                        System.out.println("[Hybrid-MultiVersion] Routing Java client (" + versionLabel + ") -> Core 1.21.11 (774)");
                    }
                    this.state = nextState;

                } else if (state == 1 && packetId == 0x00) {
                    int replyProtocol = acceptAllVersions ? clientProtocol : 774;
                    String jsonResponse = String.format(
                        "{\"version\":{\"name\":\"Hybrid 1.21.11 (%s)\",\"protocol\":%d},\"players\":{\"max\":%d,\"online\":0},\"description\":{\"text\":\"%s\"}}",
                        worldName, replyProtocol, maxPlayers, motd.replace("\"", "\\\"")
                    );

                    ByteBuf reply = Unpooled.buffer();
                    writeVarInt(reply, 0x00);
                    writeString(reply, jsonResponse);
                    sendFramedPacket(ctx, reply);

                } else if (state == 1 && packetId == 0x01) {
                    long payload = buf.readLong();
                    ByteBuf pong = Unpooled.buffer();
                    writeVarInt(pong, 0x01);
                    pong.writeLong(payload);
                    sendFramedPacket(ctx, pong);
                    ctx.close();
                } else {
                    buf.skipBytes(buf.readableBytes());
                }
            }
        } finally {
            buf.release();
        }
    }

    private void sendFramedPacket(ChannelHandlerContext ctx, ByteBuf payload) {
        ByteBuf framed = Unpooled.buffer();
        writeVarInt(framed, payload.readableBytes());
        framed.writeBytes(payload);
        payload.release();
        ctx.writeAndFlush(framed);
    }

    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        byte currentByte;
        while (true) {
            currentByte = buf.readByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new RuntimeException("VarInt too big");
        }
        return value;
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte((value & 127) | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public static String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buf, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }
}
