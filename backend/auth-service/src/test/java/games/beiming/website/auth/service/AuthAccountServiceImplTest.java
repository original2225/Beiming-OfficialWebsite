package games.beiming.website.auth.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import games.beiming.website.auth.security.AuthPasswordService;
import games.beiming.website.auth.security.AuthTokenService;
import games.beiming.website.auth.service.impl.AuthAccountServiceImpl;
import games.beiming.website.auth.vo.AuthTokenVO;
import games.beiming.website.common.security.enums.PermissionLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthAccountServiceImplTest {

    private final AuthUserMapper authUserMapper = mock(AuthUserMapper.class);
    private final AuthInviteCodeMapper authInviteCodeMapper = mock(AuthInviteCodeMapper.class);
    private final AuthInviteCodeUsageMapper authInviteCodeUsageMapper = mock(AuthInviteCodeUsageMapper.class);
    private final AuthPasswordService authPasswordService = new AuthPasswordService();
    private final AuthTokenService authTokenService = mock(AuthTokenService.class);
    private final AuthAccountService authAccountService = new AuthAccountServiceImpl(
            authUserMapper,
            authInviteCodeMapper,
            authInviteCodeUsageMapper,
            authPasswordService,
            authTokenService
    );

    @Test
    void registerCreatesUserWithHashedPasswordAndRecordsInviteUsage() {
        RegisterRequestDTO request = new RegisterRequestDTO();
        request.setUsername("steve");
        request.setPassword("plain-password");
        request.setNickname("Steve");
        request.setInviteCode("BM-TEST-001");
        request.setMinecraftId("SteveMC");

        AuthInviteCode inviteCode = activeInviteCode();

        when(authInviteCodeMapper.selectOne(any(Wrapper.class))).thenReturn(inviteCode);
        when(authUserMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(authUserMapper.insert(any(AuthUser.class))).thenAnswer(invocation -> {
            AuthUser user = invocation.getArgument(0);
            user.setId(100L);
            return 1;
        });
        when(authInviteCodeMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(authInviteCodeUsageMapper.insert(any(AuthInviteCodeUsage.class))).thenReturn(1);
        when(authTokenService.createToken(100L, "steve", PermissionLevel.USER)).thenReturn("token-value");

        AuthTokenVO response = authAccountService.register(request, "127.0.0.1");

        assertEquals("token-value", response.getToken());
        assertEquals("steve", response.getUser().getUsername());
        assertEquals(PermissionLevel.USER, response.getUser().getPermissionLevel());

        ArgumentCaptor<AuthUser> userCaptor = ArgumentCaptor.forClass(AuthUser.class);
        verify(authUserMapper).insert(userCaptor.capture());
        AuthUser savedUser = userCaptor.getValue();
        assertEquals("steve", savedUser.getUsername());
        assertEquals("SteveMC", savedUser.getMinecraftId());
        assertEquals(AuthUserStatus.ENABLED, savedUser.getStatus());
        assertNotEquals("plain-password", savedUser.getPasswordHash());
        assertTrue(authPasswordService.matches("plain-password", savedUser.getPasswordHash()));

        ArgumentCaptor<AuthInviteCodeUsage> usageCaptor = ArgumentCaptor.forClass(AuthInviteCodeUsage.class);
        verify(authInviteCodeUsageMapper).insert(usageCaptor.capture());
        assertEquals(10L, usageCaptor.getValue().getInviteCodeId());
        assertEquals(100L, usageCaptor.getValue().getUserId());
        assertEquals("127.0.0.1", usageCaptor.getValue().getUsedIp());
    }

    @Test
    void registerRejectsMissingInviteCode() {
        RegisterRequestDTO request = new RegisterRequestDTO();
        request.setUsername("steve");
        request.setPassword("plain-password");
        request.setInviteCode("NOPE");
        request.setMinecraftId("SteveMC");

        when(authInviteCodeMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThrows(AuthBusinessException.class, () -> authAccountService.register(request, "127.0.0.1"));

        verify(authUserMapper, never()).insert(any(AuthUser.class));
    }

    @Test
    void registerRejectsDuplicateUsername() {
        RegisterRequestDTO request = new RegisterRequestDTO();
        request.setUsername("steve");
        request.setPassword("plain-password");
        request.setInviteCode("BM-TEST-001");
        request.setMinecraftId("SteveMC");

        when(authInviteCodeMapper.selectOne(any(Wrapper.class))).thenReturn(activeInviteCode());
        when(authUserMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThrows(AuthBusinessException.class, () -> authAccountService.register(request, "127.0.0.1"));

        verify(authUserMapper, never()).insert(any(AuthUser.class));
    }

    @Test
    void loginReturnsTokenWhenPasswordMatches() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setUsername("steve");
        request.setPassword("plain-password");

        AuthUser user = new AuthUser();
        user.setId(100L);
        user.setUsername("steve");
        user.setNickname("Steve");
        user.setMinecraftId("SteveMC");
        user.setPasswordHash(authPasswordService.encode("plain-password"));
        user.setPermissionLevel(PermissionLevel.USER);
        user.setStatus(AuthUserStatus.ENABLED);

        when(authUserMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        when(authTokenService.createToken(100L, "steve", PermissionLevel.USER)).thenReturn("token-value");

        AuthTokenVO response = authAccountService.login(request);

        assertEquals("token-value", response.getToken());
        assertEquals("steve", response.getUser().getUsername());
    }

    @Test
    void loginRejectsWrongPassword() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setUsername("steve");
        request.setPassword("wrong-password");

        AuthUser user = new AuthUser();
        user.setUsername("steve");
        user.setPasswordHash(authPasswordService.encode("right-password"));
        user.setPermissionLevel(PermissionLevel.USER);
        user.setStatus(AuthUserStatus.ENABLED);

        when(authUserMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        assertThrows(AuthBusinessException.class, () -> authAccountService.login(request));
    }

    private AuthInviteCode activeInviteCode() {
        AuthInviteCode inviteCode = new AuthInviteCode();
        inviteCode.setId(10L);
        inviteCode.setCode("BM-TEST-001");
        inviteCode.setPermissionLevel(PermissionLevel.USER);
        inviteCode.setMaxUses(5);
        inviteCode.setUsedCount(1);
        inviteCode.setExpiresAt(LocalDateTime.now().plusDays(1));
        inviteCode.setStatus(InviteCodeStatus.ENABLED);
        return inviteCode;
    }
}
