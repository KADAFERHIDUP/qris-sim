package id.my.kadaferhidup.qrissim.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Serves the web UI from the classpath folder {@code web/}. */
public final class StaticFiles {

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "txt", "text/plain; charset=utf-8");

    void serve(HttpExchange ex, String path) throws IOException {
        String p = path.equals("/") ? "/index.html" : path;
        if (!p.matches("^/[a-zA-Z0-9._-]+(/[a-zA-Z0-9._-]+)*$") || p.contains("..")) {
            throw new ApiException(404, "NOT_FOUND", "Berkas tidak ada");
        }
        String ext = p.substring(p.lastIndexOf('.') + 1);
        String type = TYPES.get(ext);
        try (InputStream in = StaticFiles.class.getResourceAsStream("/web" + p)) {
            if (in == null || type == null) {
                throw new ApiException(404, "NOT_FOUND", "Berkas tidak ada");
            }
            byte[] b = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Cache-Control", p.equals("/index.html") ? "no-cache" : "public, max-age=3600");
            boolean head = ex.getRequestMethod().equals("HEAD");
            ex.sendResponseHeaders(200, head ? -1 : b.length);
            if (!head) {
                ex.getResponseBody().write(b);
            }
        }
    }
}
