package games.beiming.website.auth.controller;

import games.beiming.website.auth.service.AuthTestConnectionService;
import games.beiming.website.auth.vo.AuthTestConnectionVO;
import games.beiming.website.common.core.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthTestConnectionController {

    private final AuthTestConnectionService authTestConnectionService;

    public AuthTestConnectionController(AuthTestConnectionService authTestConnectionService) {
        this.authTestConnectionService = authTestConnectionService;
    }

    @GetMapping("/api/auth/test-connection")
    public ApiResponse<AuthTestConnectionVO> testConnection() {
        return ApiResponse.success(authTestConnectionService.testConnection());
    }
}
