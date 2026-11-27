import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Copies an offline H2 workspace into an empty PostgreSQL database and verifies every copied field. */
class CopyWorkplaceDatabase {
  private static final List<String> TABLES = List.of(
      "audit_lock", "credential", "station_registry", "workplace_employee", "workplace_account",
      "workplace_session", "workplace_login_history", "workplace_browser_device", "device_binding",
      "access_attempt", "gate_incident_review", "central_sync_lock", "central_access_event", "central_token_use", "used_token", "edge_outbox", "idempotency_request", "edge_audit",
      "employee_presence", "attendance_record", "timesheet_day_lock", "timesheet_adjustment",
      "daily_timesheet", "timesheet_publication", "hr_export_batch");

  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !args[0].endsWith(".mv.db") || !args[1].startsWith("jdbc:postgresql:")) {
      throw new IllegalArgumentException("Pass H2 .mv.db path, PostgreSQL JDBC URL, backup path and schema.sql path.");
    }
    Path source = Path.of(args[0]).toRealPath();
    Path backup = Path.of(args[2]).toAbsolutePath();
    String h2 = source.toString().replace('\\', '/').replaceFirst("\\.mv\\.db$", "");
    try (var input = DriverManager.getConnection("jdbc:h2:file:" + h2 + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r",
        System.getenv().getOrDefault("TMC_H2_USERNAME", "sa"), System.getenv().getOrDefault("TMC_H2_PASSWORD", ""));
        var output = DriverManager.getConnection(args[1], required("TMC_DB_USERNAME"), required("TMC_DB_PASSWORD"))) {
      var sourceTables = sourceTables(input);
      var required = new LinkedHashSet<>(TABLES);
      required.removeAll(List.of("gate_incident_review", "central_sync_lock", "central_access_event", "central_token_use"));
      if (!sourceTables.containsAll(required) || !Set.copyOf(TABLES).containsAll(sourceTables)) {
        throw new IllegalStateException("H2 has unexpected or missing tables. Check its schema before copying.");
      }
      Files.copy(source, backup);
      System.out.println("H2 backup: " + backup);
      output.setAutoCommit(false);
      try {
        createSchema(output, Path.of(args[3]));
        for (String table : TABLES) {
          if (!seeded(table) && queryCount(output, table) != 0) {
            throw new IllegalStateException("PostgreSQL target must be empty. Found existing rows in " + table);
          }
          long rows = sourceTables.contains(table) ? copy(input, output, table) : 0;
          System.out.printf("%s: %d rows verified%n", table, rows);
        }
        try (var statement = output.createStatement()) {
          statement.executeQuery("""
              SELECT setval(pg_get_serial_sequence('edge_audit','id'), COALESCE(MAX(id),1), MAX(id) IS NOT NULL)
              FROM edge_audit
              """).close();
        }
        output.commit();
      } catch (Exception failed) {
        output.rollback();
        throw failed;
      }
    }
  }

  private static Set<String> sourceTables(Connection input) throws SQLException {
    var result = new LinkedHashSet<String>();
    try (var statement = input.createStatement();
        var rows = statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='PUBLIC'")) {
      while (rows.next()) result.add(rows.getString(1).toLowerCase(Locale.ROOT));
    }
    return result;
  }

  private static String required(String environment) {
    String value = System.getenv(environment);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + environment);
    return value;
  }

  private static void createSchema(Connection output, Path schema) throws Exception {
    try (var statement = output.createStatement()) {
      for (String command : Files.readString(schema).split(";")) {
        if (!command.isBlank()) statement.execute(command);
      }
    }
  }

  private static long copy(Connection input, Connection output, String table) throws Exception {
    var expected = new ArrayList<String>();
    String columns;
    try (var source = input.createStatement(); var rows = source.executeQuery("SELECT * FROM " + table)) {
      var metadata = rows.getMetaData();
      int width = metadata.getColumnCount();
      var names = new ArrayList<String>(width);
      for (int column = 1; column <= width; column++) {
        names.add(metadata.getColumnName(column).toLowerCase(Locale.ROOT));
      }
      columns = String.join(",", names);
      String bind = String.join(",", Collections.nCopies(width, "?"));
      try (var insert = output.prepareStatement("INSERT INTO " + table + "(" + columns + ") VALUES (" + bind + ")")) {
        while (rows.next()) {
          var values = values(rows);
          expected.add(hash(values));
          if (seeded(table)) continue;
          for (int index = 0; index < values.size(); index++) insert.setObject(index + 1, values.get(index));
          insert.addBatch();
          if (expected.size() % 250 == 0) insert.executeBatch();
        }
        if (!seeded(table)) insert.executeBatch();
      }
    }
    var actual = new ArrayList<String>();
    try (var target = output.createStatement(); var rows = target.executeQuery("SELECT " + columns + " FROM " + table)) {
      while (rows.next()) actual.add(hash(values(rows)));
    }
    Collections.sort(expected);
    Collections.sort(actual);
    if (!actual.equals(expected)) throw new IllegalStateException("Rows differ after copy in " + table);
    return actual.size();
  }

  private static boolean seeded(String table) {
    return "audit_lock".equals(table) || "central_sync_lock".equals(table);
  }

  private static long queryCount(Connection database, String table) throws SQLException {
    try (var statement = database.createStatement(); var row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      row.next();
      return row.getLong(1);
    }
  }

  private static List<Object> values(ResultSet row) throws SQLException {
    var metadata = row.getMetaData();
    var result = new ArrayList<>(metadata.getColumnCount());
    for (int column = 1; column <= metadata.getColumnCount(); column++) {
      int type = metadata.getColumnType(column);
      result.add(switch (type) {
        case Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP -> row.getObject(column, OffsetDateTime.class);
        case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB -> row.getString(column);
        default -> row.getObject(column);
      });
    }
    return result;
  }

  private static String hash(List<Object> values) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    for (Object value : values) {
      if (value == null) {
        digest.update((byte) 0);
      } else {
        String canonical = value instanceof OffsetDateTime date ? date.toInstant().toString() : value.toString();
        byte[] data = canonical.getBytes(StandardCharsets.UTF_8);
        digest.update((data.length + ":").getBytes(StandardCharsets.US_ASCII));
        digest.update(data);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
