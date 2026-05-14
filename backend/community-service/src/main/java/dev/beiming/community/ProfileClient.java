package dev.beiming.community;

public interface ProfileClient {
  AuthorSnapshot resolve(String authorization, CurrentUserView user);
}
