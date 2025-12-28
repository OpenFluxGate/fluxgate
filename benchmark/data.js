window.BENCHMARK_DATA = {
  "lastUpdate": 1766928821994,
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
      },
      {
        "commit": {
          "author": {
            "email": "41769568+rojae@users.noreply.github.com",
            "name": "JaeSeong Oh",
            "username": "rojae"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "2f6793b4f85f8a9703c9d9d92d9020e31f4a0339",
          "message": "Merge pull request #62 from OpenFluxGate/development/fluxgate-testkit-for-jmh\n\nfix: git-workflow apply benchmark",
          "timestamp": "2025-12-28T22:14:37+09:00",
          "tree_id": "52e147fb202cb31fb07e0428e0b52393582241b7",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/2f6793b4f85f8a9703c9d9d92d9020e31f4a0339"
        },
        "date": 1766927822551,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 6.520865811138347,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 16.19926832233631,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 11.135312759718143,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 6.315512213527472,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 0.15104940761052346,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 0.23973234961441198,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 0.36122097597805114,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 0.15310531752114975,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "41769568+rojae@users.noreply.github.com",
            "name": "JaeSeong Oh",
            "username": "rojae"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "e28dc405f2246bf91c95da7988587c1f197038da",
          "message": "Merge pull request #63 from OpenFluxGate/development/fluxgate-testkit-for-jmh\n\nfix: git-workflow apply benchmark",
          "timestamp": "2025-12-28T22:23:28+09:00",
          "tree_id": "8a8005c5f5ae9135bca7e347be25ee513a6e6d93",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/e28dc405f2246bf91c95da7988587c1f197038da"
        },
        "date": 1766928356749,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 6.565176678816231,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 12.819770674424268,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 8.48559758996166,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 6.550471830379307,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 0.15001699490088174,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 0.3211817948045435,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 0.4494018040627501,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 0.15726706236921642,
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
      },
      {
        "commit": {
          "author": {
            "email": "41769568+rojae@users.noreply.github.com",
            "name": "JaeSeong Oh",
            "username": "rojae"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "2f6793b4f85f8a9703c9d9d92d9020e31f4a0339",
          "message": "Merge pull request #62 from OpenFluxGate/development/fluxgate-testkit-for-jmh\n\nfix: git-workflow apply benchmark",
          "timestamp": "2025-12-28T22:14:37+09:00",
          "tree_id": "52e147fb202cb31fb07e0428e0b52393582241b7",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/2f6793b4f85f8a9703c9d9d92d9020e31f4a0339"
        },
        "date": 1766927902516,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 3.623959119777296,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 8.335583705729427,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 3.5233822360140103,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 0.2787412001064843,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 0.4630243808624712,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 0.2802941496568958,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 3.7311430777428725,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 9.527149995130014,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 3.65169786997901,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 0.26877300107790225,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 0.4418448671716021,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 0.272200881817654,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "41769568+rojae@users.noreply.github.com",
            "name": "JaeSeong Oh",
            "username": "rojae"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "e28dc405f2246bf91c95da7988587c1f197038da",
          "message": "Merge pull request #63 from OpenFluxGate/development/fluxgate-testkit-for-jmh\n\nfix: git-workflow apply benchmark",
          "timestamp": "2025-12-28T22:23:28+09:00",
          "tree_id": "8a8005c5f5ae9135bca7e347be25ee513a6e6d93",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/e28dc405f2246bf91c95da7988587c1f197038da"
        },
        "date": 1766928821728,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 3.632498163919872,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 8.353291884860791,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 3.5122962840269216,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 0.27770685064410505,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 0.4844848340145271,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 0.28398078084576006,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 3.655381027932927,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 9.430746898886836,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 3.5639336347272534,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 0.2759782370633159,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 0.4461770043257733,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 0.272080274569035,
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
      },
      {
        "commit": {
          "author": {
            "email": "41769568+rojae@users.noreply.github.com",
            "name": "JaeSeong Oh",
            "username": "rojae"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "2f6793b4f85f8a9703c9d9d92d9020e31f4a0339",
          "message": "Merge pull request #62 from OpenFluxGate/development/fluxgate-testkit-for-jmh\n\nfix: git-workflow apply benchmark",
          "timestamp": "2025-12-28T22:14:37+09:00",
          "tree_id": "52e147fb202cb31fb07e0428e0b52393582241b7",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/2f6793b4f85f8a9703c9d9d92d9020e31f4a0339"
        },
        "date": 1766927914550,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 15.530092170823398,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 15.37068268659093,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 15.562877667083736,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 15.804334176177377,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 15.78839392987931,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 15.803811106139463,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 0.2566487861471898,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 0.2568534093515861,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 0.25691535674255095,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 0.2562474601431006,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 0.25557709029193215,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 0.2542631600131431,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "41769568+rojae@users.noreply.github.com",
            "name": "JaeSeong Oh",
            "username": "rojae"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "e28dc405f2246bf91c95da7988587c1f197038da",
          "message": "Merge pull request #63 from OpenFluxGate/development/fluxgate-testkit-for-jmh\n\nfix: git-workflow apply benchmark",
          "timestamp": "2025-12-28T22:23:28+09:00",
          "tree_id": "8a8005c5f5ae9135bca7e347be25ee513a6e6d93",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/e28dc405f2246bf91c95da7988587c1f197038da"
        },
        "date": 1766928594996,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 14.674111872375246,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 14.655821272247072,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 14.691374029297938,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 14.84363732525737,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 14.920312863020499,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 14.831782752831211,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 0.27273605282446367,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 0.2741969422552528,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 0.2728741332416416,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 0.27062030024989636,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 0.27007526997030534,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 0.2694214100550002,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          }
        ]
      }
    ]
  }
}