package io.assay.server.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ServerInfoController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("service", "assay-server", "status", "ok");
    }
}
