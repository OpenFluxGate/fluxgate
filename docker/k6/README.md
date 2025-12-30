# FluxGate k6 Load Testing

rate-limiter test with `k6`

## Quick Start

### 1. k6 container start

```bash
cd docker/k6
docker-compose up -d
```

### 2. Run test

```bash
# Rate Limit Accuracy Test
docker exec fluxgate-k6 k6 run /scripts/rate-limit-test.js

# Stress Test
docker exec fluxgate-k6 k6 run /scripts/stress-test.js
```

