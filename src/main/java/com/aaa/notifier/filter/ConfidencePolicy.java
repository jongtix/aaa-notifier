package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.util.List;

/**
 * confidence 방향성 약화 판정과 저확신 표시 (REQ-052/053, TECHSPEC §8.4).
 *
 * <p><b>약화 추세</b>: 회전 윈도가 가득 찼고(N개), 오래된 값부터 최신 값까지 매 단계 엄격히 하락했으며, 처음 − 마지막 하락 폭이 임계 이상일 때. 윈도가 N개
 * 미만(신규 상장·신규 편입)이면 추세 판단 불가로 보고 약화로 치지 않는다 — 표본 부족을 하락 추세로 오판하지 않는다(acceptance E5).
 *
 * <p>약화 강등은 <b>Tier 구분 없이</b> 모든 전환 후보에 적용된다(REQ-052, 2026-09-29 확정 — Tier 1 STRONG 도달도 예외 없음). 저확신
 * 표시는 그와 별개로 발송을 막지 않는 표시 전용 정보다(REQ-042 후단·REQ-053 — static confidence 게이트 제거).
 *
 * @param confidence 수치 설정
 */
// @MX:NOTE: [AUTO] "연속 약화"를 매 단계 엄격 하락 + 누적 하락 폭 ≥ 임계로 해석한다 — 단계별 하락 폭 조건보다 느슨해 더 많이 억제하는(과소 알림) 쪽
public record ConfidencePolicy(FilterProperties.Confidence confidence) {

    /** 회전 윈도 길이(N). */
    public int windowSize() {
        return confidence.windowSize();
    }

    /**
     * 회전 윈도가 약화 추세인지.
     *
     * @param window 오래된 값 → 최신 값 순서
     */
    public boolean isWeakening(List<BigDecimal> window) {
        if (window.size() < confidence.windowSize()) {
            return false;
        }
        for (int i = 1; i < window.size(); i++) {
            if (window.get(i).compareTo(window.get(i - 1)) >= 0) {
                return false;
            }
        }
        return window.getFirst().subtract(window.getLast()).compareTo(confidence.weakeningDelta())
                >= 0;
    }

    /**
     * 저확신인지 — 신호 미수신({@code null})은 저확신으로 표시하지 않는다(판단 근거 없음).
     *
     * @param value 신호 confidence
     */
    public boolean isLow(BigDecimal value) {
        return value != null && value.compareTo(confidence.lowThreshold()) < 0;
    }
}
