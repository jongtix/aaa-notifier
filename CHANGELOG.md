# Changelog

이 프로젝트의 주요 변경사항을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따른다.

## [Unreleased]

### Changed

- docker.yml에 Trivy CVE 게이트/빌드 실패 시 독립 Telegram 알림 스텝 추가(SPEC-INFRA-CVE-SCAN-004 M1) — deploy.yml의 workflow_run 게이팅과 무관하게 항상 발동, 기존 시스템봇 재사용

### Added

- 필터/게이팅 파이프라인 + `notification_log` DRYRUN 기록 (SPEC-NOTIFIER-FILTER-001, AC 23건)
  - `filter/FilterPipeline` — CONSUMER-001의 `StreamObservationHandler` 포트를 처음으로 실제 동작하게 구현. 체결 틱만 파이프라인을 구동하고, 신호 수신은 `filter:signal` 스냅샷과 confidence 회전 윈도 갱신만 하며, 호가는 해석하지 않는다. 틱 경로에서는 DB를 조회하지 않고 장전 적재 스냅샷만 읽는다
  - 6단계 판정 — 이원 경계(PROMOTE·DEMOTE 파티션) 밴드 판정과 데드존 유효 등급 유지(히스테리시스) → 거래량·시간대(개장 직후·마감 직전)·ATR 과열 가드(`GuardEvaluator`) → 5×5 전환 매트릭스(`TransitionMatrix`)로 Tier(1/2/3)와 전환 유형(직접/HOLD 이탈/HOLD 진입) 분류, 유형별 확증 카운터(순수 강도 강화·완화 칸은 무확증) → 종목·horizon·Tier 단위 쿨다운(`TransitionGate`) → confidence 방향성 약화 시 Tier 구분 없이 강등·억제, 저확신은 표시만(`ConfidencePolicy`). 억제 사유 6종(`SuppressionReason`)
  - 장전 참조 데이터 적재(`ReferenceDataLoader`/`ReferenceDataRefresher`) — `stocks`·`daily_ohlcv`·`signal_price_bands`를 각 1회 SELECT(종목 목록은 서브쿼리로 묶어 N+1 없음). 국내 KST 08:30·해외 ET 09:00 cron과 기동 시 1회 재구성. 유효 등급(`filter:grade`)은 키가 없을 때만 전일 종가 기준으로 초기화해 장중 재시작이 기존 등급을 덮어쓰지 않는다. 적재 실패 시 이전 스냅샷 유지 + WARN + 실패 카운터
  - Redis 필터 상태(`RedisFilterStateStore`) — `filter:grade`/`filter:signal`/`filter:confirm`/`filter:cooldown`/`filter:confidence` 키
  - 결정 싱크 포트 `AlertDecisionSink` + 기본 구현 `JdbcDryRunAlertDecisionSink` — 발송 후보·억제 결정 모두 `notification_log`에 `event_type='DRYRUN'` 행으로 INSERT(`message_text`·`telegram_message_id`는 NULL, 텔레그램 발송 없음). 억제 사유는 전용 컬럼이 없어 WARN 구조화 로그로 남긴다. INSERT는 틱 소비 스레드에서 동기 호출되며 지연 없이 최대 3회 시도 후 실패 카운터로 폴백(fail-open). 대기 억제는 사유가 바뀔 때만 결정을 내보내 억제 행 범람을 막는다. TELEGRAM-001은 이 포트의 실발송 구현체만 빈으로 올리면 된다
  - 계측(`FilterMetrics`) — `notifier.filter.reference.load`/`reference.stocks`/`stage`/`decision`/`decision.persist.failure`/`confirm.pending`(확증 대기 게이지, 기동 시 등록)
  - 수치 파라미터 11개(δ 밴드 마진, 거래량 비율, 개장·마감 제외 시간, ATR 배수, 유형별 확증 횟수 3종, 유형별 쿨다운 3종)와 Tier 1 반복·Tier 3 완화 쿨다운, confidence 약화 임계·저확신 임계·윈도 길이를 `application.yml`의 `notifier.filter.*`로 외부화(`FilterProperties`) — 전부 과소 알림 방향의 잠정값으로, 드라이런 관측 후 보정 예정. δ는 analyzer `grade_boundaries.json`과 대조용 미러 값이다. `notifier.filter.enabled=false`면 파이프라인 빈을 올리지 않고 소비 계층의 무동작 핸들러가 남는다
