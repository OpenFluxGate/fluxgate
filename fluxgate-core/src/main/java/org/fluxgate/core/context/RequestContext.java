package org.fluxgate.core.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Represents contextual information about the incoming request. This allows custom KeyResolvers and
 * strategies to determine which rate-limit bucket should be used.
 *
 * <p>Contains request metadata for rate limiting decisions and event tracking:
 *
 * <ul>
 *   <li>Client identification: IP, userId, apiKey
 *   <li>Request info: endpoint, method
 *   <li>HTTP headers: collected in headers map; header names are case-insensitive (RFC 9110), so
 *       {@code getHeader("x-tier")} finds a header added as {@code X-Tier}
 *   <li>Custom attributes: extensible key-value pairs
 * </ul>
 */
public final class RequestContext {

  private final String clientIp;
  private final String userId;
  private final String apiKey;
  private final String endpoint;
  private final String method;

  /** HTTP request headers (e.g., User-Agent, Referer, X-Request-Id). */
  private final Map<String, String> headers;

  /** Custom attributes for user-defined metadata. */
  private final Map<String, Object> attributes;

  private RequestContext(Builder builder) {
    this.clientIp = builder.clientIp;
    this.userId = builder.userId;
    this.apiKey = builder.apiKey;
    this.endpoint = builder.endpoint;
    this.method = builder.method;
    Map<String, String> headerCopy = newHeaderMap();
    headerCopy.putAll(builder.headers);
    this.headers = Collections.unmodifiableMap(headerCopy);
    this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
  }

  /**
   * Returns the client IP address.
   *
   * @return the client IP address, may be null
   */
  public String getClientIp() {
    return clientIp;
  }

  /**
   * Returns the user ID.
   *
   * @return the user ID, may be null
   */
  public String getUserId() {
    return userId;
  }

  /**
   * Returns the API key.
   *
   * @return the API key, may be null
   */
  public String getApiKey() {
    return apiKey;
  }

  /**
   * Returns the request endpoint path.
   *
   * @return the endpoint path, may be null
   */
  public String getEndpoint() {
    return endpoint;
  }

  /**
   * Returns the HTTP method.
   *
   * @return the HTTP method (GET, POST, etc.), may be null
   */
  public String getMethod() {
    return method;
  }

  /**
   * Returns all HTTP headers collected from the request.
   *
   * <p>The map compares header names case-insensitively; names that differ only in case are a
   * single entry (the last value added wins).
   *
   * @return unmodifiable, case-insensitive map of header names to values
   */
  public Map<String, String> getHeaders() {
    return headers;
  }

  /**
   * Returns a specific HTTP header value.
   *
   * @param name the header name (case-insensitive)
   * @return the header value, or null if not present
   */
  public String getHeader(String name) {
    return name == null ? null : headers.get(name);
  }

  /**
   * Returns custom attributes for user-defined metadata.
   *
   * @return unmodifiable map of attributes
   */
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  /**
   * Returns a specific attribute value.
   *
   * @param key the attribute key
   * @return the attribute value, or null if not present
   */
  public Object getAttribute(String key) {
    return attributes.get(key);
  }

  /** Header names are case-insensitive; {@code CASE_INSENSITIVE_ORDER} is locale-independent. */
  private static Map<String, String> newHeaderMap() {
    return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  }

  /**
   * Creates a new Builder instance.
   *
   * @return a new Builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for creating RequestContext instances. */
  public static final class Builder {
    private String clientIp;
    private String userId;
    private String apiKey;
    private String endpoint;
    private String method;
    private final Map<String, String> headers = newHeaderMap();
    private final Map<String, Object> attributes = new HashMap<>();

    /** Creates a new Builder instance. */
    public Builder() {
      // Default constructor
    }

    // =========================================================================
    // Setters
    // =========================================================================

    /**
     * Sets the client IP address.
     *
     * @param clientIp the client IP address
     * @return this builder
     */
    public Builder clientIp(String clientIp) {
      this.clientIp = clientIp;
      return this;
    }

    /**
     * Sets the user ID.
     *
     * @param userId the user ID
     * @return this builder
     */
    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }

    /**
     * Sets the API key.
     *
     * @param apiKey the API key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the request endpoint path.
     *
     * @param endpoint the endpoint path
     * @return this builder
     */
    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Sets the HTTP method.
     *
     * @param method the HTTP method
     * @return this builder
     */
    public Builder method(String method) {
      this.method = method;
      return this;
    }

    /**
     * Adds a single HTTP header.
     *
     * @param name the header name, case-insensitive (null names are ignored)
     * @param value the header value (null values are ignored)
     * @return this builder
     */
    public Builder header(String name, String value) {
      if (name != null && value != null) {
        this.headers.put(name, value);
      }
      return this;
    }

    /**
     * Adds multiple HTTP headers at once.
     *
     * @param headers map of header names to values (null names and values are ignored)
     * @return this builder
     */
    public Builder headers(Map<String, String> headers) {
      if (headers != null) {
        headers.forEach(
            (k, v) -> {
              if (k != null && v != null) {
                this.headers.put(k, v);
              }
            });
      }
      return this;
    }

    /**
     * Adds a custom attribute.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this builder
     */
    public Builder attribute(String key, Object value) {
      this.attributes.put(key, value);
      return this;
    }

    /**
     * Adds multiple custom attributes at once.
     *
     * @param attributes map of attribute keys to values
     * @return this builder
     */
    public Builder attributes(Map<String, Object> attributes) {
      if (attributes != null) {
        this.attributes.putAll(attributes);
      }
      return this;
    }

    // =========================================================================
    // Getters - for use in RequestContextCustomizer
    // =========================================================================

    /**
     * Returns the current clientIp value.
     *
     * @return the client IP address
     */
    public String getClientIp() {
      return clientIp;
    }

    /**
     * Returns the current userId value.
     *
     * @return the user ID
     */
    public String getUserId() {
      return userId;
    }

    /**
     * Returns the current apiKey value.
     *
     * @return the API key
     */
    public String getApiKey() {
      return apiKey;
    }

    /**
     * Returns the current endpoint value.
     *
     * @return the endpoint path
     */
    public String getEndpoint() {
      return endpoint;
    }

    /**
     * Returns the current method value.
     *
     * @return the HTTP method
     */
    public String getMethod() {
      return method;
    }

    /**
     * Returns the current headers map (modifiable, case-insensitive on header names, rejects null
     * names).
     *
     * @return the headers map
     */
    public Map<String, String> getHeaders() {
      return headers;
    }

    /**
     * Returns a specific header value.
     *
     * @param name the header name (case-insensitive)
     * @return the header value, or null if not present
     */
    public String getHeader(String name) {
      return name == null ? null : headers.get(name);
    }

    /**
     * Returns the current attributes map (modifiable).
     *
     * @return the attributes map
     */
    public Map<String, Object> getAttributes() {
      return attributes;
    }

    /**
     * Returns a specific attribute value.
     *
     * @param key the attribute key
     * @return the attribute value, or null if not present
     */
    public Object getAttribute(String key) {
      return attributes.get(key);
    }

    /**
     * Builds and returns a new RequestContext instance.
     *
     * @return a new RequestContext
     */
    public RequestContext build() {
      return new RequestContext(this);
    }
  }
}
