package games.beiming.website.auth.vo;

public class AuthTokenVO {

    private String token;
    private AuthUserVO user;

    public AuthTokenVO() {
    }

    public AuthTokenVO(String token, AuthUserVO user) {
        this.token = token;
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public AuthUserVO getUser() {
        return user;
    }

    public void setUser(AuthUserVO user) {
        this.user = user;
    }
}
