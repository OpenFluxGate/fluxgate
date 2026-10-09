package org.fluxgate.spring.properties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fluxgate.core.constants.FluxgateConstants;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.spring.filter.IdentitySource;
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

  /** Rule reload configuration for hot reload support. */
  @NestedConfigurationProperty private ReloadProperties reload = new ReloadProperties();

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

  public ReloadProperties getReload() {
    return reload;
  }

  public void setReload(ReloadProperties reload) {
    this.reload = reload;
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

    /**
     * How long a rate limit event is kept in {@code event-collection}.
     *
     * <p>Applied as a MongoDB TTL index on the event document's {@code createdAt} field. Without
     * one the collection grows without bound, and every document in it carries a client IP, a user
     * id and an API key fingerprint - so an old backup stays a data breach for as long as it
     * exists. Set to zero to manage retention yourself; the index is then not created.
     */
    private Duration eventRetention = Duration.ofDays(30);

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

    public Duration getEventRetention() {
      return eventRetention;
    }

    public void setEventRetention(Duration eventRetention) {
      this.eventRetention = eventRetention;
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

    private static final String SCHEME_SEPARATOR = "://";

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

    /**
     * Fail application startup when the Redis connection or the Lua script load fails.
     *
     * <p>Kept {@code false} by default: rate limiting is an auxiliary concern, so the context still
     * starts and the connection is established on first use with a background retry. The health
     * indicator reports the limiter as degraded until it succeeds. Set to {@code true} to restore
     * the eager connect that aborts the boot.
     */
    private boolean failFast = false;

    /**
     * Upper bound on the TTL of a token bucket in Redis.
     *
     * <p>A bucket normally lives its band's window plus 10%. Without a cap, a rule with a long
     * window keeps one Redis hash per distinct key alive for that whole window - and identity keys
     * are cheap to forge, so the amount of memory an unauthenticated caller can pin down grows with
     * the window. Seven days keeps long windows usable while bounding that. Raise it deliberately,
     * and only for scopes whose cardinality you control; the limiter logs a warning once per rule
     * whose window this cap shortens.
     */
    private Duration maxBucketTtl = Duration.ofDays(7);

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

    public boolean isFailFast() {
      return failFast;
    }

    public void setFailFast(boolean failFast) {
      this.failFast = failFast;
    }

    public long getTimeoutMs() {
      return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
      this.timeoutMs = timeoutMs;
    }

    public Duration getMaxBucketTtl() {
      return maxBucketTtl;
    }

    public void setMaxBucketTtl(Duration maxBucketTtl) {
      this.maxBucketTtl = maxBucketTtl;
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
        return listsMultipleNodes(uri) ? "cluster" : "standalone";
      }
    }

    /**
     * Whether the URI describes several nodes rather than one.
     *
     * <p>A cluster is recognised either by more than one URI scheme or by a comma that sits
     * <em>after</em> the credentials. A plain {@code contains(",")} misreads {@code
     * redis://:pa,ss@host:6379} as two malformed nodes and silently starts the client in cluster
     * mode against a standalone server.
     *
     * <p>This mirrors {@code org.fluxgate.redis.connection.RedisUriUtils#detectMode}, which owns
     * the same rule for the Redis module. It is deliberately not delegated to: {@code
     * fluxgate-redis-ratelimiter} is an optional dependency, while this properties class is loaded
     * by every application using the starter, including Mongo-only control-plane deployments that
     * do not have the Redis module or Lettuce on the classpath. Referencing it here would turn a
     * supported deployment into a {@code NoClassDefFoundError} at startup.
     */
    private static boolean listsMultipleNodes(String uri) {
      if (uri == null || uri.trim().isEmpty()) {
        return false;
      }
      if (countSchemes(uri) > 1) {
        return true;
      }
      return uri.indexOf(',', authorityStart(uri)) >= 0;
    }

    private static int countSchemes(String uri) {
      int count = 0;
      int from = uri.indexOf(SCHEME_SEPARATOR);
      while (from >= 0) {
        count++;
        from = uri.indexOf(SCHEME_SEPARATOR, from + SCHEME_SEPARATOR.length());
      }
      return count;
    }

    /** Index just after the credentials, which is the first place a node separator may appear. */
    private static int authorityStart(String uri) {
      int credentialsEnd = uri.lastIndexOf('@');
      if (credentialsEnd >= 0) {
        return credentialsEnd + 1;
      }
      int scheme = uri.indexOf(SCHEME_SEPARATOR);
      return scheme >= 0 ? scheme + SCHEME_SEPARATOR.length() : 0;
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
     * Enable HTTP filter for automatic rate limiting.
     *
     * @deprecated this flag never controlled registration. The filter is registered by
     *     {@code @EnableFluxgateFilter} and switched off by {@code
     *     fluxgate.ratelimit.enabled=false}; the health indicator reports whether a filter bean
     *     actually exists.
     */
    @Deprecated private boolean filterEnabled = false;

    /** Default rule set ID to use when no specific rule set is matched. */
    private String defaultRuleSetId;

    /**
     * Behavior when no matching rule set is found.
     *
     * <ul>
     *   <li>ALLOW - Allow the request (permissive mode)
     *   <li>DENY - Deny the request (default, fail-closed)
     * </ul>
     *
     * <p>In production environments with strict security requirements, consider setting this to
     * DENY to ensure all requests are rate-limited.
     */
    private MissingRuleBehavior missingRuleBehavior = MissingRuleBehavior.DENY;

    /**
     * Behavior when the rate limiter itself fails, such as Redis/API errors.
     *
     * <ul>
     *   <li>ALLOW - Allow the request (fail-open)
     *   <li>DENY - Deny the request (default, fail-closed)
     * </ul>
     */
    private FailureBehavior failureBehavior = FailureBehavior.DENY;

    /**
     * Filter order (lower = higher priority).
     *
     * <p>When not set, {@code @EnableFluxgateFilter#filterOrder()} is used, which defaults to 1.
     * Identity based scopes ({@code PER_USER}, {@code PER_API_KEY}) need an order <em>after</em>
     * Spring Security so the authenticated principal is available; {@code PER_IP} may run earlier.
     */
    private Integer filterOrder;

    /**
     * URL patterns to include in rate limiting, matched against the normalized, context-path
     * independent request path.
     *
     * <p>When not set, {@code @EnableFluxgateFilter#includePatterns()} is used, and when that is
     * empty too every path is rate limited, which is equivalent to {@code /**}. Note that {@code
     * /*} matches a single segment only, so use {@code /**} to cover nested paths.
     */
    private String[] includePatterns;

    /**
     * URL patterns to exclude from rate limiting. Example: /health, /actuator/**
     *
     * <p>When not set, {@code @EnableFluxgateFilter#excludePatterns()} is used.
     */
    private String[] excludePatterns;

    /**
     * Whether include/exclude patterns are matched case sensitively. Default true, which keeps the
     * historical behaviour. Set to false to match the way most servlet containers route.
     */
    private boolean caseSensitivePatterns = true;

    /** Header name for client IP when behind a proxy. Common values: X-Forwarded-For, X-Real-IP */
    private String clientIpHeader = "X-Forwarded-For";

    /**
     * Whether to trust the client IP header. Keep false unless the application is behind a trusted
     * proxy that strips incoming client-supplied forwarding headers.
     */
    private boolean trustClientIpHeader = false;

    /**
     * Reverse proxies allowed to set the forwarding header, as IP literals or CIDR blocks such as
     * {@code 10.0.0.0/8} or {@code 2001:db8::/32}. Both IPv4 and IPv6 are supported.
     *
     * <p>Default empty: nothing can be verified, so with {@code trust-client-ip-header=true} the
     * forwarding header is taken at face value and one warning is logged at startup. Configure this
     * in production, otherwise a client can rotate the header per request and bypass every {@code
     * PER_IP} limit while filling the store with one bucket per forged value.
     */
    private List<String> trustedProxies = new ArrayList<>();

    /**
     * Whether allow-listed request headers are copied into the rate limit context. Default false.
     *
     * <p>The context is handed to metrics recorders that may persist it, so headers are opt-in and
     * restricted to {@link #getHeaderAllowlist()}. Credential carrying headers are never copied,
     * whatever the allow list says: {@code Authorization}, {@code Authentication}, {@code
     * WWW-Authenticate}, {@code Proxy-Authenticate}, {@code Proxy-Authorization}, {@code Cookie},
     * {@code Set-Cookie}, {@code X-API-Key}, {@code X-Auth-Token}, {@code X-CSRF-Token}, {@code
     * X-XSRF-Token} and {@code X-Amz-Security-Token}. The requested session id is never collected
     * either.
     */
    private boolean collectHeaders = false;

    /**
     * Request headers that may be copied into the rate limit context when {@link
     * #isCollectHeaders()} is enabled. Matched case-insensitively.
     */
    private List<String> headerAllowlist = new ArrayList<>();

    /**
     * Whether the raw query string is put into the logging MDC. Default false, because query
     * strings routinely carry tokens and other credentials.
     */
    private boolean logQueryString = false;

    /**
     * Optional request header carrying the cost of the request in permits, for example {@code
     * X-RateLimit-Cost}. When not set every request costs one permit.
     *
     * <p>Values below 1 and unparseable values are treated as 1, and the value is capped by {@link
     * #getMaxCost()}. Weighted requests require a handler that implements the three argument {@code
     * tryConsume}.
     */
    private String costHeader;

    /** Upper bound applied to the value of {@link #getCostHeader()}. Default 1000. */
    private long maxCost = 1000;

    /**
     * Enable rate limit response headers. Master switch over {@link #getResponse()}: when false
     * neither the legacy {@code X-RateLimit-*} nor the IETF {@code RateLimit-*} family is written.
     */
    private boolean includeHeaders = true;

    /** 429 response shape configuration. */
    @NestedConfigurationProperty private ResponseProperties response = new ResponseProperties();

    /**
     * Which rate limiter backs the library-provided handler.
     *
     * <ul>
     *   <li>AUTO - Redis when {@code fluxgate.redis.enabled=true}, otherwise in-memory (default)
     *   <li>REDIS - always the distributed Redis limiter
     *   <li>IN_MEMORY - always the in-memory Bucket4j limiter (single instance / development)
     * </ul>
     */
    private RateLimiterMode mode = RateLimiterMode.AUTO;

    /**
     * Behavior when the value required by a rule's {@code LimitScope} is missing from the request.
     *
     * <ul>
     *   <li>FALLBACK_TO_IP - fall back to the client IP (default, legacy behavior)
     *   <li>REJECT - reject the request instead of limiting a broader key
     * </ul>
     */
    private MissingKeyBehavior missingKeyBehavior = MissingKeyBehavior.FALLBACK_TO_IP;

    /** Where the caller's identity is taken from. */
    @NestedConfigurationProperty private IdentityProperties identity = new IdentityProperties();

    /** Fallback limiter used when the primary limiter fails or its circuit is open. */
    @NestedConfigurationProperty private FallbackProperties fallback = new FallbackProperties();

    /** WAIT_FOR_REFILL policy configuration. */
    @NestedConfigurationProperty
    private WaitForRefillProperties waitForRefill = new WaitForRefillProperties();

    /**
     * Whether a missing or unusable rate limiting setup should fail the application startup instead
     * of degrading silently.
     *
     * <p>When {@code true} and a {@link org.fluxgate.core.ratelimiter.RateLimiter} bean exists but
     * no {@link org.fluxgate.core.spi.RateLimitRuleSetProvider} is available (so no limit can
     * actually be enforced), the application context will refuse to start. Default {@code false}:
     * the {@link org.fluxgate.spring.handler.MissingRuleSetProviderRateLimitHandler} logs one
     * startup ERROR and applies {@code failure-behavior} instead.
     */
    private boolean failOnMissingHandler = false;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * @return the configured flag
     * @deprecated see the {@code filterEnabled} field
     */
    @Deprecated
    public boolean isFilterEnabled() {
      return filterEnabled;
    }

    /**
     * @param filterEnabled the flag to set
     * @deprecated see the {@code filterEnabled} field
     */
    @Deprecated
    public void setFilterEnabled(boolean filterEnabled) {
      this.filterEnabled = filterEnabled;
    }

    public String getDefaultRuleSetId() {
      return defaultRuleSetId;
    }

    public void setDefaultRuleSetId(String defaultRuleSetId) {
      this.defaultRuleSetId = defaultRuleSetId;
    }

    public Integer getFilterOrder() {
      return filterOrder;
    }

    public void setFilterOrder(Integer filterOrder) {
      this.filterOrder = filterOrder;
    }

    public boolean isCaseSensitivePatterns() {
      return caseSensitivePatterns;
    }

    public void setCaseSensitivePatterns(boolean caseSensitivePatterns) {
      this.caseSensitivePatterns = caseSensitivePatterns;
    }

    public List<String> getTrustedProxies() {
      return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
      this.trustedProxies = trustedProxies;
    }

    public boolean isCollectHeaders() {
      return collectHeaders;
    }

    public void setCollectHeaders(boolean collectHeaders) {
      this.collectHeaders = collectHeaders;
    }

    public List<String> getHeaderAllowlist() {
      return headerAllowlist;
    }

    public void setHeaderAllowlist(List<String> headerAllowlist) {
      this.headerAllowlist = headerAllowlist;
    }

    public boolean isLogQueryString() {
      return logQueryString;
    }

    public void setLogQueryString(boolean logQueryString) {
      this.logQueryString = logQueryString;
    }

    public String getCostHeader() {
      return costHeader;
    }

    public void setCostHeader(String costHeader) {
      this.costHeader = costHeader;
    }

    public long getMaxCost() {
      return maxCost;
    }

    public void setMaxCost(long maxCost) {
      this.maxCost = maxCost;
    }

    public ResponseProperties getResponse() {
      return response;
    }

    public void setResponse(ResponseProperties response) {
      this.response = response;
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

    public MissingRuleBehavior getMissingRuleBehavior() {
      return missingRuleBehavior;
    }

    public void setMissingRuleBehavior(MissingRuleBehavior missingRuleBehavior) {
      this.missingRuleBehavior = missingRuleBehavior;
    }

    /**
     * Check if requests should be denied when no rule is found.
     *
     * @return true if missing rules should result in denial
     */
    public boolean isDenyWhenRuleMissing() {
      return missingRuleBehavior == MissingRuleBehavior.DENY;
    }

    public FailureBehavior getFailureBehavior() {
      return failureBehavior;
    }

    public void setFailureBehavior(FailureBehavior failureBehavior) {
      this.failureBehavior = failureBehavior;
    }

    /**
     * Check if requests should be allowed when the rate limiter fails.
     *
     * @return true if rate limiter errors should fail open
     */
    public boolean isAllowWhenLimiterFails() {
      return failureBehavior == FailureBehavior.ALLOW;
    }

    public RateLimiterMode getMode() {
      return mode;
    }

    public void setMode(RateLimiterMode mode) {
      this.mode = mode;
    }

    public MissingKeyBehavior getMissingKeyBehavior() {
      return missingKeyBehavior;
    }

    public void setMissingKeyBehavior(MissingKeyBehavior missingKeyBehavior) {
      this.missingKeyBehavior = missingKeyBehavior;
    }

    public IdentityProperties getIdentity() {
      return identity;
    }

    public void setIdentity(IdentityProperties identity) {
      this.identity = identity;
    }

    public FallbackProperties getFallback() {
      return fallback;
    }

    public void setFallback(FallbackProperties fallback) {
      this.fallback = fallback;
    }

    public WaitForRefillProperties getWaitForRefill() {
      return waitForRefill;
    }

    public void setWaitForRefill(WaitForRefillProperties waitForRefill) {
      this.waitForRefill = waitForRefill;
    }

    public boolean isFailOnMissingHandler() {
      return failOnMissingHandler;
    }

    public void setFailOnMissingHandler(boolean failOnMissingHandler) {
      this.failOnMissingHandler = failOnMissingHandler;
    }

    /**
     * YAML-defined rule sets.
     *
     * <p>When non-empty a {@code PropertiesRuleSetProvider} is registered automatically. If MongoDB
     * is also enabled, the two sources are composed: YAML rules take precedence and MongoDB is used
     * as a fallback for rule set ids not found in YAML.
     *
     * <pre>
     * fluxgate:
     *   ratelimit:
     *     rule-sets:
     *       - id: api-limits
     *         description: Default API limits
     *         access-control:
     *           denied-ips: [203.0.113.0/24]
     *           allowed-keys: [key:internal-service]
     *         rules:
     *           - id: per-ip-100rpm
     *             scope: PER_IP
     *             key-strategy-id: ip
     *             on-limit-exceed-policy: REJECT_REQUEST
     *             matcher:
     *               path-patterns: [/api/**]
     *             bands:
     *               - capacity: 100
     *                 window: 60s
     * </pre>
     */
    private List<RuleSetProperties> ruleSets = new java.util.ArrayList<>();

    /**
     * Returns the list of YAML-defined rule sets. Empty when no rule sets are configured.
     *
     * @return the rule sets list (never null)
     */
    public List<RuleSetProperties> getRuleSets() {
      return ruleSets;
    }

    /**
     * Sets the YAML-defined rule sets.
     *
     * @param ruleSets the rule sets (must not be null)
     */
    public void setRuleSets(List<RuleSetProperties> ruleSets) {
      this.ruleSets = ruleSets != null ? ruleSets : new java.util.ArrayList<>();
    }
  }

  /**
   * How the caller's identity is resolved for {@code PER_USER} and {@code PER_API_KEY} scopes.
   *
   * <pre>
   * fluxgate:
   *   ratelimit:
   *     identity:
   *       source: PRINCIPAL_THEN_HEADERS
   *       user-id-header: X-User-Id
   *       api-key-header: X-API-Key
   * </pre>
   */
  public static class IdentityProperties {

    /**
     * Where the user id comes from.
     *
     * <ul>
     *   <li>HEADERS - {@link #getUserIdHeader()} and {@link #getApiKeyHeader()} only
     *   <li>PRINCIPAL - the authenticated principal only, identity headers are ignored
     *   <li>PRINCIPAL_THEN_HEADERS - the principal, falling back to the header when unauthenticated
     * </ul>
     *
     * <p>Unset means {@code PRINCIPAL_THEN_HEADERS} when Spring Security is on the classpath and
     * {@code HEADERS} otherwise, so a secured application gets verified identities without
     * configuring anything. The effective value is logged once at startup.
     */
    private IdentitySource source;

    /** Header carrying the user id when identity comes from headers. Default {@code X-User-Id}. */
    private String userIdHeader = FluxgateConstants.Headers.USER_ID;

    /** Header carrying the API key. Default {@code X-API-Key}. */
    private String apiKeyHeader = FluxgateConstants.Headers.API_KEY;

    /**
     * Returns the configured identity source.
     *
     * @return the configured source, or null when it should be derived from the classpath
     */
    public IdentitySource getSource() {
      return source;
    }

    public void setSource(IdentitySource source) {
      this.source = source;
    }

    public String getUserIdHeader() {
      return userIdHeader;
    }

    public void setUserIdHeader(String userIdHeader) {
      this.userIdHeader = userIdHeader;
    }

    public String getApiKeyHeader() {
      return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
      this.apiKeyHeader = apiKeyHeader;
    }
  }

  /** Which rate limiter implementation backs the library-provided handler. */
  public enum RateLimiterMode {
    /** Redis when it is enabled, otherwise the in-memory limiter. */
    AUTO,
    /** Always use the distributed Redis limiter. */
    REDIS,
    /** Always use the in-memory Bucket4j limiter (not distributed). */
    IN_MEMORY
  }

  /** Fallback limiter used when the primary limiter fails or its circuit breaker is open. */
  public static class FallbackProperties {

    /**
     * Fallback mode.
     *
     * <ul>
     *   <li>NONE - apply {@code failure-behavior} (default)
     *   <li>IN_MEMORY - limit locally with Bucket4j so each instance keeps enforcing its own share
     * </ul>
     */
    private FallbackMode mode = FallbackMode.NONE;

    /** Maximum number of buckets held by the in-memory fallback limiter. */
    private long maxBuckets = 100_000L;

    /** Idle time after which an in-memory fallback bucket is evicted. */
    private Duration expireAfterAccess = Duration.ofHours(1);

    public FallbackMode getMode() {
      return mode;
    }

    public void setMode(FallbackMode mode) {
      this.mode = mode;
    }

    public long getMaxBuckets() {
      return maxBuckets;
    }

    public void setMaxBuckets(long maxBuckets) {
      this.maxBuckets = maxBuckets;
    }

    public Duration getExpireAfterAccess() {
      return expireAfterAccess;
    }

    public void setExpireAfterAccess(Duration expireAfterAccess) {
      this.expireAfterAccess = expireAfterAccess;
    }
  }

  /** Fallback behavior when the primary rate limiter is unavailable. */
  public enum FallbackMode {
    /** No fallback limiter; {@code failure-behavior} decides. */
    NONE,
    /** Fall back to an in-memory Bucket4j limiter (per-instance limits, not distributed). */
    IN_MEMORY
  }

  /** Behavior when no matching rate limit rule is found. */
  public enum MissingRuleBehavior {
    /** Allow the request to proceed (permissive mode). */
    ALLOW,
    /** Deny the request (strict mode, fail-closed). */
    DENY
  }

  /** Behavior when rate limiter execution fails. */
  public enum FailureBehavior {
    /** Allow the request to proceed (fail-open). */
    ALLOW,
    /** Deny the request (fail-closed). */
    DENY
  }

  /**
   * Configuration for WAIT_FOR_REFILL policy.
   *
   * <p>When a rule has OnLimitExceedPolicy.WAIT_FOR_REFILL, the filter will wait for tokens to
   * become available instead of immediately rejecting the request.
   *
   * <p>Example configuration:
   *
   * <pre>
   * fluxgate:
   *   ratelimit:
   *     waitForRefill:
   *       enabled: true
   *       maxWaitTimeMs: 5000
   *       maxConcurrentWaits: 100
   * </pre>
   */
  public static class WaitForRefillProperties {

    /**
     * Enable WAIT_FOR_REFILL behavior. When false, requests exceeding the limit are immediately
     * rejected even if the rule has WAIT_FOR_REFILL policy.
     */
    private boolean enabled = false;

    /**
     * Maximum time to wait for token refill in milliseconds. If the required wait time exceeds this
     * value, the request is rejected immediately. Default: 5000ms (5 seconds).
     */
    private long maxWaitTimeMs = 5000;

    /**
     * Maximum number of concurrent waiting requests. A single semaphore, shared by the filter or
     * the aspect, limits how many requests may be parked at the same time; the rest are rejected
     * immediately. Default: 50.
     *
     * <p>Waiting parks a container worker thread. Tomcat's default {@code
     * server.tomcat.threads.max} is 200, so 50 permits already let a quarter of the pool sit idle
     * for up to {@link #getMaxWaitTimeMs()} each. Raise this only after raising the thread pool,
     * and prefer answering 429 with an accurate {@code Retry-After} over parking threads at all.
     */
    private int maxConcurrentWaits = 50;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public long getMaxWaitTimeMs() {
      return maxWaitTimeMs;
    }

    public void setMaxWaitTimeMs(long maxWaitTimeMs) {
      this.maxWaitTimeMs = maxWaitTimeMs;
    }

    public int getMaxConcurrentWaits() {
      return maxConcurrentWaits;
    }

    public void setMaxConcurrentWaits(int maxConcurrentWaits) {
      this.maxConcurrentWaits = maxConcurrentWaits;
    }
  }

  /**
   * Shape of the 429 response.
   *
   * <pre>
   * fluxgate:
   *   ratelimit:
   *     response:
   *       include-legacy-headers: true
   *       include-standard-headers: true
   *       content-type: application/problem+json
   * </pre>
   *
   * <p>Register a {@code org.fluxgate.spring.filter.RateLimitResponseWriter} bean to replace the
   * body entirely; these properties then only drive the headers.
   */
  public static class ResponseProperties {

    /**
     * Write the legacy {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining} and {@code
     * X-RateLimit-Reset} (epoch seconds) headers, plus {@code Retry-After} on rejection. Default
     * true.
     */
    private boolean includeLegacyHeaders = true;

    /**
     * Write the IETF style {@code RateLimit-Limit}, {@code RateLimit-Remaining}, {@code
     * RateLimit-Reset} (delta seconds) and {@code RateLimit-Policy} headers. Default true.
     */
    private boolean includeStandardHeaders = true;

    /**
     * Content type of the 429 body. Default {@code application/problem+json} (RFC 9457); {@code
     * charset=UTF-8} is appended automatically when the value does not carry a charset.
     */
    private String contentType = "application/problem+json";

    /**
     * Optional body replacing the default problem document. The placeholders {@code {status}},
     * {@code {retryAfterSeconds}}, {@code {retryAfterMillis}}, {@code {remaining}} and {@code
     * {limit}} are substituted.
     */
    private String bodyTemplate;

    public boolean isIncludeLegacyHeaders() {
      return includeLegacyHeaders;
    }

    public void setIncludeLegacyHeaders(boolean includeLegacyHeaders) {
      this.includeLegacyHeaders = includeLegacyHeaders;
    }

    public boolean isIncludeStandardHeaders() {
      return includeStandardHeaders;
    }

    public void setIncludeStandardHeaders(boolean includeStandardHeaders) {
      this.includeStandardHeaders = includeStandardHeaders;
    }

    public String getContentType() {
      return contentType;
    }

    public void setContentType(String contentType) {
      this.contentType = contentType;
    }

    public String getBodyTemplate() {
      return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
      this.bodyTemplate = bodyTemplate;
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

    /**
     * Replace high cardinality path segments with {@code {id}} before using the path as a metric
     * tag. Numeric, UUID and 24 character hex segments are normalized, so {@code
     * /api/users/12345/orders} becomes {@code /api/users/{id}/orders}. Default true.
     *
     * <p>Without this, one meter is created per distinct URI: an application with path variables,
     * or anyone scanning URLs, grows the registry without bound.
     */
    private boolean endpointNormalization = true;

    /**
     * Hard cap on the number of distinct endpoint tag values across <em>all</em> FluxGate meters -
     * counters, timers and gauges alike. Default 1000.
     *
     * <p>N-13: once the cap is reached the recorder collapses further endpoints into the single tag
     * value {@code other}, so a scanner inventing paths adds one series instead of one per path. A
     * Micrometer {@code MeterFilter} denies anything past the cap as a second line of defence, for
     * meters a custom recorder registers.
     */
    private int maxEndpointTags = 1000;

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

    public boolean isEndpointNormalization() {
      return endpointNormalization;
    }

    public void setEndpointNormalization(boolean endpointNormalization) {
      this.endpointNormalization = endpointNormalization;
    }

    public int getMaxEndpointTags() {
      return maxEndpointTags;
    }

    public void setMaxEndpointTags(int maxEndpointTags) {
      this.maxEndpointTags = maxEndpointTags;
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

      /**
       * Whether dependency detail such as {@code host:port}, cluster node counts and failure
       * messages is included in the health payload. Default false.
       *
       * <p>N-6b: those details are reconnaissance for anyone who can read the endpoint, and {@code
       * management.endpoint.health.show-details=always} is a common setting. Without them the
       * payload still reports the status and the exception type, and the full message is logged at
       * WARN. Turn this on only behind authentication ({@code show-details=when_authorized}).
       */
      private boolean includeEndpointDetails = false;

      /**
       * HTTP status code returned when the {@link org.springframework.boot.actuate.health.Status}
       * is {@code DEGRADED}.
       *
       * <p>Spring Boot maps any unrecognised status to {@code 200}; a degraded FluxGate indicates
       * partial functionality (e.g. the Redis limiter is unreachable and the circuit has opened),
       * so {@code 503} is a safer default to signal load balancers and health check probes.
       *
       * <p>Set to {@code 0} or a negative value to disable the automatic mapping bean and keep
       * Spring Boot's default behaviour. A user-defined {@code HttpCodeStatusMapper} bean always
       * takes precedence regardless of this setting.
       */
      private int degradedHttpStatus = 503;

      public boolean isEnabled() {
        return enabled;
      }

      public void setEnabled(boolean enabled) {
        this.enabled = enabled;
      }

      public boolean isIncludeEndpointDetails() {
        return includeEndpointDetails;
      }

      public void setIncludeEndpointDetails(boolean includeEndpointDetails) {
        this.includeEndpointDetails = includeEndpointDetails;
      }

      public int getDegradedHttpStatus() {
        return degradedHttpStatus;
      }

      public void setDegradedHttpStatus(int degradedHttpStatus) {
        this.degradedHttpStatus = degradedHttpStatus;
      }
    }
  }

  /**
   * Rule reload configuration for hot reload support.
   *
   * <p>Supports multiple strategies:
   *
   * <ul>
   *   <li>AUTO - Automatically select best strategy (Pub/Sub if Redis available, else Polling)
   *   <li>PUBSUB - Use Redis Pub/Sub for real-time notifications
   *   <li>POLLING - Periodically check for changes
   *   <li>NONE - Disable hot reload (always fetch fresh from provider)
   * </ul>
   *
   * <pre>
   * fluxgate:
   *   reload:
   *     enabled: true
   *     strategy: AUTO
   *     cache:
   *       enabled: true
   *       ttl: 5m
   *       max-size: 1000
   *     polling:
   *       interval: 30s
   *     pubsub:
   *       channel: fluxgate:rule-reload
   * </pre>
   */
  public static class ReloadProperties {

    /** Enable rule hot reload feature. */
    private boolean enabled = true;

    /**
     * Reload strategy to use.
     *
     * <ul>
     *   <li>AUTO - Use Pub/Sub if Redis is available, otherwise use Polling
     *   <li>PUBSUB - Use Redis Pub/Sub only
     *   <li>POLLING - Use periodic polling only
     *   <li>NONE - Disable caching and always fetch fresh rules
     * </ul>
     */
    private ReloadStrategy strategy = ReloadStrategy.AUTO;

    /** Cache configuration. */
    private CacheProperties cache = new CacheProperties();

    /** Polling strategy configuration. */
    private PollingProperties polling = new PollingProperties();

    /** Pub/Sub strategy configuration. */
    private PubSubProperties pubsub = new PubSubProperties();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public ReloadStrategy getStrategy() {
      return strategy;
    }

    public void setStrategy(ReloadStrategy strategy) {
      this.strategy = strategy;
    }

    public CacheProperties getCache() {
      return cache;
    }

    public void setCache(CacheProperties cache) {
      this.cache = cache;
    }

    public PollingProperties getPolling() {
      return polling;
    }

    public void setPolling(PollingProperties polling) {
      this.polling = polling;
    }

    public PubSubProperties getPubsub() {
      return pubsub;
    }

    public void setPubsub(PubSubProperties pubsub) {
      this.pubsub = pubsub;
    }

    /** Rule cache configuration. */
    public static class CacheProperties {

      /** Enable local caching of rules. */
      private boolean enabled = true;

      /** Time-to-live for cached rules. Rules will be refetched after this duration. */
      private Duration ttl = Duration.ofMinutes(5);

      /** Maximum number of rules to cache. */
      private int maxSize = 1000;

      /**
       * Time-to-live for negative (not found) results.
       *
       * <p>Caching misses for a few seconds stops a single mistyped rule set id from querying the
       * rule store on every request. Set to zero to disable negative caching.
       */
      private Duration negativeTtl = Duration.ofSeconds(5);

      public boolean isEnabled() {
        return enabled;
      }

      public void setEnabled(boolean enabled) {
        this.enabled = enabled;
      }

      public Duration getTtl() {
        return ttl;
      }

      public void setTtl(Duration ttl) {
        this.ttl = ttl;
      }

      public int getMaxSize() {
        return maxSize;
      }

      public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
      }

      public Duration getNegativeTtl() {
        return negativeTtl;
      }

      public void setNegativeTtl(Duration negativeTtl) {
        this.negativeTtl = negativeTtl;
      }
    }

    /** Polling strategy configuration. */
    public static class PollingProperties {

      /** Interval between polling checks. */
      private Duration interval = Duration.ofSeconds(30);

      /** Initial delay before first poll. */
      private Duration initialDelay = Duration.ofSeconds(10);

      public Duration getInterval() {
        return interval;
      }

      public void setInterval(Duration interval) {
        this.interval = interval;
      }

      public Duration getInitialDelay() {
        return initialDelay;
      }

      public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
      }
    }

    /** Pub/Sub strategy configuration. */
    public static class PubSubProperties {

      /** Redis channel name for reload notifications. */
      private String channel = FluxgateConstants.Channels.RULE_RELOAD;

      /** Retry subscription on failure. */
      private boolean retryOnFailure = true;

      /** Interval between retry attempts. */
      private Duration retryInterval = Duration.ofSeconds(5);

      /**
       * Interval of the low-frequency polling backstop that runs alongside the subscription.
       *
       * <p>Redis Pub/Sub is at-most-once, so a dropped message would otherwise leave this instance
       * serving stale rules until the cache TTL expires. The backstop converges within this
       * interval. Set to zero to disable it.
       */
      private Duration backstopPollingInterval = Duration.ofSeconds(60);

      /**
       * Shared secret the publisher signs rule change notifications with (HMAC-SHA256).
       *
       * <p>Unset by default, which keeps the previous behaviour: any message on the channel is
       * obeyed, so anyone who can {@code PUBLISH} to the Redis can reset every token bucket with
       * one line. Set this to the same value as the control plane's {@code fluxgate.control.secret}
       * and every message without a valid signature - including the legacy {@code "*"} full reload
       * - is logged at WARN and ignored.
       */
      private String secret;

      /**
       * How old a signed message may be before it is ignored.
       *
       * <p>A signature alone does not stop a captured message from being published again. Only
       * applied when {@link #getSecret()} is set, because an unsigned message has no trustworthy
       * timestamp to compare against. Keep it well above the clock skew between control plane and
       * data plane.
       */
      private Duration maxMessageAge = Duration.ofMinutes(5);

      public String getChannel() {
        return channel;
      }

      public void setChannel(String channel) {
        this.channel = channel;
      }

      public boolean isRetryOnFailure() {
        return retryOnFailure;
      }

      public void setRetryOnFailure(boolean retryOnFailure) {
        this.retryOnFailure = retryOnFailure;
      }

      public Duration getRetryInterval() {
        return retryInterval;
      }

      public void setRetryInterval(Duration retryInterval) {
        this.retryInterval = retryInterval;
      }

      public Duration getBackstopPollingInterval() {
        return backstopPollingInterval;
      }

      public void setBackstopPollingInterval(Duration backstopPollingInterval) {
        this.backstopPollingInterval = backstopPollingInterval;
      }

      public String getSecret() {
        return secret;
      }

      public void setSecret(String secret) {
        this.secret = secret;
      }

      public Duration getMaxMessageAge() {
        return maxMessageAge;
      }

      public void setMaxMessageAge(Duration maxMessageAge) {
        this.maxMessageAge = maxMessageAge;
      }
    }
  }

  // ===== YAML-defined rule set nested properties =====

  /**
   * Properties for a single YAML-defined rule set (under {@code fluxgate.ratelimit.rule-sets[n]}).
   *
   * @since 0.4.0
   */
  public static class RuleSetProperties {

    /** Unique rule set identifier (required). */
    private String id;

    /** Optional human-readable description. */
    private String description;

    /** Access-control rules evaluated before any rate limiter is consulted. */
    @org.springframework.boot.context.properties.NestedConfigurationProperty
    private AccessControlProperties accessControl = new AccessControlProperties();

    /** The rate limit rules that belong to this rule set. */
    private List<RuleProperties> rules = new java.util.ArrayList<>();

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public AccessControlProperties getAccessControl() {
      return accessControl;
    }

    public void setAccessControl(AccessControlProperties accessControl) {
      this.accessControl = accessControl != null ? accessControl : new AccessControlProperties();
    }

    public List<RuleProperties> getRules() {
      return rules;
    }

    public void setRules(List<RuleProperties> rules) {
      this.rules = rules != null ? rules : new java.util.ArrayList<>();
    }
  }

  /**
   * Properties for the rule-set-level access control (under {@code
   * fluxgate.ratelimit.rule-sets[n].access-control}).
   *
   * @since 0.4.0
   */
  public static class AccessControlProperties {

    /**
     * Allowed IP CIDRs. Matching requests bypass rate limiting entirely ({@code ALLOW_BYPASS}).
     * Denied rules take precedence over allowed ones.
     */
    private List<String> allowedIps = new java.util.ArrayList<>();

    /** Denied IP CIDRs. Matching requests are rejected immediately ({@code DENY}). */
    private List<String> deniedIps = new java.util.ArrayList<>();

    /**
     * Allowed resolved key values (e.g. {@code "user:alice"}, {@code "key:internal-service"}).
     * Matching requests bypass rate limiting.
     */
    private List<String> allowedKeys = new java.util.ArrayList<>();

    /** Denied resolved key values. Matching requests are rejected immediately. */
    private List<String> deniedKeys = new java.util.ArrayList<>();

    public List<String> getAllowedIps() {
      return allowedIps;
    }

    public void setAllowedIps(List<String> allowedIps) {
      this.allowedIps = allowedIps != null ? allowedIps : new java.util.ArrayList<>();
    }

    public List<String> getDeniedIps() {
      return deniedIps;
    }

    public void setDeniedIps(List<String> deniedIps) {
      this.deniedIps = deniedIps != null ? deniedIps : new java.util.ArrayList<>();
    }

    public List<String> getAllowedKeys() {
      return allowedKeys;
    }

    public void setAllowedKeys(List<String> allowedKeys) {
      this.allowedKeys = allowedKeys != null ? allowedKeys : new java.util.ArrayList<>();
    }

    public List<String> getDeniedKeys() {
      return deniedKeys;
    }

    public void setDeniedKeys(List<String> deniedKeys) {
      this.deniedKeys = deniedKeys != null ? deniedKeys : new java.util.ArrayList<>();
    }
  }

  /**
   * Properties for a single rate limit rule (under {@code
   * fluxgate.ratelimit.rule-sets[n].rules[m]}).
   *
   * @since 0.4.0
   */
  public static class RuleProperties {

    /** Unique rule identifier (required within a rule set). */
    private String id;

    /** Display name (defaults to {@code id} when absent). */
    private String name;

    /** Whether this rule is active. Default {@code true}. */
    private boolean enabled = true;

    /**
     * Priority within the rule set. Higher values are evaluated first; ties are broken by rule id
     * ascending. Default {@code 0}.
     */
    private int priority = 0;

    /** Scope that determines which bucket a request maps to. Default {@code PER_IP}. */
    private org.fluxgate.core.config.LimitScope scope = org.fluxgate.core.config.LimitScope.PER_IP;

    /** Key strategy identifier (e.g. {@code "ip"}, {@code "userId"}). Default {@code "ip"}. */
    private String keyStrategyId = "ip";

    /** Policy applied when the limit is exceeded. Default {@code REJECT_REQUEST}. */
    private org.fluxgate.core.config.OnLimitExceedPolicy onLimitExceedPolicy =
        org.fluxgate.core.config.OnLimitExceedPolicy.REJECT_REQUEST;

    /** Arbitrary user-defined attributes passed through to the core rule. */
    private java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();

    /** Predicate that decides whether this rule applies to a given request. */
    @org.springframework.boot.context.properties.NestedConfigurationProperty
    private MatcherProperties matcher = new MatcherProperties();

    /** Rate limit bands that define the limits for this rule. */
    private List<BandProperties> bands = new java.util.ArrayList<>();

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getPriority() {
      return priority;
    }

    public void setPriority(int priority) {
      this.priority = priority;
    }

    public org.fluxgate.core.config.LimitScope getScope() {
      return scope;
    }

    public void setScope(org.fluxgate.core.config.LimitScope scope) {
      this.scope = scope;
    }

    public String getKeyStrategyId() {
      return keyStrategyId;
    }

    public void setKeyStrategyId(String keyStrategyId) {
      this.keyStrategyId = keyStrategyId;
    }

    public org.fluxgate.core.config.OnLimitExceedPolicy getOnLimitExceedPolicy() {
      return onLimitExceedPolicy;
    }

    public void setOnLimitExceedPolicy(
        org.fluxgate.core.config.OnLimitExceedPolicy onLimitExceedPolicy) {
      this.onLimitExceedPolicy = onLimitExceedPolicy;
    }

    public java.util.Map<String, Object> getAttributes() {
      return attributes;
    }

    public void setAttributes(java.util.Map<String, Object> attributes) {
      this.attributes = attributes != null ? attributes : new java.util.LinkedHashMap<>();
    }

    public MatcherProperties getMatcher() {
      return matcher;
    }

    public void setMatcher(MatcherProperties matcher) {
      this.matcher = matcher != null ? matcher : new MatcherProperties();
    }

    public List<BandProperties> getBands() {
      return bands;
    }

    public void setBands(List<BandProperties> bands) {
      this.bands = bands != null ? bands : new java.util.ArrayList<>();
    }
  }

  /**
   * Properties for the request matcher of a rule (under {@code
   * fluxgate.ratelimit.rule-sets[n].rules[m].matcher}).
   *
   * @since 0.4.0
   */
  public static class MatcherProperties {

    /**
     * Allowed HTTP methods (upper-case). Empty means any method matches.
     *
     * <p>Example: {@code [GET, POST]}
     */
    private List<String> methods = new java.util.ArrayList<>();

    /**
     * Ant-style path include patterns. Empty means any path matches.
     *
     * <p>Example: {@code [/api/**, /v2/**]}
     */
    private List<String> pathPatterns = new java.util.ArrayList<>();

    /**
     * Ant-style path exclude patterns. Exclusion beats inclusion.
     *
     * <p>Example: {@code [/api/health, /api/metrics]}
     */
    private List<String> excludePathPatterns = new java.util.ArrayList<>();

    /**
     * Header equality constraints (lower-cased header name → expected exact value).
     *
     * <p>Example: {@code {"x-tier": "premium"}}
     */
    private java.util.Map<String, String> headerEquals = new java.util.LinkedHashMap<>();

    /**
     * Header names (lower-cased) that must be present in the request.
     *
     * <p>Example: {@code [x-api-key]}
     */
    private List<String> headerPresent = new java.util.ArrayList<>();

    public List<String> getMethods() {
      return methods;
    }

    public void setMethods(List<String> methods) {
      this.methods = methods != null ? methods : new java.util.ArrayList<>();
    }

    public List<String> getPathPatterns() {
      return pathPatterns;
    }

    public void setPathPatterns(List<String> pathPatterns) {
      this.pathPatterns = pathPatterns != null ? pathPatterns : new java.util.ArrayList<>();
    }

    public List<String> getExcludePathPatterns() {
      return excludePathPatterns;
    }

    public void setExcludePathPatterns(List<String> excludePathPatterns) {
      this.excludePathPatterns =
          excludePathPatterns != null ? excludePathPatterns : new java.util.ArrayList<>();
    }

    public java.util.Map<String, String> getHeaderEquals() {
      return headerEquals;
    }

    public void setHeaderEquals(java.util.Map<String, String> headerEquals) {
      this.headerEquals = headerEquals != null ? headerEquals : new java.util.LinkedHashMap<>();
    }

    public List<String> getHeaderPresent() {
      return headerPresent;
    }

    public void setHeaderPresent(List<String> headerPresent) {
      this.headerPresent = headerPresent != null ? headerPresent : new java.util.ArrayList<>();
    }
  }

  /**
   * Properties for a single rate limit band (under {@code
   * fluxgate.ratelimit.rule-sets[n].rules[m].bands[k]}).
   *
   * @since 0.4.0
   */
  public static class BandProperties {

    /** Maximum number of requests (tokens) allowed within the window (required). */
    private long capacity;

    /**
     * Time window duration (required). Supports Spring Boot's Duration binding ({@code 60s}, {@code
     * 1m}, {@code PT1H}).
     */
    private Duration window;

    /** Rate limiting algorithm. Default {@code TOKEN_BUCKET}. */
    private org.fluxgate.core.config.RateLimitAlgorithm algorithm =
        org.fluxgate.core.config.RateLimitAlgorithm.TOKEN_BUCKET;

    /**
     * Calendar-aligned quota period. Only valid with {@code algorithm: FIXED_WINDOW}. Default
     * {@code null}.
     */
    private org.fluxgate.core.config.QuotaPeriod quotaPeriod;

    /**
     * Time zone for calendar alignment. Accepts IANA zone ids (e.g. {@code UTC}, {@code
     * America/New_York}). Default {@code UTC}.
     */
    private String zoneId = "UTC";

    /**
     * Number of sub-buckets for the {@code SLIDING_WINDOW} algorithm. Must be in [2, 60]. Default
     * {@code 10}.
     */
    private int slidingWindowBuckets = 10;

    /**
     * Optional human-readable label for metrics / admin UI. Drives the storage bucket key when set.
     */
    private String label;

    public long getCapacity() {
      return capacity;
    }

    public void setCapacity(long capacity) {
      this.capacity = capacity;
    }

    public Duration getWindow() {
      return window;
    }

    public void setWindow(Duration window) {
      this.window = window;
    }

    public org.fluxgate.core.config.RateLimitAlgorithm getAlgorithm() {
      return algorithm;
    }

    public void setAlgorithm(org.fluxgate.core.config.RateLimitAlgorithm algorithm) {
      this.algorithm = algorithm;
    }

    public org.fluxgate.core.config.QuotaPeriod getQuotaPeriod() {
      return quotaPeriod;
    }

    public void setQuotaPeriod(org.fluxgate.core.config.QuotaPeriod quotaPeriod) {
      this.quotaPeriod = quotaPeriod;
    }

    public String getZoneId() {
      return zoneId;
    }

    public void setZoneId(String zoneId) {
      this.zoneId = zoneId;
    }

    public int getSlidingWindowBuckets() {
      return slidingWindowBuckets;
    }

    public void setSlidingWindowBuckets(int slidingWindowBuckets) {
      this.slidingWindowBuckets = slidingWindowBuckets;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }
  }

  /** Reload strategy options. */
  public enum ReloadStrategy {
    /** Automatically select best strategy based on available infrastructure. */
    AUTO,
    /** Use Redis Pub/Sub for real-time reload notifications. */
    PUBSUB,
    /** Use periodic polling to check for changes. */
    POLLING,
    /** Disable hot reload - always fetch fresh rules from provider. */
    NONE
  }
}
