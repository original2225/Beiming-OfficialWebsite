package games.beiming.website.profile.controller;

import games.beiming.website.common.core.response.ApiResponse;
import games.beiming.website.profile.service.PingService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {
    private final PingService pingService;
    public PingController(PingService pingService) {
        this.pingService = pingService;
    }
    @GetMapping("/api/profile/ping")
    public ApiResponse<Map<String, String>> ping() {
        return ApiResponse.success(pingService.ping());
    }
}
