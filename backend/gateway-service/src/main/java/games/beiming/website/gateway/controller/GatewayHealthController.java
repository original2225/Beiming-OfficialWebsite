package games.beiming.website.gateway.controller;

import games.beiming.website.common.core.response.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayHealthController {

    @GetMapping("/internal/health")
    public ApiResponse<Map<String, String>> health() {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("status", "UP");
        data.put("service", "gateway-service");
        return ApiResponse.success(data);
    }

    @GetMapping("/api/gateway/ping")
    public ApiResponse<Map<String, String>> ping() {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("service", "gateway-service");
        data.put("message", "pong");
        return ApiResponse.success(data);
    }
}
