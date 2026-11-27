package tmc.shared;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseDialect {
  private DatabaseDialect() {}

  public static boolean isPostgres(JdbcTemplate jdbc) {
    return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
        "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
  }
}
