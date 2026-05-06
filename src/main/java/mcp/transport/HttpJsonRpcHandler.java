package mcp.transport;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpDispatcher;
import mcp.protocol.McpError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

public class HttpJsonRpcHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonRpcHandler.class);
    private static final String ENDPOINT = "/mcp";

    private final AuthFilter auth;
    private final McpDispatcher dispatcher;
    private final boolean requestLog;

    public HttpJsonRpcHandler(AuthFilter auth, McpDispatcher dispatcher, boolean requestLog) {
        this.auth = auth;
        this.dispatcher = dispatcher;
        this.requestLog = requestLog;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        Instant t0 = Instant.now();
        if (!ENDPOINT.equals(req.uri())) {
            send(ctx, HttpResponseStatus.NOT_FOUND, "{}");
            return;
        }
        if (req.method() != HttpMethod.POST) {
            send(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{}");
            return;
        }
        String authHeader = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth.check(authHeader) != AuthFilter.Result.OK) {
            send(ctx, HttpResponseStatus.UNAUTHORIZED, "{}");
            return;
        }
        String body = req.content().toString(StandardCharsets.UTF_8);

        JsonRpc.Response resp;
        String methodName = "?";
        try {
            JsonRpc.Request rpc = JsonRpc.parseRequest(body);
            methodName = rpc.method();
            resp = dispatcher.dispatch(rpc);
        } catch (JsonRpc.ParseException e) {
            resp = JsonRpc.error(JsonRpc.MAPPER.nullNode(), McpError.parseError(e.getMessage()));
        }

        String json;
        try {
            json = JsonRpc.MAPPER.writeValueAsString(resp);
        } catch (Exception e) {
            log.warn("failed to serialize response", e);
            json = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"serialization error\"}}";
        }
        send(ctx, HttpResponseStatus.OK, json);

        if (requestLog) {
            long ms = Duration.between(t0, Instant.now()).toMillis();
            String caller = ctx.channel().remoteAddress() == null ? "?" : ctx.channel().remoteAddress().toString();
            boolean ok = resp.error() == null;
            log.info("mcp method={} caller={} dur_ms={} ok={}", methodName, caller, ms, ok);
        }
    }

    private static void send(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("mcp handler exception", cause);
        ctx.close();
    }
}
