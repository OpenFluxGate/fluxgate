package org.fluxgate.spring.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration properties for FluxGate Spring Boot Starter.
 *
 * <p>Supports role-separated deployments:
 *
 * <ul>
 *   <li>Pod A (Control-plane): Only Mongo rule management
 *   <li>Pod B (Data-plane): Only Redis rate limiting + Filter
 *   <li>Pod C (Full gateway): Mongo + Redis + Filter
 * </ul>
 *
 * <pre>
 * fluxgate:
 *   mongo:
 *     enabled: true
 *     uri: mongodb://localhost:27017
 *     database: fluxgate
 *   redis:
 *     enabled: true
 *     uri: redis://localhost:6379
 *   ratelimit:
 *     enabled: true
 *     filter-enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "fluxgate")
public class FluxgateProperties {

  /** MongoDB configuration for rule storage. */
  @NestedConfigurationProperty private MongoProperties mongo = new MongoProperties();

  /** Redis configuration for rate limiting runtime. */
  @NestedConfigurationProperty private RedisProperties redis = new RedisProperties();

  /** Rate limiting configuration. */
  @NestedConfigurationProperty private RateLimitProperties ratelimit = new RateLimitProperties();

  /** Metrics configuration. */
  @NestedConfigurationProperty private MetricsProperties metrics = new MetricsProperties();

  /** Actuator configuration. */
  @NestedConfigurationProperty private ActuatorProperties actuator = new ActuatorProperties();

  public MongoProperties getMongo() {
    return mongo;
  }

  public void setMongo(MongoProperties mongo) {
    this.mongo = mongo;
  }

  public RedisProperties getRedis() {
    return redis;
  }

  public void setRedis(RedisProperties redis) {
    this.redis = redis;
  }

  public RateLimitProperties getRatelimit() {
    return ratelimit;
  }

  public void setRatelimit(RateLimitProperties ratelimit) {
    this.ratelimit = ratelimit;
  }

  public MetricsProperties getMetrics() {
    return metrics;
  }

  public void setMetrics(MetricsProperties metrics) {
    this.metrics = metrics;
  }

  public ActuatorProperties getActuator() {
    return actuator;
  }

  public void setActuator(ActuatorProperties actuator) {
    this.actuator = actuator;
  }

  // =========================================================================
  // Nested Configuration Classes
  // =========================================================================

  /** MongoDB-specific configuration. */
  public static class MongoProperties {

    /** Enable MongoDB integration for rule storage. When false, no Mongo beans are created. */
    private boolean enabled = false;

    /**
     * MongoDB connection URI. Example:
     * mongodb://user:pass@localhost:27017/fluxgate?authSource=admin
     */
    private String uri = "mongodb://localhost:27017/fluxgate";

    /** MongoDB database name. */
    private String database = "fluxgate";

    /** Collection name for rate limit rules. */
    private String ruleCollection = "rate_limit_rules";

    /**
     * Collection name for rate limit events/metrics. Optional - if not set, event recording is
     * disabled.
     */
    private String eventCollection;

    /**
     * DDL auto mode for MongoDB collections.
     *
     * <ul>
     *   <li>VALIDATE - Only validate that collections exist (default)
     *   <li>CREATE - Create collections and indexes if they don't exist
     * </ul>
     */
    private DdlAuto ddlAuto = DdlAuto.VALIDATE;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getUri() {
      return uri;
    }

    public void setUri(String uri) {
      this.uri = uri;
    }

    public String getDatabase() {
      return database;
    }

    public void setDatabase(String database) {
      this.database = database;
    }

    public String getRuleCollection() {
      return ruleCollection;
    }

    public void setRuleCollection(String ruleCollection) {
      this.ruleCollection = ruleCollection;
    }

    public String getEventCollection() {
      return eventCollection;
    }

    public void setEventCollection(String eventCollection) {
      this.eventCollection = eventCollection;
    }

    public DdlAuto getDdlAuto() {
      return ddlAuto;
    }

    public void setDdlAuto(DdlAuto ddlAuto) {
      this.ddlAuto = ddlAuto;
    }

    /** Check if event collection is configured. */
    public boolean hasEventCollection() {
      return eventCollection != null && !eventCollection.trim().isEmpty();
    }
  }

  /** DDL auto mode for MongoDB collections. */
  public enum DdlAuto {
    /** Only validate that collections exist. Throws error if missing. */
    VALIDATE,
    /** Create collections and indexes if they don't exist. */
    CREATE
  }

  /**
   * Redis-specific configuration.
   *
   * <p>Supports both Standalone and Cluster modes:
   *
   * <ul>
   *   <li>Standalone: Single URI (e.g., redis://localhost:6379)
   *   <li>Cluster: Comma-separated URIs (e.g., redis://node1:6379,redis://node2:6379)
   *   <li>Explicit mode: Set mode property to "standalone" or "cluster"
   * </ul>
   */
  public static class RedisProperties {

    /**
     * Enable Redis integration for rate limiting runtime. When false, no Redis beans are created.
     */
    private boolean enabled = false;

    /**
     * Redis connection mode. Options: "standalone" (default), "cluster", or "auto" (auto-detect).
     *
     * <p>When set to "auto" (default), the mode is detected from the URI:
     *
     * <ul>
     *   <li>Single URI → Standalone
     *   <li>Comma-separated URIs → Cluster
     * </ul>
     */
    private String mode = "auto";

