# Hot Reload

FluxGate supports hot reloading of rate limit rules without restart.

[< Back to Architecture Overview](README.md)

---

## Strategies

### PollingReloadStrategy

Periodically polls MongoDB for rule changes.

```yaml
fluxgate:
  ratelimit:
    reload:
      strategy: polling
      polling-interval: 30s
```

**How it works:**
1. Scheduler runs at configured interval
2. Invalidates rule cache
3. Next request loads fresh rules from MongoDB

### RedisPubSubReloadStrategy

Real-time rule propagation via Redis Pub/Sub.

```yaml
fluxgate:
  ratelimit:
    reload:
      strategy: redis-pubsub
```

**How it works:**
1. Admin updates rule in MongoDB
2. Publishes change event to Redis channel
3. All application instances receive event
4. Cache invalidated + buckets reset

---

## BucketResetHandler

When rules change, existing token buckets may need to be reset.

**Options:**
- Reset all buckets for the changed rule set
- Keep existing buckets (gradual transition)

---

## Flow

```
Admin updates MongoDB
        ↓
Redis Pub/Sub event
        ↓
┌───────────────────────────────────┐
│ All Application Instances         │
│                                   │
│ 1. RuleCache.invalidate()         │
│ 2. BucketResetHandler.reset()     │
│ 3. Next request loads new rules   │
└───────────────────────────────────┘
```

---

## Related

- [Storage Layer](storage-layer.md)
- [Architecture Overview](README.md)
