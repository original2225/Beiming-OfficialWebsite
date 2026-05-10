package games.beiming.website.auth.security;

import games.beiming.website.auth.exception.AuthBusinessException;
import games.beiming.website.common.security.enums.PermissionLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthTokenServiceTest {

    @Test
    void createsAndParsesToken() {
        AuthTokenService tokenService = new AuthTokenService("test-secret-value-that-is-long-enough", 3600L);

        String token = tokenService.createToken(12L, "steve", PermissionLevel.ADMIN);
        AuthTokenClaims claims = tokenService.parseToken(token);

        assertEquals(12L, claims.getUserId());
        assertEquals("steve", claims.getUsername());
        assertEquals(PermissionLevel.ADMIN, claims.getPermissionLevel());
    }

    @Test
    void rejectsTamperedToken() {
        AuthTokenService tokenService = new AuthTokenService("test-secret-value-that-is-long-enough", 3600L);

        String token = tokenService.createToken(12L, "steve", PermissionLevel.ADMIN);
        String tamperedToken = token.substring(0, token.length() - 2) + "xx";

        assertThrows(AuthBusinessException.class, () -> tokenService.parseToken(tamperedToken));
    }
}
