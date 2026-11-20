package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.policy.MongoPolicyRepository;
import org.fluxgate.adapter.mongo.policy.PublishedMongoRuleSetProvider;
import org.fluxgate.control.notify.RuleChangeMessage;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.reload.PublishedEpochResetGuard;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.reload.RuleReloadStrategy;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.reload.strategy.RedisPubSubReloadStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Real standalone Redis/Mongo; this is not a cluster, mixed-version or performance proof. */
class PublishedEpochEndToEndIntegrationTest {
  private static final String SECRET = "isolated-test-only-32-byte-signing-secret";
  private static final RequestContext REQUEST =
      RequestContext.builder().clientIp("192.0.2.10").endpoint("/api").build();

  @Test
  void publicationAndRealPubSubNeverDestructivelyResetPublishedCounters() throws Exception {
    try (GenericContainer<?> mongo = container("mongo:7.0", 27017, 512);
        GenericContainer<?> redis = container("redis:7-alpine", 6379, 128)) {
      mongo.start();
      redis.start();
      assertLoopback(mongo, 27017);
      assertLoopback(redis, 6379);
      String redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
      String mongoUri = "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017);
      String channel = "epoch-test-" + UUID.randomUUID();
      RedisClient redisClient = RedisClient.create(redisUri);
      try (MongoClient mongoClient = MongoClients.create(mongoUri);
          StatefulRedisConnection<String, String> inspection = redisClient.connect()) {
        MongoPolicyRepository repository =
            new MongoPolicyRepository(mongoClient.getDatabase("epoch_e2e"), "rules");
        Document first = publish(repository, 0, 2, true, "initial");
        RedisCommands<String, String> commands = inspection.sync();
        subscriber(repository, redisUri, channel, "PUBLISHED_EPOCH")
            .run(
                context -> {
                  assertThat(context).hasNotFailed();
                  assertThat(context).hasSingleBean(PublishedEpochResetGuard.class);
                  RuleReloadStrategy strategy = context.getBean(RuleReloadStrategy.class);
                  assertThat(strategy).isInstanceOf(RedisPubSubReloadStrategy.class);
                  List<RuleReloadEvent> received = new CopyOnWriteArrayList<>();
                  // Completion barrier after cache invalidation AND any bucket reset listeners.
                  // A prior listener failure prevents this marker, so the bounded await also fails.
                  ((RedisPubSubReloadStrategy) strategy)
                      .addListener(received::add, Integer.MAX_VALUE);
                  await(() -> commands.pubsubNumsub(channel).getOrDefault(channel, 0L) == 1L);
                  RateLimitEngine engine = context.getBean(RateLimitEngine.class);
                  // Measure only publication / notification / consumption, not fixture lifecycle.
                  Map<String, Long> before = destructiveCalls(commands);
                  assertThat(before.values()).containsOnly(0L);
                  assertThat(engine.check("orders", REQUEST, 2).isAllowed()).isTrue();
                  assertThat(engine.check("orders", REQUEST).isAllowed()).isFalse();

                  Document preserved = publish(repository, 1, 3, false, "preserve");
                  assertThat(preserved.getString("counterEpoch"))
                      .isEqualTo(first.getString("counterEpoch"));
                  String oldNotice = notice(channel, false, System.currentTimeMillis() - 1000);
                  String newNotice = notice(channel, false, System.currentTimeMillis());
                  assertThat(commands.publish(channel, newNotice)).isEqualTo(1L);
                  await(() -> received.size() == 1);
                  assertRevision(context.getBean(RateLimitRuleSetProvider.class), 2);
                  // Capacity increases by one, retaining the two permits already consumed.
                  assertThat(engine.check("orders", REQUEST).isAllowed()).isTrue();
                  assertThat(engine.check("orders", REQUEST).isAllowed()).isFalse();

                  Document reset = publish(repository, 2, 3, true, "reset");
                  assertThat(reset.getString("counterEpoch"))
                      .isNotEqualTo(first.getString("counterEpoch"));
                  commands.publish(channel, notice(channel, false, System.currentTimeMillis()));
                  await(() -> received.size() == 2);
                  assertThat(engine.check("orders", REQUEST, 3).isAllowed()).isTrue();
                  assertThat(engine.check("orders", REQUEST).isAllowed()).isFalse();

                  // Replay the same nonce, then an older signed notice and an explicit full reload.
                  commands.publish(channel, newNotice);
                  commands.publish(channel, oldNotice);
                  commands.publish(channel, notice(channel, true, System.currentTimeMillis()));
                  await(() -> received.size() >= 4);
                  assertThat(received).hasSize(4);
                  assertRevision(context.getBean(RateLimitRuleSetProvider.class), 3);
                  assertThat(engine.check("orders", REQUEST).isAllowed()).isFalse();

                  // Stop only the subscriber: Redis and the data path remain live.
                  strategy.stop();
                  await(() -> commands.pubsubNumsub(channel).getOrDefault(channel, 0L) == 0L);
                  publish(repository, 3, 4, false, "lost-preserve");
                  assertThat(
                          commands.publish(
                              channel, notice(channel, false, System.currentTimeMillis())))
                      .isZero();
                  // The next engine request, with no reload delivery, observes the increased
                  // capacity.
                  assertThat(engine.check("orders", REQUEST).isAllowed()).isTrue();
                  assertRevision(context.getBean(RateLimitRuleSetProvider.class), 4);
                  assertThat(engine.check("orders", REQUEST).isAllowed()).isFalse();
                  publish(repository, 4, 4, true, "lost-reset");
                  assertThat(
                          commands.publish(
                              channel, notice(channel, false, System.currentTimeMillis())))
                      .isZero();
                  assertThat(engine.check("orders", REQUEST, 4).isAllowed()).isTrue();
                  assertThat(engine.check("orders", REQUEST).isAllowed()).isFalse();
                  assertRevision(context.getBean(RateLimitRuleSetProvider.class), 5);
                  assertThat(received).hasSize(4);

                  strategy.start();
                  await(() -> commands.pubsubNumsub(channel).getOrDefault(channel, 0L) == 1L);
                  commands.publish(channel, notice(channel, true, System.currentTimeMillis()));
                  await(() -> received.size() == 5);
                  assertThat(engine.check("orders", REQUEST).isAllowed()).isFalse();
                  assertThat(destructiveCalls(commands)).isEqualTo(before);
                });
      } finally {
        redisClient.shutdown();
      }
    }
  }

