package cn.cctstudio.cctsystem.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidBinaryTest {
    @Test
    void roundTripsUuidWithoutStringConversion() {
        UUID uuid = UUID.fromString("df448d73-4c96-4b0a-888b-7529a88cfbb8");
        assertEquals(uuid, UuidBinary.decode(UuidBinary.encode(uuid)));
    }

    @Test
    void rejectsInvalidBinaryLength() {
        assertThrows(IllegalArgumentException.class, () -> UuidBinary.decode(new byte[15]));
    }
}
