window.BENCHMARK_DATA = {
  "lastUpdate": 1771251572065,
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
      },
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
          "id": "908eb4138724225bcdc339228a08be5840ba585f",
          "message": "README.md",
          "timestamp": "2025-12-29T00:08:29+09:00",
          "tree_id": "ade32e99d66c4aa6423c7acbaf9b6747490fea5f",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/908eb4138724225bcdc339228a08be5840ba585f"
        },
        "date": 1766934659404,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 6.450114820784823,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 15.953500593606071,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 11.250910773681715,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 6.5159345103067805,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 0.1508230035067176,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 0.23773932041138943,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 0.3473826646972591,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 0.1526080023959596,
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
          "id": "bd07747413ac8622e7b8cc3a78c82256633336ca",
          "message": "Update README.md",
          "timestamp": "2025-12-30T01:50:22+09:00",
          "tree_id": "3a9cb1caa69b2e4018622c68379acd876ab8fd50",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/bd07747413ac8622e7b8cc3a78c82256633336ca"
        },
        "date": 1767027175266,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 6.6031641086682145,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 17.192144255080244,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 11.448641036573273,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 6.5111357805990995,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 0.15278213010918265,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 0.25844635969201557,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 0.3832042336612175,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 0.1534923388508513,
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
          "id": "1390ddf2787820c274ca1d7abf9963cee21253da",
          "message": "Update README.md",
          "timestamp": "2026-02-16T23:16:52+09:00",
          "tree_id": "12f910094206e51e06f93e23bc14fbfc0edb282e",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/1390ddf2787820c274ca1d7abf9963cee21253da"
        },
        "date": 1771251571560,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 6.509067011272902,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 16.031557540452308,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 11.181760761494747,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 6.459410047094844,
            "unit": "ops/us",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeBurst",
            "value": 0.1517639640043901,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultiThread",
            "value": 0.2665733330890209,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeMultipleClients",
            "value": 0.35874862903482657,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.StandaloneRateLimiterBenchmark.tryConsumeSingleThread",
            "value": 0.15895490805501886,
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
      },
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
          "id": "908eb4138724225bcdc339228a08be5840ba585f",
          "message": "README.md",
          "timestamp": "2025-12-29T00:08:29+09:00",
          "tree_id": "ade32e99d66c4aa6423c7acbaf9b6747490fea5f",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/908eb4138724225bcdc339228a08be5840ba585f"
        },
        "date": 1766934894948,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 15.714649449764181,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 15.604205951993512,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 15.610729310116024,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 15.912073600496043,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 15.848477578352771,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 16.053313351948297,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 0.25571151923866114,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 0.25735054358336534,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 0.25470764475425356,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 0.25133083657941924,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 0.2507007869602473,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 0.2539166382957051,
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
          "id": "bd07747413ac8622e7b8cc3a78c82256633336ca",
          "message": "Update README.md",
          "timestamp": "2025-12-30T01:50:22+09:00",
          "tree_id": "3a9cb1caa69b2e4018622c68379acd876ab8fd50",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/bd07747413ac8622e7b8cc3a78c82256633336ca"
        },
        "date": 1767027410320,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 15.575129661175925,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 15.56499286834521,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 15.622226373838567,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 15.744409358934707,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 15.895342751846815,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 15.807386152349096,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"1\"} )",
            "value": 0.25783385360594413,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"4\"} )",
            "value": 0.25565830186833755,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsume ( {\"threadCount\":\"8\"} )",
            "value": 0.2583919755747746,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"1\"} )",
            "value": 0.25353073264773973,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"4\"} )",
            "value": 0.25428631071099234,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.RedisRateLimiterBenchmark.tryConsumeBurst ( {\"threadCount\":\"8\"} )",
            "value": 0.2529964996801593,
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
      },
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
          "id": "908eb4138724225bcdc339228a08be5840ba585f",
          "message": "README.md",
          "timestamp": "2025-12-29T00:08:29+09:00",
          "tree_id": "ade32e99d66c4aa6423c7acbaf9b6747490fea5f",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/908eb4138724225bcdc339228a08be5840ba585f"
        },
        "date": 1766935121712,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 3.5690899554957065,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 8.330407940564779,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 3.5015717175746737,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 0.27779918215667043,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 0.4882658103343959,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 0.28884773370366845,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 3.6590519885729145,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 9.095673764549964,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 3.5338246210766933,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 0.2748949269499672,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 0.45029884489508165,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 0.27247445270918164,
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
          "id": "bd07747413ac8622e7b8cc3a78c82256633336ca",
          "message": "Update README.md",
          "timestamp": "2025-12-30T01:50:22+09:00",
          "tree_id": "3a9cb1caa69b2e4018622c68379acd876ab8fd50",
          "url": "https://github.com/OpenFluxGate/fluxgate/commit/bd07747413ac8622e7b8cc3a78c82256633336ca"
        },
        "date": 1767027644316,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 3.6366891660065663,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 8.492869672325623,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 3.460407744388528,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.findAllRules",
            "value": 0.28299159036539334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetMultiThread",
            "value": 0.49922712577451944,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoRuleLoadingBenchmark.loadRuleSetSingleThread",
            "value": 0.28905808549226025,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 3.6436124208182235,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 9.36644261944912,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 3.5137651686623292,
            "unit": "ops/ms",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordAllowedEventSingleThread",
            "value": 0.27966095496277804,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordEventMultiThread",
            "value": 0.4679981163795143,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 4"
          },
          {
            "name": "org.fluxgate.testkit.benchmark.MongoEventRecordingBenchmark.recordRejectedEventSingleThread",
            "value": 0.2767170717812379,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}