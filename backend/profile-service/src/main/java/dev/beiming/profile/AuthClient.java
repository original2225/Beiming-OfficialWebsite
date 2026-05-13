package dev.beiming.profile;

public interface AuthClient {
  CurrentUserView requireUser(String authorization);

  CurrentUserView optionalUser(String authorization);
}
