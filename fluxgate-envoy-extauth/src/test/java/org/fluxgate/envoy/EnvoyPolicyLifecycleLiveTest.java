package org.fluxgate.envoy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.fluxgate.adapter.mongo.policy.MongoPolicyRepository;
import org.fluxgate.adapter.mongo.policy.PublishedMongoRuleSetProvider;
import org.fluxgate.core.key.LimitScopeKeyResolver;

/**
 * Explicit local-dev executable, deliberately without JUnit annotations. Requires the deployed
 * enterprise two-rule GLOBAL policy (capacities 3 and 2, one-hour windows), two distinct authz pod
 * forwards, and Envoy. It publishes two audited resets for the GLOBAL and identity phases,
 * preserves each epoch within its phase, temporarily tampers one immutable snapshot to exercise
 * integrity failure, and restores exact BSON in finally. Never invokes a reload notifier or
 * deployment command. Run with the built test/runtime classpath.
 *
 * <p>Required environment: FLUXGATE_LIVE_MONGO_URI, FLUXGATE_LIVE_ENVOY_URL,
 * FLUXGATE_LIVE_AUTHZ_POD_URLS (comma-separated TLS bases), FLUXGATE_LIVE_READY_URLS (two plaintext
 * readiness URLs), FLUXGATE_LIVE_CLIENT_CERT, FLUXGATE_LIVE_CLIENT_KEY, FLUXGATE_LIVE_CA_CERT.
 * Optional: FLUXGATE_LIVE_DATABASE=fluxgate, FLUXGATE_LIVE_COLLECTION=rate_limit_rules,
 * FLUXGATE_LIVE_RULE_SET=default-limits, FLUXGATE_LIVE_PATH=/api/lifecycle.
 */
public final class EnvoyPolicyLifecycleLiveTest {
  private final String id = env("FLUXGATE_LIVE_RULE_SET", "default-limits");
  private final String collection = env("FLUXGATE_LIVE_COLLECTION", "rate_limit_rules");
  private final String run = "live-" + UUID.randomUUID();
  private final String gateway = required("FLUXGATE_LIVE_ENVOY_URL");
  private final String path = env("FLUXGATE_LIVE_PATH", "/api/lifecycle");
  private final List<String> pods = urls("FLUXGATE_LIVE_AUTHZ_POD_URLS");
  private final List<String> ready = urls("FLUXGATE_LIVE_READY_URLS");
  private final List<Document> evidence = new ArrayList<>();
  private MongoPolicyRepository repository;
  private PublishedMongoRuleSetProvider provider;
  private Document current;
  private String epoch;

  public static void main(String[] args) throws Exception {
    new EnvoyPolicyLifecycleLiveTest().run();
  }

