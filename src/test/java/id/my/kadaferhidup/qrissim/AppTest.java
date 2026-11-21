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
        app = App.start(new App.Config(0, "123456", "whsec_test_secret_123456", 60));
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

    Res get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (headers.length > 0) {
            b.headers(headers);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), Json.parse(r.body()));
    }

    Res post(String path, Object body, String idempotencyKey, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        if (headers.length > 0) {
            b.headers(headers);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), Json.parse(r.body()));
    }

    /** A fresh demo wallet: its id and the token that must accompany every use. */
    record W(String id, String token) {
        String[] auth() {
            return new String[] {"X-Wallet-Token", token};
        }
    }

    W wallet() throws Exception {
        Res r = post("/api/wallets", Map.of(), null);
        return new W((String) r.map().get("id"), (String) r.map().get("token"));
    }

    Res pay(Object payload, W w, String pin, String key) throws Exception {
        return post("/api/payments", Map.of("payload", payload, "wallet_id", w.id(), "pin", pin), key, w.auth());
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

        W w = wallet();
        Res paid = pay(payload, w, "123456", "key-1");
        assertEquals(201, paid.status());
        Res replay = pay(payload, w, "123456", "key-1");
        assertEquals(paid.map().get("id"), replay.map().get("id"));

        Res invoice = get("/api/invoices/" + id);
        assertEquals("PAID", invoice.map().get("status"));
        assertFalse(invoice.map().containsKey("payer_name"), "nama pembayar tidak dibuka ke siapa pun yang tahu id tagihan");
        assertEquals(468_000L, get("/api/wallets/" + w.id(), w.auth()).map().get("balance"));

        Res again = pay(payload, w, "123456", "key-2");
        assertEquals(409, again.status());
        assertEquals("ALREADY_PAID", errorCode(again));
    }

    @Test
    void webhookReachesTheDemoMerchantSigned() throws Exception {
        Res created = post("/api/invoices", Map.of("merchant_id", "M001", "amount", 5_000), null);
        pay(created.map().get("payload"), wallet(), "123456", "k");

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
        Res r = pay(created.map().get("payload"), wallet(), "999999", "k");
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
        W w = wallet();
        pay(created.map().get("payload"), w, "123456", "k");
        String line = seen.get(5, TimeUnit.SECONDS);
        assertTrue(line.contains("\"status\":\"PAID\""), line);
        // the stream reaches every visitor, so it must not name the payer's wallet or the QR
        assertFalse(line.contains(w.id()), line);
        assertFalse(line.contains("wallet"), line);
        assertFalse(line.contains("payload"), line);
        stream.body().close();
    }

    @Test
    void everyVisitorGetsTheirOwnWallet() throws Exception {
        Res a = post("/api/wallets", Map.of(), null);
        assertEquals(201, a.status());
        assertEquals("Ahmad Umraithi", a.map().get("owner"));
        assertEquals(500_000L, a.map().get("balance"));
        W wa = new W((String) a.map().get("id"), (String) a.map().get("token"));
        W wb = wallet();
        assertFalse(wa.id().equals(wb.id()));

        Res created = post("/api/invoices", Map.of("merchant_id", "M001", "amount", 100_000), null);
        pay(created.map().get("payload"), wa, "123456", "k");
        assertEquals(400_000L, get("/api/wallets/" + wa.id(), wa.auth()).map().get("balance"));
        assertEquals(500_000L, get("/api/wallets/" + wb.id(), wb.auth()).map().get("balance"));

        Res reset = post("/api/wallets/" + wa.id() + "/reset", Map.of(), null, wa.auth());
        assertEquals(500_000L, reset.map().get("balance"));
    }

    @Test
    void walletIdAloneOpensNothing() throws Exception {
        W victim = wallet();
        W attacker = wallet();
        Res created = post("/api/invoices", Map.of("merchant_id", "M001", "amount", 50_000), null);

        assertEquals(404, get("/api/wallets/" + victim.id()).status());
        assertEquals(404, get("/api/wallets/" + victim.id(), attacker.auth()).status());
        assertEquals(404, post("/api/wallets/" + victim.id() + "/reset", Map.of(), null).status());
        Res stolen = post("/api/payments", Map.of("payload", created.map().get("payload"), "wallet_id", victim.id(),
                "pin", "123456"), "curi-1", attacker.auth());
        assertEquals(404, stolen.status());
        assertEquals(500_000L, get("/api/wallets/" + victim.id(), victim.auth()).map().get("balance"));
    }

    @Test
    void onlyTheCashierWhoCreatedABillCanCancelIt() throws Exception {
        Res created = post("/api/invoices", Map.of("merchant_id", "M001", "amount", 5_000), null);
        String id = (String) created.map().get("id");
        String token = (String) created.map().get("cancel_token");
        assertTrue(token != null && token.length() >= 40);

        assertEquals(403, post("/api/invoices/" + id + "/cancel", Map.of(), null).status());
        assertEquals(403, post("/api/invoices/" + id + "/cancel", Map.of(), null, "X-Cancel-Token", "tebakan").status());
        Res ok = post("/api/invoices/" + id + "/cancel", Map.of(), null, "X-Cancel-Token", token);
        assertEquals(200, ok.status());
        assertEquals("CANCELLED", ok.map().get("status"));
        assertFalse(get("/api/invoices/" + id).map().containsKey("cancel_token"));
    }

    @Test
    void thereIsNoPublicListOfBills() throws Exception {
        post("/api/invoices", Map.of("merchant_id", "M001", "amount", 5_000), null);
        assertEquals(405, get("/api/invoices").status());
    }

    @Test
    void forgedForwardedForDoesNotDodgeTheLimit() throws Exception {
        int limited = 0;
        for (int i = 0; i < 45; i++) {
            // a client can write anything before the proxy's own entry; only the last one counts
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/qris/decode"))
                    .header("Content-Type", "application/json").header("X-Forwarded-For", "10.0.0." + i + ", 203.0.113.50")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"payload\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429) {
                limited++;
            }
        }
        assertTrue(limited > 0, "alamat palsu di depan tidak boleh membuat kuota baru");
    }

    @Test
    void floodOfWritesIsSlowedDown() throws Exception {
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 45; i++) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/qris/decode"))
                    .header("Content-Type", "application/json").header("X-Forwarded-For", "203.0.113.7")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"payload\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429) {
                limited++;
                retryAfter = r.headers().firstValue("Retry-After").orElse(null);
            }
        }
        assertTrue(limited > 0, "sebagian permintaan harus ditahan");
        assertTrue(retryAfter != null && Long.parseLong(retryAfter) >= 1);
        // another visitor is not affected
        HttpResponse<String> other = http.send(HttpRequest.newBuilder(URI.create(base + "/api/qris/decode"))
                .header("Content-Type", "application/json").header("X-Forwarded-For", "198.51.100.9")
                .POST(HttpRequest.BodyPublishers.ofString("{\"payload\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, other.statusCode());
    }
}
