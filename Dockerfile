# === Build stage ===
# digest pin: 이미지 변경 시 docker manifest inspect로 AMD64 digest 재조회 필요
FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS build
WORKDIR /notifier

# Gradle wrapper + 빌드 설정 (의존성 레이어 캐시용 — src 변경 시 재다운로드 방지)
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN ./gradlew dependencies --no-daemon

# 소스 복사 및 빌드
# -x check: 정적 분석(spotbugs, pmd, spotless)과 테스트는 CI(release.yml)에서 실행하므로
#           Docker 빌드에서는 JAR 생성만 수행 (config/ 룰 파일은 check 태스크에서만 참조되어 불필요)
COPY src/ src/
RUN ./gradlew build -x check --no-daemon

# === Runtime stage ===
# digest pin: 이미지 변경 시 docker manifest inspect로 AMD64 digest 재조회 필요
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0

# OS 패키지 업그레이드: base digest 자체는 최신이나 상류 이미지가 재빌드되지 않아
# alpine 패키지(libexpat, p11-kit 등)가 배포판 최신 패치를 반영하지 못한 상태로 남을 수 있다.
# 최종 런타임 스테이지에서만 적용 — 빌드 스테이지는 재현성을 위해 불변 유지.
RUN apk upgrade --no-cache

# 비루트 유저 생성 + 로그/힙덤프 디렉토리 준비 (read_only 컨테이너에서 notifier 유저 쓰기 권한 보장)
# UID/GID 1006 — collector(1004)·analyzer(1005)와 비충돌 (REQ-NOTIFIER-FOUNDATION-020, 2026-07-05 확정)
RUN addgroup -S -g 1006 notifier && adduser -S -u 1006 notifier -G notifier \
    && mkdir -p /var/log/aaa-notifier/dump && chown -R notifier:notifier /var/log/aaa-notifier

# 애플리케이션 JAR 복사
WORKDIR /notifier
COPY --chown=notifier:notifier --from=build /notifier/build/libs/aaa-notifier.jar aaa-notifier.jar

USER notifier
EXPOSE 8080

# 헬스체크: Spring Actuator /actuator/health/liveness (Alpine BusyBox wget 사용)
# liveness는 JVM/프로세스 생존만 반영(외부 의존성 미포함) — Redis 일시 장애로 aggregate health가 DOWN이 되어도
# Docker HEALTHCHECK/CD --wait가 정상 배포를 오탐 롤백하지 않도록 분리한다.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

# JVM 옵션: TECHSPEC 10.3절 기준 (컨테이너 limit 600MB에 맞춘 힙/메타스페이스/다이렉트 상한)
# collector의 AIA chasing 2프로퍼티는 미이식 — koreaexim TLS 전용이며, 텔레그램은 유효 인증서 체인이라 불필요.
ENTRYPOINT ["java", \
  "-Xms64m", "-Xmx192m", \
  "-XX:MaxMetaspaceSize=128m", "-XX:MaxDirectMemorySize=64m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/var/log/aaa-notifier/dump/", \
  "-XX:ErrorFile=/var/log/aaa-notifier/dump/hs_err_pid%p.log", \
  "-Duser.timezone=Asia/Seoul", \
  "-jar", "aaa-notifier.jar"]
