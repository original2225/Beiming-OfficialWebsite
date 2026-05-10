package games.beiming.website.auth.security;

import games.beiming.website.auth.exception.AuthBusinessException;
import games.beiming.website.common.core.result.ErrorCode;
import games.beiming.website.common.security.annotation.RequireAnyPermission;
import games.beiming.website.common.security.annotation.RequirePermission;
import games.beiming.website.common.security.enums.PermissionLevel;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuthPermissionAspect {

    private final AuthPermissionChecker authPermissionChecker;

    public AuthPermissionAspect(AuthPermissionChecker authPermissionChecker) {
        this.authPermissionChecker = authPermissionChecker;
    }

    @Before("@annotation(requirePermission)")
    public void requirePermission(RequirePermission requirePermission) {
        authPermissionChecker.require(requirePermission.value());
    }

    @Before("@annotation(requireAnyPermission)")
    public void requireAnyPermission(RequireAnyPermission requireAnyPermission) {
        AuthTokenClaims currentUser = AuthContext.getCurrentUser();
        if (currentUser == null) {
            throw new AuthBusinessException(ErrorCode.UNAUTHORIZED, "login required");
        }
        for (PermissionLevel permissionLevel : requireAnyPermission.value()) {
            if (authPermissionChecker.hasPermission(currentUser.getPermissionLevel(), permissionLevel)) {
                return;
            }
        }
        throw new AuthBusinessException(ErrorCode.FORBIDDEN, "permission denied");
    }
}
