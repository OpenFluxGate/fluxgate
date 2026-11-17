package org.fluxgate.spring.actuator;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes FluxGate's default health status order and HTTP status mappings as the
 * lowest-precedence property source.
 *
 * <p>Spring Boot's {@code SimpleHttpCodeStatusMapper} only uses its built-in defaults ({@code DOWN}
 * and {@code OUT_OF_SERVICE} to 503) while {@code management.endpoint.health.status.http-mapping}
 * is empty. Adding a {@code DEGRADED} entry therefore silently turned {@code DOWN} into HTTP 200
 * for every application. Instead of replacing Boot's mapper, this post-processor adds the missing
 * entries as property defaults:
 *
 * <ul>
 *   <li>{@code down=503} and {@code out-of-service=503}, Boot's own defaults
 *   <li>{@code degraded=<fluxgate.actuator.health.degraded-http-status>} (503 by default)
 * </ul>
 *
 * <p>The mapping alone is not enough: Boot's {@code SimpleStatusAggregator} drops every status that
 * is not in {@code management.endpoint.health.status.order}, and its default order does not know
 * {@code DEGRADED}. {@code /actuator/health} and the readiness group would then aggregate a
 * degraded FluxGate to {@code UP}. Unless the application sets an order itself, this post-processor
 * therefore also contributes {@code
 * management.endpoint.health.status.order=down,out-of-service,degraded,up,unknown} - Boot's default
 * with {@code degraded} between {@code out-of-service} and {@code up}.
 *
 * <p>An entry is only added when the application has not configured it itself, and the property
 * source is the last one, so every user setting wins. Health groups without their own mapping or
 * order (for example {@code liveness} and {@code readiness}) inherit the result. An order of the
 * application's own - for the endpoint or for a group - that does not list {@code degraded} is kept
 * but reported at WARN, since that aggregation ignores a degraded FluxGate. Nothing is added when
 * {@code fluxgate.actuator.health.enabled=false}; {@code degraded-http-status} of {@code 0} or less
 * adds the order but no mapping, so {@code DEGRADED} aggregates but answers HTTP 200.
 */
