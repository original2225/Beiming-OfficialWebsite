package dev.beiming.auth;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionServiceTest {
  private JdbcTemplate jdbc;
  private SessionService sessions;

  @BeforeEach
  void setUp() {
    var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:session-service-test-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    jdbc = new JdbcTemplate(dataSource);
    sessions = new SessionService(jdbc, 1);
    jdbc.execute("""
      create table beiming_users (
        id varchar(64) primary key,
        name varchar(120) not null,
        email varchar(255) not null unique,
        password_hash text not null,
        password_salt text not null,
        role varchar(32) not null,
        status varchar(32) not null,
        created_at bigint not null,
        updated_at bigint not null,
        last_login_at bigint not null
      )
    """);
    jdbc.execute("""
      create table beiming_sessions (
        token_hash varchar(128) primary key,
        user_id varchar(64) not null references beiming_users(id) on delete cascade,
        created_at bigint not null,
        expires_at bigint not null
      )
    """);
    insertUser("user-1", UserStatus.ACTIVE);
  }

  @Test
  void createsOpaqueTokenAndResolvesActiveUser() {
    var user = user("user-1", UserStatus.ACTIVE);

    var token = sessions.createSession(user);
    var resolved = sessions.requireUser(token);

    assertThat(token).isNotBlank();
    assertThat(jdbc.queryForObject("select count(*) from beiming_sessions where token_hash = ?", Integer.class, token)).isZero();
    assertThat(resolved.id()).isEqualTo("user-1");
  }

  @Test
  void revokesSessionsByTokenAndUserId() {
    var user = user("user-1", UserStatus.ACTIVE);
    var firstToken = sessions.createSession(user);
    var secondToken = sessions.createSession(user);

    sessions.logout(firstToken);
    assertThatThrownBy(() -> sessions.requireUser(firstToken)).isInstanceOf(ApiException.class);
    assertThat(sessions.requireUser(secondToken).id()).isEqualTo("user-1");

    sessions.revokeUserSessions("user-1");
    assertThatThrownBy(() -> sessions.requireUser(secondToken)).isInstanceOf(ApiException.class);
  }

  private void insertUser(String id, UserStatus status) {
    var now = System.currentTimeMillis();
    jdbc.update(
      """
        insert into beiming_users
        (id, name, email, password_hash, password_salt, role, status, created_at, updated_at, last_login_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      id,
      "Owner",
      id + "@example.com",
      "hash",
      "salt",
      UserRole.SUPER_ADMIN.name(),
      status.name(),
      now,
      now,
      now
    );
  }

  private UserRecord user(String id, UserStatus status) {
    var now = System.currentTimeMillis();
    return new UserRecord(id, "Owner", id + "@example.com", "hash", "salt", UserRole.SUPER_ADMIN.name(), status.name(), now, now, now);
  }
}
