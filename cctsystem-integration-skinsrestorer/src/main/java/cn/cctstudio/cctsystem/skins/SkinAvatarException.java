package cn.cctstudio.cctsystem.skins;

public final class SkinAvatarException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final boolean retryable;

    SkinAvatarException(String code, String message, boolean retryable) {
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
