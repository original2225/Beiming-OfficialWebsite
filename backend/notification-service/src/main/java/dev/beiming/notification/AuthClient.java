package dev.beiming.notification;

public interface AuthClient {
  CurrentUserView requireUser(String authorization);

  CurrentUserView optionalUser(String authorization);
}
