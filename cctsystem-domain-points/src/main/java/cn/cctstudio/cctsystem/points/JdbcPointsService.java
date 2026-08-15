package cn.cctstudio.cctsystem.points;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class JdbcPointsService implements PointsService {
    private static final String INSERT_OPERATION = """
        INSERT INTO cct_point_operations(
            operation_id, player_uuid, delta_points, balance_before, status,
            source_type, source_ref
        ) VALUES (?, ?, ?, ?, 'REQUESTED', ?, ?)
        """;
    private static final String FIND_OPERATION = """
        SELECT status, balance_before, balance_after, error_code
        FROM cct_point_operations
        WHERE operation_id = ?
        """;
    private static final String FINISH_OPERATION = """
        UPDATE cct_point_operations
        SET status = ?, balance_after = ?, error_code = ?
        WHERE operation_id = ? AND status IN ('REQUESTED', 'REVIEW_REQUIRED')
        """;

    private final DatabaseAccess database;
    private final PointsGateway gateway;
    private final CctExecutors executors;

    JdbcPointsService(DatabaseAccess database, PointsGateway gateway, CctExecutors executors) {
        this.database = database;
        this.gateway = gateway;
        this.executors = executors;
    }

    @Override
    public CompletionStage<Integer> balance(UUID playerUuid) {
        return gateway.balance(playerUuid);
    }

    @Override
    public CompletionStage<PointsMutationResult> credit(
        UUID playerUuid,
        int points,
        UUID operationId,
        String sourceType,
        String sourceReference
    ) {
        return mutate(playerUuid, requirePositive(points), operationId, sourceType, sourceReference, true);
    }

    @Override
    public CompletionStage<PointsMutationResult> debit(
        UUID playerUuid,
        int points,
        UUID operationId,
        String sourceType,
        String sourceReference
    ) {
        return mutate(playerUuid, requirePositive(points), operationId, sourceType, sourceReference, false);
    }

    private CompletionStage<PointsMutationResult> mutate(
        UUID playerUuid,
        int points,
        UUID operationId,
        String sourceType,
        String sourceReference,
        boolean credit
    ) {
        validateReference(sourceType, sourceReference);
        return gateway.balance(playerUuid).handle((balance, throwable) -> throwable == null ? balance : null)
            .thenCompose(before -> begin(
                playerUuid,
                credit ? points : -points,
                operationId,
                before,
                sourceType,
                sourceReference
            ).thenCompose(existing -> existing.isPresent()
                ? CompletableFuture.completedFuture(existing.orElseThrow())
                : invokeGateway(playerUuid, points, operationId, before, credit)));
    }

    private CompletionStage<PointsMutationResult> invokeGateway(
        UUID playerUuid,
        int points,
        UUID operationId,
        Integer before,
        boolean credit
    ) {
        CompletableFuture<PointsMutationResult> result = new CompletableFuture<>();
        CompletionStage<Boolean> mutation = credit
            ? gateway.credit(playerUuid, points)
            : gateway.debit(playerUuid, points);
        mutation.whenComplete((applied, mutationFailure) -> {
            if (mutationFailure != null) {
                finish(operationId, "REVIEW_REQUIRED", null, "POINTS_RESULT_UNKNOWN")
                    .whenComplete((ignored, auditFailure) -> result.complete(new PointsMutationResult(
                        operationId,
                        PointsMutationDisposition.AMBIGUOUS,
                        before,
                        null,
                        "POINTS_RESULT_UNKNOWN"
                    )));
                return;
            }
            if (!Boolean.TRUE.equals(applied)) {
                finish(operationId, "FAILED", before, "POINTS_MUTATION_REJECTED")
                    .whenComplete((ignored, auditFailure) -> result.complete(new PointsMutationResult(
                        operationId,
                        PointsMutationDisposition.REJECTED,
                        before,
                        before,
                        "POINTS_MUTATION_REJECTED"
                    )));
                return;
            }
            gateway.balance(playerUuid).handle((after, balanceFailure) -> balanceFailure == null ? after : null)
                .thenCompose(after -> finish(operationId, "COMPLETED", after, null)
                    .handle((ignored, auditFailure) -> {
                        if (auditFailure != null) {
                            return new PointsMutationResult(
                                operationId,
                                PointsMutationDisposition.AMBIGUOUS,
                                before,
                                after,
                                "POINTS_AUDIT_WRITE_FAILED"
                            );
                        }
                        return new PointsMutationResult(
                            operationId,
                            PointsMutationDisposition.COMPLETED,
                            before,
                            after,
                            null
                        );
                    }))
                .whenComplete((completed, failure) -> {
                    if (failure == null) {
                        result.complete(completed);
                    } else {
                        result.complete(new PointsMutationResult(
                            operationId,
                            PointsMutationDisposition.AMBIGUOUS,
                            before,
                            null,
                            "POINTS_RESULT_UNKNOWN"
                        ));
                    }
                });
        });
        return result;
    }

    private CompletionStage<Optional<PointsMutationResult>> begin(
        UUID playerUuid,
        int delta,
        UUID operationId,
        Integer balanceBefore,
        String sourceType,
        String sourceReference
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement statement = connection.prepareStatement(INSERT_OPERATION)) {
                statement.setBytes(1, UuidBinary.encode(operationId));
                statement.setBytes(2, UuidBinary.encode(playerUuid));
                statement.setInt(3, delta);
                if (balanceBefore == null) {
                    statement.setNull(4, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(4, balanceBefore);
                }
                statement.setString(5, sourceType);
                statement.setString(6, sourceReference);
                statement.executeUpdate();
                return Optional.empty();
            } catch (SQLException exception) {
                if ("23000".equals(exception.getSQLState())) {
                    return Optional.of(readExisting(operationId));
                }
                throw new CompletionException("Unable to start point operation", exception);
            }
        }, executors.blocking());
    }

    private PointsMutationResult readExisting(UUID operationId) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_OPERATION)) {
            statement.setBytes(1, UuidBinary.encode(operationId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Point operation disappeared after duplicate key");
                }
                String status = result.getString("status");
                Integer before = nullableInt(result, "balance_before");
                Integer after = nullableInt(result, "balance_after");
                return new PointsMutationResult(
                    operationId,
                    switch (status) {
                        case "COMPLETED" -> PointsMutationDisposition.COMPLETED;
                        case "FAILED" -> PointsMutationDisposition.REJECTED;
                        default -> PointsMutationDisposition.AMBIGUOUS;
                    },
                    before,
                    after,
                    result.getString("error_code")
                );
            }
        } catch (SQLException exception) {
            throw new CompletionException("Unable to read point operation", exception);
        }
    }

    private CompletionStage<Void> finish(
        UUID operationId,
        String status,
        Integer balanceAfter,
        String errorCode
    ) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement statement = connection.prepareStatement(FINISH_OPERATION)) {
                statement.setString(1, status);
                if (balanceAfter == null) {
                    statement.setNull(2, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(2, balanceAfter);
                }
                statement.setString(3, errorCode);
                statement.setBytes(4, UuidBinary.encode(operationId));
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Point operation state could not be updated");
                }
            } catch (SQLException exception) {
                throw new CompletionException("Unable to finish point operation", exception);
            }
        }, executors.blocking());
    }

    private static Integer nullableInt(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static int requirePositive(int points) {
        if (points <= 0) {
            throw new IllegalArgumentException("points must be positive");
        }
        return points;
    }

    private static void validateReference(String sourceType, String sourceReference) {
        if (sourceType == null || sourceType.isBlank() || sourceType.length() > 32
            || sourceReference == null || sourceReference.isBlank() || sourceReference.length() > 80) {
            throw new IllegalArgumentException("Invalid point operation source");
        }
    }
}
