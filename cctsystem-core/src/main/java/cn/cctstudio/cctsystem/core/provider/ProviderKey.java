package cn.cctstudio.cctsystem.core.provider;

import java.util.Objects;

public record ProviderKey<T>(String id, Class<T> type) {
    public ProviderKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }

    public static <T> ProviderKey<T> of(String id, Class<T> type) {
        return new ProviderKey<>(id, type);
    }
}
