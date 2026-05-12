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
  ApiEnvelope<LoginResponse> register(@RequestBody RegisterRequest body) {
    return ApiEnvelope.ok(auth.register(body));
  }

  @PostMapping("/api/auth/login")
  ApiEnvelope<LoginResponse> login(@RequestBody LoginRequest body) {
    return ApiEnvelope.ok(auth.login(body));
  }

  @GetMapping("/api/auth/me")
  ApiEnvelope<PublicUserView> me(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
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
    @RequestBody ChangePasswordRequest body
  ) {
    return ApiEnvelope.ok(auth.changePassword(bearer(authorization), body));
  }

  @GetMapping("/api/auth/validate")
  ApiEnvelope<Map<String, Object>> validate(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(auth.validate(bearer(authorization)));
  }

  @GetMapping("/api/users")
  ApiEnvelope<List<PublicUserView>> users(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(auth.publicUsers(bearer(authorization)));
  }

  @GetMapping("/api/users/{userId}")
  ApiEnvelope<PublicUserView> user(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String userId
  ) {
    return ApiEnvelope.ok(auth.publicUser(bearer(authorization), userId));
  }

  @PostMapping("/api/invite-codes")
  ApiEnvelope<InviteCodeView> createInviteCode(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestBody CreateInviteCodeRequest body
  ) {
    return ApiEnvelope.ok(auth.createInviteCode(bearer(authorization), body));
  }

  @GetMapping("/api/invite-codes")
  ApiEnvelope<List<InviteCodeView>> inviteCodes(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(auth.inviteCodes(bearer(authorization)));
  }

  @PostMapping("/api/invite-codes/{inviteCodeId}/disable")
  ApiEnvelope<InviteCodeView> disableInviteCode(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String inviteCodeId
  ) {
    return ApiEnvelope.ok(auth.disableInviteCode(bearer(authorization), inviteCodeId));
  }

  @PatchMapping("/api/users/{userId}")
  ApiEnvelope<PublicUserView> updateUser(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String userId,
    @RequestBody UpdateUserRequest body
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