- notifier 최초 DB 접근(읽기+쓰기) 개시 — 장전 참조 SELECT 3종 + `notification_log` DRYRUN INSERT
  - 런타임 의존성 추가: `spring-boot-starter-jdbc`(JdbcTemplate — JPA·Flyway는 도입하지 않음, DDL은 계속 collector Flyway 소관), `mysql-connector-j`(runtimeOnly). 테스트 의존성: `testcontainers-mysql`, `commons-codec`
  - `spring.datasource` 신설 — `notifier` 계정, 환경변수 `MYSQL_HOST`/`MYSQL_PORT`/`MYSQL_DATABASE`/`MYSQL_NOTIFIER_PASSWORD` 필요. Hikari 풀 2, `connection-timeout` 1초, `initialization-fail-timeout: -1`(DB 장애가 기동 실패로 번지지 않음)
  - `management.health.db.enabled: false` — DB는 fail-open 경로라 DB 일시 장애가 `/actuator/health` DOWN으로 번지지 않게 기본 인디케이터를 끔
  - 배포 전제: aaa-infra `docker-compose.yml`의 notifier 서비스에 `.env.notifier` env_file 배선 필요(aaa-infra 측 동반 변경). `notifier` 계정의 참조 테이블 SELECT 권한은 배포 시점에 라이브로 확인해야 한다
- CI/CD 룰셋 강화 (SPEC-INFRA-CICD-002)
  - `main` 브랜치 룰셋(`main-protection`) 신설 — 선형 히스토리 강제, 강제 푸시/삭제 차단, `test` 상태 체크 필수
  - `release.yml`의 test job에 `pull_request` 트리거 추가 — PR에서 머지 전 실제 CI 검증
  - GitHub App(`aaa-ci-release-bot`)이 `actions/create-github-app-token`으로 보호된 `main`을 우회해 릴리스 태그/커밋을 푸시(룰셋 `bypass_actors`에 유일하게 등재), 사람은 PR 경로만 허용
  - `docker.yml` 트리거를 `workflow_run: ["Release"]`에서 `push: tags: ['v*']`로 변경 — `workflow_run` 3단 체인(GitHub 문서상 깊이 제한)을 2단으로 축소, App이 푸시한 태그로도 안정적으로 빌드 발화. 중복 태그 탐색용 2중 체크아웃 로직 제거
  - `deploy.yml`/`release.yml`에 `concurrency` 그룹 추가 — 배포/릴리스 중복 실행 방지
  - `dependabot-auto-merge.yml` 신규 — non-major Dependabot PR을 CI 통과 후 자동 머지(`dependabot/fetch-metadata` + `gh pr merge --auto --rebase`), `dependabot.yml`에 3일 쿨다운 추가
  - `tag-protection` 룰셋 신설(`refs/tags/v*`) — 릴리스 태그 삭제·재태그 차단
  - 체크아웃 스텝에 `persist-credentials: false` 추가(푸시가 필요 없는 스텝 한정)
  - 릴리스 커밋백(commit-back) 메커니즘 제거 — `.releaserc.js`에서 `@semantic-release/exec`/`@semantic-release/git` 제거, 버전은 Docker 빌드 시점에 `ARG VERSION` → `-Pversion=${VERSION}`로 주입. `gradle.properties`의 정적 버전 필드는 이제 비활성 placeholder(`0.0.0+placeholder`, 코드에서 미참조)
- 🐛 fix(ci): `deploy.yml`의 `workflow_run.head_branch == 'main'` 게이트가 태그 트리거 Docker 실행 시 `head_branch`가 태그명으로 보고되는 것을 놓쳐 M5 적용 후 모든 릴리스에서 Deploy가 조용히 스킵되던 결함 수정 — `startsWith(github.event.workflow_run.head_branch, 'v')` 조건으로 교체. v1.0.3 배포로 라이브 검증 완료
