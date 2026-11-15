package db.migration;

import java.sql.Connection;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve legacy unknown-UID runs while allowing a newly observed UID its own history. */
public class V18__workload_uid_identity extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    Connection connection=context.getConnection();
    String quote=connection.getMetaData().getIdentifierQuoteString().trim();
    String table="ops_workload_run";
    try(var statement=connection.createStatement()) {
      // Keep a covering server_id index in place while MySQL removes the legacy foreign-key index.
      statement.execute("CREATE UNIQUE INDEX uk_workload_uid_identity ON "+table+" (server_id,root_pid,process_started_at,linux_uid)");
      if(connection.getMetaData().getDatabaseProductName().equals("H2")) {
        var names=new ArrayList<String>();
        try(var rows=statement.executeQuery("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE LOWER(TABLE_NAME)='ops_workload_run' AND CONSTRAINT_TYPE='UNIQUE' AND LOWER(CONSTRAINT_NAME)<>'uk_workload_uid_identity'")) {
          while(rows.next()) names.add(rows.getString(1));
        }
        for(String name:names) statement.execute("ALTER TABLE "+table+" DROP CONSTRAINT "+quote+name+quote);
      } else {
        Map<String,Set<String>> indices=new LinkedHashMap<>();
        try(var rows=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,"ops_workload_run",true,false)) {
          while(rows.next()) {
            String name=rows.getString("INDEX_NAME"),column=rows.getString("COLUMN_NAME");
            if(name!=null && column!=null) indices.computeIfAbsent(name,k->new HashSet<>()).add(column.toLowerCase(Locale.ROOT));
          }
        }
        for(var entry:indices.entrySet()) {
          if(entry.getValue().equals(Set.of("server_id","root_pid","process_started_at")))
            statement.execute("ALTER TABLE "+table+" DROP INDEX "+quote+entry.getKey()+quote);
        }
      }
    }
  }
}
