package com.aaa.notifier.stream;

/**
 * 페이로드가 구조적으로 해석 불가함을 나타내는 예외 (REQ-NOTIFIER-CONSUMER-021 ②).
 *
 * <p>미지의 {@code trId}, 레코드 필드 수로 나누어떨어지지 않는 {@code data}, 필수 필드 누락, 숫자/시각 변환 실패가 여기에 해당한다. 재시도해도
 * 유효해지지 않으므로 재전달 임계를 기다리지 않고 <b>즉시</b> DLQ로 이관한다.
 *
 * <p><b>해외 체결가 {@code ZDIV} 자리수 검증 실패는 이 예외의 대상이 아니다</b>(REQ-034 후단). 그 경우 필드 수·{@code ^} 분할·타입 변환이
 * 전부 정상이고 자리수만 어긋난 상태라, DLQ로 이관하면 이관 사유가 사실과 다르게 표기된다 — WARN 로그를 남기고 원문 그대로 전달을 계속한다.
 */
public class PayloadUnparseableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 해석 불가 사유 (DLQ 진단에 쓰이므로 구체적으로 적는다)
     */
    public PayloadUnparseableException(String message) {
        super(message);
    }

    /**
     * @param message 해석 불가 사유
     * @param cause 원인 예외 ({@code NumberFormatException}, {@code DateTimeParseException} 등)
     */
    public PayloadUnparseableException(String message, Throwable cause) {
        super(message, cause);
    }
}
