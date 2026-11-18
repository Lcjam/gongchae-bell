package kr.gongchae.bell.alerts;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR-0015 with the capability-token exception (06/07/08): every state-changing request must come from our origin
 * (Origin, or Referer when Origin is absent). Only POSTs authorised by a purpose token in the request (confirm,
 * manage, unsubscribe, RFC 8058 one-click) also accept {@code Origin: null} or no Origin, which a no-referrer page or
 * a mail provider sends; a foreign Origin is refused there too. Refusals here are not token failures and are not
 * counted (a forged cross-site form must not lock out the victim's IP).
 */
@Component
public class OriginFilter extends OncePerRequestFilter {
    static final Set<String> TOKEN_POSTS = Set.of("/subscriptions/confirm", "/subscriptions/manage",
            "/subscriptions/unsubscribe", "/subscriptions/unsubscribe/one-click");
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final String origin;

    public OriginFilter(@Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        origin = originOf(baseUrl);
    }

    static String originOf(String url) {
        URI uri = URI.create(url.trim());
        if (uri.getScheme() == null || uri.getHost() == null) return null;
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String given = request.getHeader("Origin");
        boolean allowed;
        if (TOKEN_POSTS.contains(path)) allowed = given == null || given.equals("null") || given.equals(origin);
        else if (given != null) allowed = given.equals(origin);
        else allowed = sameOrigin(request.getHeader("Referer"));
        if (!allowed) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean sameOrigin(String referer) {
        if (referer == null) return false;
        try { return origin != null && origin.equals(originOf(referer)); }
        catch (IllegalArgumentException malformed) { return false; }
    }
}
