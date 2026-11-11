package io.assay.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityStartupCheckTest {

    @Test
    void warnsButDoesNotFailByDefault() {
        AssayServerProperties properties = new AssayServerProperties();

        assertDoesNotThrow(() -> SecurityStartupCheck.check(properties, new MockEnvironment()));
    }

    @Test
    void failsWhenConfiguredAndBoundBeyondLoopback() {
        AssayServerProperties properties = new AssayServerProperties();
        properties.setFailOnInsecureBinding(true);

        assertThrows(
                IllegalStateException.class,
                () -> SecurityStartupCheck.check(properties, new MockEnvironment()));
    }

    @Test
    void allowsLoopbackEvenWhenFailFastIsEnabled() {
        AssayServerProperties properties = new AssayServerProperties();
        properties.setFailOnInsecureBinding(true);
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.address", "127.0.0.1");

        assertDoesNotThrow(() -> SecurityStartupCheck.check(properties, environment));
    }

    @Test
    void allowsConfiguredTokens() {
        AssayServerProperties properties = new AssayServerProperties();
        properties.setFailOnInsecureBinding(true);
        properties.setAuthTokens(List.of("token"));

        assertDoesNotThrow(() -> SecurityStartupCheck.check(properties, new MockEnvironment()));
    }
}