  @Test
  void twoSubscribersProcessSharedRedisNotificationsWithoutScanningInEpochMode() throws Exception {
    try (GenericContainer<?> mongo = container("mongo:7.0", 27017, 512);
        GenericContainer<?> redis = container("redis:7-alpine", 6379, 128)) {
      mongo.start();
      redis.start();
      assertLoopback(mongo, 27017);
      assertLoopback(redis, 6379);
      String redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
      String mongoUri = "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017);
      RedisClient client = RedisClient.create(redisUri);
      try (MongoClient mongoClient = MongoClients.create(mongoUri);
          StatefulRedisConnection<String, String> connection = client.connect()) {
        RedisCommands<String, String> commands = connection.sync();
        Map<String, String> unrelated = new LinkedHashMap<>();
        for (int i = 0; i < 4096; i++) {
          unrelated.put("unrelated:" + i, "fixture");
        }
        commands.mset(unrelated);
        MongoPolicyRepository repository =
            new MongoPolicyRepository(mongoClient.getDatabase("epoch_comparison"), "rules");
        publish(repository, 0, 3, true, "initial");
        for (String mode : List.of("LEGACY_RESET", "PUBLISHED_EPOCH")) {
          String channel = UUID.randomUUID().toString();
          ApplicationContextRunner runner = subscriber(repository, redisUri, channel, mode);
          runner.run(
              first ->
                  runner.run(
                      second -> {
                        assertThat(first).hasNotFailed();
                        assertThat(second).hasNotFailed();
                        BlockingQueue<Long> firstSeen = new LinkedBlockingQueue<>();
                        BlockingQueue<Long> secondSeen = new LinkedBlockingQueue<>();
                        first
                            .getBean(RedisPubSubReloadStrategy.class)
                            .addListener(
                                event -> firstSeen.add(System.nanoTime()), Integer.MAX_VALUE);
                        second
                            .getBean(RedisPubSubReloadStrategy.class)
                            .addListener(
                                event -> secondSeen.add(System.nanoTime()), Integer.MAX_VALUE);
                        await(() -> commands.pubsubNumsub(channel).getOrDefault(channel, 0L) == 2);
                        Map<String, Long> before = destructiveCalls(commands);
                        List<Long> latencyMicros = new ArrayList<>();
                        for (int i = 0; i < 10; i++) {
                          String message = notice(channel, false, System.currentTimeMillis());
                          long start = System.nanoTime();
                          assertThat(commands.publish(channel, message)).isEqualTo(2);
                          Long firstEnd = firstSeen.poll(10, TimeUnit.SECONDS);
                          Long secondEnd = secondSeen.poll(10, TimeUnit.SECONDS);
                          assertThat(firstEnd).isNotNull();
                          assertThat(secondEnd).isNotNull();
                          latencyMicros.add(
                              TimeUnit.NANOSECONDS.toMicros(Math.max(firstEnd, secondEnd) - start));
                        }
                        Map<String, Long> after = destructiveCalls(commands);
                        long scans = after.get("scan") - before.get("scan");
                        assertThat(after.get("keys")).isEqualTo(before.get("keys"));
                        if ("PUBLISHED_EPOCH".equals(mode)) {
                          assertThat(scans).isZero();
                        } else {
                          assertThat(scans).isGreaterThanOrEqualTo(20);
                        }
                        System.out.printf(
                            "reload-comparison mode=%s keys=4096 subscribers=2 notices=10 scans=%d latencyMicros=%s%n",
                            mode, scans, latencyMicros);
                      }));
        }
      } finally {
        client.shutdown();
      }
    }
  }

