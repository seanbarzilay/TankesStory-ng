package mcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import mcp.config.McpConfig;
import mcp.protocol.McpDispatcher;
import mcp.protocol.ToolRegistry;
import mcp.transport.AuthFilter;
import mcp.transport.HttpJsonRpcHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final McpConfig config;
    private final ToolRegistry registry;
    private final McpDispatcher dispatcher;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel channel;

    public McpServer(McpConfig config, ToolRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.dispatcher = new McpDispatcher(registry);
    }

    public void start() throws Exception {
        if (!config.enabled()) {
            log.info("mcp disabled, skipping start");
            return;
        }
        AuthFilter auth = new AuthFilter(config.authToken());
        SslContext sslCtx = config.tlsEnabled()
                ? SslContextBuilder.forServer(new File(config.tlsCert()), new File(config.tlsKey())).build()
                : null;
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (sslCtx != null) {
                            ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()));
                        }
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_BODY_BYTES));
                        ch.pipeline().addLast(new HttpJsonRpcHandler(auth, dispatcher, config.requestLog()));
                    }
                });
        channel = b.bind(config.bindAddr(), config.port()).sync().channel();
        log.info("mcp listening on {}:{} (tls={})", config.bindAddr(), config.port(), config.tlsEnabled());
    }

    public void stop() {
        if (channel == null) return;
        log.info("mcp stopping");
        dispatcher.shutdown();
        try {
            channel.close().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker.shutdownGracefully();
        boss.shutdownGracefully();
    }
}