public class FluxgateHealthStatusEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  /** Name of the property source holding the defaults. */
  public static final String PROPERTY_SOURCE_NAME = "fluxgateHealthStatusDefaults";

  /** Boot's default status order with {@code degraded} added between out-of-service and up. */
  public static final String DEFAULT_STATUS_ORDER = "down,out-of-service,degraded,up,unknown";

  private static final String HTTP_MAPPING = "management.endpoint.health.status.http-mapping";

  private static final String STATUS_ORDER = "management.endpoint.health.status.order";

  private static final String GROUPS = "management.endpoint.health.group";

  private static final int SERVICE_UNAVAILABLE = 503;

  private final Log log;

  /**
   * Creates the post-processor with Boot's deferred log, which replays the messages once the
   * logging system is initialised. A plain logger would be silenced at this point of the startup.
   * It is the only constructor, so Spring Boot always selects it.
   *
   * @param logFactory the deferred log factory supplied by Spring Boot
   */
  public FluxgateHealthStatusEnvironmentPostProcessor(DeferredLogFactory logFactory) {
    this.log = logFactory.getLog(FluxgateHealthStatusEnvironmentPostProcessor.class);
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    // Idempotent when the environment is post-processed more than once (e.g. a bootstrap context).
    environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);

    Binder binder = Binder.get(environment);
    boolean enabled =
        binder.bind("fluxgate.actuator.health.enabled", Boolean.class).orElse(Boolean.TRUE);
    if (!enabled) {
      return;
    }
    int degradedStatus =
        binder
            .bind("fluxgate.actuator.health.degraded-http-status", Integer.class)
            .orElse(SERVICE_UNAVAILABLE);

    Map<String, Object> defaults = new LinkedHashMap<>();
    addStatusOrder(binder, defaults);
    if (degradedStatus > 0) {
      addHttpMappings(binder, defaults, degradedStatus);
    }
    if (!defaults.isEmpty()) {
      environment
          .getPropertySources()
          .addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }
  }

  /**
   * Adds the default order unless the application set one, and reports every order of the
   * application's own - the endpoint's or a group's - that leaves {@code DEGRADED} out.
   */
  private void addStatusOrder(Binder binder, Map<String, Object> defaults) {
    Optional<List<String>> configured = bindOrder(binder, STATUS_ORDER);
    if (configured.isPresent()) {
      warnIfDegradedMissing(STATUS_ORDER, configured.get());
    } else {
      defaults.put(STATUS_ORDER, DEFAULT_STATUS_ORDER);
    }
    Map<String, GroupProperties> groups =
        binder
            .bind(GROUPS, Bindable.mapOf(String.class, GroupProperties.class))
            .orElse(Collections.emptyMap());
    for (Map.Entry<String, GroupProperties> group : new TreeMap<>(groups).entrySet()) {
      List<String> order = group.getValue().getStatus().getOrder();
      if (order != null && !order.isEmpty()) {
        warnIfDegradedMissing(GROUPS + "." + group.getKey() + ".status.order", order);
      }
    }
  }

  private static Optional<List<String>> bindOrder(Binder binder, String property) {
    List<String> order =
        binder.bind(property, Bindable.listOf(String.class)).orElse(Collections.emptyList());
    return order.isEmpty() ? Optional.empty() : Optional.of(order);
  }

  private void warnIfDegradedMissing(String property, List<String> order) {
    for (String status : order) {
      if ("degraded".equals(uniform(status))) {
        return;
      }
    }
    log.warn(
        property
            + "="
            + String.join(",", order)
            + " does not list DEGRADED, so that health aggregation ignores a degraded FluxGate"
            + " (it reports UP). Add 'degraded', e.g. "
            + DEFAULT_STATUS_ORDER);
  }

  private static void addHttpMappings(
      Binder binder, Map<String, Object> defaults, int degradedStatus) {
    Map<String, Integer> configured =
        binder
            .bind(HTTP_MAPPING, Bindable.mapOf(String.class, Integer.class))
            .orElse(Collections.emptyMap());
    Set<String> mapped = new HashSet<>();
    for (String status : configured.keySet()) {
      mapped.add(uniform(status));
    }
    addIfUnmapped(defaults, mapped, "down", SERVICE_UNAVAILABLE);
    addIfUnmapped(defaults, mapped, "out-of-service", SERVICE_UNAVAILABLE);
    addIfUnmapped(defaults, mapped, "degraded", degradedStatus);
  }

  private static void addIfUnmapped(
      Map<String, Object> defaults, Set<String> mapped, String status, int httpStatus) {
    if (!mapped.contains(uniform(status))) {
      defaults.put(HTTP_MAPPING + "." + status, httpStatus);
    }
  }

  /** Same normalisation as Boot's mapper: letters and digits only, lower case. */
  private static String uniform(String status) {
    StringBuilder builder = new StringBuilder(status.length());
    for (int i = 0; i < status.length(); i++) {
      char c = status.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        builder.append(c);
      }
    }
    return builder.toString().toLowerCase(Locale.ROOT);
  }

  @Override
  public int getOrder() {
    // After ConfigDataEnvironmentPostProcessor, so application.yml mappings are visible.
    return Ordered.LOWEST_PRECEDENCE;
  }

  /**
   * The part of a {@code management.endpoint.health.group.<name>} entry this post-processor reads,
   * bound with relaxed binding so any property source and spelling (including environment
   * variables) is seen. Every other group property is ignored.
   */
  static final class GroupProperties {

    private final StatusProperties status = new StatusProperties();

    public StatusProperties getStatus() {
      return status;
    }
  }

  /** {@code status.order} of a health group. */
  static final class StatusProperties {

    private List<String> order;

    public List<String> getOrder() {
      return order;
    }

    public void setOrder(List<String> order) {
      this.order = order;
    }
  }
}
