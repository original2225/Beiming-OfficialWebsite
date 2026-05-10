package games.beiming.website.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import games.beiming.website.auth.dto.CreateInviteCodeRequestDTO;
import games.beiming.website.auth.entity.AuthInviteCode;
import games.beiming.website.auth.enums.InviteCodeStatus;
import games.beiming.website.auth.exception.AuthBusinessException;
import games.beiming.website.auth.mapper.AuthInviteCodeMapper;
import games.beiming.website.auth.security.AuthContext;
import games.beiming.website.auth.security.AuthPermissionChecker;
import games.beiming.website.auth.security.AuthTokenClaims;
import games.beiming.website.auth.service.AuthInviteCodeService;
import games.beiming.website.auth.vo.InviteCodeVO;
import games.beiming.website.common.core.result.ErrorCode;
import games.beiming.website.common.security.enums.PermissionLevel;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuthInviteCodeServiceImpl implements AuthInviteCodeService {

    private final AuthInviteCodeMapper authInviteCodeMapper;
    private final AuthPermissionChecker authPermissionChecker;

    public AuthInviteCodeServiceImpl(AuthInviteCodeMapper authInviteCodeMapper,
                                     AuthPermissionChecker authPermissionChecker) {
        this.authInviteCodeMapper = authInviteCodeMapper;
        this.authPermissionChecker = authPermissionChecker;
    }

    @Override
    public InviteCodeVO createInviteCode(CreateInviteCodeRequestDTO request) {
        authPermissionChecker.require(PermissionLevel.ADMIN);
        validateCreateRequest(request);
        if (request.getPermissionLevel() == PermissionLevel.OWNER) {
            authPermissionChecker.require(PermissionLevel.OWNER);
        }
        if (request.getPermissionLevel() == PermissionLevel.ADMIN) {
            authPermissionChecker.require(PermissionLevel.OWNER);
        }
        Long count = authInviteCodeMapper.selectCount(new QueryWrapper<AuthInviteCode>()
                .eq("code", request.getCode().trim()));
        if (count > 0) {
            throw new AuthBusinessException(ErrorCode.CONFLICT, "invite code already exists");
        }
        AuthTokenClaims currentUser = AuthContext.getCurrentUser();
        LocalDateTime now = LocalDateTime.now();
        AuthInviteCode inviteCode = new AuthInviteCode();
        inviteCode.setCode(request.getCode().trim());
        inviteCode.setPermissionLevel(request.getPermissionLevel());
        inviteCode.setMaxUses(request.getMaxUses());
        inviteCode.setUsedCount(0);
        inviteCode.setExpiresAt(request.getExpiresAt());
        inviteCode.setStatus(InviteCodeStatus.ENABLED);
        inviteCode.setCreatedBy(currentUser == null ? null : currentUser.getUserId());
        inviteCode.setCreatedAt(now);
        inviteCode.setUpdatedAt(now);
        authInviteCodeMapper.insert(inviteCode);
        return toVO(inviteCode);
    }

    @Override
    public InviteCodeVO disableInviteCode(Long id) {
        authPermissionChecker.require(PermissionLevel.ADMIN);
        AuthInviteCode inviteCode = authInviteCodeMapper.selectById(id);
        if (inviteCode == null) {
            throw new AuthBusinessException(ErrorCode.NOT_FOUND, "invite code not found");
        }
        inviteCode.setStatus(InviteCodeStatus.DISABLED);
        inviteCode.setUpdatedAt(LocalDateTime.now());
        authInviteCodeMapper.updateById(inviteCode);
        return toVO(inviteCode);
    }

    private void validateCreateRequest(CreateInviteCodeRequestDTO request) {
        if (request == null || isBlank(request.getCode()) || request.getPermissionLevel() == null
                || request.getMaxUses() == null || request.getMaxUses() < 1) {
            throw new AuthBusinessException(ErrorCode.BAD_REQUEST, "code, permissionLevel and maxUses are required");
        }
    }

    private InviteCodeVO toVO(AuthInviteCode inviteCode) {
        InviteCodeVO vo = new InviteCodeVO();
        vo.setId(inviteCode.getId());
        vo.setCode(inviteCode.getCode());
        vo.setPermissionLevel(inviteCode.getPermissionLevel());
        vo.setMaxUses(inviteCode.getMaxUses());
        vo.setUsedCount(inviteCode.getUsedCount());
        vo.setExpiresAt(inviteCode.getExpiresAt());
        vo.setStatus(inviteCode.getStatus());
        return vo;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
