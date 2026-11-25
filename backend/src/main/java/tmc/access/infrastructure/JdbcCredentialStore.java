package tmc.access.infrastructure;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import tmc.access.domain.port.Credential;
import tmc.access.domain.port.CredentialReader;
import tmc.access.domain.port.CredentialWriter;
public final class JdbcCredentialStore implements CredentialReader, CredentialWriter {
 private final JdbcTemplate jdbc;
 public JdbcCredentialStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public void seedIfMissing(Credential credential) {
   try {
     jdbc.update(
         "INSERT INTO credential(credential_id,employee_id,version,active,device_hash) VALUES(?,?,?,?,?)",
         credential.id(), credential.employeeId(), credential.version(), credential.active(), credential.deviceHash()
     );
   } catch (DuplicateKeyException existing) {
     // Preserve revocation and version from the previous run.
   }
 }
 @Override public Optional<Credential> getCredential(String id){return jdbc.query("SELECT * FROM credential WHERE credential_id=?",rs->rs.next()?Optional.of(new Credential(rs.getString("credential_id"),rs.getString("employee_id"),rs.getInt("version"),rs.getBoolean("active"),rs.getString("device_hash"))):Optional.empty(),id);}
 @Override public void save(Credential c){int n=jdbc.update("UPDATE credential SET employee_id=?,version=?,active=?,device_hash=? WHERE credential_id=?",c.employeeId(),c.version(),c.active(),c.deviceHash(),c.id());if(n==0){try{jdbc.update("INSERT INTO credential(credential_id,employee_id,version,active,device_hash) VALUES(?,?,?,?,?)",c.id(),c.employeeId(),c.version(),c.active(),c.deviceHash());}catch(DuplicateKeyException race){save(c);}}}
}
