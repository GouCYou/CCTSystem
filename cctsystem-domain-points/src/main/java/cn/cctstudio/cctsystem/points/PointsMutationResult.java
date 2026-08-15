package cn.cctstudio.cctsystem.points;

import java.util.UUID;

public record PointsMutationResult(
    UUID operationId,
    PointsMutationDisposition disposition,
    Integer balanceBefore,
    Integer balanceAfter,
    String errorCode
) {
}
