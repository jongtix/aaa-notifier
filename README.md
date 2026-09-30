# aaa-notifier

AAA(Algorithmic Alpha Advisor) Phase 3 알림 서비스.

analyzer가 발행하는 `stream:signal:*`와 collector가 발행하는 `stream:tick:*` Redis Streams를 구독해, 6단계 필터 파이프라인(밴드 판정·히스테리시스·확증·쿨다운·confidence·Tier)을 거친 뒤 매매봇 텔레그램 발송, `notification_log` INSERT, `stream:alert` 발행까지 수행하는 것이 최종 목표다.

> 이 레포의 현재 범위는 SPEC-NOTIFIER-FOUNDATION-001(레포·프로세스·CI 골격), SPEC-NOTIFIER-CONSUMER-001(Redis Streams 소비 계층), SPEC-NOTIFIER-FILTER-001(6단계 필터 파이프라인 + `notification_log` DRYRUN 기록), SPEC-NOTIFIER-TELEGRAM-001(매매봇 텔레그램 실발송 능력)이다. 실발송 모드는 기본 꺼짐이라 필터 결정은 여전히 `event_type='DRYRUN'` 행으로만 남는다 — 드라이런 검토를 마친 운영자가 켤 때 비로소 텔레그램으로 나간다. 리포트·경보 룰은 후속 SPEC(REPORT/OBSV) 소관이며 아직 구현되어 있지 않다.

## 기술 스택

> 버전 정보는 `gradle/libs.versions.toml`과 `build.gradle.kts`를 참조한다. README에 중복 기재하지 않는다.

| 구성 요소 | 비고 |
|-----------|------|
| Java 21 | Virtual Threads 활성화 (`spring.threads.virtual.enabled`) |
| Spring Boot | Web, Actuator, Data Redis, JDBC |
| Micrometer | `/actuator/prometheus` 노출 |
| Gradle | Kotlin DSL |

SPEC-NOTIFIER-FILTER-001부터 MySQL에 접근한다(JdbcTemplate, `notifier` 계정) — 장전 참조 데이터 SELECT(`stocks`·`daily_ohlcv`·`signal_price_bands`)와 `notification_log` INSERT만 수행한다(실발송 모드가 꺼져 있으면 DRYRUN 행, 켜져 있으면 발송 사건 행 — 아래 텔레그램 실발송 절). JPA·Flyway는 도입하지 않으며, DDL은 collector Flyway가 소유한다(ADR-016).

## 전제조건

- Java 21
- 실행 시 Redis 접속 정보 환경변수: `REDIS_HOST`, `REDIS_PORT`, `REDIS_APPUSER_USERNAME`, `REDIS_APPUSER_PASSWORD` (`src/main/resources/application.yml`의 `${...}` 자리)
- 실행 시 MySQL 접속 정보 환경변수: `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`, `MYSQL_NOTIFIER_PASSWORD`
- 실발송 모드를 켤 때만 필요한 환경변수: `NOTIFIER_TELEGRAM_ENABLED=true`, `TELEGRAM_TRADE_BOT_TOKEN`, `TELEGRAM_TRADE_CHAT_ID` (꺼져 있으면 비어 있어도 기동한다. 켜졌는데 비어 있으면 기동 실패)
- 통합 테스트(`@Tag("integration")`)는 Testcontainers로 Redis 컨테이너를 띄우므로 컨테이너 런타임이 필요하다

## Quick Start

```bash
# Git hook 설치 (최초 1회)
./gradlew installGitHooks

# 빌드 (단위 테스트 포함, 통합 테스트 제외)
./gradlew build

# 전체 검증 — CI 게이트 (Spotless + SpotBugs + PMD + 단위/통합 테스트 + JaCoCo 85% 라인 커버리지)
./gradlew check

# 실행 (Redis·MySQL 접속 환경변수 필요)
./gradlew bootRun
```

`./gradlew test`는 PMD·SpotBugs·Spotless·JaCoCo를 포함하지 않는다. "전부 통과" 판단은 `./gradlew check` 기준이다.

## 소비 계층 (SPEC-NOTIFIER-CONSUMER-001)

단일 Consumer Group `notifier`로 아래 4개 스트림을 소비한다. 스트림마다 가상 스레드 1개가 독립된 소비 단위로 돌며, 한 스트림의 실패가 나머지를 멈추지 않는다.

| 스트림 | 발행자 | 컨슈머 이름 |
|--------|--------|-------------|
| `stream:tick:domestic` | collector | `notifier-tick-domestic` |
| `stream:tick:overseas` | collector | `notifier-tick-overseas` |
| `stream:signal:domestic` | analyzer | `notifier-signal-domestic` |
| `stream:signal:overseas` | analyzer | `notifier-signal-overseas` |

