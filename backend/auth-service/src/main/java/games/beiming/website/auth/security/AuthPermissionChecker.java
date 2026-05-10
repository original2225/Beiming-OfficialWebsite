package games.beiming.website.auth.security;

import games.beiming.website.auth.exception.AuthBusinessException;
import games.beiming.website.common.core.result.ErrorCode;
import games.beiming.website.common.security.enums.PermissionLevel;
import org.springframework.stereotype.Component;

@Component
public class AuthPermissionChecker {

    public void require(PermissionLevel requiredPermission) {
        AuthTokenClaims currentUser = AuthContext.getCurrentUser();
        if (currentUser == null) {
            throw new AuthBusinessException(ErrorCode.UNAUTHORIZED, "login required");
        }
        if (!hasPermission(currentUser.getPermissionLevel(), requiredPermission)) {
            throw new AuthBusinessException(ErrorCode.FORBIDDEN, "permission denied");
        }
    }

    public boolean hasPermission(PermissionLevel actualPermission, PermissionLevel requiredPermission) {
        return rank(actualPermission) >= rank(requiredPermission);
    }

    private int rank(PermissionLevel permissionLevel) {
        if (permissionLevel == PermissionLevel.OWNER) {
            return 4;
        }
        if (permissionLevel == PermissionLevel.ADMIN) {
            return 3;
        }
        if (permissionLevel == PermissionLevel.HELPER) {
            return 2;
        }
        return 1;
    }
}
