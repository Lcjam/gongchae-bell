package kr.gongchae.bell.catalog;

import java.net.URI;
import java.util.Locale;

public final class SafeUrlValidator {
    private SafeUrlValidator() {
    }

    public static boolean isSafeHttpUrl(String raw) {
        return sanitizeHttpUrl(raw) != null;
    }

    public static String sanitizeHttpUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isISOControl(ch)) {
                return null;
            }
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            return null;
        }
        if (uri.getRawUserInfo() != null) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        return trimmed;
    }

    public static String alioDetailUrl(long sn) {
        return "https://job.alio.go.kr/recruitview.do?idx=" + sn;
    }
}
