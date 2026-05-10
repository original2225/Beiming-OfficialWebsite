package games.beiming.website.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import games.beiming.website.auth.dto.LoginRequestDTO;
import games.beiming.website.auth.dto.RegisterRequestDTO;
import games.beiming.website.auth.entity.AuthInviteCode;
import games.beiming.website.auth.entity.AuthInviteCodeUsage;
import games.beiming.website.auth.entity.AuthUser;
import games.beiming.website.auth.enums.AuthUserStatus;
import games.beiming.website.auth.enums.InviteCodeStatus;
import games.beiming.website.auth.exception.AuthBusinessException;
import games.beiming.website.auth.mapper.AuthInviteCodeMapper;
import games.beiming.website.auth.mapper.AuthInviteCodeUsageMapper;
import games.beiming.website.auth.mapper.AuthUserMapper;
import games.beiming.website.auth.security.AuthContext;
import games.beiming.website.auth.security.AuthPasswordService;
import games.beiming.website.auth.security.AuthTokenClaims;
import games.beiming.website.auth.security.AuthTokenService;
import games.beiming.website.auth.service.AuthAccountService;
import games.beiming.website.auth.vo.AuthTokenVO;
import games.beiming.website.auth.vo.AuthUserVO;
import games.beiming.website.common.core.result.ErrorCode;
import games.beiming.website.common.security.enums.PermissionLevel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthAccountServiceImpl implements AuthAccountService {

    private final AuthUserMapper authUserMapper;
    private final AuthInviteCodeMapper authInviteCodeMapper;
    private final AuthInviteCodeUsageMapper authInviteCodeUsageMapper;
    private final AuthPasswordService authPasswordService;
    private final AuthTokenService authTokenService;

    public AuthAccountServiceImpl(AuthUserMapper authUserMapper,
                                  AuthInviteCodeMapper authInviteCodeMapper,
                                  AuthInviteCodeUsageMapper authInviteCodeUsageMapper,
                                  AuthPasswordService authPasswordService,
                                  AuthTokenService authTokenService) {
        this.authUserMapper = authUserMapper;
        this.authInviteCodeMapper = authInviteCodeMapper;
        this.authInviteCodeUsageMapper = authInviteCodeUsageMapper;
        this.authPasswordService = authPasswordService;
        this.authTokenService = authTokenService;
    }

    @Override
    @Transactional
    public AuthTokenVO register(RegisterRequestDTO request, String clientIp) {
        validateRegisterRequest(request);
        AuthInviteCode inviteCode = findUsableInviteCode(request.getInviteCode());
        ensureUniqueUsername(request.getUsername());
        ensureUniqueMinecraftId(request.getMinecraftId());

        LocalDateTime now = LocalDateTime.now();
        AuthUser user = new AuthUser();
        user.setUsername(request.getUsername().trim());
        user.setPasswordHash(authPasswordService.encode(request.getPassword()));
        user.setNickname(defaultIfBlank(request.getNickname(), request.getUsername()).trim());
        user.setMinecraftId(request.getMinecraftId().trim());
        user.setPermissionLevel(inviteCode.getPermissionLevel());
        user.setStatus(AuthUserStatus.ENABLED);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        authUserMapper.insert(user);

        authInviteCodeMapper.update(null, new UpdateWrapper<AuthInviteCode>()
                .eq("id", inviteCode.getId())
                .set("used_count", inviteCode.getUsedCount() + 1)
                .set("updated_at", now));

        AuthInviteCodeUsage usage = new AuthInviteCodeUsage();
        usage.setInviteCodeId(inviteCode.getId());
        usage.setUserId(user.getId());
        usage.setUsedAt(now);
        usage.setUsedIp(clientIp);
        authInviteCodeUsageMapper.insert(usage);

        return toTokenVO(user);
    }

    @Override
    public AuthTokenVO login(LoginRequestDTO request) {
        if (request == null || isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            throw new AuthBusinessException(ErrorCode.BAD_REQUEST, "username and password are required");
        }
        AuthUser user = authUserMapper.selectOne(new QueryWrapper<AuthUser>()
                .eq("username", request.getUsername().trim()));
        if (user == null || user.getStatus() != AuthUserStatus.ENABLED) {
            throw new AuthBusinessException(ErrorCode.UNAUTHORIZED, "username or password is incorrect");
        }
        if (!authPasswordService.matches(request.getPassword(), user.getPasswordHash())) {
            throw new AuthBusinessException(ErrorCode.UNAUTHORIZED, "username or password is incorrect");
        }
        return toTokenVO(user);
    }

    @Override
    public AuthUserVO me() {
        AuthTokenClaims claims = AuthContext.getCurrentUser();
        if (claims == null) {
            throw new AuthBusinessException(ErrorCode.UNAUTHORIZED, "login required");
        }
        AuthUser user = authUserMapper.selectById(claims.getUserId());
        if (user == null || user.getStatus() != AuthUserStatus.ENABLED) {
            throw new AuthBusinessException(ErrorCode.UNAUTHORIZED, "login required");
        }
        return toUserVO(user);
    }

    private void validateRegisterRequest(RegisterRequestDTO request) {
        if (request == null || isBlank(request.getUsername()) || isBlank(request.getPassword())
                || isBlank(request.getInviteCode()) || isBlank(request.getMinecraftId())) {
            throw new AuthBusinessException(ErrorCode.BAD_REQUEST, "username, password, inviteCode and minecraftId are required");
        }
        if (request.getPassword().length() < 6) {
            throw new AuthBusinessException(ErrorCode.BAD_REQUEST, "password must be at least 6 characters");
        }
    }

    private AuthInviteCode findUsableInviteCode(String code) {
        AuthInviteCode inviteCode = authInviteCodeMapper.selectOne(new QueryWrapper<AuthInviteCode>()
                .eq("code", code.trim()));
        if (inviteCode == null || inviteCode.getStatus() != InviteCodeStatus.ENABLED) {
            throw new AuthBusinessException(ErrorCode.INVITE_CODE_INVALID, "invite code expired or invalid");
        }
        if (inviteCode.getExpiresAt() != null && inviteCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthBusinessException(ErrorCode.INVITE_CODE_INVALID, "invite code expired or invalid");
        }
        if (inviteCode.getUsedCount() >= inviteCode.getMaxUses()) {
            throw new AuthBusinessException(ErrorCode.INVITE_CODE_INVALID, "invite code has been used up");
        }
        return inviteCode;
    }

    private void ensureUniqueUsername(String username) {
        Long count = authUserMapper.selectCount(new QueryWrapper<AuthUser>()
                .eq("username", username.trim()));
        if (count > 0) {
            throw new AuthBusinessException(ErrorCode.CONFLICT, "username already exists");
        }
    }

    private void ensureUniqueMinecraftId(String minecraftId) {
        Long count = authUserMapper.selectCount(new QueryWrapper<AuthUser>()
                .eq("minecraft_id", minecraftId.trim()));
        if (count > 0) {
            throw new AuthBusinessException(ErrorCode.CONFLICT, "minecraft id already exists");
        }
    }

    private AuthTokenVO toTokenVO(AuthUser user) {
        String token = authTokenService.createToken(user.getId(), user.getUsername(), user.getPermissionLevel());
        return new AuthTokenVO(token, toUserVO(user));
    }

    private AuthUserVO toUserVO(AuthUser user) {
        AuthUserVO userVO = new AuthUserVO();
        userVO.setId(user.getId());
        userVO.setUsername(user.getUsername());
        userVO.setNickname(user.getNickname());
        userVO.setMinecraftId(user.getMinecraftId());
        userVO.setPermissionLevel(user.getPermissionLevel());
        return userVO;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }
}