- 소비 회차는 `XPENDING` 판독 → 유휴 임계를 넘긴 미확인 메시지 `XCLAIM` 재소유 → `XREADGROUP` 신규 읽기 순서다.
- 하류 전달이 정상 반환한 뒤에만 확인응답(`XACK`)한다. 하류가 예외를 던지면 미확인으로 남아 다음 회차에 재소유된다.
- 재전달 횟수가 임계를 넘기거나 페이로드를 해석할 수 없으면 `stream:dlq:{원본 스트림명}`으로 이관한 뒤 원본을 확인응답한다. DLQ 길이는 MAXLEN 정확 트리밍으로 제한한다.
- 파싱된 관측치는 `StreamObservationHandler` 포트로 동기 전달한다. 구현체가 없으면 관측치를 소모하고 DEBUG 로그만 남기는 기본 구현이 주입된다.

### 설정

`application.yml`의 `notifier.stream.*`이며, Spring 환경변수 바인딩(예: `NOTIFIER_STREAM_DLQ_MAX_LEN`)으로 재정의할 수 있다.

| 키 | 기본값 | 의미 |
|----|--------|------|
| `enabled` | `true` | 소비 활성화 (`NOTIFIER_STREAM_ENABLED`). `false`면 그룹 생성도 소비 스레드 기동도 하지 않는다 |
| `block-timeout` | `2s` | `XREADGROUP` 블로킹 대기 시간 |
| `read-batch-size` | `10` | 1회 읽기 건수 |
| `claim-idle-threshold` | `30s` | 이 시간을 넘게 미확인인 메시지를 재소유한다 |
| `max-delivery-count` | `3` | 재전달 횟수가 이 값을 초과하면 DLQ로 이관한다 |
| `error-backoff` | `5s` | 일시적 오류 후 재시도까지의 대기 시간 |
| `dlq-max-len` | `500` | DLQ 길이 상한 |

## 텔레그램 실발송 (SPEC-NOTIFIER-TELEGRAM-001)

필터 파이프라인의 결정 싱크 포트(`AlertDecisionSink`)를 매매봇 실발송 구현으로 대체한다. **실발송 모드는 기본 꺼짐**이며, 꺼져 있으면 `telegram` 패키지의 빈이 하나도 올라오지 않고 FILTER-001의 DRYRUN 싱크가 그대로 쓰인다(텔레그램·대기 큐·safe_mode·`stream:alert` 모두 미사용).

켜져 있을 때의 흐름:

- 발송 후보만 보낸다. 억제 결정은 WARN 구조화 로그와 계측만 남기고 `notification_log` 행을 쓰지 않는다.
- 결정 수신은 텔레그램 응답을 기다리지 않는다. 단일 디스패처가 같은 chat_id에 최소 1초 간격으로 `sendMessage`를 보낸다. 인라인 버튼은 붙이지 않는다(Phase 4 trader 소관).
- 응답 처리: 성공 → `SENT` 행 + `stream:alert` 발행 / 429 → `retry_after`만큼 대기 후 재발송(상한 초과 시 대기 큐) / 5xx·타임아웃 → 백오프 재시도 후 대기 큐 + `QUEUED` / 429 외 4xx → `SEND_FAILED`, 재시도 없음.
- 대기 큐(`queue:telegram:pending`)에 쌓인 알림은 복구 후 요약 1건(`SUMMARY_SENT`)으로 보낸다. 24시간을 넘긴 항목은 폐기한다.

### safe_mode (`safe_mode:notifier:telegram`)

| 값 | 쓰는 주체 | 동작 |
|----|-----------|------|
| `OFF` 또는 키 없음 | 운영자·notifier | 정상 발송 |
| `AUTO` | notifier(연속 실패 N건) | 발송 중단, 후보는 대기 큐에 쌓임. 1분 cron `getMe` 프로브 성공 시 notifier가 스스로 `OFF`로 해제 |
| `ON` | 운영자(kill switch) | 발송 중단, 후보는 `DRYRUN` 행으로만 기록. 해제 후에도 재전달·요약 없음. 프로브를 보내지 않으며 운영자만 해제 |

값은 공백·대소문자를 무시하고 비교하며, 세 값 외의 값(`1`·`true`·빈 문자열 등)은 `ON`으로 간주한다(WARN 로그). 재기동 없이 반영된다.

```bash
# kill switch 켜기 / 끄기 (redis-cli)
SET safe_mode:notifier:telegram ON
SET safe_mode:notifier:telegram OFF   # 또는 DEL safe_mode:notifier:telegram
```

### 설정

`application.yml`의 `notifier.telegram.*`이며, 수치는 전부 잠정값이다.

