package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.aiops.backend.security.JwtService;
import com.aiops.backend.service.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aiops.checkpoint.enabled=true",
      "aiops.ai-service.service-key=fixture-engineering-only-key-0000000000",
      "spring.datasource.url=jdbc:h2:mem:engineering_recovery;DB_CLOSE_DELAY=-1;MODE=MySQL"
    })
class EngineeringRecoveryTests extends ClusterTestSupport {
  @Autowired InvestigationTaskService tasks;
  @Autowired InvestigationCheckpointService checkpoints;
  @Autowired IncidentService incidents;
  @Autowired AgentAccessService agents;
  @Autowired JwtService jwt;
  @LocalServerPort int port;

  @org.springframework.test.context.DynamicPropertySource
  static void isolatedMysql(org.springframework.test.context.DynamicPropertyRegistry registry) {
    String port = System.getenv("AIOPS_ENGINEERING_MYSQL_PORT");
    if (port == null) return;
    int fixturePort = Integer.parseInt(port);
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:mysql://127.0.0.1:"
                + fixturePort
                + "/engineering?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC");
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.datasource.username", () -> "root");
    registry.add(
        "spring.datasource.password", () -> System.getenv("AIOPS_ENGINEERING_MYSQL_PASSWORD"));
  }

  private long fixtureKb, fixtureDoc, fixtureServer;
  private static final String KEY = "fixture-engineering-only-key-0000000000";

  private long[] enqueue() {
    String name = UUID.randomUUID().toString();
    var node =
        agents.enroll(
            new AgentAccessService.EnrollmentRequest(
                issue(agents).token(),
                name,
                name,
                name,
                null,
                null,
                null,
                null,
                2,
                10000L,
                name,
                "fixture"));
    agents.recordMetric(
        node.agentId(),
        new AgentAccessService.MetricRequest(
            15.,
            10000L,
            4000L,
            10000L,
            2000L,
            Instant.now(),
            "AVAILABLE",
            List.of(
                new AgentAccessService.GpuSampleRequest(
                    0,
                    "GPU-fixture",
                    "fixture",
                    1000L,
                    100L,
                    900L,
                    java.math.BigDecimal.TEN,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of())),
            null,
            Map.of()));
    var incident =
        incidents.create(
            new IncidentService.Create(node.nodeId(), "GPU 训练利用率下降", "缺少训练进度", List.of()));
    long i = ((Number) incident.get("id")).longValue();
    long run;
    var span =
        io.opentelemetry.api.GlobalOpenTelemetry.getTracer("engineering.tests")
            .spanBuilder("test.create")
            .startSpan();
    try (var scope = span.makeCurrent()) {
      run = ((Number) incidents.investigate(i, name).get("id")).longValue();
    } finally {
      span.end();
    }
    return new long[] {node.nodeId(), i, run};
  }

  @Test
  void competingWorkersAndFencedCheckpoint() throws Exception {
    var ids = enqueue();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Map<String, Object>> claim =
          () -> {
            start.await();
            return tasks.claim();
          };
      var a = pool.submit(claim);
      var b = pool.submit(claim);
      start.countDown();
      var claims = java.util.stream.Stream.of(a.get(), b.get()).filter(Objects::nonNull).toList();
      assertEquals(1, claims.size());
      var old = claims.get(0);
      var entry =
          new InvestigationCheckpointService.Entry(
              "c1", null, Map.of("id", "c1"), Map.of("step", 1), null);
      checkpoints.save(ids[2], old.get("lease_token").toString(), entry);
      expire(ids[2]);
      var next = tasks.claim();
      assertNotEquals(old.get("lease_token"), next.get("lease_token"));
      assertThrows(
          ResponseStatusException.class,
          () -> checkpoints.save(ids[2], old.get("lease_token").toString(), entry));
      assertThrows(ResponseStatusException.class, () -> checkpoints.read(ids[2], "other", null));
      assertTrue(
          checkpoints
              .read(ids[2], next.get("lease_token").toString(), null)
              .toString()
              .contains("c1"));
      tasks.complete(old, null, "STALE_WORKER");
      assertEquals("RUNNING", status(ids[2]));
      tasks.complete(next, null, "FIXTURE_DONE");
    } finally {
      pool.shutdownNow();
    }
  }

  private String status(long id) {
    return testJdbc.queryForObject(
        "SELECT status FROM ops_investigation_run WHERE id=?", String.class, id);
  }

  private void expire(long id) {
    testJdbc.update(
        "UPDATE ops_investigation_run SET lease_until=? WHERE id=?",
        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
        id);
  }

  private ProcessBuilder python(String... args) {
    var cmd = new ArrayList<String>();
    cmd.add(System.getenv("AIOPS_ENGINEERING_PYTHON"));
    cmd.addAll(List.of(args));
    var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment()
        .put(
            "PYTHONPATH",
            Path.of("agent/ai-service").toAbsolutePath()
                + java.io.File.pathSeparator
                + Path.of(".").toAbsolutePath());
    if (fixtureDoc != 0) {
      pb.environment().put("FIXTURE_KB", Long.toString(fixtureKb));
      pb.environment().put("FIXTURE_DOCUMENT", Long.toString(fixtureDoc));
      pb.environment().put("FIXTURE_SERVER", Long.toString(fixtureServer));
      pb.environment().put("AIOPS_HYBRID_ENABLED", "true");
    }
    pb.environment().put("AIOPS_BACKEND_URL", "http://127.0.0.1:" + port);
    pb.environment().put("AIOPS_CHECKPOINT_ENABLED", "true");
    pb.environment().put("AIOPS_AI_SERVICE_KEY", KEY);
    pb.environment().put("OTEL_ENABLED", "true");
    pb.environment().put("PYTHONUTF8", "1");
    return pb;
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AIOPS_ENGINEERING_PYTHON", matches = ".+")
  void measureConcurrentFixtureInvestigations() throws Exception {
    var ids = new ArrayList<Long>();
    for (int n = 0; n < 4; n++) ids.add(enqueue()[2]);
    int aiPort;
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      aiPort = socket.getLocalPort();
    }
    Process ai = launch(aiPort, false);
    var pool = Executors.newFixedThreadPool(2);
    try {
      long start = System.nanoTime();
      Callable<Void> drain =
          () -> {
            var w = new InvestigationWorker(tasks, jwt, "http://127.0.0.1:" + aiPort, KEY);
            while (ids.stream().anyMatch(id -> Set.of("PENDING", "RUNNING").contains(status(id)))) {
              if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(50))
                throw new TimeoutException();
              w.poll();
              Thread.sleep(10);
            }
            return null;
          };
      var a = pool.submit(drain);
      var b = pool.submit(drain);
      a.get();
      b.get();
      assertTrue(ids.stream().allMatch(id -> "SUCCEEDED".equals(status(id))));
      System.out.println(
          "ENGINEERING_THROUGHPUT runs=4 workers=2 seconds=" + (System.nanoTime() - start) / 1e9);
    } finally {
      pool.shutdownNow();
      ai.destroyForcibly();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named="AIOPS_ENGINEERING_RESTART_PHASE",matches="prepare|resume")
  void recoverAcrossJvms() throws Exception {
    assertNotNull(System.getenv("AIOPS_ENGINEERING_MYSQL_PORT"));
    boolean first="prepare".equals(System.getenv("AIOPS_ENGINEERING_RESTART_PHASE"));
    Path state=Path.of(System.getenv("AIOPS_ENGINEERING_RESTART_FILE"));
    long id=first?enqueue()[2]:Long.parseLong(Files.readString(state).trim());
    if(!first) {
      assertEquals("RUNNING",status(id));
      expire(id);
    }
    int aiPort;
    try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {aiPort=socket.getLocalPort();}
    Process ai=launch(aiPort,first);
    try {
      new InvestigationWorker(tasks,jwt,"http://127.0.0.1:"+aiPort,KEY).poll();
      if(first) {
        assertTrue(ai.waitFor(15,TimeUnit.SECONDS));
        assertEquals(75,ai.exitValue());
        assertTrue(testJdbc.queryForObject("SELECT COUNT(*) FROM ops_graph_checkpoint WHERE run_id=?",Integer.class,id)>1);
        assertEquals("RUNNING",status(id));
        Files.writeString(state,Long.toString(id));
      } else {
        assertEquals("SUCCEEDED",status(id));
        assertEquals(1,testJdbc.queryForObject("SELECT recovery_attempts FROM ops_investigation_run WHERE id=?",Integer.class,id));
        assertEquals(1,testJdbc.queryForObject("SELECT COUNT(*) FROM ops_diagnosis_report WHERE run_id=?",Integer.class,id));
      }
      System.out.println("ENGINEERING_RESTART phase="+(first?"prepare":"resume")+" jvm="+ProcessHandle.current().pid()+" run="+id);
    } finally {ai.destroyForcibly();}
  }

  private Process launch(int aiPort, boolean crash) throws Exception {
    var pb = python("tests/engineering/worker_fixture.py", Integer.toString(aiPort));
    pb.environment().put("ENGINEERING_CRASH", crash ? "yes" : "no");
    Process p = pb.redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
    var http = HttpClient.newHttpClient();
    try {
      for (int n = 0; n < 180 && p.isAlive(); n++) {
        try {
          var res =
              http.send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + aiPort + "/api/ai/health"))
                      .timeout(Duration.ofSeconds(1))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding());
          if (res.statusCode() == 200) return p;
        } catch (java.io.IOException ignored) {
        }
        Thread.sleep(500);
      }
      throw new IllegalStateException("test FastAPI startup failed");
    } catch (Exception e) {
      p.destroyForcibly();
      throw e;
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AIOPS_ENGINEERING_PYTHON", matches = ".+")
  void crashedFastApiResumesWithNewWorkerAndMcpReadsResult() throws Exception {
    var ids = enqueue();
    fixtureServer = ids[0];
    Long actor =
        testJdbc.queryForObject(
            "SELECT id FROM sys_user WHERE username='fixtureAdmin'", Long.class);
    testJdbc.update(
        "INSERT INTO knowledge_base(owner_user_id,name,status,created_at,updated_at)"
            + " VALUES(?,'engineering','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        actor);
    fixtureKb = testJdbc.queryForObject("SELECT MAX(id) FROM knowledge_base", Long.class);
    testJdbc.update(
        "INSERT INTO"
            + " knowledge_document(kb_id,original_filename,mime_type,sha256,size_bytes,storage_key,status,chunk_count,embedding_version,index_version,created_at,updated_at)"
            + " VALUES(?,'fixture.md','text/markdown',?,32,'fixture','READY',1,'fixture-feature-hash-v1',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        fixtureKb,
        "0".repeat(64));
    fixtureDoc = testJdbc.queryForObject("SELECT MAX(id) FROM knowledge_document", Long.class);
    int aiPort;
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      aiPort = socket.getLocalPort();
    }
    String url = "http://127.0.0.1:" + aiPort;
    Process first = launch(aiPort, true);
    try {
      String parent =
          testJdbc.queryForObject(
              "SELECT trace_parent FROM ops_investigation_run WHERE id=?", String.class, ids[2]);
      if (parent != null) {
        var client =
            python("tests/engineering/scenario_client.py", url, Long.toString(ids[0]), parent);
        client.environment().put("AIOPS_MCP_USER_JWT", jwt.issue(actor, "fixtureAdmin"));
        Process scenario = client.redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
        try {
          assertTrue(scenario.waitFor(90, TimeUnit.SECONDS));
          assertEquals(0, scenario.exitValue());
        } finally {
          scenario.destroyForcibly();
        }
      }
      new InvestigationWorker(tasks, jwt, url, KEY).poll();
      assertTrue(first.waitFor(15, TimeUnit.SECONDS), "did not reach injected crash");
      assertEquals(75, first.exitValue());
      assertEquals("RUNNING", status(ids[2]));
    } finally {
      first.destroyForcibly();
    }
    int count =
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_graph_checkpoint WHERE run_id=?", Integer.class, ids[2]);
    assertTrue(count >= 2);
    expire(ids[2]);
    Process resumed = launch(aiPort, false);
    try {
      new InvestigationWorker(tasks, jwt, url, KEY).poll();
      assertEquals("SUCCEEDED", status(ids[2]));
      var report =
          testJdbc.queryForObject(
              "SELECT report_json FROM ops_diagnosis_report WHERE run_id=?", String.class, ids[2]);
      assertTrue(report.contains("executionTrace"));
      String trace =
          testJdbc.queryForObject(
              "SELECT result_trace_id FROM ops_investigation_run WHERE id=?", String.class, ids[2]);
      assertEquals(ids[1], incidents.trace(trace).get("incidentId"));
      if ("true".equals(System.getenv("OTEL_ENABLED"))) {
        var parent =
            testJdbc.queryForObject(
                "SELECT trace_parent FROM ops_investigation_run WHERE id=?", String.class, ids[2]);
        assertEquals(
            trace,
            parent.substring(3, 35),
            "Java enqueue and resumed Python graph must share trace");
      }
      var parsed =
          new tools.jackson.databind.ObjectMapper().readTree(report).path("executionTrace");
      System.out.println("ENGINEERING_TRACE " + parsed);
      var pb =
          python(
              "tests/engineering/mcp_stdio_client.py",
              Long.toString(ids[0]),
              Long.toString(ids[1]));
      Long uid =
          testJdbc.queryForObject(
              "SELECT id FROM sys_user WHERE username='fixtureAdmin'", Long.class);
      pb.environment().put("AIOPS_MCP_USER_JWT", jwt.issue(uid, "fixtureAdmin"));
      Process mcp = pb.redirectOutput(Path.of("target", "engineering-mcp.log").toFile()).start();
      try {
        assertTrue(mcp.waitFor(90, TimeUnit.SECONDS));
        assertEquals(0, mcp.exitValue());
      } finally {
        mcp.destroyForcibly();
      }
    } finally {
      resumed.destroyForcibly();
    }
  }
}
