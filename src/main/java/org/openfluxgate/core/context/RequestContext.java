package org.openfluxgate.core.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents contextual information about the incoming request.
 * This allows custom KeyResolvers and strategies
 * to determine which rate-limit bucket should be used.
 */
public final class RequestContext {

    private final String clientIp;
    private final String userId;
    private final String apiKey;
    private final String endpoint;
    private final String method;

    private final Map<String, Object> attributes;

    private RequestContext(Builder builder) {
        this.clientIp = builder.clientIp;
        this.userId = builder.userId;
        this.apiKey = builder.apiKey;
        this.endpoint = builder.endpoint;
        this.method = builder.method;
        this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getUserId() {
        return userId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getMethod() {
        return method;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String clientIp;
        private String userId;
        private String apiKey;
        private String endpoint;
        private String method;

        private final Map<String, Object> attributes = new HashMap<>();

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public RequestContext build() {
            return new RequestContext(this);
        }
    }
}