package org.fluxgate.envoy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;
import org.bson.Document;
import org.fluxgate.adapter.mongo.policy.MongoPolicyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Explicit local-only, repeatable seed; the repository handles publication replay. */
@Component
@Profile("local-enterprise")
@ConditionalOnProperty(name = "fluxgate.envoy.bootstrap-file")
public class LocalPolicyBootstrap implements ApplicationRunner {
  private static final int MAX_BYTES = 1024 * 1024;
  private static final Set<String> FIELDS =
      Set.of("ruleSetId", "operationId", "rules", "accessControl");
  private final MongoPolicyRepository repository;
  private final Path file;

  public LocalPolicyBootstrap(
      MongoPolicyRepository repository,
      @Value("${fluxgate.envoy.bootstrap-file}") String filename) {
    this.repository = repository;
    this.file = Path.of(filename);
  }

  @Override
  public void run(ApplicationArguments args) {
    Document payload = readPayload();
    repository.publish(
        payload.getString("ruleSetId"),
        0L,
        payload.getList("rules", Document.class),
        payload.get("accessControl", Document.class),
        true,
        "Initialize isolated local environment",
        payload.getString("operationId"),
        "local-bootstrap");
  }

  private Document readPayload() {
    try (InputStream stream = Files.newInputStream(file)) {
      byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES)
        throw new IllegalArgumentException("Bootstrap file exceeds 1 MiB");
      ObjectMapper mapper =
          new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
      mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
      JsonNode root = mapper.readTree(bytes);
      if (root == null || !root.isObject())
        throw new IllegalArgumentException("Bootstrap payload must be an object");
      Iterator<String> fields = root.fieldNames();
      while (fields.hasNext()) {
        if (!FIELDS.contains(fields.next()))
          throw new IllegalArgumentException("Unknown bootstrap payload field");
      }
      requiredIdentifier(root, "ruleSetId");
      requiredIdentifier(root, "operationId");
      JsonNode rules = root.get("rules");
      if (rules == null || !rules.isArray() || rules.isEmpty())
        throw new IllegalArgumentException("Bootstrap rules must be a non-empty object array");
      for (JsonNode rule : rules) {
        if (!rule.isObject()) throw new IllegalArgumentException("Bootstrap rules must be objects");
      }
      JsonNode acl = root.get("accessControl");
      if (acl != null && !acl.isObject())
        throw new IllegalArgumentException("Bootstrap accessControl must be an object");
      Document payload = Document.parse(root.toString());
      if (acl == null) payload.put("accessControl", new Document());
      return payload;
    } catch (IOException | org.bson.json.JsonParseException ex) {
      throw new IllegalArgumentException("Cannot read valid bootstrap JSON file", ex);
    }
  }

  private static void requiredIdentifier(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null
        || !value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > 128)
      throw new IllegalArgumentException(
          "Bootstrap " + field + " must be a non-empty string of at most 128 characters");
  }
}
