package net.server.chat.telegram;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process fake of the Telegram Bot API for tests. Listens on a random local
 * port; pengrad's TelegramBot.Builder().apiUrl("http://127.0.0.1:PORT") routes
 * traffic to it.
 *
 * Supports just /bot{token}/getUpdates and /bot{token}/sendMessage. Tests inject
 * canned getUpdates JSON via injectUpdate(...) and read recorded sendMessage
 * bodies via takeSentMessage(...).
 */
public final class FakeTelegramApi implements AutoCloseable {

    private final HttpServer server;
    private final LinkedBlockingQueue<String> sentMessages = new LinkedBlockingQueue<>();
    private final List<String> queuedUpdates = new ArrayList<>();   // JSON snippets

    public FakeTelegramApi() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::route);
        this.server.start();
    }

    public String urlBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized void injectUpdate(String updateJson) {
        queuedUpdates.add(updateJson);
    }

    public String takeSentMessage(long timeoutMs) throws InterruptedException {
        return sentMessages.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override public void close() { server.stop(0); }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/getUpdates")) {
            handleGetUpdates(ex);
        } else if (path.endsWith("/sendMessage")) {
            handleSendMessage(ex);
        } else {
            respond(ex, 404, "{\"ok\":false,\"description\":\"unknown\"}");
        }
    }

    private synchronized void handleGetUpdates(HttpExchange ex) throws IOException {
        StringBuilder body = new StringBuilder("{\"ok\":true,\"result\":[");
        for (int i = 0; i < queuedUpdates.size(); i++) {
            if (i > 0) body.append(',');
            body.append(queuedUpdates.get(i));
        }
        body.append("]}");
        queuedUpdates.clear();
        respond(ex, 200, body.toString());
    }

    private void handleSendMessage(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        sentMessages.offer(body);
        respond(ex, 200,
                "{\"ok\":true,\"result\":{\"message_id\":1,\"date\":0," +
                "\"chat\":{\"id\":-1001,\"type\":\"supergroup\",\"title\":\"t\"}," +
                "\"text\":\"\"}}");
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
