package games.beiming.website.auth.controller;

import games.beiming.website.auth.dto.CreateInviteCodeRequestDTO;
import games.beiming.website.auth.service.AuthInviteCodeService;
import games.beiming.website.auth.vo.InviteCodeVO;
import games.beiming.website.common.core.response.ApiResponse;
import games.beiming.website.common.security.annotation.RequirePermission;
import games.beiming.website.common.security.enums.PermissionLevel;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthInviteCodeController {

    private final AuthInviteCodeService authInviteCodeService;

    public AuthInviteCodeController(AuthInviteCodeService authInviteCodeService) {
        this.authInviteCodeService = authInviteCodeService;
    }

    @PostMapping("/api/auth/invite-codes")
    @RequirePermission(PermissionLevel.ADMIN)
    public ApiResponse<InviteCodeVO> createInviteCode(@RequestBody CreateInviteCodeRequestDTO request) {
        return ApiResponse.success(authInviteCodeService.createInviteCode(request));
    }

    @PostMapping("/api/auth/invite-codes/{id}/disable")
    @RequirePermission(PermissionLevel.ADMIN)
    public ApiResponse<InviteCodeVO> disableInviteCode(@PathVariable Long id) {
        return ApiResponse.success(authInviteCodeService.disableInviteCode(id));
    }
}
