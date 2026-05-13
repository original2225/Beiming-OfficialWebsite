package dev.beiming.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuthFlywayLegacySchemaTest {
  @Autowired
  JdbcTemplate jdbc;

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    var legacySchema = String.join("\\;",
      """
        create table if not exists beiming_users (
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
      """,
      """
        create table if not exists beiming_cloud_drives (
          id varchar(64) primary key,
          user_id varchar(64) not null,
          provider varchar(32) not null,
          display_name varchar(160) not null,
          account_name varchar(255) not null,
          drive_id text not null,
          root_item_id text not null,
          access_token text not null,
          refresh_token text not null,
          token_expires_at bigint not null,
          created_at bigint not null,
          updated_at bigint not null,
          unique(user_id, provider, drive_id)
        )
      """,
      """
        create table if not exists beiming_cloud_oauth_configs (
          id varchar(64) primary key,
          user_id varchar(64) not null,
          provider varchar(32) not null,
          client_id text not null,
          client_secret text not null,
          redirect_uri text not null,
          created_at bigint not null,
          updated_at bigint not null,
          unique(user_id, provider)
        )
      """
    ).replace('\n', ' ');
    registry.add("spring.datasource.url", () -> "jdbc:h2:mem:auth-legacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;INIT=" + legacySchema);
    registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
    registry.add("beiming.data-dir", () -> System.getProperty("java.io.tmpdir"));
    registry.add("beiming.session-ttl-hours", () -> "1");
  }

  @Test
  void flywayAddsCloudDriveColumnsForLegacySchemas() {
    assertThat(columnExists("beiming_cloud_drives", "auth_mode")).isTrue();
    assertThat(columnExists("beiming_cloud_oauth_configs", "cdn_host")).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class)).isGreaterThan(0);
  }

  private boolean columnExists(String tableName, String columnName) {
    Integer count = jdbc.queryForObject(
      """
        select count(*)
        from information_schema.columns
        where table_name = ? and column_name = ?
      """,
      Integer.class,
      tableName,
      columnName
    );
    return count != null && count > 0;
  }
}