  private static ApplicationContextRunner subscriber(
      MongoPolicyRepository repository, String redisUri, String channel, String mode) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                FluxgateRedisAutoConfiguration.class,
                FluxgateRateLimiterAutoConfiguration.class,
                FluxgateReloadAutoConfiguration.class))
        .withBean(
            "delegateRuleSetProvider",
            RateLimitRuleSetProvider.class,
            () ->
                new PublishedMongoRuleSetProvider(
                    repository, new LimitScopeKeyResolver(), () -> null))
        .withPropertyValues(
            "fluxgate.reload.counter-mode=" + mode,
            "fluxgate.redis.enabled=true",
            "fluxgate.redis.uri=" + redisUri,
            "fluxgate.ratelimit.mode=REDIS",
            "fluxgate.ratelimit.fallback.mode=NONE",
            "fluxgate.ratelimit.failure-behavior=DENY",
            "fluxgate.ratelimit.missing-rule-behavior=DENY",
            "fluxgate.reload.strategy=PUBSUB",
            "fluxgate.reload.cache.enabled=true",
            "fluxgate.reload.pubsub.channel=" + channel,
            "fluxgate.reload.pubsub.secret=" + SECRET,
            "fluxgate.reload.pubsub.backstop-polling-interval=0s");
  }

  private static void assertLoopback(GenericContainer<?> container, int port) {
    Ports.Binding[] bindings =
        container
            .getContainerInfo()
            .getNetworkSettings()
            .getPorts()
            .getBindings()
            .get(new ExposedPort(port));
    assertThat(bindings).isNotEmpty();
    for (Ports.Binding binding : bindings) {
      assertThat(binding.getHostIp()).isEqualTo("127.0.0.1");
    }
  }

  private static GenericContainer<?> container(String image, int port, long memoryMb) {
    return new GenericContainer<>(DockerImageName.parse(image))
        .withExposedPorts(port)
        .withCreateContainerCmdModifier(
            cmd ->
                cmd.getHostConfig()
                    .withMemory(memoryMb * 1024 * 1024)
                    .withNanoCPUs(500_000_000L)
                    .withPortBindings(
                        new PortBinding(
                            Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(port))))
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofSeconds(120));
  }

  private static Document publish(
      MongoPolicyRepository repository,
      long revision,
      long capacity,
      boolean reset,
      String operation) {
    Document rule =
        RateLimitRuleMongoConverter.toBson(
            RateLimitRuleMongoConverter.toDto(
                RateLimitRule.builder("one")
                    .ruleSetId("orders")
                    .scope(LimitScope.PER_IP)
                    .addBand(
                        RateLimitBand.builder(Duration.ofHours(1), capacity)
                            .label("stable")
                            .build())
                    .build()));
    return repository.publish(
        "orders",
        revision,
        List.of(rule),
        new Document(),
        reset,
        reset ? "isolated test reset" : null,
        operation,
        "isolated-test");
  }

  private static String notice(String channel, boolean full, long timestamp) {
    try {
      return new ObjectMapper()
          .writeValueAsString(
              new RuleChangeMessage(full ? null : "orders", full, timestamp, "isolated-test")
                  .signed(SECRET, channel));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void assertRevision(RateLimitRuleSetProvider provider, long revision) {
    assertThat(provider.findById("orders").orElseThrow().getRules().get(0).getAttributes())
        .containsEntry("fluxgate.counterRevision", revision);
  }

  private static Map<String, Long> destructiveCalls(RedisCommands<String, String> commands) {
    String info = commands.info("commandstats");
    Map<String, Long> result = new java.util.LinkedHashMap<>();
    for (String command : List.of("keys", "scan", "del", "unlink")) {
      Matcher matcher = Pattern.compile("(?m)^cmdstat_" + command + ":calls=(\\d+),").matcher(info);
      result.put(command, matcher.find() ? Long.parseLong(matcher.group(1)) : 0L);
    }
    return result;
  }

  private static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline)
        throw new AssertionError("Timed out waiting for real Pub/Sub");
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }
}
