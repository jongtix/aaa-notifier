# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Service Overview

Phase 3 알림 서비스. `stream:signal:*`(analyzer) + `stream:tick:*`(collector) Redis Streams를 구독해 6단계 필터 파이프라인(밴드 판정·히스테리시스·확증·쿨다운·confidence·Tier)을 거쳐 매매봇 텔레그램 발송 + `notification_log` INSERT + `stream:alert` 발행을 수행하는 것이 최종 목표다. 본 레포는 그 목표로 가는 **레포·프로세스·CI 골격**(SPEC-NOTIFIER-FOUNDATION-001)만 확립되어 있으며, 실제 필터 로직·텔레그램 발송·스트림 소비·리포트는 전부 후속 SPEC(CONSUMER/FILTER/TELEGRAM/REPORT/OBSV) 소관이다.

## Tech Stack

- Java 21, Virtual Threads
- Spring Boot (버전: `gradle/libs.versions.toml` 참조)
- Spring Data Redis (Redis 8.6) — 필터 상태 저장소 연결 (실제 `filter:*` 키 접근은 FILTER-001 소관)
- **DB 접근 없음** — notifier는 DDL이 없고 `notification_log` 마이그레이션은 collector Flyway가 소유한다(ADR-016). JPA/MySQL/Flyway/DataSource는 이 골격에 포함되지 않으며, 이를 최초로 필요로 하는 후속 SPEC(FILTER-001 등)에서 도입한다.

## Build & Run

```bash
./gradlew build       # 컴파일 + 단위 테스트(통합 제외) + 정적 분석 일부
./gradlew check       # [CI 게이트] 전체 검증 — Spotless + SpotBugs + PMD + test + integrationTest + JaCoCo 85% 라인 게이트
./gradlew bootRun     # 로컬 실행
./gradlew installGitHooks   # 최초 1회 — scripts/pre-commit·pre-push를 .git/hooks에 설치
```

- **[HARD] 검증 게이트는 `./gradlew check`다** — `./gradlew test`는 PMD/SpotBugs/Spotless/JaCoCo를 포함하지 않는다. "all green" 판단은 반드시 `check` 기준으로 한다.

## Package Structure (ADR-010 package-by-feature)

루트 패키지 `com.aaa.notifier`. 6개 feature 서브패키지가 후속 SPEC과 1:1 대응한다:

| 패키지 | 소관 SPEC | 현재 상태 |
|--------|-----------|-----------|
| `stream` | CONSUMER-001 | 패키지 경계만 (스트림 소비 로직 없음) |
| `filter` | FILTER-001 | 패키지 경계만 (필터 로직·`filter:*` 키 접근 없음) |
| `telegram` | TELEGRAM-001 | 패키지 경계만 (아키텍처=Spring RestClient 직접 호출 확정, sendMessage/DTO/명세 없음) |
| `report` | REPORT-001 | 패키지 경계만 |
| `observability` | OBSV-001 | 패키지 경계 + actuator prometheus 노출 |
| `common` | — | health(RedisPingHealthIndicator)·logging(TraceIdManager/SafeMdc)·trace·config |

## Key Conventions

- 스케줄링: `@Scheduled` cron만 사용 (`fixedDelay` 금지 — Virtual Threads 버그, ADR-008). PMD 가드 `NoFixedDelayScheduled`가 빌드 타임에 강제한다.
- 시간대: KST 통일. `main()`의 `TimeZone.setDefault(Asia/Seoul)`(프로덕션) + build.gradle.kts test/bootRun의 `-Duser.timezone=Asia/Seoul` jvmArgs(테스트·로컬)로 이중 보장(ADR-009). 단 ECS 로그의 `@timestamp`는 ECS 표준상 항상 UTC로 직렬화된다.
- 로깅: ECS JSON 구조화 로그(`logging.structured.format.file: ecs`) + trace_id(ADR-011). Trace ID는 `common/logging/TraceIdManager`로 발급/전파하며, Virtual Thread 자식 스레드는 MDC를 상속하지 않으므로 자식에서 `set()`을 별도 호출해야 한다.
- 프로파일 설정: 민감 값은 환경변수(`${VAR}`)로 주입, YAML에 하드코딩 금지.
- Redis 헬스: 커스텀 `RedisPingHealthIndicator`(PING 기반)가 `/actuator/health`에 liveness를 기여한다(ADR-015). Spring 기본 인디케이터(INFO 명령)는 `management.health.redis.enabled: false`로 비활성화한다. `RedisHealthConfig`는 `@AutoConfiguration(after = RedisAutoConfiguration.class)`로 선언해야 인디케이터 빈이 실제 등록된다(일반 `@Configuration` + `@ConditionalOnBean`은 auto-configuration보다 먼저 평가되어 빈이 등록되지 않는다 — 실측 확인).
- 버전 관리: 의존성 버전은 `gradle/libs.versions.toml`을 단일 소스로 관리. README/문서에 버전 중복 기재 금지(stale 방지).
- 패키지 루트: `com.aaa.notifier`.

