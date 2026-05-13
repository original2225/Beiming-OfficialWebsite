package dev.beiming.profile;

final class MinecraftSkinUrl {
  private MinecraftSkinUrl() {
  }

  static String resolve(String minecraftId, String minecraftUuid) {
    var uuid = clean(minecraftUuid);
    if (!uuid.isBlank()) return "https://minotar.net/skin/" + uuid;
    var id = clean(minecraftId);
    if (!id.isBlank()) return "https://minotar.net/skin/" + id;
    return "";
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
