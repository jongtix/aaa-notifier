package com.aaa.notifier.filter;

import java.util.Objects;

/**
 * 한 종목·horizon의 이원 경계 파티션 쌍 (REQ-011, TECHSPEC §8.2).
 *
 * @param promote 승급 경계 파티션 ({@code boundary_set = 'PROMOTE'})
 * @param demote 강등 경계 파티션 ({@code boundary_set = 'DEMOTE'})
 */
public record HorizonBands(BandPartition promote, BandPartition demote) {

    public HorizonBands {
        Objects.requireNonNull(promote, "promote must not be null");
        Objects.requireNonNull(demote, "demote must not be null");
    }
}
