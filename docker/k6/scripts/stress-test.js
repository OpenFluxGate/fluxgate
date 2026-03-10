/**
 * FluxGate Stress Test (1 Million Requests)
 *
 * This script tests the rate limiter's stability under high load.
 * It runs 1,000,000 total requests using concurrent virtual users to verify:
 *   1. System remains responsive under sustained load
 *   2. Rate limiting works correctly with many concurrent users
 *   3. No unexpected errors or memory leaks occur
 *
 * Configuration:
 *   - Total Requests: 1,000,000
 *   - Virtual Users: 100 concurrent
 *   - Iterations per VU: 10,000
 *
 * Usage:
 *   docker exec fluxgate-k6 k6 run /scripts/stress-test.js
 *
 * Environment Variables:
 *   - BASE_URL: Target server URL (default: http://localhost:8085)
 *   - VUS: Number of virtual users (default: 100)
 *   - ITERATIONS: Iterations per VU (default: 10000)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { htmlReport } from 'https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js';

// =============================================================================
// Custom Metrics
// =============================================================================
// Track request outcomes separately for detailed analysis

const rateLimitedRequests = new Counter('rate_limited_requests'); // HTTP 429 responses
const successfulRequests = new Counter('successful_requests');    // HTTP 200 responses
const errorRequests = new Counter('error_requests');              // Other status codes (errors)

// =============================================================================
// Configuration
// =============================================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const VUS = parseInt(__ENV.VUS) || 100;           // 100 virtual users
const ITERATIONS = parseInt(__ENV.ITERATIONS) || 10000; // 10,000 iterations per VU = 1M total

// =============================================================================
// Test Options
// =============================================================================

export const options = {
  scenarios: {
    stress_test: {
      // 'per-vu-iterations': Each VU runs exactly N iterations
      // Total requests = VUS * ITERATIONS = 100 * 10,000 = 1,000,000
      executor: 'per-vu-iterations',

      // Number of concurrent virtual users
      vus: VUS,

      // Each VU runs this many iterations
      iterations: ITERATIONS,

      // Maximum time allowed (adjust if needed for slower systems)
      maxDuration: '30m',
    },
  },

  // Pass/fail thresholds
  thresholds: {
    // 95% of requests should complete within 1 second
    http_req_duration: ['p(95)<1000'],

    // Less than 1% of requests should fail (network errors, timeouts)
    // Note: HTTP 429 is NOT counted as a failure here
    http_req_failed: ['rate<0.01'],
  },
};

// =============================================================================
// Main Test Function
// =============================================================================
// Each VU executes this function repeatedly

export default function () {
  // Send request
  const res = http.get(`${BASE_URL}/api/test`);

  // Categorize response and increment appropriate counter
  if (res.status === 200) {
    successfulRequests.add(1);
  } else if (res.status === 429) {
    // Rate limited - this is expected behavior, not an error
    rateLimitedRequests.add(1);
  } else {
    // Unexpected status (5xx, connection errors, etc.)
    errorRequests.add(1);
  }

  // Verify expected behavior
  check(res, {
    // Valid responses are either success (200) or rate-limited (429)
    'status is 200 or 429': (r) => r.status === 200 || r.status === 429,

    // Individual request should be fast
    'response time < 500ms': (r) => r.timings.duration < 500,
  });

  // No sleep for maximum throughput in stress test
  // Remove or reduce this value to increase request rate
}

// =============================================================================
// Summary Handler
// =============================================================================

export function handleSummary(data) {
  // Extract metrics
  const successful = data.metrics.successful_requests?.values?.count || 0;
  const rateLimited = data.metrics.rate_limited_requests?.values?.count || 0;
  const errors = data.metrics.error_requests?.values?.count || 0;
  const total = successful + rateLimited + errors;

  // Calculate error rate (excluding rate-limited responses)
  const errorRate = total > 0 ? ((errors / total) * 100).toFixed(2) : '0.00';

  // Print human-readable summary
  console.log(`\n========== Stress Test Summary ==========`);
  console.log(`Total Requests: ${total}`);
  console.log(`Successful (200): ${successful}`);
  console.log(`Rate Limited (429): ${rateLimited}`);
  console.log(`Errors: ${errors}`);
  console.log(`Error Rate: ${errorRate}%`);
  console.log(`==========================================\n`);

  // Generate output files
  return {
    '/results/stress-test-result.html': htmlReport(data),
    '/results/stress-test-result.json': JSON.stringify(data, null, 2),
  };
}
