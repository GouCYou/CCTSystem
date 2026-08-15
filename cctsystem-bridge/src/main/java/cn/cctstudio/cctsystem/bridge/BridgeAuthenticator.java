package cn.cctstudio.cctsystem.bridge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class BridgeAuthenticator {
    private static final String ALGORITHM = "HmacSHA256";

    private BridgeAuthenticator() {
    }

    public static String canonicalRequest(
        String path,
        String networkId,
        String serverId,
        String nodeId,
        long timestamp,
        String nonce
    ) {
        return String.join(
            "\n",
            "GET",
            path,
            networkId,
            serverId,
            nodeId,
            Long.toString(timestamp),
            nonce
        );
    }

    public static String sign(String secret, String canonicalRequest) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign bridge request", exception);
        }
    }
}
