# Changelog

이 프로젝트의 주요 변경사항을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따른다.

## [Unreleased]

### Added

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
