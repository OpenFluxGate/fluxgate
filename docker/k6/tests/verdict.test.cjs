const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function run(script, statuses, { missingHeaders = false, stepMs = 1 } = {}) {
  const metrics = {};
  const messages = [];
  let callback;
  let now = 0;
  class Metric {
    constructor(name) { this.name = name; metrics[name] = []; }
    add(value) { metrics[this.name].push(Number(value)); }
  }
  const sandbox = {
    __ENV: {}, __ITER: 0, Counter: Metric, Rate: Metric, Trend: Metric,
    Date: { now: () => now },
    console: { log: value => messages.push(value) },
    htmlReport: () => '', sleep: () => {},
    check: (res, checks) => {
      metrics.checks ??= [];
      for (const predicate of Object.values(checks)) metrics.checks.push(Number(predicate(res)));
    },
    http: {
      expectedStatuses: (...codes) => codes,
      setResponseCallback: value => { callback = value; },
      get: () => {
        now += stepMs;
        return { status: statuses[sandbox.__ITER], timings: { duration: 1 },
          headers: missingHeaders ? {} : { 'Retry-After': '1', 'X-Ratelimit-Remaining': '0' } };
      },
    },
  };
  const source = fs.readFileSync(path.join(__dirname, '../scripts', script), 'utf8')
    .replace(/^import .*;\n/gm, '')
    .replace('export const options', 'const options')
    .replace('export default function ()', 'function iteration()')
    .replace('export function handleSummary', 'function handleSummary');
  vm.createContext(sandbox);
  vm.runInContext(source + '\nthis.api = { options, iteration, handleSummary };', sandbox);
  for (let i = 0; i < statuses.length; i++) { sandbox.__ITER = i; sandbox.api.iteration(); }
  const data = { metrics: Object.fromEntries(Object.entries(metrics).map(([name, values]) => [name, {
    values: { count: values.reduce((a, b) => a + b, 0), rate: values.length ? values.reduce((a,b)=>a+b,0)/values.length : 0 },
  }])) };
  sandbox.api.handleSummary(data);
  return { metrics, messages, callback, options: sandbox.api.options };
}
const burst = [...Array(10).fill(200), ...Array(5).fill(429)];
test('fresh burst records every decision and passes', () => {
  const r = run('rate-limit-test.js', burst);
  assert.equal(r.metrics.rate_limit_accuracy.length, 15);
  assert.ok(r.metrics.rate_limit_accuracy.every(Boolean));
  assert.ok(r.messages.includes('Accuracy: PASS'));
  assert.deepEqual([...r.options.thresholds.iterations], ['count==15']);
});
for (const [name, statuses] of [['all allowed', Array(15).fill(200)], ['all blocked', Array(15).fill(429)], ['all server errors', Array(15).fill(503)], ['incomplete', [200]]]) {
  test(name + ' does not report accuracy PASS', () => {
    const r = run('rate-limit-test.js', statuses);
    assert.ok(r.messages.includes('Accuracy: FAIL'));
  });
}
test('missing rejection headers fails checks', () => {
  const r = run('rate-limit-test.js', burst, { missingHeaders: true });
  assert.ok(r.messages.includes('Accuracy: FAIL'));
  assert.deepEqual([...r.options.thresholds.checks], ['rate==1']);
});
test('refill window invalidates accuracy claim', () => {
  const r = run('rate-limit-test.js', burst, { stepMs: 1000 });
  assert.ok(r.messages.includes('Accuracy: FAIL'));
});
test('stress counts 429 as expected, 503 as an error', () => {
  const r = run('stress-test.js', [200, 429, 503]);
  assert.deepEqual([...r.callback], [200, 429]);
  assert.equal(r.metrics.error_requests.reduce((a,b)=>a+b,0), 1);
});
