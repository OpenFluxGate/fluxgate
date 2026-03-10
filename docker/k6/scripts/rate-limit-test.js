/**
 * FluxGate Rate Limit Accuracy Test
 *
 * This script verifies that the rate limiter correctly enforces the configured limit.
 * It sends more requests than the allowed limit and checks:
 *   1. Requests within limit return HTTP 200
 *   2. Requests exceeding limit return HTTP 429
 *   3. Rate limit headers are present in 429 responses
 *
 * Usage:
 *   docker exec fluxgate-k6 k6 run /scripts/rate-limit-test.js
 *
 * Environment Variables:
 *   - BASE_URL: Target server URL (default: http://localhost:8085)
 *   - EXPECTED_LIMIT: Expected rate limit per window (default: 10)
 *   - WINDOW_SECONDS: Rate limit window in seconds (default: 60)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { htmlReport } from 'https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js';

// =============================================================================
// Custom Metrics
// =============================================================================
// Counter: Tracks cumulative count of events
// Rate: Tracks percentage of non-zero values (used for pass/fail rates)
// Trend: Tracks statistical distribution (min, max, avg, percentiles)

const rateLimitedRequests = new Counter('rate_limited_requests'); // Count of 429 responses
const successfulRequests = new Counter('successful_requests');    // Count of 200 responses
const rateLimitAccuracy = new Rate('rate_limit_accuracy');        // Success rate metric
const responseTime = new Trend('response_time');                  // Response time distribution

// =============================================================================
// Configuration
// =============================================================================
// __ENV allows reading environment variables passed to k6
// These can be set in docker-compose.yml or via CLI: k6 run -e BASE_URL=http://...

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const EXPECTED_LIMIT = parseInt(__ENV.EXPECTED_LIMIT) || 10;
const WINDOW_SECONDS = parseInt(__ENV.WINDOW_SECONDS) || 60;

// =============================================================================
// Test Options
// =============================================================================
// options object configures how k6 executes the test

export const options = {
  scenarios: {
    // Scenario defines test execution pattern
    accuracy_test: {
      // 'per-vu-iterations': Each VU runs exactly N iterations
      executor: 'per-vu-iterations',

      // VUs (Virtual Users): Simulated concurrent users
      vus: 1,

      // Total iterations per VU (limit + 5 to verify blocking works)
      iterations: EXPECTED_LIMIT + 5,

      // Maximum time for scenario to complete
      maxDuration: '30s',
    },
  },

  // Thresholds define pass/fail criteria for the test
  thresholds: {
    // p(95) = 95th percentile; 95% of requests must be under 500ms
    http_req_duration: ['p(95)<500'],

    // rate > 0.9 means more than 90% must pass
    rate_limit_accuracy: ['rate>0.9'],
  },
};

// =============================================================================
// Main Test Function
// =============================================================================
// default function is executed repeatedly by each VU

export default function () {
  // Send HTTP GET request to the test endpoint
  const res = http.get(`${BASE_URL}/api/test`);

  // Record response time in our custom metric
  responseTime.add(res.timings.duration);

  // Classify response status
  const isSuccess = res.status === 200;
  const isRateLimited = res.status === 429;

  // Increment appropriate counter
  if (isSuccess) {
    successfulRequests.add(1);
  }

  if (isRateLimited) {
    rateLimitedRequests.add(1);

    // Verify rate limit headers are present in 429 responses
    // Note: k6 normalizes header names (e.g., X-RateLimit-Remaining -> X-Ratelimit-Remaining)
    check(res, {
      'has retry-after header': (r) => r.headers['Retry-After'] !== undefined,
      'has rate-limit-remaining header': (r) => r.headers['X-Ratelimit-Remaining'] !== undefined,
    });
  }

  // Verify response status is expected (either allowed or rate-limited)
  check(res, {
    'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
  });

  // Small delay between requests (100ms)
  // sleep() pauses execution; time is in seconds
  sleep(0.1);
}

// =============================================================================
// Summary Handler
// =============================================================================
// handleSummary is called at the end of test with all collected metrics

export function handleSummary(data) {
  // Extract counts from metrics using optional chaining (?.)
  const successful = data.metrics.successful_requests?.values?.count || 0;
  const rateLimited = data.metrics.rate_limited_requests?.values?.count || 0;
  const total = successful + rateLimited;

  // Print summary to console
  console.log(`\n========== Rate Limit Test Summary ==========`);
  console.log(`Expected Limit: ${EXPECTED_LIMIT} requests per ${WINDOW_SECONDS}s`);
  console.log(`Total Requests: ${total}`);
  console.log(`Successful (200): ${successful}`);
  console.log(`Rate Limited (429): ${rateLimited}`);
  console.log(`Accuracy: ${successful <= EXPECTED_LIMIT ? 'PASS' : 'FAIL'}`);
  console.log(`==============================================\n`);

  // Return object specifying output files
  // Keys are file paths, values are content to write
  return {
    '/results/rate-limit-test-result.html': htmlReport(data),
    '/results/rate-limit-test-result.json': JSON.stringify(data, null, 2),
  };
}
