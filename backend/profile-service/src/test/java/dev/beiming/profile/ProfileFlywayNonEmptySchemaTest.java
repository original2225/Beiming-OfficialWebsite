package dev.beiming.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:profile-nonempty;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;INIT=CREATE TABLE IF NOT EXISTS existing_auth_table(id INT)",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "beiming.services.auth-url=http://127.0.0.1:8792"
})
class ProfileFlywayNonEmptySchemaTest {
  @Autowired
  JdbcTemplate jdbc;

  @Test
  void migrationsRunWhenSharedDatabaseAlreadyHasOtherServiceTables() {
    assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class)).isGreaterThan(0);
    assertThat(jdbc.queryForObject("select count(*) from beiming_member_profiles", Integer.class)).isZero();
  }
}
