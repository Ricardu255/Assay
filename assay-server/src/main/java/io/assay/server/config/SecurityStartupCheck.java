package io.assay.server.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Checks at startup whether the HTTP API is unauthenticated and bound beyond loopback.
 *
 * <p>The default configuration keeps the server open for local development, as documented in the
 * README; setting {@code assay.server.fail-on-insecure-binding=true} turns the warning into a
 * startup failure for network-exposed deployments.
 */
@Component
class SecurityStartupCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityStartupCheck.class);

    private final AssayServerProperties properties;
    private final Environment environment;

    SecurityStartupCheck(AssayServerProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        check(properties, environment);
    }

    static void check(AssayServerProperties properties, Environment environment) {
        if (!properties.getAuthTokens().isEmpty()) {
            return;
        }
        String address = environment.getProperty("server.address");
        if (isLoopback(address)) {
            return;
        }
        String displayAddress =
                address == null || address.isBlank() ? "0.0.0.0 (all interfaces)" : address;
        if (properties.isFailOnInsecureBinding()) {
            throw new IllegalStateException(
                    "assay.server.auth-tokens is empty and server.address is " + displayAddress
                            + "; refusing to start. Bind to 127.0.0.1, configure tokens, or unset"
                            + " assay.server.fail-on-insecure-binding.");
        }
        LOGGER.warn(
                "assay.server.auth-tokens is empty and server.address is {}; the HTTP API is"
                        + " unauthenticated. Bind to 127.0.0.1 or configure tokens before exposing"
                        + " this server.",
                displayAddress);
    }

    private static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(address)) {
            return true;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException unresolvable) {
            return false;
        }
    }
}
