package dev.beiming.auth;

public record CloudDriveRecord(
  String id,
  String userId,
  String provider,
  String displayName,
  String accountName,
  String driveId,
  String rootItemId,
  String accessToken,
  String refreshToken,
  long tokenExpiresAt,
  String authMode,
  long createdAt,
  long updatedAt
) {
  CloudDriveRecord normalized() {
    return new CloudDriveRecord(
      string(id),
      string(userId),
      string(provider).isBlank() ? "onedrive" : string(provider),
      string(displayName),
      string(accountName),
      string(driveId),
      string(rootItemId),
      string(accessToken),
      string(refreshToken),
      tokenExpiresAt,
      string(authMode).isBlank() ? "beiming" : string(authMode),
      createdAt,
      updatedAt
    );
  }

  private String string(String value) {
    return value == null ? "" : value.trim();
  }
}