  private void run() throws Exception {
    verifyLocalFixture();
    check(pods.size() == 2 && !pods.get(0).equals(pods.get(1)), "two distinct pod URLs required");
    check(ready.size() == 2, "two readiness URLs required");
    try (MongoClient client = MongoClients.create(required("FLUXGATE_LIVE_MONGO_URI"))) {
      MongoDatabase database = client.getDatabase(env("FLUXGATE_LIVE_DATABASE", "fluxgate"));
      repository = new MongoPolicyRepository(database, collection);
      provider =
          new PublishedMongoRuleSetProvider(repository, new LimitScopeKeyResolver(), () -> null);
      current = repository.findActive(id).orElseThrow();
      List<Document> originalRules = rules(current);
      Document originalAcl = acl(current);
      check(originalRules.size() == 2, "requires exact two-rule enterprise policy");
      for (Document rule : originalRules) {
        check("GLOBAL".equals(rule.getString("scope")), "requires GLOBAL rules");
        List<Document> bands = rule.getList("bands", Document.class);
        check(
            bands.size() == 1 && ((Number) bands.get(0).get("windowSeconds")).longValue() == 3600,
            "requires one-hour single-band rules");
      }
      check(
          capacity(originalRules, "route-quota") == 3
              && capacity(originalRules, "shared-quota") == 2,
          "requires route-quota=3 and shared-quota=2");
      current =
          repository.publish(
              id,
              revision(),
              originalRules,
              originalAcl,
              true,
              "Explicit isolated local-dev lifecycle validation reset " + run,
              run + "-reset",
              "live-harness");
      epoch = current.getString("counterEpoch");
      check(
          !"legacy".equals(epoch) && current.getString("resetReason").contains(run),
          "audited reset");
      Document reset = cloneDoc(current);
      record("audited-reset");
      publish(
          originalRules,
          new Document(originalAcl).append("deniedKeys", List.of("global")),
          "acl-deny");
      probes("acl-only-convergence", 403, 403);
      readiness(200);
      publish(originalRules, originalAcl, "acl-restore");
      request("gateway-first", gateway + path, false, 200);
      request("gateway-second", gateway + path, false, 200);
      // The third attempt charges route-quota's last token before shared-quota denies.
      request("gateway-third-shared-quota", gateway + path, false, 429);
      List<Document> unmasked = rules(current);
      setCapacity(unmasked, "shared-quota", 100);
      unmasked.stream()
          .filter(rule -> "route-quota".equals(rule.getString("id")))
          .findFirst()
          .orElseThrow()
          .put("enabled", false);
      publish(unmasked, originalAcl, "unmask-route-quota");
      // A preceding rejected rule would otherwise prevent the shared rule's lazy migration.
      // Prime shared capacity through Envoy, proving it can admit before re-enabling route quota.
      request("prove-shared-has-quota", gateway + path, false, 200);
      unmasked.stream()
          .filter(rule -> "route-quota".equals(rule.getString("id")))
          .findFirst()
          .orElseThrow()
          .put("enabled", true);
      publish(unmasked, originalAcl, "restore-route-quota-with-usage");
      Document rollbackTarget = cloneDoc(current);
      probes("route-usage-unmasked", 429, 429);
      List<Document> metadata = rules(current);
      metadata.get(0).put("name", "Live metadata revision " + run);
      publish(metadata, originalAcl, "metadata");
      // shared-quota now has 97 tokens. A reset of route-quota would produce an unexpected 200.
      probes("metadata-keeps-usage", 429, 429);
      List<Document> lowered = rules(current);
      lowered.stream()
          .filter(r -> "route-quota".equals(r.getString("id")))
          .findFirst()
          .orElseThrow()
          .getList("bands", Document.class)
          .get(0)
          .put("capacity", 1L);
      publish(lowered, originalAcl, "capacity-down");
      probes("capacity-down", 429, 429);
      publish(metadata, originalAcl, "capacity-up");
      probes("capacity-up-no-free-tokens", 429, 429);
      current =
          repository.rollback(
              id,
              revision(),
              ((Number) rollbackTarget.get("revision")).longValue(),
              false,
              null,
              run + "-rollback",
              "live-harness");
      record("rollback");
      probes("rollback-no-free-tokens", 429, 429);
      long before = revision();
      expectFailure(
          () ->
              repository.publish(
                  id,
                  before - 1,
                  originalRules,
                  originalAcl,
                  false,
                  null,
                  run + "-stale",
                  "live-harness"),
          IllegalStateException.class,
          "Active policy revision conflict");
      check(
          repository.findActive(id).orElseThrow().get("revision").equals(current.get("revision")),
          "CAS failure must not advance pointer");
      Document replay =
          repository.publish(
              id,
              ((Number) reset.get("revision")).longValue() - 1,
              originalRules,
              originalAcl,
              true,
              reset.getString("resetReason"),
              run + "-reset",
              "live-harness");
      check(
          replay.get("snapshotId").equals(reset.get("snapshotId")),
          "operation replay after successors");
      record("CAS-conflict-and-operation-replay");
      Document exact = repository.findActive(id).orElseThrow();
      var revisions = database.getCollection(collection + "_revisions");
      Document tampered = cloneDoc(exact);
      tampered.getList("rules", Document.class).get(0).put("name", "intentional integrity fault");
      try {
        var write = revisions.replaceOne(Filters.eq("_id", exact.get("_id")), tampered);
        check(
            write.getMatchedCount() == 1 && write.getModifiedCount() == 1,
            "tamper matched exact snapshot");
        expectFailure(
            () -> provider.findById(id),
            IllegalStateException.class,
            "Policy snapshot integrity failure");
        probes("invalid-snapshot-fails-closed", 503, 503);
        readiness(503);
      } finally {
        var restored = revisions.replaceOne(Filters.eq("_id", exact.get("_id")), exact);
        check(
            restored.getMatchedCount() == 1 && restored.getModifiedCount() == 1,
            "exact snapshot restoration write");
        check(
            exact.equals(revisions.find(Filters.eq("_id", exact.get("_id"))).first()),
            "exact BSON restored");
      }
      record("exact-snapshot-restored");
      readiness(200);
      probes("recovered-without-free-tokens", 429, 429);
      List<Document> scoped = rules(current);
      setCapacity(scoped, "shared-quota", 2);
      for (Document rule : scoped) {
        boolean user = "route-quota".equals(rule.getString("id"));
        rule.put("scope", user ? "PER_USER" : "PER_API_KEY");
        rule.put("keyStrategyId", user ? "user" : "api-key");
      }
      current =
          repository.publish(
              id,
              revision(),
              scoped,
              originalAcl,
              true,
              "Explicit local-dev identity scope validation reset " + run,
              run + "-identity-reset",
              "live-harness");
      epoch = current.getString("counterEpoch");
      record("audited-identity-scope-reset");
      List<String> spoof = List.of("X-User-Id: local-user");
      request("gateway-raw-user-ignored", gateway + path, false, 403, spoof);
      for (int i = 0; i < pods.size(); i++) {
        request("pod" + i + "-raw-user-ignored", pods.get(i) + "/authz" + path, true, 403, spoof);
        request(
            "pod" + i + "-invalid-key",
            pods.get(i) + "/authz" + path,
            true,
            403,
            List.of("X-Api-Key: invalid-local-fixture"));
      }
      request(
          "gateway-invalid-key",
          gateway + path,
          false,
          403,
          List.of("X-Api-Key: invalid-local-fixture"));
      List<String> valid = List.of("X-Api-Key: fluxgate-local-test-key");
      request("verified-identity-first", gateway + path, false, 200, valid);
      request("verified-identity-second", gateway + path, false, 200, valid);
      request("verified-identity-quota", gateway + path, false, 429, valid);
      for (int i = 0; i < pods.size(); i++) {
        request(
            "pod" + i + "-permits-header-ignored",
            pods.get(i) + "/authz" + path,
            true,
            429,
            List.of("X-Api-Key: fluxgate-local-test-key", "X-FluxGate-Permits: 0"));
      }
      readiness(200);
      System.out.println(
          new Document("result", "PASS")
              .append("run", run)
              .append("revision", revision())
              .append("counterEpoch", epoch)
              .append("assertions", evidence.size())
              .append("evidence", evidence)
              .toJson());
    }
  }

