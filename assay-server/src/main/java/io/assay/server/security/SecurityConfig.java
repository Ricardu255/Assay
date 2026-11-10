package io.assay.server.security;

import io.assay.server.config.AssayServerProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless API security.
 *
 * <p>When {@code assay.server.auth-tokens} is empty the server stays open for local development.
 * When tokens are configured every API call except {@code /api/ping} and the actuator probes must
 * carry one of them, either as {@code Authorization: Bearer <token>} or {@code X-API-Key}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AssayServerProperties properties) throws Exception {
        boolean tokensConfigured = !properties.getAuthTokens().isEmpty();
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(handling -> handling.authenticationEntryPoint(unauthorized()))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/ping", "/actuator/health", "/actuator/info").permitAll();
                    if (tokensConfigured) {
                        auth.anyRequest().authenticated();
                    } else {
                        auth.anyRequest().permitAll();
                    }
                });
        if (tokensConfigured) {
            http.addFilterBefore(
                    new ApiTokenFilter(properties.getAuthTokens()), UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    private static AuthenticationEntryPoint unauthorized() {
        return (request, response, failure) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter()
                    .write("{\"title\":\"Unauthorized\",\"status\":401,"
                            + "\"detail\":\"missing or invalid API token\"}");
        };
    }
}
