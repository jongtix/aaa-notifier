package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.web.util.HtmlUtils;

/**
 * 매매봇 메시지 본문 렌더링 (REQ-NOTIFIER-TELEGRAM-011, plan.md §D.3 — 서식 모드 HTML).
 *
 * <p><b>왜 HTML인가</b>: 이스케이프 대상이 {@code <}·{@code >}·{@code &} 3종뿐이다. MarkdownV2는 {@code .}·{@code
 * -} 등 18종이 예약 문자라 {@code BRK.B} 심볼이나 {@code -4.30%} score에서 하나만 빠져도 400 영구 실패로 알림이 사라진다. 등급만 굵게
 * 표시한다.
 *
 * <p>모든 동적 값은 {@link #escape}를 거친다. 본문은 텔레그램 상한 4096자 이내로 자른다.
 */
public class TelegramMessageFormatter {

    /** Bot API {@code text} 상한(UTF-16 코드 유닛). */
    static final int MAX_LENGTH = 4096;

    private static final String ELLIPSIS = "…";
    private static final int PERCENT_SCALE = 2;
    private static final int CONFIDENCE_SCALE = 3;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /**
     * 대기 큐 요약 본문 (TECHSPEC §1.3) — "장애 기간: {시작}~{종료}, 미발송 {N}건" + 목록 최대 {@code listLimit}줄 + 나머지는
     * 건수만.
     *
     * @param start 장애 기간 시작 (포함 항목 중 가장 오래된 적재 시각, KST)
     * @param end 장애 기간 종료 (요약 생성 시각, KST)
     * @param count 요약에 포함한 항목 수 (목록 표기분 + 건수 표기분)
     * @param listed 목록에 표기할 수 있는 항목 (FIFO 순서)
     * @param listLimit 목록 상한
     * @return 이스케이프·길이 상한이 적용된 HTML 본문
     */
    public String formatSummary(
            LocalDateTime start,
            LocalDateTime end,
            int count,
            List<QueuedAlert> listed,
            int listLimit) {
        StringBuilder text = new StringBuilder(128 + 64 * Math.min(listLimit, listed.size()));
        text.append("<b>매매봇 발송 장애 요약</b>\n장애 기간: ")
                .append(escape(start.format(PERIOD)))
                .append('~')
                .append(escape(end.format(PERIOD)))
                .append(", 미발송 ")
                .append(count)
                .append('건');
        int shown = Math.min(listLimit, listed.size());
        for (QueuedAlert alert : listed.subList(0, shown)) {
            text.append('\n')
                    .append(
                            escape(
                                    alert.queuedAt() == null
                                            ? "-"
                                            : alert.queuedAt().format(LINE_TIME)))
                    .append(' ')
                    .append(escape(alert.symbol()))
                    .append(' ')
                    .append(horizonLabel(alert.horizon() == null ? "-" : alert.horizon()))
                    .append(' ')
                    .append(escape(alert.from()))
                    .append('→')
                    .append(escape(alert.to()))
                    .append(' ')
                    .append(escape(formatScore(alert.score())));
        }
        if (count > shown) {
            text.append("\n외 ").append(count - shown).append('건');
        }
        return truncate(text.toString());
    }

    /**
     * 개별 알림 본문을 만든다.
     *
     * @param decision 발송 후보 결정
     * @return 이스케이프·길이 상한이 적용된 HTML 본문
     */
    public String formatAlert(AlertDecision decision) {
        StringBuilder text = new StringBuilder(160);
        text.append("[Tier ")
                .append(decision.tier())
                .append("] ")
                .append(escape(decision.symbol()))
                .append(' ')
                .append(horizonLabel(decision.horizon()))
                .append("\n<b>")
                .append(decision.fromGrade().name())
                .append("</b> → <b>")
                .append(decision.toGrade().name())
                .append("</b>\n")
                .append(escape(formatScore(decision.score())))
                .append(" · ")
                .append(escape(formatConfidence(decision.confidence())))
                .append("\n발동가 ")
                .append(escape(formatPrice(decision.triggerPrice())));
        if (decision.lowConfidenceFlag()) {
            text.append("\n⚠ 저확신");
        }
        return truncate(text.toString());
    }

    /** {@code D20} → {@code [단기]}, {@code D60} → {@code [중기]}, 그 밖은 원문을 이스케이프해 괄호로 감싼다. */
    static String horizonLabel(String horizon) {
        return switch (horizon) {
            case "D20" -> "[단기]";
            case "D60" -> "[중기]";
            default -> "[" + escape(horizon) + "]";
        };
    }

    /** score를 부호 있는 백분율로 ({@code 0.043} → {@code +4.30%}). {@code null}이면 미표기 문구. */
    static String formatScore(BigDecimal score) {
        if (score == null) {
            return "score 미수신";
        }
        BigDecimal percent = score.movePointRight(2).setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        String sign = percent.signum() >= 0 ? "+" : "";
        return "score " + sign + percent.toPlainString() + "%";
    }

    private static String formatConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return "confidence 미수신";
        }
        return "confidence "
                + confidence.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatPrice(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }

    /**
     * HTML 서식 모드의 예약 문자를 이스케이프한다.
     *
     * <p>인코딩을 UTF-8로 지정하면 {@code <}·{@code >}·{@code &}·{@code "}·{@code '}만 문자 참조로 바뀐다 — 텔레그램 HTML
     * 서식이 받아들이는 범위({@code &lt;}·{@code &gt;}·{@code &amp;}·{@code &quot;}와 숫자 참조)다. 인코딩을 생략하면
     * {@code &eacute;} 같은 이름 참조까지 만들어져 텔레그램이 400으로 거부한다.
     *
     * @param value 동적 값 ({@code null}이면 빈 문자열)
     * @return 이스케이프된 값
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    /** 4096자를 넘으면 잘라 말줄임표를 붙인다. */
    static String truncate(String text) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
    }
}
