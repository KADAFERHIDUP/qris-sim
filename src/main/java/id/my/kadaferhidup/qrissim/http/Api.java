package id.my.kadaferhidup.qrissim.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import id.my.kadaferhidup.qrissim.core.Invoice;
import id.my.kadaferhidup.qrissim.core.Payment;
import id.my.kadaferhidup.qrissim.core.PaymentException;
import id.my.kadaferhidup.qrissim.core.PaymentService;
import id.my.kadaferhidup.qrissim.core.Wallet;
import id.my.kadaferhidup.qrissim.json.Json;
import id.my.kadaferhidup.qrissim.qris.Merchant;
import id.my.kadaferhidup.qrissim.qris.QrisException;
import id.my.kadaferhidup.qrissim.qris.QrisPayload;
import id.my.kadaferhidup.qrissim.webhook.WebhookDispatcher;
import id.my.kadaferhidup.qrissim.webhook.WebhookEndpoint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Every HTTP route. Bodies and answers are JSON; errors are {@code {"error":{"code","message"}}}. */
public final class Api implements HttpHandler {

    private static final int MAX_BODY = 16 * 1024;
    private static final Pattern INVOICE = Pattern.compile("^/api/invoices/([A-Z0-9]{1,32})$");
    private static final Pattern CANCEL = Pattern.compile("^/api/invoices/([A-Z0-9]{1,32})/cancel$");
    private static final Pattern STATIC_QR = Pattern.compile("^/api/merchants/([A-Z0-9]{1,32})/static-qr$");
    private static final Pattern WALLET = Pattern.compile("^/api/wallets/([A-Z0-9]{1,32})$");

    private final PaymentService svc;
    private final WebhookDispatcher webhooks;
    private final Map<String, WebhookEndpoint> endpoints;
    private final DemoMerchant demo;
    private final EventHub hub;
    private final StaticFiles files;

