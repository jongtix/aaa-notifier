package com.aaa.notifier.filter;

/**
 * 가격 1건의 파티션 조회 결과.
 *
 * @param grade 가격이 속한 파티션의 등급
 * @param clamped 가격이 그리드 범위 밖이라 최외곽 밴드로 클램프했는지 여부 (REQ-014)
 */
public record BandLookup(Grade grade, boolean clamped) {}
