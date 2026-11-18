package kr.gongchae.bell.alerts;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Alerts (M2) settings. With {@code enabled=false} (default) nothing is collected or sent and every key may be empty.
 * With {@code enabled=true} every key and every privacy-notice value must be configured, or the app refuses to start:
 * the notice values are a real-operation gate and are never filled with guesses. Errors carry no values.
 */
@ConfigurationProperties("alerts")
public record AlertsProperties(boolean enabled, String tokenKey, String hmacKey, String webhookKey, String webhookKeyId,
        String erasureLog, Mail mail, Privacy privacy) {

    public record Mail(String apiKey, String from, String baseUrl) {
        @Override public String toString() { return "Mail[from set=" + (from != null) + "]"; }
    }

    /** Values of the privacy policy that only the operator can confirm (Sol source review, 2026-11-16). */
    public record Privacy(String officer, String contact, String policyVersion, String effectiveDate,
            String hostingLocation, String overseasBasis, String resendRetention) {
        boolean complete() {
            return present(officer) && present(contact) && present(policyVersion) && present(effectiveDate)
                    && present(hostingLocation) && present(overseasBasis) && present(resendRetention);
        }
    }

    public AlertsProperties {
        if (mail == null) mail = new Mail(null, null, null);
        if (mail.baseUrl() == null || mail.baseUrl().isBlank()) mail = new Mail(mail.apiKey(), mail.from(), "https://api.resend.com/");
        if (privacy == null) privacy = new Privacy(null, null, null, null, null, null, null);
        if (enabled) {
            key(tokenKey, "ALERTS_TOKEN_KEY", false);
            key(hmacKey, "ALERTS_HMAC_KEY", false);
            key(webhookKey, "ALERTS_WEBHOOK_KEY", true);
            if (webhookKeyId == null || !webhookKeyId.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("ALERTS_WEBHOOK_KEY_ID_REQUIRED");
            if (!present(erasureLog)) throw new IllegalArgumentException("ALERTS_ERASURE_LOG_REQUIRED");
            if (!present(mail.apiKey()) || !present(mail.from())) throw new IllegalArgumentException("ALERTS_MAIL_REQUIRED");
            if (!privacy.complete()) throw new IllegalArgumentException("ALERTS_PRIVACY_NOTICE_REQUIRED");
        }
    }

    private static void key(String base64, String name, boolean exactly32) {
        byte[] bytes;
        try { bytes = present(base64) ? Base64.getDecoder().decode(base64.trim()) : new byte[0]; }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(name + "_INVALID"); }
        if (exactly32 ? bytes.length != 32 : bytes.length < 32) throw new IllegalArgumentException(name + "_INVALID");
    }

    static boolean present(String value) { return value != null && !value.isBlank(); }

    @Override public String toString() { return "AlertsProperties[enabled=" + enabled + "]"; }
}
