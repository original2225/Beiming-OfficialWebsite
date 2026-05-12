package dev.beiming.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordServiceTest {
  private final PasswordService passwords = new PasswordService();

  @Test
  void hashesPasswordWithRandomSaltAndVerifiesMatches() {
    var first = passwords.hash("password123");
    var second = passwords.hash("password123");

    assertThat(first.passwordHash()).isNotEqualTo(second.passwordHash());
    assertThat(first.passwordSalt()).isNotEqualTo(second.passwordSalt());
    assertThat(passwords.matches("password123", first.passwordHash(), first.passwordSalt())).isTrue();
    assertThat(passwords.matches("wrongpass", first.passwordHash(), first.passwordSalt())).isFalse();
  }
}
