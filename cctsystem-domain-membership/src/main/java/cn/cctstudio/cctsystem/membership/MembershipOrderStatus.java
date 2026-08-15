package cn.cctstudio.cctsystem.membership;

public enum MembershipOrderStatus {
    CREATED,
    RESERVED,
    POINTS_DEBIT_REQUESTED,
    POINTS_DEBITED,
    APPLYING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_PENDING,
    REVIEW_REQUIRED
}
