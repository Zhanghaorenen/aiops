package com.aiops.backend;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import java.sql.*;
import java.util.*;
class SharedClusterMigrationTests {
 private Flyway flyway(String url,String target){return Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target(MigrationVersion.fromVersion(target)).load();}
 private List<String> snapshot(Connection c,String table)throws SQLException{var rows=new ArrayList<String>();try(var s=c.createStatement();var rs=s.executeQuery("SELECT * FROM "+table+" ORDER BY id")){int n=rs.getMetaData().getColumnCount();while(rs.next()){var values=new ArrayList<String>();for(int i=1;i<=n;i++){String column=rs.getMetaData().getColumnName(i);if(!"CLUSTER_ID".equalsIgnoreCase(column))values.add(column+"="+rs.getString(i));}rows.add(values.toString());}}return rows;}
 @Test void upgradePreservesHistoricalRowsAndOwnership()throws Exception{
  String url="jdbc:h2:mem:cluster_upgrade_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";flyway(url,"7").migrate();
  try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
   s.executeUpdate("INSERT INTO sys_user VALUES(1,'historical','hash','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO ops_node(id,node_key,display_name,hostname,status,created_at,updated_at,owner_user_id) VALUES(91,'historical-node','node','host','ONLINE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)");
   s.executeUpdate("INSERT INTO ops_agent VALUES(81,91,'old-agent','"+"a".repeat(64)+"','1','ONLINE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO ops_agent_enroll_token(id,token_hash,expires_at,created_at,issued_by_user_id) VALUES(71,'"+"b".repeat(64)+"',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)");
   s.executeUpdate("INSERT INTO metric_host_sample VALUES(61,91,81,50,100,50,50,100,50,50,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO metric_gpu_snapshot VALUES(51,91,81,'AVAILABLE',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO metric_gpu_sample(id,snapshot_id,node_id,agent_id,gpu_index,gpu_uuid,collected_at,received_at) VALUES(41,51,91,81,0,'GPU-historical',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO metric_gpu_process_sample(id,snapshot_id,node_id,gpu_index,pid,collected_at,received_at) VALUES(31,51,91,0,123,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO knowledge_base VALUES(21,1,'private','manual','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO knowledge_document VALUES(11,21,'gpu.pdf','application/pdf','"+"c".repeat(64)+"',10,'storage','READY',1,'model-v1',2,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO agents VALUES(7,'legacy','host','127.0.0.1','ONLINE','1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.executeUpdate("INSERT INTO metrics VALUES(8,7,'host',1,2,3,4,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   var tables=List.of("sys_user","ops_node","ops_agent","ops_agent_enroll_token","metric_host_sample","metric_gpu_snapshot","metric_gpu_sample","metric_gpu_process_sample","knowledge_base","knowledge_document","agents","metrics");
   var before=new HashMap<String,List<String>>();for(String t:tables)before.put(t,snapshot(c,t));
   flyway(url,"8").migrate();flyway(url,"8").migrate();
   for(String t:tables)assertEquals(before.get(t),snapshot(c,t),t+" historical data changed");
   try(var rs=s.executeQuery("SELECT COUNT(*) FROM ops_node n JOIN ops_cluster c ON n.cluster_id=c.id WHERE c.cluster_name='default-cluster' AND n.id=91 AND n.owner_user_id=1")){rs.next();assertEquals(1,rs.getInt(1));}
   try(var rs=s.executeQuery("SELECT COUNT(*) FROM ops_cluster_member")){rs.next();assertEquals(0,rs.getInt(1));}
   assertThrows(SQLException.class,()->s.executeUpdate("INSERT INTO ops_node(node_key,display_name,hostname,status,created_at,updated_at) VALUES('invalid','invalid','host','ONLINE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"));
   assertThrows(SQLException.class,()->s.executeUpdate("UPDATE ops_node SET cluster_id=999999 WHERE id=91"));
  }
 }
 @Test void emptyDatabaseMigratesToEight()throws Exception{
  String url="jdbc:h2:mem:cluster_empty_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";flyway(url,"8").migrate();
  try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM ops_cluster WHERE cluster_name='default-cluster' AND bootstrap_completed_at IS NULL")){rs.next();assertEquals(1,rs.getInt(1));}
 }
}
