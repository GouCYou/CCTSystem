package cn.cctstudio.cctsystem.bridge;

public final class RpcHandlingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;

    public RpcHandlingException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
