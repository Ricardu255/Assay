package io.assay.server;

import io.assay.server.config.AssayServerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Optional Spring Boot entry point for the HTTP evaluation API. */
@SpringBootApplication
@EnableConfigurationProperties(AssayServerProperties.class)
public class AssayServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssayServerApplication.class, args);
    }
}
