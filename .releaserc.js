// Gitmoji + Conventional Commits 파싱을 위한 headerPattern
// - \p{Emoji_Presentation}, \p{Extended_Pictographic}: Unicode 이모지 매칭 (u flag 필수)
// - \uFE0F?: Variation Selector-16 (♻️, 🏗️ 등 multi-codepoint 이모지 지원)
// - 캡처 그룹: (type)(scope)(breaking)(subject)
const HEADER_PATTERN =
    /^(?:[\p{Emoji_Presentation}\p{Extended_Pictographic}]\uFE0F?\s*)?(\w+)(?:\(([\w$.\-*\s]*)\))?(!)??:\s(.*)$/u;

// breaking marker(!) 판정 전용 패턴 — PARSER_OPTS가 breakingHeaderPattern을 덮어쓰지 않으면
// preset(conventionalcommits)의 업스트림 기본값 /^(\w*)(?:\((.*)\))?!: (.*)$/ 이 쓰이는데,
// 이 정규식은 선행 이모지를 허용하지 않는다.
// - 증상: `✨ feat(api)!:`의 `!`가 무시되어 major여야 할 릴리스가 아무 경고 없이 조용히 minor가 된다
// - 이모지 접두부는 위 HEADER_PATTERN과 바이트 동일해야 한다 (두 정규식이 갈라지면 같은 침묵 결함이 재발)
// - 나머지 본문은 업스트림 기본값 그대로 유지한다 (이모지 허용 외의 동작 변화 금지)
const BREAKING_HEADER_PATTERN =
    /^(?:[\p{Emoji_Presentation}\p{Extended_Pictographic}]\uFE0F?\s*)?(\w*)(?:\((.*)\))?!: (.*)$/u;

const PARSER_OPTS = {
    headerPattern: HEADER_PATTERN,
    headerCorrespondence: ["type", "scope", "breaking", "subject"],
    breakingHeaderPattern: BREAKING_HEADER_PATTERN,
};

module.exports = {
    branches: ["main"],
    plugins: [
        // 커밋 메시지 분석 → 릴리즈 타입 결정 (feat→minor, fix/perf→patch, !→major)
        // custom 규칙 먼저 확인 → 매칭 없으면 기본 규칙 폴백 (feat, fix, perf, revert, breaking)
        // 기본 규칙에도 없는 타입(chore, docs, style 등)은 릴리즈를 트리거하지 않음
        [
            "@semantic-release/commit-analyzer",
            {
                preset: "conventionalcommits",
                parserOpts: PARSER_OPTS,
                releaseRules: [
                    { type: "feat", release: "minor" },
                    { type: "fix", release: "patch" },
                    { type: "perf", release: "patch" },
                    { breaking: true, release: "major" },
                ],
            },
        ],
        // 릴리즈 노트 자동 생성 (GitHub Release에 포함)
        [
            "@semantic-release/release-notes-generator",
            {
                preset: "conventionalcommits",
                parserOpts: PARSER_OPTS,
            },
        ],
        // GitHub Release 생성 + git 태그 push → docker.yml 트리거
        // 버전은 gradle.properties commit-back 대신 빌드 시점에 태그에서 파생한다
        // (-Pversion=${GITHUB_REF_NAME#v}, SPEC-INFRA-CICD-002 M7)
        "@semantic-release/github",
    ],
};
