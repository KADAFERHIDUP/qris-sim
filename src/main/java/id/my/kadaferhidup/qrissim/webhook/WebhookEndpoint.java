package id.my.kadaferhidup.qrissim.webhook;

/** Where a merchant wants its notifications, and the secret both sides share. */
public record WebhookEndpoint(String merchantId, String url, String secret) {

    public WebhookEndpoint {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("URL webhook harus http:// atau https://");
        }
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("Secret webhook minimal 16 karakter");
        }
    }
}
