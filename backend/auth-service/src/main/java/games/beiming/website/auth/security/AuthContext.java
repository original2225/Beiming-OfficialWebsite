package games.beiming.website.auth.security;

public final class AuthContext {

    private static final ThreadLocal<AuthTokenClaims> CURRENT_USER = new ThreadLocal<AuthTokenClaims>();

    private AuthContext() {
    }

    public static void setCurrentUser(AuthTokenClaims currentUser) {
        CURRENT_USER.set(currentUser);
    }

    public static AuthTokenClaims getCurrentUser() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
