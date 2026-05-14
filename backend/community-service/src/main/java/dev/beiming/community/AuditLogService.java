package dev.beiming.community;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditLogService {
  private final AuditLogRepository auditLogs;
  private final AuthClient auth;

  AuditLogService(AuditLogRepository auditLogs, AuthClient auth) {
    this.auditLogs = auditLogs;
    this.auth = auth;
  }

  void record(CurrentUserView actor, String action, String targetType, String targetId, String detail) {
    auditLogs.insert(new AuditLogRecord(
      "audit-" + UUID.randomUUID(),
      actor.id(),
      CommunityRules.clean(actor.name()),
      action,
      targetType,
      targetId,
      CommunityRules.clean(detail),
      CommunityRules.now()
    ));
  }

  PageResult<AuditLogView> list(String authorization, int page, int pageSize) {
    var user = auth.requireUser(authorization);
    CommunityRules.requireAdmin(user);
    var normalizedPage = CommunityRules.normalizePage(page);
    var normalizedSize = CommunityRules.normalizePageSize(pageSize);
    var items = auditLogs.list(normalizedPage, normalizedSize).stream().map(AuditLogView::fromRecord).toList();
    return new PageResult<>(items, normalizedPage, normalizedSize, auditLogs.count());
  }
}
