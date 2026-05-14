package dev.beiming.community;

public interface AuthClient {
  CurrentUserView requireUser(String authorization);

  CurrentUserView optionalUser(String authorization);
}
