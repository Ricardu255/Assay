package io.assay.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates API calls with a bearer token or an {@code X-API-Key} header.
 *
 * <p>Token comparison is constant time. The filter is only installed when at least one token is
 * configured; the default setup keeps the server open for local development.
 */
public final class ApiTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final List<String> tokens;

    public ApiTokenFilter(List<String> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidate = token(request);
        if (candidate != null && matches(candidate)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "api-token", null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
        }
        chain.doFilter(request, response);
    }

    private static String token(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            return value.isEmpty() ? null : value;
        }
        String key = request.getHeader("X-API-Key");
        return key == null || key.isBlank() ? null : key.trim();
    }

    private boolean matches(String candidate) {
        byte[] candidateBytes = candidate.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String token : tokens) {
            matched |= MessageDigest.isEqual(candidateBytes, token.getBytes(StandardCharsets.UTF_8));
        }
        return matched;
    }
}
