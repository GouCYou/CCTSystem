package cn.cctstudio.cctsystem.exchange;

public enum ExchangeStatus {
    CREATED,
    RESERVED,
    MONEY_DEBIT_REQUESTED,
    MONEY_DEBITED,
    POINTS_CREDIT_REQUESTED,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_PENDING,
    REVIEW_REQUIRED
}
