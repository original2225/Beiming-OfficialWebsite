package dev.beiming.profile;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ProfileController {
  private final ProfileService profiles;

  ProfileController(ProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping("/health")
  ApiEnvelope<Map<String, Object>> health() {
    return ApiEnvelope.ok(Map.of("service", "beiming-profile-service"));
  }

  @GetMapping("/api/profile/me")
  ApiEnvelope<MemberProfileView> me(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(profiles.me(authorization));
  }

  @PutMapping("/api/profile/me")
  ApiEnvelope<MemberProfileView> updateMe(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestBody ProfileUpsertRequest body
  ) {
    return ApiEnvelope.ok(profiles.upsertMe(authorization, body));
  }

  @GetMapping("/api/profile/members")
  ApiEnvelope<PageResult<MemberProfileView>> members(
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize,
    @RequestParam(defaultValue = "") String q
  ) {
    return ApiEnvelope.ok(profiles.publicMembers(page, pageSize, q));
  }

  @GetMapping("/api/profile/members/{profileId}")
  ApiEnvelope<MemberProfileView> member(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String profileId
  ) {
    return ApiEnvelope.ok(profiles.publicDetail(authorization, profileId));
  }

  @GetMapping("/api/profile/admin/members")
  ApiEnvelope<PageResult<MemberProfileView>> adminMembers(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize,
    @RequestParam(defaultValue = "") String q
  ) {
    return ApiEnvelope.ok(profiles.adminMembers(authorization, page, pageSize, q));
  }

  @PutMapping("/api/profile/admin/members/{profileId}")
  ApiEnvelope<MemberProfileView> adminUpdate(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String profileId,
    @RequestBody AdminProfileUpdateRequest body
  ) {
    return ApiEnvelope.ok(profiles.adminUpdate(authorization, profileId, body));
  }
}
