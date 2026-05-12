package dev.beiming.auth;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class AuthController {
  private final AuthService auth;

  AuthController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping("/health")
  ApiEnvelope<Map<String, Object>> health() {
    return ApiEnvelope.ok(Map.of("service", "beiming-auth-service"));
  }

  @PostMapping("/api/auth/register")
  ApiEnvelope<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
    return ApiEnvelope.ok(auth.register(body));
  }

  @PostMapping("/api/auth/login")
  ApiEnvelope<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
    return ApiEnvelope.ok(auth.login(body));
  }

  @GetMapping("/api/auth/me")
  ApiEnvelope<Map<String, Object>> me(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(auth.me(bearer(authorization)));
  }

  @PostMapping("/api/auth/logout")
  ApiEnvelope<Map<String, Object>> logout(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    auth.logout(bearer(authorization));
    return ApiEnvelope.ok(Map.of("loggedOut", true));
  }

  @PostMapping("/api/auth/logout-all")
  ApiEnvelope<Map<String, Object>> logoutAll(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(auth.logoutAll(bearer(authorization)));
  }

  @PostMapping("/api/auth/change-password")
  ApiEnvelope<Map<String, Object>> changePassword(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestBody Map<String, Object> body
  ) {
    return ApiEnvelope.ok(auth.changePassword(bearer(authorization), body));
  }

  @GetMapping("/api/auth/validate")
  ApiEnvelope<Map<String, Object>> validate(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(auth.validate(bearer(authorization)));
  }

  @GetMapping("/api/users")
  ApiEnvelope<List<Map<String, Object>>> users(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(auth.publicUsers(bearer(authorization)));
  }

  @GetMapping("/api/users/{userId}")
  ApiEnvelope<Map<String, Object>> user(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String userId
  ) {
    return ApiEnvelope.ok(auth.publicUser(bearer(authorization), userId));
  }

  @PostMapping("/api/invite-codes")
  ApiEnvelope<Map<String, Object>> createInviteCode(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestBody Map<String, Object> body
  ) {
    return ApiEnvelope.ok(auth.createInviteCode(bearer(authorization), body));
  }

  @GetMapping("/api/invite-codes")
  ApiEnvelope<List<Map<String, Object>>> inviteCodes(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(auth.inviteCodes(bearer(authorization)));
  }

  @PostMapping("/api/invite-codes/{inviteCodeId}/disable")
  ApiEnvelope<Map<String, Object>> disableInviteCode(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String inviteCodeId
  ) {
    return ApiEnvelope.ok(auth.disableInviteCode(bearer(authorization), inviteCodeId));
  }

  @PatchMapping("/api/users/{userId}")
  ApiEnvelope<Map<String, Object>> updateUser(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String userId,
    @RequestBody Map<String, Object> body
  ) {
    return ApiEnvelope.ok(auth.updateUser(bearer(authorization), userId, body));
  }

  @PostMapping("/api/users/{userId}/sessions/revoke")
  ApiEnvelope<Map<String, Object>> revokeUserSessions(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String userId
  ) {
    return ApiEnvelope.ok(auth.revokeUserSessions(bearer(authorization), userId));
  }

  private String bearer(String authorization) {
    var value = authorization == null ? "" : authorization.trim();
    return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : "";
  }
}
