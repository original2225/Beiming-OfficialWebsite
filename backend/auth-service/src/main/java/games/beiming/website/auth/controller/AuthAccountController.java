package games.beiming.website.auth.controller;

import games.beiming.website.auth.dto.LoginRequestDTO;
import games.beiming.website.auth.dto.RegisterRequestDTO;
import games.beiming.website.auth.service.AuthAccountService;
import games.beiming.website.auth.vo.AuthTokenVO;
import games.beiming.website.auth.vo.AuthUserVO;
import games.beiming.website.common.core.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
public class AuthAccountController {

    private final AuthAccountService authAccountService;

    public AuthAccountController(AuthAccountService authAccountService) {
        this.authAccountService = authAccountService;
    }

    @PostMapping("/api/auth/register")
    public ApiResponse<AuthTokenVO> register(@RequestBody RegisterRequestDTO request, HttpServletRequest servletRequest) {
        return ApiResponse.success(authAccountService.register(request, clientIp(servletRequest)));
    }

    @PostMapping("/api/auth/login")
    public ApiResponse<AuthTokenVO> login(@RequestBody LoginRequestDTO request) {
        return ApiResponse.success(authAccountService.login(request));
    }

    @GetMapping("/api/auth/me")
    public ApiResponse<AuthUserVO> me() {
        return ApiResponse.success(authAccountService.me());
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.trim().isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