| 키 | 기본값 | 의미 |
|----|--------|------|
| `enabled` | `false` | 실발송 모드 (`NOTIFIER_TELEGRAM_ENABLED`) |
| `bot-token` / `chat-id` | (빈 값) | `TELEGRAM_TRADE_BOT_TOKEN` / `TELEGRAM_TRADE_CHAT_ID` |
| `min-send-interval` | `1s` | 같은 chat_id 연속 요청 최소 간격 |
| `connect-timeout` / `read-timeout` | `3s` / `10s` | HTTP 타임아웃 |
| `dispatch-buffer-capacity` | `100` | 디스패치 버퍼 상한(초과분은 대기 큐로) |
| `alert-stream-max-len` | `500` | `stream:alert` 근사 MAXLEN |
| `retry.transient-retries` | `2` | 일시 오류 재시도 횟수(백오프 `1s` × `2`) |
| `rate-limit.default-wait` | `5s` | `retry_after`가 없는 429의 대기 |
| `rate-limit.max-cumulative-wait` / `max-resends` | `30s` / `3` | 알림 1건당 429 누적 대기·재발송 상한 |
| `safe-mode.failure-threshold` | `3` | `AUTO` 자동 진입 연속 실패 건수 |
| `safe-mode.probe-cron` | `0 * * * * *` | 프로브·요약 계기(cron 전용) |
| `queue.retention` / `summary-list-limit` | `24h` / `10` | 대기 큐 보존 기간·요약 목록 상한 |

## 헬스체크·메트릭

`management.endpoints.web.exposure.include`는 `health,prometheus`다. readiness 그룹은 `readinessState`와 `redis`(PING 기반 `RedisPingHealthIndicator`, ADR-015)를 포함한다.

실발송 모드가 켜져 있으면 `notifier.telegram.*` 계측이 추가로 등록된다 — 발송 결과(4분류 × 알림/요약)·발송 지연·대기 큐 길이·safe_mode 상태(출처 구분)·기록/발행/큐 쓰기 실패·큐 항목 폐기(사유 구분). 두 게이지(대기 큐 길이·safe_mode)는 첫 발송 전에도 기동 시 등록된다.

## 코드 품질 도구

| 도구 | 용도 | 실행 방법 |
|------|------|-----------|
| Spotless | 코드 포맷 자동화 (Google Java Format AOSP) | `./gradlew spotlessApply` |
| SpotBugs | 버그 패턴 탐지 + FindSecBugs | `./gradlew spotbugsMain` |
| PMD | 코드 품질 검사 | `./gradlew pmdMain` |
| pre-commit hook | `spotlessCheck` | `./gradlew installGitHooks` |
| pre-push hook | `pmdMain pmdTest test` (단위 테스트만, 컨테이너 미기동) | `./gradlew installGitHooks` |

`@Container` 필드를 가진 테스트 클래스에는 클래스 레벨 `@Tag("integration")`이 필수이며, `arch/IntegrationTagGuardTest`가 빌드 타임에 강제한다.

## 디렉토리 구조

```
aaa-notifier/
├── .github/workflows/      — release, docker, deploy, dependabot 자동 머지
├── config/                 — 정적 분석 도구 설정 (PMD, SpotBugs)
├── gradle/                 — 버전 카탈로그(libs.versions.toml)와 wrapper
├── scripts/                — Git hook 스크립트 (pre-commit, pre-push)
├── src/main/java/com/aaa/notifier/
│   ├── stream/             — Redis Streams 소비 계층 (CONSUMER-001)
│   ├── filter/             — 필터 파이프라인 + DRYRUN 결정 기록 (FILTER-001)
│   ├── telegram/           — 매매봇 실발송·safe_mode·대기 큐·stream:alert (TELEGRAM-001, 기본 꺼짐)
│   ├── report/             — 리포트 (REPORT-001, 패키지 경계만)
│   ├── observability/      — 메트릭·알림 룰 (OBSV-001, 패키지 경계만)
│   └── common/             — health, logging(Trace ID), config, trace
├── src/main/resources/     — application.yml
├── src/test/               — 단위·통합·아키텍처 테스트
├── Dockerfile
├── build.gradle.kts
├── CHANGELOG.md            — semantic-release가 생성
├── CLAUDE.md               — 서비스 로컬 작업 지침
└── README.md
```

## 관련 문서

- [PRD](../aaa-infra/docs/PRD.md) — 제품 요구사항
- [TECHSPEC](../aaa-infra/docs/TECHSPEC.md) — 아키텍처와 데이터 흐름 (Redis Streams 정책은 5.1절)
- [ADR](../aaa-infra/docs/ADR/) — 개별 결정의 배경과 트레이드오프
- [MILESTONE](../MILESTONE.md) — Phase별 마일스톤
- [TODO](../TODO.md) — 현재 작업 진행 상태
