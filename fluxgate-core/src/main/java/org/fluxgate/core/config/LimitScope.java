package org.fluxgate.core.config;

/** Describes the logical scope of a rate limit. */
public enum LimitScope {

  /** Single global bucket for all traffic. */
  GLOBAL,

  /** One bucket per API key (for example, X-API-Key header). */
  PER_API_KEY,

  /** One bucket per user identifier. */
  PER_USER,

  /** One bucket per client IP address. */
  PER_IP,

  /** Custom scope resolved by a pluggable key strategy. */
  CUSTOM
}
