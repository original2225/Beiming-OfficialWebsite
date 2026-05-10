package games.beiming.website.auth.security;

import games.beiming.website.auth.exception.AuthBusinessException;
import games.beiming.website.common.security.enums.PermissionLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthPermissionAspectTest {

    private final AuthPermissionChecker permissionChecker = new AuthPermissionChecker();

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void allowsHigherPermissionToAccessLowerRequirement() {
        AuthContext.setCurrentUser(new AuthTokenClaims(1L, "owner", PermissionLevel.OWNER));

        assertDoesNotThrow(() -> permissionChecker.require(PermissionLevel.ADMIN));
    }

    @Test
    void rejectsLowerPermissionFromHigherRequirement() {
        AuthContext.setCurrentUser(new AuthTokenClaims(2L, "user", PermissionLevel.USER));

        assertThrows(AuthBusinessException.class, () -> permissionChecker.require(PermissionLevel.ADMIN));
    }
}
