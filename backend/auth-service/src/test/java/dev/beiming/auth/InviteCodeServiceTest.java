package dev.beiming.auth;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InviteCodeServiceTest {
  private JdbcTemplate jdbc;
  private InviteCodeService invites;

  @BeforeEach
  void setUp() {
    var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:invite-code-service-test-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    jdbc = new JdbcTemplate(dataSource);
    invites = new InviteCodeService(jdbc);
    jdbc.execute("""
      create table beiming_invite_codes (
        id varchar(64) primary key,
        code varchar(64) not null unique,
        type varchar(32) not null,
        role varchar(32) not null,
        status varchar(32) not null,
        max_uses int not null,
        used_count int not null,
        expires_at bigint not null,
        created_by varchar(64) not null,
        created_at bigint not null,
        updated_at bigint not null
      )
    """);
    jdbc.execute("""
      create table beiming_invite_code_usages (
        id varchar(64) primary key,
        invite_code_id varchar(64) not null,
        user_id varchar(64) not null,
        used_at bigint not null
      )
    """);
  }

  @Test
  void adminCanCreateMemberInviteButNotAdminInvite() {
    var admin = user("admin-1", UserRole.ADMIN);

    var memberInvite = invites.createInviteCode(admin, new CreateInviteCodeRequest("MEMBER", 2, null));

    assertThat(memberInvite.role()).isEqualTo("MEMBER");
    assertThat(memberInvite.maxUses()).isEqualTo(2);
    assertThatThrownBy(() -> invites.createInviteCode(admin, new CreateInviteCodeRequest("ADMIN", 1, null)))
      .isInstanceOf(ApiException.class);
  }

  @Test
  void consumeInviteCodeAndRecordUsageIncrementsUsedCount() {
    var owner = user("owner-1", UserRole.SUPER_ADMIN);
    var invite = invites.createInviteCode(owner, new CreateInviteCodeRequest("MEMBER", 1, null));

    var role = invites.consumeInviteCode(invite.code());
    invites.recordInviteUsage(invite.code(), "user-1");

    assertThat(role).isEqualTo("MEMBER");
    assertThat(jdbc.queryForObject("select used_count from beiming_invite_codes where code = ?", Integer.class, invite.code())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from beiming_invite_code_usages", Integer.class)).isEqualTo(1);
    assertThatThrownBy(() -> invites.consumeInviteCode(invite.code())).isInstanceOf(ApiException.class);
  }

  private UserRecord user(String id, UserRole role) {
    var now = System.currentTimeMillis();
    return new UserRecord(id, "Owner", id + "@example.com", "hash", "salt", role.name(), UserStatus.ACTIVE.name(), now, now, now);
  }
}
