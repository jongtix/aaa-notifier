# aaa-notifier

AAA(Algorithmic Alpha Advisor) Phase 3 알림 서비스.

analyzer가 발행하는 `stream:signal:*`와 collector가 발행하는 `stream:tick:*` Redis Streams를 구독해, 6단계 필터 파이프라인(밴드 판정·히스테리시스·확증·쿨다운·confidence·Tier)을 거친 뒤 매매봇 텔레그램 발송, `notification_log` INSERT, `stream:alert` 발행까지 수행하는 것이 최종 목표다.

> 이 레포의 현재 범위는 SPEC-NOTIFIER-FOUNDATION-001(레포·프로세스·CI 골격)과 SPEC-NOTIFIER-CONSUMER-001(Redis Streams 소비 계층)이다. 필터 로직·텔레그램 발송·리포트·메트릭 정의는 후속 SPEC(FILTER/TELEGRAM/REPORT/OBSV) 소관이며 아직 구현되어 있지 않다.

## 기술 스택

> 버전 정보는 `gradle/libs.versions.toml`과 `build.gradle.kts`를 참조한다. README에 중복 기재하지 않는다.

| 구성 요소 | 비고 |
|-----------|------|
| Java 21 | Virtual Threads 활성화 (`spring.threads.virtual.enabled`) |
| Spring Boot | Web, Actuator, Data Redis |
| Micrometer | `/actuator/prometheus` 노출 |
| Gradle | Kotlin DSL |

DB 접근은 없다. JPA·MySQL·Flyway·DataSource는 이를 처음 필요로 하는 후속 SPEC에서 도입한다.

## 전제조건

- Java 21
- 실행 시 Redis 접속 정보 환경변수: `REDIS_HOST`, `REDIS_PORT`, `REDIS_APPUSER_USERNAME`, `REDIS_APPUSER_PASSWORD` (`src/main/resources/application.yml`의 `${...}` 자리)
- 통합 테스트(`@Tag("integration")`)는 Testcontainers로 Redis 컨테이너를 띄우므로 컨테이너 런타임이 필요하다

## Quick Start

```bash
# Git hook 설치 (최초 1회)
./gradlew installGitHooks

# 빌드 (단위 테스트 포함, 통합 테스트 제외)
./gradlew build

# 전체 검증 — CI 게이트 (Spotless + SpotBugs + PMD + 단위/통합 테스트 + JaCoCo 85% 라인 커버리지)
./gradlew check

# 실행 (Redis 접속 환경변수 필요)
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

## 헬스체크·메트릭

`management.endpoints.web.exposure.include`는 `health,prometheus`다. readiness 그룹은 `readinessState`와 `redis`(PING 기반 `RedisPingHealthIndicator`, ADR-015)를 포함한다.

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
│   ├── filter/             — 필터 파이프라인 (FILTER-001, 패키지 경계만)
│   ├── telegram/           — 텔레그램 발송 (TELEGRAM-001, 패키지 경계만)
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