    /**
     * Redis connection URI for standalone mode, or comma-separated URIs for cluster mode.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>Standalone: redis://localhost:6379
     *   <li>Standalone with auth: redis://:password@localhost:6379/0
     *   <li>Cluster: redis://node1:6379,redis://node2:6379,redis://node3:6379
     * </ul>
     */
    private String uri = "redis://localhost:6379";

    /** Connection timeout in milliseconds. Default: 5000 (5 seconds). */
    private long timeoutMs = 5000;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getMode() {
      return mode;
    }

    public void setMode(String mode) {
      this.mode = mode;
    }

    public String getUri() {
      return uri;
    }

    public void setUri(String uri) {
      this.uri = uri;
    }

    public long getTimeoutMs() {
      return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
      this.timeoutMs = timeoutMs;
    }

    /**
     * Determine the effective Redis mode based on configuration.
     *
     * @return "standalone" or "cluster"
     */
    public String getEffectiveMode() {
      if ("cluster".equalsIgnoreCase(mode)) {
        return "cluster";
      } else if ("standalone".equalsIgnoreCase(mode)) {
        return "standalone";
      } else {
        // Auto-detect from URI
        return (uri != null && uri.contains(",")) ? "cluster" : "standalone";
      }
    }

    /**
     * Check if cluster mode is configured.
     *
     * @return true if cluster mode
     */
    public boolean isClusterMode() {
      return "cluster".equals(getEffectiveMode());
    }
  }

  /** Rate limiting behavior configuration. */
  public static class RateLimitProperties {

    /** Enable rate limiting in general. Master switch for rate limiting functionality. */
    private boolean enabled = true;

    /**
     * Enable HTTP filter for automatic rate limiting. Requires a RateLimiter bean to be present.
     */
    private boolean filterEnabled = false;

    /** Default rule set ID to use when no specific rule set is matched. */
    private String defaultRuleSetId;

    /**
     * Filter order (lower = higher priority). Default is high priority to run before other filters.
     */
    private int filterOrder = Integer.MIN_VALUE + 100;

    /** URL patterns to include in rate limiting. Default: all URLs (/**) */
    private String[] includePatterns = {"/*"};

    /** URL patterns to exclude from rate limiting. Example: /health, /actuator/** */
    private String[] excludePatterns = {};

    /** Header name for client IP when behind a proxy. Common values: X-Forwarded-For, X-Real-IP */
    private String clientIpHeader = "X-Forwarded-For";

    /**
     * Whether to trust the client IP header. Set to false in production if not behind a trusted
     * proxy.
     */
    private boolean trustClientIpHeader = true;

    /** Enable rate limit response headers. X-RateLimit-Limit, X-RateLimit-Remaining, etc. */
    private boolean includeHeaders = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isFilterEnabled() {
      return filterEnabled;
    }

    public void setFilterEnabled(boolean filterEnabled) {
      this.filterEnabled = filterEnabled;
    }

    public String getDefaultRuleSetId() {
      return defaultRuleSetId;
    }

    public void setDefaultRuleSetId(String defaultRuleSetId) {
      this.defaultRuleSetId = defaultRuleSetId;
    }

    public int getFilterOrder() {
      return filterOrder;
    }

    public void setFilterOrder(int filterOrder) {
      this.filterOrder = filterOrder;
    }

    public String[] getIncludePatterns() {
      return includePatterns;
    }

    public void setIncludePatterns(String[] includePatterns) {
      this.includePatterns = includePatterns;
    }

    public String[] getExcludePatterns() {
      return excludePatterns;
    }

    public void setExcludePatterns(String[] excludePatterns) {
      this.excludePatterns = excludePatterns;
    }

    public String getClientIpHeader() {
      return clientIpHeader;
    }

    public void setClientIpHeader(String clientIpHeader) {
      this.clientIpHeader = clientIpHeader;
    }

    public boolean isTrustClientIpHeader() {
      return trustClientIpHeader;
    }

    public void setTrustClientIpHeader(boolean trustClientIpHeader) {
      this.trustClientIpHeader = trustClientIpHeader;
    }

    public boolean isIncludeHeaders() {
      return includeHeaders;
    }

    public void setIncludeHeaders(boolean includeHeaders) {
      this.includeHeaders = includeHeaders;
    }
  }

  /** Metrics configuration for Prometheus/Micrometer integration. */
  public static class MetricsProperties {

    /** Enable FluxGate metrics collection. Requires Micrometer on the classpath. */
    private boolean enabled = true;

    /**
     * Include endpoint tag in metrics. When true, metrics are tagged with the request endpoint.
     * Disable if you have many unique endpoints to reduce cardinality.
     */
    private boolean includeEndpoint = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isIncludeEndpoint() {
      return includeEndpoint;
    }

    public void setIncludeEndpoint(boolean includeEndpoint) {
      this.includeEndpoint = includeEndpoint;
    }
  }

  /** Actuator configuration for health endpoints. */
  public static class ActuatorProperties {

    /** Nested health configuration. */
    private HealthProperties health = new HealthProperties();

    public HealthProperties getHealth() {
      return health;
    }

    public void setHealth(HealthProperties health) {
      this.health = health;
    }

    /** Health endpoint configuration. */
    public static class HealthProperties {

      /** Enable FluxGate health indicator. Provides health status at /actuator/health/fluxgate. */
      private boolean enabled = true;

      public boolean isEnabled() {
        return enabled;
      }

      public void setEnabled(boolean enabled) {
        this.enabled = enabled;
      }
    }
  }
}
