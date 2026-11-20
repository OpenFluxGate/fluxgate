package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bson.Document;
import org.fluxgate.adapter.mongo.policy.MongoPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalPolicyBootstrapTest {
  @TempDir Path directory;

  @Test
  void boundedPayloadUsesFixedBootstrapAuthorityAndReplayOperation() throws Exception {
    Path file = directory.resolve("policy.json");
    Files.writeString(
        file,
        "{\"ruleSetId\":\"local\",\"operationId\":\"seed-v1\",\"rules\":[{\"id\":\"r\"}],\"accessControl\":{\"deniedKeys\":[\"key:bad\"]}}");
    MongoPolicyRepository repository = mock(MongoPolicyRepository.class);
    LocalPolicyBootstrap runner = new LocalPolicyBootstrap(repository, file.toString());
    runner.run(new DefaultApplicationArguments(new String[0]));
    runner.run(new DefaultApplicationArguments(new String[0]));
    verify(repository, times(2))
        .publish(
            eq("local"),
            eq(0L),
            eq(List.of(new Document("id", "r"))),
            eq(new Document("deniedKeys", List.of("key:bad"))),
            eq(true),
            eq("Initialize isolated local environment"),
            eq("seed-v1"),
            eq("local-bootstrap"));
  }

  @Test
  void malformedAndOversizedPayloadNeverPublishes() throws Exception {
    MongoPolicyRepository repository = mock(MongoPolicyRepository.class);
    Path file = directory.resolve("bad.json");
    for (String contents :
        List.of(
            "{}",
            "{\"ruleSetId\":\"x\",\"operationId\":\"op\",\"rules\":[],\"actor\":\"admin\"}",
            "{\"ruleSetId\":\"x\",\"ruleSetId\":\"y\",\"operationId\":\"op\",\"rules\":[]}",
            "not-json")) {
      Files.writeString(file, contents);
      assertThatThrownBy(
              () ->
                  new LocalPolicyBootstrap(repository, file.toString())
                      .run(new DefaultApplicationArguments(new String[0])))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Files.write(file, new byte[1024 * 1024 + 1]);
    assertThatThrownBy(
            () ->
                new LocalPolicyBootstrap(repository, file.toString())
                    .run(new DefaultApplicationArguments(new String[0])))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void bootstrapBeanRequiresLocalProfileAndProperty() {
    new ApplicationContextRunner()
        .withUserConfiguration(LocalPolicyBootstrap.class)
        .withPropertyValues("fluxgate.envoy.bootstrap-file=/does/not/matter")
        .run(context -> assertThat(context).doesNotHaveBean(LocalPolicyBootstrap.class));
    new ApplicationContextRunner()
        .withUserConfiguration(LocalPolicyBootstrap.class)
        .withPropertyValues("spring.profiles.active=local-enterprise")
        .run(context -> assertThat(context).doesNotHaveBean(LocalPolicyBootstrap.class));
  }

  @Test
  void enabledLocalProfileRegistersRunnerAndTrailingJsonFails() throws Exception {
    MongoPolicyRepository repository = mock(MongoPolicyRepository.class);
    Path file = directory.resolve("trailing.json");
    Files.writeString(file, "{\"ruleSetId\":\"local\",\"operationId\":\"op\",\"rules\":[{}]} {}");
    assertThatThrownBy(
            () ->
                new LocalPolicyBootstrap(repository, file.toString())
                    .run(new DefaultApplicationArguments(new String[0])))
        .isInstanceOf(IllegalArgumentException.class);
    new ApplicationContextRunner()
        .withUserConfiguration(LocalPolicyBootstrap.class)
        .withBean(MongoPolicyRepository.class, () -> repository)
        .withPropertyValues(
            "spring.profiles.active=local-enterprise", "fluxgate.envoy.bootstrap-file=" + file)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(LocalPolicyBootstrap.class);
            });
    verifyNoInteractions(repository);
  }
}