## Test Tagging Convention (REQ-NOTIFIER-FOUNDATION-031/032)

collector SPEC-COLLECTOR-TESTLAYER-001의 사후 치료를 그린필드 단계에서 예방적으로 이식한 규칙이다.

- **[HARD]** `@Container`(Testcontainers) 애노테이트 필드를 가진 테스트 클래스는 반드시 클래스 레벨 `@Tag("integration")`를 부여한다.
- `pre-push`는 **단위 테스트만** 실행한다(`./gradlew test` — 통합 태그 제외 필터, 컨테이너 기동 없음). 통합 테스트(`integrationTest` 태스크)는 CI(`./gradlew check`)에서만 실행된다.
- 태그 누락은 `arch/IntegrationTagGuardTest`(순수 클래스파일 스캔, 컨테이너 불필요)가 빌드 타임에 자동 탐지해 `check`(및 태그 누락 클래스가 이미 존재하는 `test`)를 실패시킨다.
- `check`가 `test`+`integrationTest`+`jacocoTestReport`+`jacocoTestCoverageVerification`을 전부 실행하므로 85% 라인 커버리지 게이트는 단위+통합 합산 기준으로 CI에서 강제된다.
- **[HARD]** `jacocoTestReport`에 `finalizedBy`(finalizer)를 부착하지 않는다 — 부착 시 `./gradlew test`가 통합 테스트를 태스크 그래프로 끌어들여 pre-push가 컨테이너를 기동하게 된다(build.gradle.kts 주석 참조).

## 4-Layer Quality Gate (ADR-005/007/016)

1. 에이전트 루프 (전역 원칙 Verify, Don't Assume — 레포에 중복 명시 안 함)
2. **pre-commit**: `./gradlew spotlessCheck` (빠른 포맷 검사만)
3. **pre-push**: `./gradlew pmdMain pmdTest test` (단위 전용 + watchdog 타임아웃, 컨테이너 미기동)
4. **CI**: `release.yml`의 `./gradlew check` (전체 게이트 + 85% 커버리지) → `docker.yml`(GHCR 3-tag) → `deploy.yml`(NAS self-hosted). notifier는 DDL이 없어 마이그레이션 체크 없이 `pull → up -d --wait` + 실패 시 무조건 롤백 + Telegram 알림(B4).

## CI/CD Notes

- semantic-release는 Node 기반(`.releaserc.js`, gitmoji+conventional headerPattern). `feat→minor`·`fix/perf→patch`·`!→major`. `sed`로 `gradle.properties` 버전 갱신.
- Docker: `eclipse-temurin:21-jre-alpine` digest 핀, 비루트 UID **1006**(collector=1004·analyzer=1005 비충돌), `linux/amd64`. read-only fs·`cap_drop`·기동 순서(collector healthy 이후)는 aaa-infra compose 반영 사항.
- Dependabot: `gradle` + `github-actions` weekly.

## Delegation to aaa-infra (본 레포 범위 밖)

compose `read_only: true`·`cap_drop: [ALL]`·`tmpfs`(로그/덤프)·`depends_on: {collector: service_healthy}`·notifier 서비스 정의·포트 미매핑·`/var/log/aaa-notifier` 쓰기 마운트·UID 대장 문서화는 aaa-infra 레포에서 처리한다. TECHSPEC 10.3 "/actuator/health만 노출" 문면은 내부 aaa-network에 prometheus도 노출하도록 정정 대상(B6).

## Project Documents

- 프로젝트 전체 문서: `aaa-infra/docs/` — 상위 `aaa/CLAUDE.md` 참고
- SPEC: `aaa/.moai/specs/SPEC-NOTIFIER-FOUNDATION-001/`
