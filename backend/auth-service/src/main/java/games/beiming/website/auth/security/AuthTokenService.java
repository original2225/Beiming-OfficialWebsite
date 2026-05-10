package games.beiming.website.auth.security;

import games.beiming.website.auth.exception.AuthBusinessException;
import games.beiming.website.common.core.result.ErrorCode;
import games.beiming.website.common.security.enums.PermissionLevel;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class AuthTokenService {

    private final SecretKey secretKey;
    private final long ttlSeconds;

    public AuthTokenService(
            @Value("${auth.jwt.secret:beiming-local-development-secret}") String secret,
            @Value("${auth.jwt.ttl-seconds:7200}") long ttlSeconds) {
        this.secretKey = Keys.hmacShaKeyFor(normalizeSecret(secret).getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String createToken(Long userId, String username, PermissionLevel permissionLevel) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + ttlSeconds * 1000L);
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .claim("permissionLevel", permissionLevel.name())
                .setIssuedAt(now)
                .setExpiration(expiresAt)
                .signWith(secretKey)
                .compact();
    }

    public AuthTokenClaims parseToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new AuthBusinessException(ErrorCode.UNAUTHORIZED, "invalid token");
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return new AuthTokenClaims(
                    Long.valueOf(claims.getSubject()),
                    claims.get("username", String.class),
                    PermissionLevel.valueOf(claims.get("permissionLevel", String.class))
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw new AuthBusinessException(ErrorCode.UNAUTHORIZED, "invalid token");
        }
    }

    private String normalizeSecret(String secret) {
        if (secret == null || secret.length() < 32) {
            return "beiming-local-development-secret-32";
        }
        return secret;
    }
}
