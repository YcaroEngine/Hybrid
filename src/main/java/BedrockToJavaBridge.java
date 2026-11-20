import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BedrockToJavaBridge extends ChannelInboundHandlerAdapter {
    private final int targetJavaPort;
    private final boolean onlineMode;
    private final boolean skinsRestorer;
    private Channel javaBackendChannel;
    private String bedrockUsername = "BedrockPlayer";
    private UUID playerUuid;

    public BedrockToJavaBridge(int targetJavaPort, boolean onlineMode, boolean skinsRestorer) {
        this.targetJavaPort = targetJavaPort;
        this.onlineMode = onlineMode;
        this.skinsRestorer = skinsRestorer;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("[Hybrid-Bedrock] Incoming Bedrock client connected from " + ctx.channel().remoteAddress());

        if (!HybridLoader.BEDROCK_PACKS.isEmpty()) {
            System.out.println("[Hybrid-BedrockPacks] Offering " + HybridLoader.BEDROCK_PACKS.size() + " pack(s) to client.");
        }

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
                             translateJavaToBedrock(ctx, javaBuf);
                             javaBuf.release();
                         }
                     }
                 });
             }
         });

        b.connect("127.0.0.1", targetJavaPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                this.javaBackendChannel = future.channel();
                this.playerUuid = UUID.nameUUIDFromBytes(("Bedrock:" + bedrockUsername).getBytes(StandardCharsets.UTF_8));
                Bridge.registerPlayer(playerUuid, bedrockUsername, true, skinsRestorer);
                sendJavaHandshakeAndLogin();
            } else {
                System.err.println("[Hybrid-Bedrock] Failed to bridge to Java core on port " + targetJavaPort);
                ctx.close();
            }
        });
    }

    private void sendJavaHandshakeAndLogin() {
        if (javaBackendChannel == null || !javaBackendChannel.isActive()) return;

        ByteBuf handshake = Unpooled.buffer();
        MultiVersionRouter.writeVarInt(handshake, 0x00);
        MultiVersionRouter.writeVarInt(handshake, 774);
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

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf bedrockBuf) {
            translateBedrockToJava(bedrockBuf);
            bedrockBuf.release();
        }
    }

    private void translateBedrockToJava(ByteBuf bedrockBuf) {
        if (javaBackendChannel == null || !javaBackendChannel.isActive()) return;
    }

    private void translateJavaToBedrock(ChannelHandlerContext bedrockCtx, ByteBuf javaBuf) {
        if (!bedrockCtx.channel().isActive()) return;
    }

    private void sendFramedToJava(ByteBuf payload) {
        ByteBuf framed = Unpooled.buffer();
        MultiVersionRouter.writeVarInt(framed, payload.readableBytes());
        framed.writeBytes(payload);
        payload.release();
        javaBackendChannel.writeAndFlush(framed);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (javaBackendChannel != null) {
            javaBackendChannel.close();
        }
    }
}
