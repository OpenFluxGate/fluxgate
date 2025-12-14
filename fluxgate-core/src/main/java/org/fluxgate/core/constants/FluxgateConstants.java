package org.fluxgate.core.constants;

/**
 * Constants used throughout the Fluxgate rate limiting framework.
 *
 * <p>This class centralizes all constant values for:
 *
 * <ul>
 *   <li>HTTP headers used for rate limiting and tracing
 *   <li>MDC (Mapped Diagnostic Context) keys for structured logging
 * </ul>
 */
public final class FluxgateConstants {

  private FluxgateConstants() {
    // Prevent instantiation
  }

  /** HTTP header names used by Fluxgate. */
  public static final class Headers {
    private Headers() {}

    /** Trace ID for request correlation across services. */
    public static final String TRACE_ID = "X-Trace-Id";

    /** User identifier header. */
    public static final String USER_ID = "X-User-Id";

    /** API key header for authentication. */
    public static final String API_KEY = "X-API-Key";

    /** Remaining rate limit tokens. */
    public static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";

    /** Seconds until rate limit resets. */
    public static final String RETRY_AFTER = "Retry-After";

    /** Client's original IP when behind a proxy. */
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";
  }

  /**
   * MDC (Mapped Diagnostic Context) keys for structured logging.
   *
   * <p>These keys are used with SLF4J MDC to provide context in log messages. When using JSON
   * logging (e.g., LogstashEncoder), these become searchable fields.
   */
  public static final class MdcKeys {
    private MdcKeys() {}

    // === Core Identifiers ===
    /** Unique identifier for tracing a request across services. */
    public static final String TRACE_ID = "traceId";

    /** Rate limit rule set identifier. */
    public static final String RULE_SET_ID = "ruleSetId";

    // === Request Information ===
    /** HTTP method (GET, POST, etc.). */
    public static final String METHOD = "method";

    /** Request endpoint/URI. */
    public static final String ENDPOINT = "endpoint";

    /** Client IP address. */
    public static final String CLIENT_IP = "clientIp";

    /** HTTP protocol version. */
    public static final String PROTOCOL = "protocol";

    /** Server port number. */
    public static final String SERVER_PORT = "serverPort";

    /** Query string parameters. */
    public static final String QUERY_STRING = "queryString";

    /** User-Agent header value. */
    public static final String USER_AGENT = "userAgent";

    /** Referer header value. */
    public static final String REFERER = "referer";

    // === User Identification ===
    /** User identifier from X-User-Id header. */
    public static final String USER_ID = "userId";

    /** API key (masked) from X-API-Key header. */
    public static final String API_KEY = "apiKey";

    // === Rate Limit Result ===
    /** Whether the request was allowed (true/false). */
    public static final String RATE_LIMIT_ALLOWED = "rateLimitAllowed";

    /** Number of remaining tokens. */
    public static final String REMAINING_TOKENS = "remainingTokens";

    /** Milliseconds until retry is allowed (when rate limited). */
    public static final String RETRY_AFTER_MS = "retryAfterMs";

    // === Response Information ===
    /** HTTP status code of the response. */
    public static final String STATUS_CODE = "statusCode";

    /** Request processing duration in milliseconds. */
    public static final String DURATION_MS = "durationMs";

    // === Error Information ===
    /** Exception class name when an error occurs. */
    public static final String ERROR = "error";

    /** Error message when an error occurs. */
    public static final String ERROR_MESSAGE = "errorMessage";
  }

  /** Micrometer/Prometheus metric names and tags. */
  public static final class Metrics {
    private Metrics() {}

    // === Metric Names ===
    /** Prefix for all Fluxgate metrics. */
    public static final String PREFIX = "fluxgate";

    /** Total requests processed. */
    public static final String REQUESTS_TOTAL = PREFIX + ".requests.total";

    /** Requests by result (allowed/rejected). */
    public static final String REQUESTS = PREFIX + ".requests";

    /** Request processing duration. */
    public static final String REQUESTS_DURATION = PREFIX + ".requests.duration";

    /** Remaining tokens gauge. */
    public static final String TOKENS_REMAINING = PREFIX + ".tokens.remaining";

    // === Tag Names ===
    /** Rule set ID tag. */
    public static final String TAG_RULE_SET = "rule_set";

    /** Endpoint tag. */
    public static final String TAG_ENDPOINT = "endpoint";

    /** HTTP method tag. */
    public static final String TAG_METHOD = "method";

    /** Result tag (allowed/rejected). */
    public static final String TAG_RESULT = "result";

    // === Tag Values ===
    /** Result value for allowed requests. */
    public static final String RESULT_ALLOWED = "allowed";

    /** Result value for rejected requests. */
    public static final String RESULT_REJECTED = "rejected";
  }
}
