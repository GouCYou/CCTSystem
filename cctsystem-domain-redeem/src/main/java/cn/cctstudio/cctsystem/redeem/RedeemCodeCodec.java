package cn.cctstudio.cctsystem.redeem;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class RedeemCodeCodec {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    RedeemCodeCodec(String pepper) {
        if (pepper == null || pepper.length() < 32) {
            throw new IllegalStateException(
                "redeem-code.pepper must be a secret of at least 32 characters"
            );
        }
        key = pepper.getBytes(StandardCharsets.UTF_8);
    }

    String generate(int length) {
        StringBuilder raw = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            raw.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        StringBuilder display = new StringBuilder(length + (length - 1) / 4);
        for (int index = 0; index < raw.length(); index++) {
            if (index > 0 && index % 4 == 0) {
                display.append('-');
            }
            display.append(raw.charAt(index));
        }
        return display.toString();
    }

    byte[] hash(String code) {
        String normalized = normalize(code);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(normalized.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    static String normalize(String code) {
        if (code == null) {
            throw new RedeemException("REDEEM_CODE_INVALID", "Invalid redeem code", false);
        }
        String normalized = code.replace("-", "").replace(" ", "")
            .trim().toUpperCase(Locale.ROOT);
        if (normalized.length() < 8 || normalized.length() > 32) {
            throw new RedeemException("REDEEM_CODE_INVALID", "Invalid redeem code", false);
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (new String(ALPHABET).indexOf(normalized.charAt(index)) < 0) {
                throw new RedeemException("REDEEM_CODE_INVALID", "Invalid redeem code", false);
            }
        }
        return normalized;
    }

    static String fingerprint(byte[] hash) {
        return HexFormat.of().formatHex(hash, 0, 6);
    }
}
