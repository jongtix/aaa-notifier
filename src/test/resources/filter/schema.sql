-- FILTER-001 통합 테스트 전용 스키마.
-- notifier는 DDL을 소유하지 않는다(ADR-016) — 운영 스키마는 aaa-collector Flyway가 만든다. 이 파일은 그 마이그레이션에서
-- notifier가 읽고 쓰는 테이블·컬럼만 옮긴 테스트 픽스처다(V1/V2/V17/V37/V44/V49 기준, 2026-09-29 대조).
CREATE TABLE stocks
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol               VARCHAR(16) NOT NULL,
    market               VARCHAR(10) NOT NULL,
    asset_type           VARCHAR(10) NOT NULL,
    active               BOOLEAN     NOT NULL DEFAULT TRUE,
    watchlist_removed_at DATETIME    NULL,
    delisted_at          DATE        NULL,
    created_at           DATETIME    NOT NULL,
    updated_at           DATETIME    NOT NULL,
    UNIQUE KEY uk_stocks_symbol_market (symbol, market)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE daily_ohlcv
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id      BIGINT         NOT NULL,
    trade_date    DATE           NOT NULL,
    open_price    DECIMAL(18, 4) NOT NULL,
    high_price    DECIMAL(18, 4) NOT NULL,
    low_price     DECIMAL(18, 4) NOT NULL,
    close_price   DECIMAL(18, 4) NOT NULL,
    volume        BIGINT         NOT NULL DEFAULT 0,
    trading_value BIGINT         NOT NULL DEFAULT 0,
    created_at    DATETIME       NOT NULL,
    updated_at    DATETIME       NOT NULL,
    CONSTRAINT fk_daily_ohlcv_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    UNIQUE KEY uk_daily_ohlcv (stock_id, trade_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE signal_price_bands
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id          BIGINT         NOT NULL,
    trade_date        DATE           NOT NULL,
    horizon           VARCHAR(3)     NOT NULL,
    boundary_set      VARCHAR(7)     NOT NULL,
    band_seq          INT            NOT NULL,
    price_low         DECIMAL(18, 4) NOT NULL,
    price_high        DECIMAL(18, 4) NOT NULL,
    signal_class      VARCHAR(11)    NOT NULL,
    regime_suppressed BOOLEAN        NOT NULL DEFAULT FALSE,
    model_version     VARCHAR(64)    NOT NULL,
    created_at        DATETIME       NOT NULL,
    UNIQUE KEY uk_signal_price_bands (stock_id, trade_date, horizon, boundary_set, band_seq),
    CONSTRAINT fk_signal_price_bands_stock FOREIGN KEY (stock_id) REFERENCES stocks (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- V49 그대로 (COMMENT 제외) — DRYRUN INSERT 대상
CREATE TABLE notification_log
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type          VARCHAR(16)   NOT NULL,
    notification_type   VARCHAR(16)   NOT NULL,
    stock_id            BIGINT        NULL,
    horizon             VARCHAR(3)    NULL,
    tier                TINYINT       NULL,
    signal_class        VARCHAR(11)   NULL,
    score               DECIMAL(8, 6) NULL,
    confidence          DECIMAL(4, 3) NULL,
    trigger_price       DECIMAL(18, 4) NULL,
    prev_close          DECIMAL(18, 4) NULL,
    message_text        TEXT          NULL,
    telegram_message_id BIGINT        NULL,
    trace_id            VARCHAR(64)   NOT NULL,
    trade_date          DATE          NULL,
    created_at          DATETIME      NOT NULL,
    CONSTRAINT fk_notification_log_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    KEY idx_notification_log_stock_trade_date (stock_id, trade_date),
    KEY idx_notification_log_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
