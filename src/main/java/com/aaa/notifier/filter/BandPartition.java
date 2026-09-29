package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 한 경계 세트(PROMOTE 또는 DEMOTE)의 가격 오름차순 파티션 (TECHSPEC §6.6).
 *
 * <p>analyzer 스윕은 그리드 포인트를 등급별로 run-length 병합해 밴드를 만든다. 그래서 밴드 {@code high}는 그 등급의 마지막 그리드 포인트, 다음
 * 밴드 {@code low}는 그다음 그리드 포인트이며 둘 사이에 그리드 한 스텝의 간격이 생긴다. 이 간격의 가격은 <b>하한이 가격 이하인 마지막 밴드</b>(아래 밴드)에
 * 속한 것으로 본다 — 그리드 포인트 사이 가격을 직전 포인트의 등급으로 읽는 것과 같다.
 *
 * <p>그리드 범위 밖 가격은 최외곽 밴드로 클램프한다(REQ-014). 국내는 가격제한폭이 곧 그리드 정의역이라 발생하지 않고, 해외 급변동일에만 발생한다.
 */
public final class BandPartition {

    private final List<PriceBand> bands;

    /**
     * @param bandsAscending {@code band_seq} 오름차순(= 가격 오름차순) 밴드 목록
     * @throws IllegalArgumentException 목록이 비어 있으면 — 빈 파티션으로는 판정할 수 없다
     */
    public BandPartition(List<PriceBand> bandsAscending) {
        if (bandsAscending.isEmpty()) {
            throw new IllegalArgumentException("밴드 파티션은 최소 1개 밴드를 가져야 한다");
        }
        this.bands = List.copyOf(bandsAscending);
    }

    /**
     * 가격이 속한 파티션 등급을 조회한다.
     *
     * @param price 체결가
     * @return 등급과 클램프 여부
     */
    public BandLookup lookup(BigDecimal price) {
        PriceBand first = bands.getFirst();
        if (price.compareTo(first.low()) < 0) {
            return new BandLookup(first.grade(), true);
        }
        PriceBand last = bands.getLast();
        if (price.compareTo(last.high()) > 0) {
            return new BandLookup(last.grade(), true);
        }
        PriceBand matched = first;
        for (PriceBand band : bands) {
            if (band.low().compareTo(price) > 0) {
                break;
            }
            matched = band;
        }
        return new BandLookup(matched.grade(), false);
    }
}
