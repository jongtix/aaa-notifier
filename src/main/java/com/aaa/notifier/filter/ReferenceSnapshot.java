package com.aaa.notifier.filter;

import com.aaa.notifier.stream.Market;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 장전 적재 결과의 불변 스냅샷 (REQ-001 — 종목×horizon 키의 프로세스 메모리 적재분).
 *
 * <p>적재기가 통째로 새 스냅샷을 만들고 {@link ReferenceDataHolder}가 참조를 한 번에 교체한다 — 틱 스레드는 교체 도중의 반쯤 채워진 맵을 보지
 * 않는다.
 */
public final class ReferenceSnapshot {

    private final Map<Key, StockReference> byKey;

    /**
     * @param references 적재된 종목 참조 데이터
     */
    public ReferenceSnapshot(Collection<StockReference> references) {
        this.byKey =
                references.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        reference ->
                                                new Key(reference.market(), reference.symbol()),
                                        Function.identity(),
                                        (first, second) -> first));
    }

    /** 아무것도 적재되지 않은 초기 상태. */
    public static ReferenceSnapshot empty() {
        return new ReferenceSnapshot(List.of());
    }

    /**
     * 시장·심볼로 참조 데이터를 찾는다.
     *
     * @param market 시장 구분
     * @param symbol 정규 종목 심볼
     * @return 감시 대상이 아니거나 이력이 없으면 빈 값
     */
    public Optional<StockReference> find(Market market, String symbol) {
        return Optional.ofNullable(byKey.get(new Key(market, symbol)));
    }

    /** 적재된 전체 종목. */
    public Collection<StockReference> stocks() {
        return byKey.values();
    }

    private record Key(Market market, String symbol) {}
}
