package cn.cctstudio.cctsystem.core.provider;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ProviderRegistry {
    private final Map<ProviderKey<?>, Object> providers = new ConcurrentHashMap<>();

    public <T> void register(ProviderKey<T> key, T provider) {
        if (providers.putIfAbsent(key, key.type().cast(provider)) != null) {
            throw new IllegalStateException("Provider is already registered: " + key.id());
        }
    }

    public <T> Optional<T> find(ProviderKey<T> key) {
        return Optional.ofNullable(providers.get(key)).map(key.type()::cast);
    }

    public boolean containsId(String providerId) {
        return providers.keySet().stream().anyMatch(key -> key.id().equals(providerId));
    }
}
