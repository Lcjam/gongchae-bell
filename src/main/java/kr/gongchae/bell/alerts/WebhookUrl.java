package kr.gongchae.bell.alerts;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Discord webhook accepted by ADR-0009: https, host exactly discord.com or discordapp.com, default port, no userinfo,
 * query or fragment, path /api/webhooks/{digits}/{token}. Both hosts normalize to discord.com, so the destination HMAC
 * is the same for either spelling. toString hides the token.
 */
public final class WebhookUrl {
    private static final Pattern PATH = Pattern.compile("/api/webhooks/(\\d{1,20})/([A-Za-z0-9._-]{1,100})");
    private final String id;
    private final String token;

    private WebhookUrl(String id, String token) { this.id = id; this.token = token; }

    public static Optional<WebhookUrl> parse(String raw) {
        if (raw == null) return Optional.empty();
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 300) return Optional.empty();
        URI uri;
        try { uri = new URI(trimmed); } catch (URISyntaxException invalid) { return Optional.empty(); }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || uri.getRawFragment() != null) return Optional.empty();
        String lower = host.toLowerCase(Locale.ROOT);
        if (!lower.equals("discord.com") && !lower.equals("discordapp.com")) return Optional.empty();
        var path = PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (!path.matches()) return Optional.empty();
        return Optional.of(new WebhookUrl(path.group(1), path.group(2)));
    }

    public String normalized() { return "https://discord.com/api/webhooks/" + id + "/" + token; }

    public String host() { return "discord.com"; }

    /** Execute Webhook with wait=true: Discord returns the created message, so 2xx means it was stored. */
    public URI executeUri() { return URI.create(normalized() + "?wait=true"); }

    @Override public boolean equals(Object other) { return other instanceof WebhookUrl w && w.normalized().equals(normalized()); }
    @Override public int hashCode() { return normalized().hashCode(); }
    @Override public String toString() { return "WebhookUrl[id=" + id + "]"; }
}
