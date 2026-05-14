package dev.beiming.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class NotificationEventService {
  private final NotificationEventRepository notificationEventRepository;
  private final NotificationRepository notificationRepository;
  private final NotificationDeliveryRepository notificationDeliveryRepository;
  private final ObjectMapper objectMapper;
  private final String internalToken;

  NotificationEventService(
    NotificationEventRepository notificationEventRepository,
    NotificationRepository notificationRepository,
    NotificationDeliveryRepository notificationDeliveryRepository,
    ObjectMapper objectMapper,
    @Value("${beiming.internal.token:}") String internalToken
  ) {
    this.notificationEventRepository = notificationEventRepository;
    this.notificationRepository = notificationRepository;
    this.notificationDeliveryRepository = notificationDeliveryRepository;
    this.objectMapper = objectMapper;
    this.internalToken = internalToken == null ? "" : internalToken.trim();
  }

  @Transactional
  CreateNotificationEventResponse createEvent(String providedToken, CreateNotificationEventRequest request) {
    validateInternalToken(providedToken);
    if (request == null) throw new ApiException(HttpStatus.BAD_REQUEST, "通知事件不能为空");
    if (sameUser(request.actorUserId(), request.recipientUserId())) {
      return new CreateNotificationEventResponse(false, false);
    }

    var now = System.currentTimeMillis();
    var payloadJson = toJson(request.payload());
    var eventId = newId("event");
    var inserted = notificationEventRepository.insert(new NotificationEventRecord(
      eventId,
      clean(request.eventKey()),
      clean(request.eventType()),
      clean(request.sourceService()),
      clean(request.sourceId()),
      clean(request.actorUserId()),
      clean(request.actorDisplayName()),
      clean(request.actorAvatarUrl()),
      clean(request.recipientUserId()),
      clean(request.targetType()),
      clean(request.targetId()),
      clean(request.title()),
      clean(request.body()),
      clean(request.actionUrl()),
      payloadJson,
      now
    ));
    if (!inserted) return new CreateNotificationEventResponse(false, true);

    var notificationId = newId("notification");
    notificationRepository.insert(new NotificationRecord(
      notificationId,
      eventId,
      clean(request.recipientUserId()),
      clean(request.eventType()),
      "UNREAD",
      clean(request.title()),
      clean(request.body()),
      clean(request.actorUserId()),
      clean(request.actorDisplayName()),
      clean(request.actorAvatarUrl()),
      clean(request.targetType()),
      clean(request.targetId()),
      clean(request.actionUrl()),
      payloadJson,
      now,
      0L,
      0L
    ));
    notificationDeliveryRepository.insert(new NotificationDeliveryRecord(
      newId("delivery"),
      notificationId,
      clean(request.recipientUserId()),
      "IN_APP",
      "DELIVERED",
      1,
      "",
      now,
      now
    ));
    return new CreateNotificationEventResponse(true, false);
  }

  private void validateInternalToken(String providedToken) {
    if (internalToken.isBlank()) return;
    if (!internalToken.equals(providedToken == null ? "" : providedToken.trim())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "内部调用凭证无效");
    }
  }

  private boolean sameUser(String actorUserId, String recipientUserId) {
    return clean(actorUserId).equals(clean(recipientUserId));
  }

  private String toJson(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (JsonProcessingException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "通知载荷格式错误");
    }
  }

  private String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private String newId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
