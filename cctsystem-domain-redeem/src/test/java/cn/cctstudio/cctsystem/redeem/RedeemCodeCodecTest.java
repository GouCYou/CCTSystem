package cn.cctstudio.cctsystem.redeem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

final class RedeemCodeCodecTest {
    @Test
    void normalizesDisplaySeparatorsAndHashesWithSecretPepper() {
        RedeemCodeCodec first = new RedeemCodeCodec("a".repeat(32));
        RedeemCodeCodec second = new RedeemCodeCodec("b".repeat(32));

        assertArrayEquals(first.hash("ABCD-EFGH-JKMN"), first.hash("abcd efgh jkmn"));
        assertNotEquals(
            java.util.HexFormat.of().formatHex(first.hash("ABCD-EFGH-JKMN")),
            java.util.HexFormat.of().formatHex(second.hash("ABCD-EFGH-JKMN"))
        );
    }

    @Test
    void generatedCodesExcludeAmbiguousCharacters() {
        RedeemCodeCodec codec = new RedeemCodeCodec("secret".repeat(8));
        for (int index = 0; index < 100; index++) {
            String code = codec.generate(12);
            assertFalse(code.matches(".*[01IO].*"));
        }
    }
}
