package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.bridge.BridgeCallException;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class MembershipFailures {
    private static final Set<String> TRANSPORT_CODES = Set.of(
        "BRIDGE_TIMEOUT", "BRIDGE_UNAVAILABLE", "BRIDGE_DISCONNECTED", "REQUEST_EXPIRED");

    private MembershipFailures() { }

    public static boolean temporaryTransport(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MembershipException membership) {
            return membership.retryable() && TRANSPORT_CODES.contains(membership.code());
        }
        return cause instanceof BridgeCallException bridge
            && bridge.retryable() && TRANSPORT_CODES.contains(bridge.code());
    }

    public static void log(CctLogger logger, String context, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (temporaryTransport(cause)) {
            String code = cause instanceof MembershipException membership
                ? membership.code() : ((BridgeCallException) cause).code();
            logger.info(context + ": service temporarily unavailable (" + code + "): " + cause.getMessage());
        } else {
            logger.warn(context, cause);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
            && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
