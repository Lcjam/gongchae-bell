package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * ADR-0008 tokens and ADR-0009 webhook storage. Keys come only from the environment (AlertsProperties).
 * Nothing here logs or returns key material.
 */
@Component
public class AlertsCrypto {
    /** Capability purposes. A token of one purpose never passes another purpose's route. */
    public enum Purpose { MANAGE, UNSUBSCRIBE }

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private final SecretKeySpec tokenKey;
    private final SecretKeySpec hmacKey;
    private final SecretKeySpec webhookKey;
    private final String webhookKeyId;
    private final SecureRandom random = new SecureRandom();

    public AlertsCrypto(AlertsProperties properties) {
        tokenKey = hmacKey(properties.tokenKey());
        hmacKey = hmacKey(properties.hmacKey());
        webhookKey = AlertsProperties.present(properties.webhookKey())
                ? new SecretKeySpec(Base64.getDecoder().decode(properties.webhookKey().trim()), "AES") : null;
        webhookKeyId = properties.webhookKeyId();
    }

    private static SecretKeySpec hmacKey(String base64) {
        return AlertsProperties.present(base64) ? new SecretKeySpec(Base64.getDecoder().decode(base64.trim()), "HmacSHA256") : null;
    }

    /** 32 random bytes; only its SHA-256 is stored (ADR-0008). */
    public String newConfirmToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }

    public static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    public static byte[] sha256(String text) { return sha256(text.getBytes(UTF_8)); }

    /** HMAC(token key, purpose | id | version): re-derivable for every mail, verified by recomputing (ADR-0008). */
    public String capability(Purpose purpose, UUID id, int tokenVersion) {
        return id + "." + URL.encodeToString(mac(require(tokenKey), purpose.name(), id.toString(), Integer.toString(tokenVersion)));
    }

    /** The subscription id a capability token claims; empty for anything malformed. Says nothing about validity. */
    public static Optional<UUID> claimedId(String token) {
        if (token == null || token.length() > 100) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot != 36) return Optional.empty();
        try { return Optional.of(UUID.fromString(token.substring(0, dot))); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
    }

    public boolean verify(Purpose purpose, UUID id, int tokenVersion, String token) {
        if (claimedId(token).filter(id::equals).isEmpty()) return false;
        byte[] given;
        try { given = URL_DECODER.decode(token.substring(37)); }
        catch (IllegalArgumentException malformed) { return false; }
        return MessageDigest.isEqual(given, mac(require(tokenKey), purpose.name(), id.toString(), Integer.toString(tokenVersion)));
    }

    /** Destination identity for dedup and webhook re-entry (ADR-0009); separate key from tokens. */
    public byte[] destination(String channel, String normalized) {
        return mac(require(hmacKey), "DEST", channel, normalized);
    }

    /** Rate-limit key: never the raw IP, address or webhook (ADR-0010). */
    public byte[] limitKey(String scope, String value) {
        return mac(require(hmacKey), "LIMIT", scope, value);
    }

    /** AES-256-GCM, fresh 12-byte nonce, AAD = subscription id; stored as nonce || ciphertext+tag (ADR-0009). */
    public byte[] encryptWebhook(UUID subscriptionId, String url) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, require(webhookKey), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(subscriptionId.toString().getBytes(UTF_8));
            byte[] sealed = cipher.doFinal(url.getBytes(UTF_8));
            return ByteBuffer.allocate(12 + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("WEBHOOK_ENCRYPT_FAILED"); }
    }

    public String decryptWebhook(UUID subscriptionId, byte[] stored, String keyId) {
        if (!require(webhookKeyId).equals(keyId)) throw new IllegalStateException("WEBHOOK_KEY_UNKNOWN");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, require(webhookKey), new GCMParameterSpec(128, stored, 0, 12));
            cipher.updateAAD(subscriptionId.toString().getBytes(UTF_8));
            return new String(cipher.doFinal(stored, 12, stored.length - 12), UTF_8);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("WEBHOOK_DECRYPT_FAILED"); }
    }

    public String webhookKeyId() { return require(webhookKeyId); }

    private static byte[] mac(SecretKeySpec key, String... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            for (String part : parts) {
                byte[] bytes = part.getBytes(UTF_8);
                mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());  // length prefix: no ambiguous joins
                mac.update(bytes);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("HMAC_FAILED"); }
    }

    private static <T> T require(T key) {
        if (key == null) throw new IllegalStateException("ALERTS_DISABLED");
        return key;
    }
}