  private void publish(List<Document> rules, Document acl, String operation) {
    current =
        repository.publish(
            id, revision(), rules, acl, false, null, run + "-" + operation, "live-harness");
    record(operation);
  }

  private void record(String step) {
    check(Objects.equals(epoch, current.getString("counterEpoch")), "epoch preserved at " + step);
    var policy = provider.findById(id).orElseThrow();
    check(
        policy.getRules().stream()
            .allMatch(
                r ->
                    ((Number) r.getAttributes().get("fluxgate.counterRevision")).longValue()
                            == revision()
                        && epoch.equals(r.getAttributes().get("fluxgate.counterEpoch"))),
        "published provider revision");
    evidence.add(new Document("step", step).append("revision", revision()).append("epoch", epoch));
  }

  private void probes(String step, int gatewayStatus, int podStatus) throws Exception {
    request(step + "-envoy", gateway + path, false, gatewayStatus);
    for (int i = 0; i < pods.size(); i++)
      request(step + "-pod" + i, pods.get(i) + "/authz" + path, true, podStatus);
  }

  private void readiness(int status) throws Exception {
    for (int i = 0; i < ready.size(); i++)
      request("readiness-pod" + i, ready.get(i), false, status);
  }

  private void request(String step, String url, boolean tls, int expected) throws Exception {
    request(step, url, tls, expected, List.of());
  }

