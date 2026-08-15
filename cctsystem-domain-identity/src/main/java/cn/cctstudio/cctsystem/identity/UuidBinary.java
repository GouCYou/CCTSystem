package cn.cctstudio.cctsystem.identity;

import java.nio.ByteBuffer;
import java.util.UUID;

public final class UuidBinary {
    private UuidBinary() {
    }

    public static byte[] encode(UUID value) {
        return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array();
    }

    public static UUID decode(byte[] value) {
        if (value == null || value.length != 16) {
            throw new IllegalArgumentException("UUID binary value must contain exactly 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
