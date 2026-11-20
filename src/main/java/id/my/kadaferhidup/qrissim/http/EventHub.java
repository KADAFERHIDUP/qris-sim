package id.my.kadaferhidup.qrissim.http;

import com.sun.net.httpserver.HttpExchange;
import id.my.kadaferhidup.qrissim.json.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Server-Sent Events: the cashier screen and the payer's phone hold one stream open and
 * see a bill turn paid, expire or get a webhook reply the moment it happens.
 */
public final class EventHub {

    private static final int MAX_CLIENTS = 200;

    private record Client(OutputStream out, CountDownLatch closed) {
    }

    private final List<Client> clients = new CopyOnWriteArrayList<>();

    /** Holds the request open until the client leaves. */
    void serve(HttpExchange ex) throws IOException {
        if (clients.size() >= MAX_CLIENTS) {
            throw new ApiException(503, "TOO_MANY_STREAMS", "Terlalu banyak koneksi live");
        }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        Client c = new Client(ex.getResponseBody(), new CountDownLatch(1));
        clients.add(c);
        write(c, ": terhubung\n\n");
        try {
            c.closed().await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(c);
            ex.close();
        }
    }

    public void publish(String event, Object data) {
        String frame = "event: " + event + "\ndata: " + Json.write(data) + "\n\n";
        for (Client c : clients) {
            write(c, frame);
        }
    }

    /** Comment line that keeps proxies from closing an idle stream. */
    public void ping() {
        for (Client c : clients) {
            write(c, ": ping\n\n");
        }
    }

    public int size() {
        return clients.size();
    }

    public void closeAll() {
        for (Client c : clients) {
            c.closed().countDown();
        }
    }

    private void write(Client c, String frame) {
        try {
            synchronized (c) {
                c.out().write(frame.getBytes(StandardCharsets.UTF_8));
                c.out().flush();
            }
        } catch (IOException e) {
            clients.remove(c);
            c.closed().countDown();
        }
    }
}