  private void request(String step, String url, boolean tls, int expected, List<String> headers)
      throws Exception {
    List<String> command =
        new ArrayList<>(
            List.of(
                "curl",
                "--silent",
                "--show-error",
                "--max-time",
                "10",
                "--noproxy",
                "*",
                "--output",
                "/dev/null",
                "--write-out",
                "%{http_code}"));
    if (tls) {
      URI uri = URI.create(url);
      command.addAll(
          List.of(
              "--cert",
              required("FLUXGATE_LIVE_CLIENT_CERT"),
              "--key",
              required("FLUXGATE_LIVE_CLIENT_KEY"),
              "--cacert",
              required("FLUXGATE_LIVE_CA_CERT"),
              "--resolve",
              uri.getHost() + ":" + uri.getPort() + ":127.0.0.1"));
    }
    for (String header : headers) command.addAll(List.of("--header", header));
    command.add(url);
    Process process = new ProcessBuilder(command).start();
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError(step + ": curl timeout");
    }
    String code =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    check(
        process.exitValue() == 0 && code.equals(Integer.toString(expected)),
        step
            + ": expected HTTP "
            + expected
            + ", got "
            + code
            + ", curl exit "
            + process.exitValue());
    evidence.add(
        new Document("step", step).append("status", expected).append("revision", revision()));
  }

  private long revision() {
    return ((Number) current.get("revision")).longValue();
  }

  private static Document cloneDoc(Document value) {
    return Document.parse(value.toJson());
  }

  private static List<Document> rules(Document value) {
    return cloneDoc(value).getList("rules", Document.class);
  }

  private static Document acl(Document value) {
    return cloneDoc(value).get("accessControl", Document.class);
  }

  private static long capacity(List<Document> rules, String id) {
    return ((Number)
            rules.stream()
                .filter(r -> id.equals(r.getString("id")))
                .findFirst()
                .orElseThrow()
                .getList("bands", Document.class)
                .get(0)
                .get("capacity"))
        .longValue();
  }

  private static List<String> urls(String name) {
    return List.of(required(name).split(","));
  }

  private static String env(String name, String fallback) {
    return System.getenv().getOrDefault(name, fallback);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Missing environment " + name);
    return value;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void expectFailure(
      Runnable action, Class<? extends RuntimeException> type, String message) {
    try {
      action.run();
    } catch (RuntimeException expected) {
      check(
          expected.getClass() == type
              && expected.getMessage() != null
              && expected.getMessage().contains(message),
          "Expected " + type.getSimpleName() + ": " + message);
      return;
    }
    throw new AssertionError("Expected " + message);
  }

  private static void setCapacity(List<Document> rules, String ruleId, long capacity) {
    rules.stream()
        .filter(rule -> ruleId.equals(rule.getString("id")))
        .findFirst()
        .orElseThrow()
        .getList("bands", Document.class)
        .get(0)
        .put("capacity", capacity);
  }

  private void verifyLocalFixture() throws Exception {
    String context = "kind-fluxgate-enterprise";
    check(
        commandOutput(List.of("kubectl", "config", "current-context")).equals(context),
        "Live harness requires kind-fluxgate-enterprise context");
    check(
        commandOutput(
                List.of(
                    "kubectl",
                    "--context",
                    context,
                    "get",
                    "namespace",
                    "fluxgate-enterprise",
                    "-o",
                    "jsonpath={.metadata.labels.fluxgate\\.io/environment}"))
            .equals("local-ephemeral"),
        "isolated local fixture namespace required");
    var mongo = new com.mongodb.ConnectionString(required("FLUXGATE_LIVE_MONGO_URI"));
    check(
        !mongo.isSrvProtocol()
            && mongo.getHosts().stream()
                .allMatch(host -> host.matches("(127\\.0\\.0\\.1|localhost):[0-9]+")),
        "Mongo URI must use loopback forwards");
    check(loopbackHttp(gateway), "Envoy URI must use loopback HTTP");
    check(
        ready.stream().allMatch(EnvoyPolicyLifecycleLiveTest::loopbackHttp),
        "readiness URLs must use loopback HTTP");
    check(
        pods.stream()
            .allMatch(
                url -> {
                  URI uri = URI.create(url);
                  return "https".equals(uri.getScheme())
                      && uri.getPort() > 0
                      && uri.getUserInfo() == null
                      && "fluxgate-authz.fluxgate-enterprise.svc.cluster.local"
                          .equals(uri.getHost());
                }),
        "pod URLs must use fixture TLS hostname resolved to loopback");
  }

  private static boolean loopbackHttp(String url) {
    URI uri = URI.create(url);
    return "http".equals(uri.getScheme())
        && uri.getUserInfo() == null
        && List.of("127.0.0.1", "localhost").contains(uri.getHost());
  }

  private static String commandOutput(List<String> command) throws Exception {
    Process process = new ProcessBuilder(command).start();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("Fixture guard command timed out");
    }
    check(process.exitValue() == 0, "Fixture guard command failed");
    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
  }
}
