package net.server.chat.irc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class FakeIrcServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptThread;
    private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    private volatile Socket client;
    private volatile PrintWriter out;
    private volatile boolean stopped = false;

    public FakeIrcServer() throws IOException {
        this.server = new ServerSocket(0);
        this.acceptThread = new Thread(this::acceptLoop, "FakeIrcServer-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int port() { return server.getLocalPort(); }

    public String takeLine(long timeoutMs) throws InterruptedException {
        return received.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public List<String> drain() {
        List<String> all = new ArrayList<>();
        received.drainTo(all);
        return all;
    }

    public void send(String line) {
        PrintWriter w = out;
        if (w != null) {
            w.print(line + "\r\n");
            w.flush();
        }
    }

    public void waitForClient(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (client == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    public void disconnectClient() throws IOException {
        Socket c = client;
        if (c != null) c.close();
        client = null;
    }

    @Override public void close() throws IOException {
        stopped = true;
        try { server.close(); } catch (IOException ignored) {}
        if (client != null) try { client.close(); } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket s = server.accept();
                client = s;
                out = new PrintWriter(s.getOutputStream(), false, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    received.offer(line);
                }
            } catch (IOException e) {
                if (!stopped) {
                    // socket closed by test or by client; loop will exit if stopped
                }
            }
        }
    }
}
