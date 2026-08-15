package cn.cctstudio.cctsystem.contract;

import java.util.Objects;

public record RpcError(String code, String message, boolean retryable) {
    public RpcError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
