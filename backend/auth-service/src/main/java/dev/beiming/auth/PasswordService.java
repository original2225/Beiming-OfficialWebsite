package dev.beiming.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class PasswordService {
  private static final SecureRandom RANDOM = new SecureRandom();

  PasswordHash hash(String password) {
    var salt = randomSalt();
    return new PasswordHash(hash(password, salt), salt);
  }

  boolean matches(String password, String passwordHash, String passwordSalt) {
    return MessageDigest.isEqual(
      hash(password, passwordSalt).getBytes(StandardCharsets.UTF_8),
      passwordHash.getBytes(StandardCharsets.UTF_8)
    );
  }

  private String hash(String password, String salt) {
    try {
      var spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), 120_000, 256);
      return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
    } catch (Exception error) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "密码哈希失败");
    }
  }

  private String randomSalt() {
    var value = new byte[18];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
