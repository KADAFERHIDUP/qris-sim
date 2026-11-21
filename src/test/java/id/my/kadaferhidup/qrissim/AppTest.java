package id.my.kadaferhidup.qrissim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import id.my.kadaferhidup.qrissim.json.Json;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Starts the real server on a free port and talks to it over HTTP. */
class AppTest {

    App app;
    HttpClient http;
    String base;

    @BeforeEach
    void start() throws Exception {
        app = App.start(new App.Config(0, "123456", "whsec_test_secret_123456"));
        base = "http://127.0.0.1:" + app.port();
        http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void stop() {
        app.close();
    }

    record Res(int status, Object body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return (Map<String, Object>) body;
        }
    }

    Res get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), Json.parse(r.body()));
    }

    Res post(String path, Object body, String idempotencyKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), Json.parse(r.body()));
    }

    @SuppressWarnings("unchecked")
    static String errorCode(Res r) {
        return (String) ((Map<String, Object>) r.map().get("error")).get("code");
    }

    @Test
    void healthAnswers() throws Exception {
        assertEquals(Map.of("status", "ok"), get("/api/health").body());
    }

    @Test
    void fullPaymentFlowOverHttp() throws Exception {
        Res created = post("/api/invoices", Map.of("merchant_id", "M001", "amount", 32_000, "note", "Mie Aceh"), null);
        assertEquals(201, created.status());
        String id = (String) created.map().get("id");
        String payload = (String) created.map().get("payload");

        Res decoded = post("/api/qris/decode", Map.of("payload", payload), null);
        assertEquals(200, decoded.status());
        assertEquals("Warung Ahmad", decoded.map().get("merchant_name"));
        assertEquals(32_000L, decoded.map().get("amount"));
        assertEquals(Boolean.TRUE, decoded.map().get("payable"));

        Map<String, Object> payBody = Map.of("payload", payload, "wallet_id", "W001", "pin", "123456");
        Res paid = post("/api/payments", payBody, "key-1");
        assertEquals(201, paid.status());
        Res replay = post("/api/payments", payBody, "key-1");
        assertEquals(paid.map().get("id"), replay.map().get("id"));

        assertEquals("PAID", get("/api/invoices/" + id).map().get("status"));
        assertEquals(468_000L, get("/api/wallets/W001").map().get("balance"));

        Res again = post("/api/payments", payBody, "key-2");
        assertEquals(409, again.status());
        assertEquals("ALREADY_PAID", errorCode(again));
    }

    @Test
    void webhookReachesTheDemoMerchantSigned() throws Exception {
        Res created = post("/api/invoices", Map.of("merchant_id", "M001", "amount", 5_000), null);
        post("/api/payments", Map.of("payload", created.map().get("payload"), "wallet_id", "W001", "pin", "123456"), "k");

        List<?> received = List.of();
        for (int i = 0; i < 50 && received.size() < 2; i++) {
            TimeUnit.MILLISECONDS.sleep(100);
            received = (List<?>) get("/demo-merchant").map().get("received");
        }
        assertEquals(2, received.size());
        for (Object o : received) {
            assertEquals("VALID", ((Map<?, ?>) o).get("verdict"));
        }
        List<?> deliveries = (List<?>) get("/api/webhooks/deliveries").body();
        assertTrue(deliveries.stream().allMatch(d -> Boolean.TRUE.equals(((Map<?, ?>) d).get("delivered"))));
    }

    @Test
    void failingMerchantGetsARetry() throws Exception {
        post("/demo-merchant", Map.of("fail_next", 1), null);
        post("/api/invoices", Map.of("merchant_id", "M001", "amount", 5_000), null);
        List<?> deliveries = List.of();
        for (int i = 0; i < 60 && deliveries.size() < 2; i++) {
            TimeUnit.MILLISECONDS.sleep(100);
            deliveries = (List<?>) get("/api/webhooks/deliveries").body();
        }
        assertEquals(2, deliveries.size(), "satu gagal, satu ulangan berhasil");
        Map<?, ?> latest = (Map<?, ?>) deliveries.get(0);
        Map<?, ?> first = (Map<?, ?>) deliveries.get(1);
        assertEquals(Boolean.TRUE, latest.get("delivered"));
        assertEquals(2L, latest.get("attempt"));
        assertEquals(503L, first.get("status_code"));
    }

    @Test
    void wrongPinIsRejectedWithItsCode() throws Exception {
        Res created = post("/api/invoices", Map.of("merchant_id", "M001", "amount", 5_000), null);
        Res r = post("/api/payments", Map.of("payload", created.map().get("payload"), "wallet_id", "W001", "pin", "999999"), "k");
        assertEquals(401, r.status());
        assertEquals("WRONG_PIN", errorCode(r));
    }

    @Test
    void badRequestsGetClearErrors() throws Exception {
        assertEquals("INVALID_QR", errorCode(post("/api/qris/decode", Map.of("payload", "000201"), null)));
        assertEquals("INVALID_REQUEST", errorCode(post("/api/invoices", Map.of("merchant_id", "M001", "amount", "lima ribu"), null)));
        assertEquals("NOT_FOUND", errorCode(get("/api/invoices/INVXXXX")));
        assertEquals(404, get("/api/tidak-ada").status());

        HttpResponse<String> notJson = http.send(HttpRequest.newBuilder(URI.create(base + "/api/invoices"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{rusak")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, notJson.statusCode());

        HttpResponse<String> noType = http.send(HttpRequest.newBuilder(URI.create(base + "/api/invoices"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(415, noType.statusCode());
    }

    @Test
    void pathTraversalIsNotServed() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/..%2Fpom.xml")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, r.statusCode());
        assertFalse(r.body().contains("<project"));
    }

    @Test
    void liveStreamAnnouncesPaidBills() throws Exception {
        HttpResponse<InputStream> stream = http.send(HttpRequest.newBuilder(URI.create(base + "/api/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals("text/event-stream; charset=utf-8", stream.headers().firstValue("Content-Type").orElse(""));
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
        CompletableFuture<String> seen = CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:") && line.contains("invoice.paid")) {
                        return line;
                    }
                }
            } catch (java.io.IOException e) {
                return "error: " + e;
            }
            return "closed";
        });
        Res created = post("/api/invoices", Map.of("merchant_id", "M001", "amount", 7_000), null);
        post("/api/payments", Map.of("payload", created.map().get("payload"), "wallet_id", "W001", "pin", "123456"), "k");
        String line = seen.get(5, TimeUnit.SECONDS);
        assertTrue(line.contains("\"status\":\"PAID\""), line);
        stream.body().close();
    }

    @Test
    void everyVisitorGetsTheirOwnWallet() throws Exception {
        Res a = post("/api/wallets", Map.of(), null);
        Res b = post("/api/wallets", Map.of(), null);
        assertEquals(201, a.status());
        assertEquals("Ahmad Umraithi", a.map().get("owner"));
        assertEquals(500_000L, a.map().get("balance"));
        assertFalse(a.map().get("id").equals(b.map().get("id")));

        String id = (String) a.map().get("id");
        Res created = post("/api/invoices", Map.of("merchant_id", "M001", "amount", 100_000), null);
        post("/api/payments", Map.of("payload", created.map().get("payload"), "wallet_id", id, "pin", "123456"), "k");
        assertEquals(400_000L, get("/api/wallets/" + id).map().get("balance"));
        assertEquals(500_000L, get("/api/wallets/" + b.map().get("id")).map().get("balance"));

        Res reset = post("/api/wallets/" + id + "/reset", Map.of(), null);
        assertEquals(500_000L, reset.map().get("balance"));
    }
}
