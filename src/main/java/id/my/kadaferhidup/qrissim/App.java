package id.my.kadaferhidup.qrissim;

import com.sun.net.httpserver.HttpServer;
import id.my.kadaferhidup.qrissim.core.PaymentEvent;
import id.my.kadaferhidup.qrissim.core.PaymentService;
import id.my.kadaferhidup.qrissim.http.Api;
import id.my.kadaferhidup.qrissim.http.DemoMerchant;
import id.my.kadaferhidup.qrissim.http.EventHub;
import id.my.kadaferhidup.qrissim.http.RateLimiter;
import id.my.kadaferhidup.qrissim.http.StaticFiles;
import id.my.kadaferhidup.qrissim.json.Json;
import id.my.kadaferhidup.qrissim.qris.Merchant;
import id.my.kadaferhidup.qrissim.webhook.WebhookDispatcher;
import id.my.kadaferhidup.qrissim.webhook.WebhookEndpoint;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Wires the simulator together and starts the HTTP server. */
public final class App implements AutoCloseable {

    static final String OWNER = "Ahmad Umraithi";
    static final long DEMO_BALANCE = 500_000;

    /** Settings read from environment variables, with demo defaults. */
    public record Config(int port, String demoPin, String webhookSecret, int writesPerMinute) {

        public static Config fromEnv() {
            String secret = System.getenv("WEBHOOK_SECRET");
            if (secret == null || secret.length() < 16) {
                byte[] b = new byte[24];
                new SecureRandom().nextBytes(b);
                secret = "whsec_" + HexFormat.of().formatHex(b);
            }
            String pin = System.getenv().getOrDefault("DEMO_PIN", "123456");
            int perMinute = Integer.parseInt(System.getenv().getOrDefault("RATE_LIMIT_PER_MINUTE", "60"));
            return new Config(Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), pin, secret, perMinute);
        }
    }

    private final HttpServer server;
    private final ScheduledExecutorService timers;
    private final EventHub hub;
    private final PaymentService svc;

    private App(HttpServer server, ScheduledExecutorService timers, EventHub hub, PaymentService svc) {
        this.server = server;
        this.timers = timers;
        this.hub = hub;
        this.svc = svc;
    }

    public static App start(Config cfg) throws IOException {
        Clock clock = Clock.systemUTC();
        PaymentService svc = new PaymentService(clock);
        svc.addMerchant(new Merchant("M001", "Warung Ahmad", "Lhokseumawe", "24351", "5812", "ID1026112100001",
                "9360081200000000017"));
        svc.addMerchant(new Merchant("M002", "Kopi Rakan", "Banda Aceh", "23111", "5814", "ID1026112100002",
                "9360081200000000025"));

        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.port()), 64);
        int port = server.getAddress().getPort();

        ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        WebhookDispatcher webhooks = new WebhookDispatcher((url, headers, body) -> {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach((k, v) -> {
                if (!k.equalsIgnoreCase("User-Agent")) {
                    rb.header(k, v);
                }
            });
            rb.header("User-Agent", headers.getOrDefault("User-Agent", "qris-sim"));
            return http.send(rb.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }, (task, delay) -> timers.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS), clock);

        Map<String, WebhookEndpoint> endpoints = new LinkedHashMap<>();
        WebhookEndpoint demoEp = new WebhookEndpoint("M001", "http://127.0.0.1:" + port + "/demo-merchant/webhook",
                cfg.webhookSecret());
        endpoints.put("M001", demoEp);
        webhooks.register(demoEp);

        EventHub hub = new EventHub();
        svc.addListener(webhooks);
        // The live stream goes to every visitor, so it carries only what a cashier screen needs:
        // never a wallet id, payer name or QR payload.
        svc.addListener(e -> hub.publish("payment", publicEvent(e)));
        webhooks.watch(a -> hub.publish("webhook", a));

        DemoMerchant demo = new DemoMerchant(cfg.webhookSecret(), clock);
        server.createContext("/", new Api(svc, webhooks, endpoints, demo, hub, new StaticFiles(),
                new Api.DemoWallet(OWNER, cfg.demoPin(), DEMO_BALANCE),
                new RateLimiter(clock, cfg.writesPerMinute(), Math.max(10, cfg.writesPerMinute() / 2))));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        timers.scheduleAtFixedRate(svc::expireDue, 1, 1, TimeUnit.SECONDS);
        timers.scheduleAtFixedRate(hub::ping, 15, 15, TimeUnit.SECONDS);
        return new App(server, timers, hub, svc);
    }

    static Map<String, Object> publicEvent(PaymentEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", e.type().wireName());
        if (e.invoice() != null) {
            Map<String, Object> inv = new LinkedHashMap<>();
            inv.put("id", e.invoice().id());
            inv.put("status", e.invoice().status());
            inv.put("amount", e.invoice().amount());
            data.put("invoice", inv);
        }
        if (e.payment() != null) {
            Map<String, Object> pay = new LinkedHashMap<>();
            pay.put("invoice_id", e.payment().invoiceId());
            pay.put("amount", e.payment().amount());
            pay.put("paid_at", e.payment().paidAt());
            data.put("payment", pay);
        }
        return data;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public PaymentService service() {
        return svc;
    }

    @Override
    public void close() {
        hub.closeAll();
        timers.shutdownNow();
        server.stop(0);
    }

    public static void main(String[] args) throws IOException {
        Config cfg = Config.fromEnv();
        App app = start(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        System.out.println(Json.write(Map.of("event", "started", "port", app.port())));
    }
}
