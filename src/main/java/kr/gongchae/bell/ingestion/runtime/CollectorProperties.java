package kr.gongchae.bell.ingestion.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.HexFormat;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("collector")
public record CollectorProperties(boolean enabled, String serviceKey, int warningLimit, int stopLimit,
        boolean backfill, String backfillSlot, String backfillFrom, String backfillTo, boolean backfillRestartPages) {
    public CollectorProperties {
        serviceKey = normalizeKey(serviceKey);
        if (warningLimit == 0) warningLimit = 500;
        if (stopLimit == 0) stopLimit = 900;
        if (warningLimit < 1 || stopLimit <= warningLimit || stopLimit > 900)
            throw new IllegalArgumentException("INVALID_BUDGET_LIMITS");
        if (enabled && backfill) throw new IllegalArgumentException("BACKFILL_REQUIRES_SCHEDULER_DISABLED");
        if (backfill && (backfillSlot == null || !backfillSlot.matches("[A-Za-z0-9_-]{1,40}")))
            throw new IllegalArgumentException("BACKFILL_SLOT_REQUIRED");
        if ((enabled || backfill) && (serviceKey == null || serviceKey.isBlank()))
            throw new IllegalArgumentException("SERVICE_KEY_REQUIRED");
    }
    @Override public String toString() { return "CollectorProperties[enabled=" + enabled + "]"; }

    /**
     * The portal shows one key two ways: Decoding (raw) and Encoding (percent-encoded). A key containing '%' is decoded
     * exactly once and every %XX must be valid ('+' stays '+'); a key without '%' is kept as is. RecruitmentClient then
     * encodes the result once. Errors never carry the key.
     */
    static String normalizeKey(String key) {
        if (key == null || key.indexOf('%') < 0) return key;
        var bytes = new ByteArrayOutputStream();
        for (int i = 0; i < key.length(); ) {
            if (key.charAt(i) == '%') {
                if (i + 2 >= key.length() || !HexFormat.isHexDigit(key.charAt(i + 1)) || !HexFormat.isHexDigit(key.charAt(i + 2)))
                    throw new IllegalArgumentException("INVALID_SERVICE_KEY");
                bytes.write(HexFormat.fromHexDigits(key, i + 1, i + 3));
                i += 3;
            } else {
                int end = key.indexOf('%', i);
                if (end < 0) end = key.length();
                bytes.writeBytes(key.substring(i, end).getBytes(UTF_8));
                i = end;
            }
        }
        try {
            return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("INVALID_SERVICE_KEY");
        }
    }
}
