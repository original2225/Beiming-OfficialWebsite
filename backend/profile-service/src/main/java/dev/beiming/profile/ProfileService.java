package dev.beiming.profile;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ProfileService {
  private final ProfileRepository profiles;
  private final AuthClient auth;

  ProfileService(ProfileRepository profiles, AuthClient auth) {
    this.profiles = profiles;
    this.auth = auth;
  }

  MemberProfileView me(String authorization) {
    var user = auth.requireUser(authorization);
    return profiles.findByUserId(user.id())
      .map(record -> MemberProfileView.fromRecord(record, true))
      .orElseGet(() -> defaultProfile(user));
  }

  synchronized MemberProfileView upsertMe(String authorization, ProfileUpsertRequest request) {
    var user = auth.requireUser(authorization);
    var existing = profiles.findByUserId(user.id());
    var now = now();
    var minecraftId = cleanRequired(request.minecraftId(), "Minecraft ID 不能为空");
    var minecraftIdKey = minecraftId.toLowerCase();
    ensureMinecraftIdAvailable(minecraftIdKey, existing.map(MemberProfileRecord::id).orElse(""));
    var visibility = ProfileVisibility.parse(request.visibility()).name();
    var requestedDisplayName = clean(request.displayName());
    var displayName = requestedDisplayName.isBlank() ? clean(user.name()) : requestedDisplayName;
    if (displayName.isBlank()) displayName = minecraftId;
    var finalDisplayName = displayName;
    var next = existing.map(record -> new MemberProfileRecord(
      record.id(),
      record.userId(),
      finalDisplayName,
      clean(request.bio()),
      clean(request.avatarUrl()),
      minecraftId,
      minecraftIdKey,
      clean(request.minecraftUuid()),
      record.memberGroup(),
      record.memberStatus(),
      visibility,
      record.joinedAt(),
      record.createdAt(),
      now,
      record.adminNote(),
      record.featured(),
      record.createdBy(),
      user.id()
    )).orElseGet(() -> new MemberProfileRecord(
      "profile-" + UUID.randomUUID().toString().substring(0, 8),
      user.id(),
      finalDisplayName,
      clean(request.bio()),
      clean(request.avatarUrl()),
      minecraftId,
      minecraftIdKey,
      clean(request.minecraftUuid()),
      MemberGroup.MEMBER.name(),
      MemberStatus.ACTIVE.name(),
      visibility,
      now,
      now,
      now,
      "",
      false,
      user.id(),
      user.id()
    ));
    existing.ifPresentOrElse(ignored -> profiles.update(next), () -> profiles.insert(next));
    return MemberProfileView.fromRecord(next, true);
  }

  PageResult<MemberProfileView> publicMembers(int page, int pageSize, String q) {
    var normalizedPage = normalizePage(page);
    var normalizedSize = normalizePageSize(pageSize);
    var items = profiles.publicProfiles(normalizedPage, normalizedSize, q).stream()
      .map(record -> MemberProfileView.fromRecord(record, true))
      .toList();
    return new PageResult<>(items, normalizedPage, normalizedSize, profiles.countPublicProfiles(q));
  }

  MemberProfileView publicDetail(String authorization, String profileId) {
    var profile = profiles.findById(profileId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "成员档案不存在"));
    if (isPublic(profile)) return MemberProfileView.fromRecord(profile, true);
    var user = auth.optionalUser(authorization);
    if (user != null && (user.isAdmin() || user.id().equals(profile.userId()))) {
      return MemberProfileView.fromRecord(profile, true);
    }
    throw new ApiException(HttpStatus.NOT_FOUND, "成员档案不存在");
  }

  PageResult<MemberProfileView> adminMembers(String authorization, int page, int pageSize, String q) {
    requireAdmin(authorization);
    var normalizedPage = normalizePage(page);
    var normalizedSize = normalizePageSize(pageSize);
    var items = profiles.allProfiles(normalizedPage, normalizedSize, q).stream()
      .map(record -> MemberProfileView.fromRecord(record, true))
      .toList();
    return new PageResult<>(items, normalizedPage, normalizedSize, profiles.countAllProfiles(q));
  }

  synchronized MemberProfileView adminUpdate(String authorization, String profileId, AdminProfileUpdateRequest request) {
    var actor = requireAdmin(authorization);
    var current = profiles.findById(profileId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "成员档案不存在"));
    var minecraftId = request.minecraftId() == null ? current.minecraftId() : cleanRequired(request.minecraftId(), "Minecraft ID 不能为空");
    var minecraftIdKey = minecraftId.toLowerCase();
    ensureMinecraftIdAvailable(minecraftIdKey, current.id());
    var next = new MemberProfileRecord(
      current.id(),
      current.userId(),
      request.displayName() == null ? current.displayName() : cleanRequired(request.displayName(), "展示昵称不能为空"),
      request.bio() == null ? current.bio() : clean(request.bio()),
      request.avatarUrl() == null ? current.avatarUrl() : clean(request.avatarUrl()),
      minecraftId,
      minecraftIdKey,
      request.minecraftUuid() == null ? current.minecraftUuid() : clean(request.minecraftUuid()),
      request.memberGroup() == null ? current.memberGroup() : MemberGroup.parse(request.memberGroup()).name(),
      request.memberStatus() == null ? current.memberStatus() : MemberStatus.parse(request.memberStatus()).name(),
      request.visibility() == null ? current.visibility() : ProfileVisibility.parse(request.visibility()).name(),
      request.joinedAt() == null ? current.joinedAt() : Math.max(0, request.joinedAt()),
      current.createdAt(),
      now(),
      request.adminNote() == null ? current.adminNote() : clean(request.adminNote()),
      request.featured() == null ? current.featured() : request.featured(),
      current.createdBy(),
      actor.id()
    );
    profiles.update(next);
    return MemberProfileView.fromRecord(next, true);
  }

  private MemberProfileView defaultProfile(CurrentUserView user) {
    var now = now();
    return MemberProfileView.fromRecord(new MemberProfileRecord(
      "",
      user.id(),
      clean(user.name()),
      "",
      "",
      "",
      "",
      "",
      MemberGroup.MEMBER.name(),
      MemberStatus.ACTIVE.name(),
      ProfileVisibility.PRIVATE.name(),
      now,
      now,
      now,
      "",
      false,
      "",
      ""
    ), false);
  }

  private CurrentUserView requireAdmin(String authorization) {
    var user = auth.requireUser(authorization);
    if (!user.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "没有管理员权限");
    return user;
  }

  private boolean isPublic(MemberProfileRecord profile) {
    return ProfileVisibility.PUBLIC.name().equals(profile.visibility()) && MemberStatus.ACTIVE.name().equals(profile.memberStatus());
  }

  private void ensureMinecraftIdAvailable(String minecraftIdKey, String currentProfileId) {
    profiles.findByMinecraftIdKey(minecraftIdKey).ifPresent(existing -> {
      if (!existing.id().equals(currentProfileId)) throw new ApiException(HttpStatus.CONFLICT, "Minecraft ID 已经被绑定");
    });
  }

  private int normalizePage(int page) {
    return Math.max(page, 1);
  }

  private int normalizePageSize(int pageSize) {
    if (pageSize <= 0) return 20;
    return Math.min(pageSize, 100);
  }

  private String cleanRequired(String value, String message) {
    var result = clean(value);
    if (result.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, message);
    return result;
  }

  private String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private long now() {
    return Instant.now().toEpochMilli();
  }
}