    public Api(PaymentService svc, WebhookDispatcher webhooks, Map<String, WebhookEndpoint> endpoints, DemoMerchant demo,
               EventHub hub, StaticFiles files) {
        this.svc = svc;
        this.webhooks = webhooks;
        this.endpoints = endpoints;
        this.demo = demo;
        this.hub = hub;
        this.files = files;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            securityHeaders(ex);
            try {
                route(ex);
            } catch (PaymentException e) {
                error(ex, e.code().httpStatus(), e.code().name(), e.getMessage());
            } catch (ApiException e) {
                error(ex, e.status(), e.code(), e.getMessage());
            } catch (QrisException e) {
                error(ex, 400, "INVALID_QR", e.getMessage());
            } catch (Json.JsonException e) {
                error(ex, 400, "INVALID_JSON", e.getMessage());
            } catch (IOException e) {
                throw e;
            } catch (RuntimeException e) {
                error(ex, 500, "INTERNAL", "Terjadi kesalahan di server");
            }
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        String p = ex.getRequestURI().getPath();
        Matcher mt;

        if (!p.startsWith("/api/") && !p.equals("/demo-merchant") && !p.startsWith("/demo-merchant/")) {
            requireMethod(m, "GET", "HEAD");
            files.serve(ex, p);
            return;
        }

        if (p.equals("/api/health")) {
            requireMethod(m, "GET");
            json(ex, 200, Map.of("status", "ok"));
        } else if (p.equals("/api/events")) {
            requireMethod(m, "GET");
            hub.serve(ex);
        } else if (p.equals("/api/merchants")) {
            requireMethod(m, "GET");
            json(ex, 200, svc.merchants().stream().map(this::merchantView).toList());
        } else if ((mt = STATIC_QR.matcher(p)).matches()) {
            requireMethod(m, "GET");
            json(ex, 200, Map.of("payload", svc.staticQr(mt.group(1))));
        } else if (p.equals("/api/invoices")) {
            if (m.equals("POST")) {
                Map<String, Object> b = body(ex);
                long ttl = optLong(b, "ttl_seconds", PaymentService.DEFAULT_TTL.toSeconds());
                Invoice.View inv = svc.createInvoice(str(b, "merchant_id"), reqLong(b, "amount"), optStr(b, "note"),
                        Duration.ofSeconds(ttl));
                json(ex, 201, inv);
            } else {
                requireMethod(m, "GET");
                json(ex, 200, svc.recentInvoices(20));
            }
        } else if ((mt = INVOICE.matcher(p)).matches()) {
            requireMethod(m, "GET");
            json(ex, 200, svc.getInvoice(mt.group(1)));
        } else if ((mt = CANCEL.matcher(p)).matches()) {
            requireMethod(m, "POST");
            json(ex, 200, svc.cancel(mt.group(1)));
        } else if (p.equals("/api/qris/decode")) {
            requireMethod(m, "POST");
            json(ex, 200, decode(str(body(ex), "payload")));
        } else if (p.equals("/api/payments")) {
            requireMethod(m, "POST");
            Map<String, Object> b = body(ex);
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            Long amount = b.get("amount") == null ? null : reqLong(b, "amount");
            Payment pay = svc.pay(new PaymentService.PayRequest(str(b, "payload"), amount, str(b, "wallet_id"),
                    str(b, "pin"), key));
            json(ex, 201, pay);
        } else if ((mt = WALLET.matcher(p)).matches()) {
            requireMethod(m, "GET");
            Wallet w = svc.wallet(mt.group(1));
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", w.id());
            v.put("owner", w.owner());
            v.put("balance", w.balance());
            v.put("locked", w.lockedAt(svc.now()));
            json(ex, 200, v);
        } else if (p.equals("/api/webhooks/deliveries")) {
            requireMethod(m, "GET");
            json(ex, 200, webhooks.recent());
        } else if (p.equals("/demo-merchant/webhook")) {
            requireMethod(m, "POST");
            Map<String, String> h = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, vs) -> h.put(k.toLowerCase(Locale.ROOT), vs.get(0)));
            int status = demo.receive(h, rawBody(ex));
            json(ex, status, Map.of("ok", status == 200));
        } else if (p.equals("/demo-merchant")) {
            if (m.equals("POST")) {
                demo.failNext((int) reqLong(body(ex), "fail_next"));
            } else {
                requireMethod(m, "GET");
            }
            json(ex, 200, demo.state());
        } else {
            throw new ApiException(404, "NOT_FOUND", "Rute tidak ada");
        }
    }

    private Map<String, Object> decode(String payload) {
        QrisPayload qr = QrisPayload.parse(payload == null ? "" : payload);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("dynamic", qr.dynamic());
        v.put("amount", qr.amount());
        v.put("merchant_name", qr.merchantName());
        v.put("city", qr.city());
        v.put("merchant_id", qr.merchantId());
        v.put("nmid", qr.nmid());
        v.put("bill_number", qr.billNumber());
        v.put("crc", qr.crc());
        v.put("fields", qr.fields());
        v.put("templates", qr.templates());
        String reason = null;
        try {
            svc.inspect(payload);
        } catch (PaymentException e) {
            reason = e.getMessage();
        }
        v.put("payable", reason == null);
        v.put("reason", reason);
        return v;
    }

    private Map<String, Object> merchantView(Merchant m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.id());
        v.put("name", m.name());
        v.put("city", m.city());
        v.put("nmid", m.nmid());
        v.put("balance", svc.merchantBalance(m.id()));
        WebhookEndpoint ep = endpoints.get(m.id());
        v.put("webhook_url", ep == null ? null : ep.url());
        return v;
    }

    // ------------------------------------------------------------------ helpers

    private static void requireMethod(String actual, String... allowed) {
        for (String a : allowed) {
            if (a.equals(actual)) {
                return;
            }
        }
        throw new ApiException(405, "METHOD_NOT_ALLOWED", "Metode " + actual + " tidak didukung di sini");
    }

    private static String rawBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) {
                throw new ApiException(413, "BODY_TOO_LARGE", "Body maksimal 16 KB");
            }
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type harus application/json");
        }
        return Json.parseObject(rawBody(ex));
    }

    private static String str(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new ApiException(400, "INVALID_REQUEST", "Kolom " + k + " wajib berupa teks");
        }
        return s;
    }

    private static String optStr(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s) || s.length() > 60) {
            throw new ApiException(400, "INVALID_REQUEST", "Kolom " + k + " harus teks maksimal 60 karakter");
        }
        return s;
    }

    private static long reqLong(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (!(v instanceof Long l)) {
            throw new ApiException(400, "INVALID_REQUEST", "Kolom " + k + " wajib berupa bilangan bulat");
        }
        return l;
    }

    private static long optLong(Map<String, Object> b, String k, long def) {
        return b.get(k) == null ? def : reqLong(b, k);
    }

    static void json(HttpExchange ex, int status, Object value) throws IOException {
        byte[] out = Json.write(value).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : out.length);
        if (!head) {
            ex.getResponseBody().write(out);
        }
    }

    private static void error(HttpExchange ex, int status, String code, String message) throws IOException {
        if (ex.getResponseCode() != -1) {
            return;
        }
        json(ex, status, Map.of("error", Map.of("code", code, "message", message == null ? "" : message)));
    }

    private static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' https://fonts.googleapis.com; "
                + "font-src https://fonts.gstatic.com; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    }

    /** Kept for the list of routes in the README. */
    static List<String> routes() {
        return List.of("GET /api/health", "GET /api/events", "GET /api/merchants", "GET /api/merchants/{id}/static-qr",
                "POST /api/invoices", "GET /api/invoices", "GET /api/invoices/{id}", "POST /api/invoices/{id}/cancel",
                "POST /api/qris/decode", "POST /api/payments", "GET /api/wallets/{id}", "GET /api/webhooks/deliveries",
                "POST /demo-merchant/webhook", "GET|POST /demo-merchant");
    }
}
