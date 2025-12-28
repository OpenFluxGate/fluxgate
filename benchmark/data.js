window.BENCHMARK_DATA = {
  "lastUpdate": 1766931486835,
  "repoUrl": "https://github.com/OpenFluxGate/fluxgate",
  "entries": {
    "FluxGate Standalone Benchmark": [
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
        "date": 1766929455288,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 6.487758957383454,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 15.664116202219523,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 10.631800171173282,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 6.474004973729697,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 0.15364060070841207,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 0.25943033424282796,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 0.3617198451958282,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 0.1544395672005468,
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
          "id": "90f7dafce9e004e8ab03ec5c8dc1617dd41867f2",
          "message": "Fix formatting in README.md for architecture section",
          "timestamp": "2025-12-28T23:07:54+09:00",
          "tree_id": "ea9458761c0a5568784aef561f8a7096e22291bf",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/90f7dafce9e004e8ab03ec5c8dc1617dd41867f2"
        },
        "date": 1766931017032,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 6.533214657250663,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 15.220273410755183,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 10.725010822879975,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 6.461480538952893,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 0.15683052382650578,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 0.2569328567495536,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 0.3462679169936472,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 0.15452481380561797,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      }
    ],
    "FluxGate Redis Benchmark": [
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
        "date": 1766929688644,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 15.664656722288806,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 15.462447358745925,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 15.608476629542968,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 15.68260742520556,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 15.766997642274124,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 15.893814989577555,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 0.2559283796369797,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 0.25667663240001265,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 0.25705852840224586,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 0.25255586289607784,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 0.25211031703310505,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 0.25445385809240173,
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
          "id": "90f7dafce9e004e8ab03ec5c8dc1617dd41867f2",
          "message": "Fix formatting in README.md for architecture section",
          "timestamp": "2025-12-28T23:07:54+09:00",
          "tree_id": "ea9458761c0a5568784aef561f8a7096e22291bf",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/90f7dafce9e004e8ab03ec5c8dc1617dd41867f2"
        },
        "date": 1766931251946,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 15.213620562054098,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 15.201408512252502,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 15.279970874787967,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 15.502018868036142,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 15.590340693721839,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 15.574700160735432,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 0.2595941539721003,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 0.2638142756116226,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 0.2616164775329265,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 0.2572664504581107,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 0.2590729950010187,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 0.2576723627619218,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          }
        ]
      }
    ],
    "FluxGate MongoDB Benchmark": [
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
        "date": 1766929917103,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 3.5619250261299142,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 8.210833252543909,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 3.459133464849851,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 0.28153254875270645,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 0.4989158828216217,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 0.28939234265667374,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 3.6176589131457377,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 8.780641221842776,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 3.5958056083103758,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 0.2745486059536778,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 0.4364506287767432,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 0.27760400281637826,
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
          "id": "90f7dafce9e004e8ab03ec5c8dc1617dd41867f2",
          "message": "Fix formatting in README.md for architecture section",
          "timestamp": "2025-12-28T23:07:54+09:00",
          "tree_id": "ea9458761c0a5568784aef561f8a7096e22291bf",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/90f7dafce9e004e8ab03ec5c8dc1617dd41867f2"
        },
        "date": 1766931485891,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 3.4211246017137684,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 8.267251335640315,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 3.312702084613176,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 0.29150225345214825,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 0.5005756356029831,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 0.30152871938534365,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 3.481901278415404,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 8.967020142679768,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 3.3528145064178205,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 0.28907812993515625,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 0.4597483883511946,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 0.2914994462673707,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}