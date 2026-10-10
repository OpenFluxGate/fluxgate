# YAML 룰 세트 가이드

Rate Limit 규칙을 MongoDB에 저장하는 대신 `application.yml`의 `fluxgate.ratelimit.rule-sets`에 정의합니다.
규칙은 경로, HTTP 메서드, 헤더로 요청을 고를 수 있고, 세 가지 알고리즘 중 하나를 쓰며, 달력 기준 쿼터를
따르고, 허용/거부 목록 뒤에 둘 수 있습니다. 모든 항목은 애플리케이션 기동 시점에 검증됩니다.

[< 문서 색인으로](../../README.ko.md) | [English](../../en/guides/yaml-rule-sets.md)

## 목차

- [첫 번째 룰 세트](#첫-번째-룰-세트)
- [YAML 룰 세트가 사용되는 방식](#yaml-룰-세트가-사용되는-방식)
- [룰 세트 키](#룰-세트-키)
- [규칙 키](#규칙-키)
- [매처](#매처)
- [대역 키와 알고리즘](#대역-키와-알고리즘)
- [달력 쿼터](#달력-쿼터-quota-period와-zone-id)
- [접근 제어](#접근-제어)
- [전체 예제](#전체-예제)
- [기동 시 검증](#기동-시-검증)
- [관련 문서](#관련-문서)

---

## 첫 번째 룰 세트

```yaml
fluxgate:
  ratelimit:
    default-rule-set-id: api-limits
    rule-sets:
      - id: api-limits
        description: Default API limits
        rules:
          - id: per-ip-100rpm
            scope: PER_IP
            matcher:
              path-patterns: [/api/**]
            bands:
              - capacity: 100
                window: 60s
```

`@EnableFluxgateFilter`와 `fluxgate.ratelimit.mode=IN_MEMORY`(또는 Redis)만 있으면 설정이 끝납니다.
MongoDB도, 핸들러 클래스도 필요 없습니다. 스타터가 `PropertiesRuleSetProvider`를 등록하고, 이 클래스가
기동 시점에 모든 룰 세트를 즉시 만듭니다.

## YAML 룰 세트가 사용되는 방식

- 요청은 id로 룰 세트를 선택합니다. `fluxgate.ratelimit.default-rule-set-id`, `@EnableFluxgateFilter`
  애트리뷰트, 또는 `@RateLimit(ruleSetId = ...)`가 그 id를 줍니다.
- YAML 룰 세트가 **먼저** 조회됩니다. MongoDB가 활성화되어 있거나(또는 직접 만든
  `delegateRuleSetProvider` 빈이 있으면) YAML이 정의하지 않은 id는 그쪽으로 폴백하며, 핫 리로드는 두 소스를
  모두 캐시합니다. 같은 id라면 YAML 룰 세트가 MongoDB 룰 세트보다 우선합니다.
- YAML 룰 세트는 정적입니다. 핫 리로드가 아니라 재시작으로 바뀝니다. 런타임에 규칙이 바뀌어야 한다면
  MongoDB를 쓰세요.
- 필터의 `include-patterns` / `exclude-patterns`는 FluxGate가 요청을 살펴볼지를 결정하고, 규칙의
  `matcher`는 그 룰 세트의 어떤 규칙이 적용될지를 결정합니다.
- 룰 세트 안에서는 일치하고 활성화된 규칙을 `priority`가 높은 것부터 평가하며, 동률이면 규칙 id 순입니다.
  요청이 통과하려면 일치한 모든 규칙에 토큰이 있어야 합니다. 어떤 규칙에도 일치하지 않는 요청은 제한 없이,
  남은 토큰 헤더도 없이 통과합니다.

## 룰 세트 키

`fluxgate.ratelimit.rule-sets[n]`

| 키 | 기본값 | 설명 |
|----|--------|------|
| `id` | 필수 | 고유한 룰 세트 id. 누락되거나 중복이면 기동 실패 |
| `description` | _(없음)_ | 사람이 읽는 설명 |
| `access-control` | 비어 있음 | 모든 리미터보다 먼저 평가되는 허용/거부 목록. [접근 제어](#접근-제어) 참고 |
| `rules` | `[]` | 이 룰 세트의 규칙들 |

## 규칙 키

`fluxgate.ratelimit.rule-sets[n].rules[m]`

| 키 | 기본값 | 설명 |
|----|--------|------|
| `id` | 필수 | 규칙 id. **룰 세트 안에서** 고유해야 합니다. 누락되거나 중복이면 기동 실패 |
| `name` | `id` | 표시 이름 |
| `enabled` | `true` | 비활성 규칙은 평가되지 않습니다 |
| `priority` | `0` | 값이 클수록 먼저 평가됩니다. 동률이면 규칙 id 오름차순 |
| `scope` | `PER_IP` | `GLOBAL`, `PER_IP`, `PER_USER`, `PER_API_KEY`, `CUSTOM` — 요청이 어느 버킷에 대응되는지 |
| `key-strategy-id` | `ip` | `CUSTOM`일 때 키를 담고 있는 `RequestContext` 속성 |
| `on-limit-exceed-policy` | `REJECT_REQUEST` | `REJECT_REQUEST` 또는 `WAIT_FOR_REFILL` (`fluxgate.ratelimit.wait-for-refill.*` 참고) |
| `attributes` | `{}` | 규칙에 그대로 전달되는 자유 형식 맵 |
| `matcher` | 모두 일치 | [매처](#매처) 참고 |
| `bands` | `[]` | 제한 정의. [대역 키와 알고리즘](#대역-키와-알고리즘) 참고 |

신원 스코프는 `fluxgate.ratelimit.identity.source`(기본 `PRINCIPAL`)를 따릅니다. 인증된 주체가 없는 상태에서
`PER_USER`나 `PER_API_KEY`를 쓰면 `missing-key-behavior`가 결정합니다. 활성화된 `PER_API_KEY` 규칙이 있는데
신원 소스가 API 키 헤더를 전혀 읽지 않으면 기동 시 WARN이 남습니다.

## 매처

`fluxgate.ratelimit.rule-sets[n].rules[m].matcher`

모든 조건은 AND로 결합됩니다. 비어 있거나 없는 조건은 "모두 일치"를 뜻합니다. 제외가 포함보다 우선합니다.

| 키 | 타입 | 설명 |
|----|------|------|
| `methods` | 목록 | HTTP 메서드. 예: `[GET, POST]`. `Locale.ROOT`로 대문자 변환. 비어 있으면 모든 메서드 |
| `path-patterns` | 목록 | Ant 스타일 포함 패턴. 예: `[/api/**, /v2/**]`. 비어 있으면 모든 경로 |
| `exclude-path-patterns` | 목록 | Ant 스타일 제외 패턴. 예: `[/api/health]`. 일치하는 경로는 포함 패턴에도 일치하더라도 선택되지 않습니다 |
| `header-equals` | 맵 | 헤더 이름 → 정확히 일치해야 하는 값. 예: `x-tier: premium` |
| `header-present` | 목록 | 요청에 존재해야 하는 헤더 이름 |

헤더 이름은 대소문자를 구분하지 않습니다. 패턴은 Ant 규칙을 따릅니다. `?`는 한 문자, `*`는 한 세그먼트
안, `**`는 여러 세그먼트에 걸치며, `**/`는 세그먼트 경계에서만 일치합니다.

```yaml
matcher:
  methods: [POST, PUT]
  path-patterns: [/api/**]
  exclude-path-patterns: [/api/health, /api/metrics]
  header-equals:
    x-tier: premium
  header-present: [x-request-id]
```

## 대역 키와 알고리즘

`fluxgate.ratelimit.rule-sets[n].rules[m].bands[k]`

| 키 | 기본값 | 설명 |
|----|--------|------|
| `capacity` | 필수 | 윈도 안에서 허용되는 요청(토큰) 수. 양수여야 합니다 |
| `window` | 필수 | 윈도 길이: `60s`, `1m`, `PT1H`, `30d`. 양수여야 합니다 |
| `algorithm` | `TOKEN_BUCKET` | `TOKEN_BUCKET`, `SLIDING_WINDOW`, `FIXED_WINDOW` |
| `quota-period` | _(없음)_ | `DAILY`, `WEEKLY`, `MONTHLY`. `FIXED_WINDOW`에서만 유효 |
| `zone-id` | `UTC` | 달력 정렬에 쓰는 IANA 시간대. 예: `Asia/Seoul`. 알 수 없는 id는 기동 실패 |
| `sliding-window-buckets` | `10` | `SLIDING_WINDOW`의 하위 버킷 수. 2 이상 60 이하여야 합니다 |
| `label` | _(자동 유도)_ | 메트릭과 관리 UI용 표시 라벨. 설정하면 저장소 버킷 키 세그먼트로도 쓰입니다 |

| 알고리즘 | 동작 |
|----------|------|
| `TOKEN_BUCKET` | 토큰이 `capacity / window` 속도로 연속 충전됩니다. `capacity`까지 버스트를 허용합니다. 기본값이며 0.4 이전의 유일한 알고리즘 |
| `SLIDING_WINDOW` | 윈도를 `sliding-window-buckets`개의 하위 버킷으로 나누고 가장 최근의 완전한 윈도만 셉니다. 고정 윈도보다 부드럽고 메모리는 제한적입니다 |
| `FIXED_WINDOW` | 텀블링 카운터. `quota-period`와 함께 쓰면 달력 경계에서 카운터가 초기화됩니다 |

라벨이 없는 대역은 설정에서 라벨을 유도하며, 이것이 Redis 키 세그먼트이기도 합니다. `100-per-60s`(토큰 버킷),
`100-per-60s-sw`(슬라이딩 윈도), `100-per-60s-fw`(고정 윈도), `1000-per-30d-monthly`(달력 쿼터)입니다.
한 규칙의 여러 대역은 서로 다른 라벨이어야 하며, 같은 라벨이 유도되는 두 대역은 거부됩니다. 라벨을 바꾸면
그 대역의 버킷이 이동하므로 한 번 초기화됩니다.

```yaml
bands:
  - capacity: 10          # 버스트 방어
    window: 1s
  - capacity: 1000        # 지속 속도
    window: 1h
    algorithm: SLIDING_WINDOW
    sliding-window-buckets: 12
```

## 달력 쿼터: `quota-period`와 `zone-id`

`quota-period`를 가진 `FIXED_WINDOW`는 대역의 `zone-id` 기준으로 자정(`DAILY`), 월요일 자정(`WEEKLY`),
매월 1일 자정(`MONTHLY`)에 초기화됩니다:

```yaml
bands:
  - capacity: 10000
    window: 30d
    algorithm: FIXED_WINDOW
    quota-period: MONTHLY
    zone-id: Asia/Seoul
```

`window`는 여전히 필수이며 유도되는 라벨(`10000-per-30d-monthly`)의 일부입니다. 사용자가 UTC에 있지 않다면
항상 `zone-id`를 지정하세요. 알 수 없는 id는 예전에는 조용히 UTC로 폴백해 모든 경계를 어긋나게 했고, 이제는
기동이 실패합니다. 24시간을 넘는 윈도가 더는 24시간마다 초기화되지 않으며, 버킷 TTL은
`fluxgate.redis.max-bucket-ttl`(기본 `7d`)로 제한됩니다. 이 상한이 윈도를 줄이는 규칙마다 리미터가 한 번
경고합니다(`FIXED_WINDOW` 카운터는 이 경고에서 제외됩니다).

## 접근 제어

`fluxgate.ratelimit.rule-sets[n].access-control`

모든 리미터보다 먼저 평가됩니다. **거부가 허용보다 우선**하며, 어느 목록에도 일치하지 않으면 일반
Rate Limiting이 적용됩니다.

| 키 | 효과 |
|----|------|
| `denied-ips` | 즉시 거부되는 CIDR (리미터를 조회하지 않으며, 필터는 대기 시간 없는 429로 응답) |
| `denied-keys` | 즉시 거부되는 해석된 키 값 |
| `allowed-ips` | Rate Limiting을 완전히 **우회**하는 CIDR |
| `allowed-keys` | Rate Limiting을 우회하는 해석된 키 값 |

IP 목록은 CIDR 문법(IPv4, IPv6)을 쓰고 모든 스코프에서 요청의 클라이언트 IP와 비교하므로, 거부된 IP는
`PER_USER` 규칙에서도 차단됩니다. 키 목록은 스코프 접두사를 포함한 **해석된** 키와 비교합니다.
`user:alice`, `key:internal-service`, `ip:192.0.2.1` 같은 형태입니다. 설정된 키는 요청 값과 같은 새니타이저를
거치므로 `user:a+1`은 신원 `a+1`과 여전히 일치하며, 바뀐 항목마다 WARN이 남습니다. 파싱할 수 없는 CIDR은
기동이 실패합니다.

```yaml
access-control:
  denied-ips: [203.0.113.0/24]
  allowed-ips: [10.0.0.0/8]
  allowed-keys: [key:internal-service]
  denied-keys: [user:abuser]
```

## 전체 예제

```yaml
fluxgate:
  ratelimit:
    default-rule-set-id: public-api
    rule-sets:
      - id: public-api
        description: Public API
        access-control:
          denied-ips: [203.0.113.0/24]
          allowed-keys: [key:internal-service]
        rules:
          - id: premium-writes
            priority: 20
            scope: PER_API_KEY
            on-limit-exceed-policy: REJECT_REQUEST
            matcher:
              methods: [POST, PUT, DELETE]
              path-patterns: [/api/**]
              exclude-path-patterns: [/api/health]
              header-equals:
                x-tier: premium
            bands:
              - capacity: 600
                window: 1m
          - id: anonymous-reads
            priority: 10
            scope: PER_IP
            matcher:
              methods: [GET]
              path-patterns: [/api/**]
            bands:
              - capacity: 30
                window: 1s
                label: burst
              - capacity: 5000
                window: 1h
                algorithm: SLIDING_WINDOW
                sliding-window-buckets: 12
          - id: monthly-export-quota
            scope: PER_USER
            matcher:
              path-patterns: [/api/export/**]
            bands:
              - capacity: 10000
                window: 30d
                algorithm: FIXED_WINDOW
                quota-period: MONTHLY
                zone-id: Asia/Seoul
```

## 기동 시 검증

`PropertiesRuleSetProvider`는 애플리케이션이 기동하는 동안 모든 룰 세트를 만들고 검증합니다. 실수는 나중에
오동작하는 대신, 룰 세트·규칙·프로퍼티 이름이 담긴 메시지와 함께 기동을 실패시킵니다:

| 실수 | 결과 |
|------|------|
| `id` 없는 룰 세트, 또는 같은 룰 세트 id 중복 | 기동 실패 |
| `id` 없는 규칙, 또는 한 룰 세트 안에서 규칙 id 중복 | 기동 실패 |
| `window` 누락, 또는 0이나 음수 | 기동 실패 |
| 대역 `capacity`가 0이나 음수 | 기동 실패 |
| 알 수 없는 `zone-id` | 기동 실패 (조용한 UTC 폴백 없음) |
| `FIXED_WINDOW`가 아닌 알고리즘에 `quota-period` 지정 | 기동 실패 |
| `SLIDING_WINDOW`의 `sliding-window-buckets`가 2~60 범위 밖 | 기동 실패 |
| 한 규칙의 두 대역이 같은 라벨로 유도됨 | 기동 실패 |
| `access-control`의 파싱할 수 없는 CIDR | 기동 실패 |
| 활성화된 `PER_API_KEY` 규칙이 있는데 `identity.source`가 API 키 헤더를 읽지 않음 | WARN |

## 관련 문서

- [0.4 마이그레이션](../operations/migration-0.4.ko.md) - 기동 시 검증과 키 형식 변경
- [@RateLimit 애노테이션 가이드](annotation.ko.md) - 이 룰 세트를 사용하는 메서드 단위 제한
- [Key Resolver](../customization/key-resolver.ko.md) - 스코프별 키가 만들어지는 방식
- [메인 README](../../../README.ko.md) - 설정 레퍼런스
