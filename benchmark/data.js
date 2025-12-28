window.BENCHMARK_DATA = {
  "lastUpdate": 1766927487364,
  "repoUrl": "https://github.com/OpenFluxGate/fluxgate",
  "entries": {
    "FluxGate Standalone Benchmark": [
      {
        "commit": {
          "author": {
            "email": "rojae@kakao.com",
            "name": "rojae",
            "username": "rojae"
          },
          "committer": {
            "email": "rojae@kakao.com",
            "name": "rojae",
            "username": "rojae"
          },
          "distinct": true,
          "id": "6162a5b9bf4cbbcfe6d15da08ab27359e4c1cb9f",
          "message": "fix(benchmark): use gh-pages branch for benchmark results\n\n- Remove skip-fetch-gh-pages and manual push steps\n- Set auto-push with gh-pages-branch: gh-pages\n- Update benchmark-data-dir-path to 'benchmark'\n- Fix README badge URLs to /benchmark/\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\nCo-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>",
          "timestamp": "2025-12-28T22:07:30+09:00",
          "tree_id": "52e147fb202cb31fb07e0428e0b52393582241b7",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/6162a5b9bf4cbbcfe6d15da08ab27359e4c1cb9f"
        },
        "date": 1766927392829,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 6.590435094709489,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 17.378189636956385,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 11.579689722025718,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 6.526752683709968,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 0.15251007104985512,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 0.24277133072964116,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 0.34651201609083504,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 0.15491304015301446,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      }
    ],
    "FluxGate MongoDB Benchmark": [
      {
        "commit": {
          "author": {
            "email": "rojae@kakao.com",
            "name": "rojae",
            "username": "rojae"
          },
          "committer": {
            "email": "rojae@kakao.com",
            "name": "rojae",
            "username": "rojae"
          },
          "distinct": true,
          "id": "6162a5b9bf4cbbcfe6d15da08ab27359e4c1cb9f",
          "message": "fix(benchmark): use gh-pages branch for benchmark results\n\n- Remove skip-fetch-gh-pages and manual push steps\n- Set auto-push with gh-pages-branch: gh-pages\n- Update benchmark-data-dir-path to 'benchmark'\n- Fix README badge URLs to /benchmark/\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\nCo-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>",
          "timestamp": "2025-12-28T22:07:30+09:00",
          "tree_id": "52e147fb202cb31fb07e0428e0b52393582241b7",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/6162a5b9bf4cbbcfe6d15da08ab27359e4c1cb9f"
        },
        "date": 1766927478729,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 4.297750791741233,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 10.203043904255319,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 4.212507340743488,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 0.23537605088886634,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 0.37927360126405973,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 0.24100474111871922,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 4.425341621413304,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 10.69265644647669,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 4.46964533837545,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 0.22837035000510708,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 0.3699794542292311,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 0.22963174956232174,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      }
    ],
    "FluxGate Redis Benchmark": [
      {
        "commit": {
          "author": {
            "email": "rojae@kakao.com",
            "name": "rojae",
            "username": "rojae"
          },
          "committer": {
            "email": "rojae@kakao.com",
            "name": "rojae",
            "username": "rojae"
          },
          "distinct": true,
          "id": "6162a5b9bf4cbbcfe6d15da08ab27359e4c1cb9f",
          "message": "fix(benchmark): use gh-pages branch for benchmark results\n\n- Remove skip-fetch-gh-pages and manual push steps\n- Set auto-push with gh-pages-branch: gh-pages\n- Update benchmark-data-dir-path to 'benchmark'\n- Fix README badge URLs to /benchmark/\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\nCo-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>",
          "timestamp": "2025-12-28T22:07:30+09:00",
          "tree_id": "52e147fb202cb31fb07e0428e0b52393582241b7",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/6162a5b9bf4cbbcfe6d15da08ab27359e4c1cb9f"
        },
        "date": 1766927486785,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 14.545756225257321,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 14.580751321405538,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 14.586152045658077,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 14.858173417195715,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 14.85930269327992,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 14.980303237358328,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 0.2739220355231666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 0.2706772483939043,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 0.27186355844523646,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 0.2691836407458448,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 0.26729308316047956,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 0.2685509614188291,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          }
        ]
      }
    ]
  }
}