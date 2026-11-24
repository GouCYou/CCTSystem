package cn.cctstudio.cctsystem.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PremiumLoginProof {
    public static final String PROPERTY = "cct_java_session";
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final long LIFETIME_SECONDS = 600;
    private final SecretKeySpec key;
    private final String networkId;

    public PremiumLoginProof(byte[] key, String networkId) {
        this.key = new SecretKeySpec(key, "HmacSHA256");
        this.networkId = networkId;
    }

    public static boolean isJavaName(String name) {
        return JAVA_NAME.matcher(name).matches();
    }

    public static UUID offlineUuid(String exactName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + exactName).getBytes(StandardCharsets.UTF_8));
    }

    public String issue(String name, UUID mojangUuid, Instant now) {
        return issue(name, mojangUuid, now, 0L);
    }

    public String issue(String name, UUID mojangUuid, Instant now, long bindingRequestedAt) {
        String body = mojangUuid + "." + UUID.randomUUID() + "." + now.getEpochSecond();
        if (bindingRequestedAt > 0) body += "." + bindingRequestedAt;
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(name, body));
    }

    public Optional<Session> verify(String value, String name, UUID playerUuid, Instant now) {
        if (value.length() > 170 || !isJavaName(name) || !offlineUuid(name).equals(playerUuid)) {
            return Optional.empty();
        }
        String[] fields = value.split("\\.", -1);
        if (fields.length != 4 && fields.length != 5) return Optional.empty();
        try {
            long issuedAt = Long.parseLong(fields[2]);
            long seconds = now.getEpochSecond();
            if (issuedAt < seconds - LIFETIME_SECONDS || issuedAt > seconds + 10) return Optional.empty();
            String body = value.substring(0, value.lastIndexOf('.'));
            if (!MessageDigest.isEqual(sign(name, body), Base64.getUrlDecoder().decode(fields[fields.length - 1]))) {
                return Optional.empty();
            }
            long bindingRequestedAt = fields.length == 5 ? Long.parseLong(fields[3]) : 0L;
            if (fields.length == 5 && (bindingRequestedAt <= 0 || bindingRequestedAt > now.toEpochMilli() + 10_000)) {
                return Optional.empty();
            }
            return Optional.of(new Session(UUID.fromString(fields[0]), UUID.fromString(fields[1]), playerUuid, name, bindingRequestedAt));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private byte[] sign(String name, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            String message = "cct-java-login-v1\n" + networkId + "\n" + name + "\n" + body;
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to authenticate Java login proof", exception);
        }
    }

    public record Session(UUID mojangUuid, UUID sessionId, UUID playerUuid, String name, long bindingRequestedAt) {
    }
}
