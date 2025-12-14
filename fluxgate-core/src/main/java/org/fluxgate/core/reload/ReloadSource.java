package org.fluxgate.core.reload;

/** Source that triggered a rule reload. */
public enum ReloadSource {

  /** Reload triggered via Redis Pub/Sub message. */
  PUBSUB,

  /** Reload triggered by periodic polling detecting a change. */
  POLLING,

  /** Reload triggered manually via API or programmatic call. */
  MANUAL,

  /** Reload triggered by an external API call (e.g., REST endpoint). */
  API,

  /** Reload triggered during application startup. */
  STARTUP,

  /** Reload triggered by cache expiration (TTL). */
  CACHE_EXPIRY
}
