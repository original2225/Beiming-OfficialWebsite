package games.beiming.website.auth.controller;

import games.beiming.website.auth.service.AuthPingService;
import games.beiming.website.auth.vo.AuthPingVO;
import games.beiming.website.common.core.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthPingController {

    private final AuthPingService authPingService;

    public AuthPingController(AuthPingService authPingService) {
        this.authPingService = authPingService;
    }

    @GetMapping("/api/auth/ping")
    public ApiResponse<AuthPingVO> ping() {
        return ApiResponse.success(authPingService.ping());
    }
}
