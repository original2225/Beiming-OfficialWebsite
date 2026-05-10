package games.beiming.website.auth.security;

import games.beiming.website.common.security.enums.PermissionLevel;

public class AuthTokenClaims {

    private final Long userId;
    private final String username;
    private final PermissionLevel permissionLevel;

    public AuthTokenClaims(Long userId, String username, PermissionLevel permissionLevel) {
        this.userId = userId;
        this.username = username;
        this.permissionLevel = permissionLevel;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public PermissionLevel getPermissionLevel() {
        return permissionLevel;
    }
}
