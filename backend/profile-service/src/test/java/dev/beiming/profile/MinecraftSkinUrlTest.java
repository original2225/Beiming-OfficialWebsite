package dev.beiming.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinecraftSkinUrlTest {
  @Test
  void uuidTakesPriorityWhenPresent() {
    assertThat(MinecraftSkinUrl.resolve("Steve", "uuid-1")).isEqualTo("https://minotar.net/skin/uuid-1");
  }

  @Test
  void minecraftIdIsUsedWhenUuidIsBlank() {
    assertThat(MinecraftSkinUrl.resolve("Steve", "")).isEqualTo("https://minotar.net/skin/Steve");
  }

  @Test
  void blankIdentityReturnsEmptyUrl() {
    assertThat(MinecraftSkinUrl.resolve(" ", null)).isEmpty();
  }
}
