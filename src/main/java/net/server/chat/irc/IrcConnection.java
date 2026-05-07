package net.server.chat.irc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class IrcConnection implements IrcSender {

    private static final Logger log = LoggerFactory.getLogger(IrcConnection.class);

    private final String host;
    private final int port;
    private final boolean tls;
    private final String nickInitial;
    private final String user;
    private final String realname;
    private final String password;
    private final List<String> channels;
    private final int queueMax;
    private final List<Integer> backoffSeconds;
    private final Consumer<IrcMessage> onMessage;

    private final LinkedBlockingQueue<String> outbox;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> currentNick = new AtomicReference<>("");
    private volatile Thread readThread;
    private volatile Thread writeThread;
    private volatile Socket socket;
    private volatile PrintWriter writer;
    private volatile boolean registered = false;
    private final java.util.concurrent.atomic.AtomicLong lastDropWarnAtMs = new java.util.concurrent.atomic.AtomicLong(0);

    private IrcConnection(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.tls = b.tls;
        this.nickInitial = b.nick;
        this.currentNick.set(b.nick);
        this.user = b.user;
        this.realname = b.realname;
        this.password = b.password == null ? "" : b.password;
        this.channels = List.copyOf(b.channels);
        this.queueMax = b.queueMax;
        this.backoffSeconds = b.backoffSeconds.isEmpty() ? List.of(5, 10, 30, 60, 60) : List.copyOf(b.backoffSeconds);
        this.onMessage = b.onMessage == null ? m -> {} : b.onMessage;
        this.outbox = new LinkedBlockingQueue<>(queueMax);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        readThread = new Thread(this::readLoop, "irc-read");
        readThread.setDaemon(true);
        writeThread = new Thread(this::writeLoop, "irc-write");
        writeThread.setDaemon(true);
        readThread.start();
        writeThread.start();
    }

    public void stop(long timeoutMs) {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (writer != null) {
                writer.print("QUIT :Cosmic shutting down\r\n");
                writer.flush();
            }
        } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        try {
            if (readThread != null) readThread.join(timeoutMs / 2);
            if (writeThread != null) writeThread.join(timeoutMs / 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public boolean enqueue(String rawIrcLine) {
        if (!running.get()) return false;
        boolean queued = outbox.offer(rawIrcLine);
        if (!queued) {
            long now = System.currentTimeMillis();
            long last = lastDropWarnAtMs.get();
            if (now - last >= 1000 && lastDropWarnAtMs.compareAndSet(last, now)) {
                log.warn("IRC outbound queue full; dropping message (queue size {})", outbox.size());
            }
        }
        return queued;
    }

    @Override public String currentNick() { return currentNick.get(); }

    private void readLoop() {
        int attempt = 0;
        while (running.get()) {
            try {
                connectAndRegister();
                attempt = 0;
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    handleLine(line);
                }
            } catch (Exception e) {
                if (running.get()) log.warn("IRC read loop error: {}", e.toString());
            } finally {
                registered = false;
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            }
            if (!running.get()) break;
            int wait = backoffSeconds.get(Math.min(attempt, backoffSeconds.size() - 1));
            attempt++;
            try { Thread.sleep(wait * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void writeLoop() {
        while (running.get()) {
            try {
                if (!registered) {
                    Thread.sleep(50);
                    continue;
                }
                String line = outbox.poll(250, TimeUnit.MILLISECONDS);
                if (line == null) continue;
                PrintWriter w = writer;
                if (w == null) continue;
                w.print(line + "\r\n");
                w.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) log.warn("IRC write loop error: {}", e.toString());
            }
        }
    }

    private void connectAndRegister() throws IOException {
        Socket s = tls
                ? SSLSocketFactory.getDefault().createSocket(host, port)
                : new Socket(host, port);
        this.socket = s;
        this.writer = new PrintWriter(s.getOutputStream(), false, StandardCharsets.UTF_8);
        if (!password.isEmpty()) write("PASS " + password);
        write("NICK " + nickInitial);
        write("USER " + user + " 0 * :" + realname);
        currentNick.set(nickInitial);
    }

    private void handleLine(String raw) {
        IrcLineParser.parse(raw).ifPresent(m -> {
            switch (m.command()) {
                case "PING" -> write("PONG :" + m.trailing());
                case "001" -> {
                    registered = true;
                    for (String ch : channels) write("JOIN " + ch);
                }
                case "433" -> {
                    String fallback = currentNick.get() + "_";
                    currentNick.set(fallback);
                    write("NICK " + fallback);
                }
                default -> {}
            }
            try { onMessage.accept(m); }
            catch (Exception e) { log.debug("listener threw on {}: {}", m.command(), e.toString()); }
        });
    }

    private void write(String line) {
        PrintWriter w = writer;
        if (w == null) return;
        w.print(line + "\r\n");
        w.flush();
    }

    public static final class Builder {
        private String host = "";
        private int port = 6697;
        private boolean tls = true;
        private String nick = "";
        private String user = "";
        private String realname = "";
        private String password = "";
        private List<String> channels = List.of();
        private int queueMax = 1000;
        private List<Integer> backoffSeconds = List.of();
        private Consumer<IrcMessage> onMessage = m -> {};

        public Builder host(String v) { this.host = v; return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder tls(boolean v) { this.tls = v; return this; }
        public Builder nick(String v) { this.nick = v; return this; }
        public Builder user(String v) { this.user = v; return this; }
        public Builder realname(String v) { this.realname = v; return this; }
        public Builder password(String v) { this.password = v; return this; }
        public Builder channels(List<String> v) { this.channels = v; return this; }
        public Builder queueMax(int v) { this.queueMax = v; return this; }
        public Builder backoffSeconds(List<Integer> v) { this.backoffSeconds = v; return this; }
        public Builder onMessage(Consumer<IrcMessage> v) { this.onMessage = v; return this; }
        public IrcConnection build() { return new IrcConnection(this); }
    }
}
