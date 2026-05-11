package dev.beiming.auth;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

@RestController
public class CloudDriveController {
  private final CloudDriveService cloudDrive;

  CloudDriveController(CloudDriveService cloudDrive) {
    this.cloudDrive = cloudDrive;
  }

  @GetMapping("/api/cloud/onedrive/status")
  ApiEnvelope<Map<String, Object>> status(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(cloudDrive.status(bearer(authorization)));
  }

  @PostMapping("/api/cloud/onedrive/config")
  ApiEnvelope<Map<String, Object>> saveConfig(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestBody Map<String, Object> body
  ) {
    return ApiEnvelope.ok(cloudDrive.saveConfig(bearer(authorization), body));
  }

  @PostMapping("/api/cloud/onedrive/auth")
  ApiEnvelope<Map<String, Object>> startAuth(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestBody(required = false) Map<String, Object> body
  ) {
    return ApiEnvelope.ok(cloudDrive.startAuth(bearer(authorization)));
  }

  @GetMapping("/api/cloud/onedrive/callback")
  RedirectView callback(
    @RequestParam(value = "code", defaultValue = "") String code,
    @RequestParam(value = "state", defaultValue = "") String state,
    @RequestParam(value = "error", defaultValue = "") String error,
    @RequestParam(value = "error_description", defaultValue = "") String errorDescription
  ) {
    return new RedirectView(cloudDrive.completeAuth(code, state, error, errorDescription));
  }

  @PostMapping("/api/cloud/onedrive/connect")
  ApiEnvelope<Map<String, Object>> connect(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestBody Map<String, Object> body
  ) {
    return ApiEnvelope.ok(cloudDrive.connect(bearer(authorization), body));
  }

  @DeleteMapping("/api/cloud/drives/{driveId}")
  ApiEnvelope<Map<String, Object>> disconnect(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String driveId
  ) {
    cloudDrive.disconnect(bearer(authorization), driveId);
    return ApiEnvelope.ok(Map.of("deleted", true));
  }

  @GetMapping("/api/cloud/drives/{driveId}/items")
  ApiEnvelope<Map<String, Object>> list(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String driveId,
    @RequestParam(value = "itemId", defaultValue = "root") String itemId
  ) {
    return ApiEnvelope.ok(cloudDrive.list(bearer(authorization), driveId, itemId));
  }

  @PostMapping("/api/cloud/drives/{driveId}/folders")
  ApiEnvelope<Map<String, Object>> mkdir(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String driveId,
    @RequestBody Map<String, Object> body
  ) {
    return ApiEnvelope.ok(cloudDrive.mkdir(bearer(authorization), driveId, body));
  }

  @PatchMapping("/api/cloud/drives/{driveId}/items/{itemId}")
  ApiEnvelope<Map<String, Object>> rename(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String driveId,
    @PathVariable String itemId,
    @RequestBody Map<String, Object> body
  ) {
    return ApiEnvelope.ok(cloudDrive.rename(bearer(authorization), driveId, itemId, body));
  }

  @DeleteMapping("/api/cloud/drives/{driveId}/items/{itemId}")
  ApiEnvelope<Map<String, Object>> delete(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String driveId,
    @PathVariable String itemId
  ) {
    cloudDrive.delete(bearer(authorization), driveId, itemId);
    return ApiEnvelope.ok(Map.of("deleted", true));
  }

  @GetMapping("/api/cloud/drives/{driveId}/items/{itemId}/download")
  ApiEnvelope<Map<String, Object>> download(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String driveId,
    @PathVariable String itemId
  ) {
    return ApiEnvelope.ok(cloudDrive.download(bearer(authorization), driveId, itemId));
  }

  @PostMapping("/api/cloud/drives/{driveId}/upload-session")
  ApiEnvelope<Map<String, Object>> uploadSession(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String driveId,
    @RequestBody Map<String, Object> body
  ) {
    return ApiEnvelope.ok(cloudDrive.uploadSession(bearer(authorization), driveId, body));
  }

  private String bearer(String authorization) {
    var value = authorization == null ? "" : authorization.trim();
    return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : "";
  }
}
