package cn.cctstudio.cctsystem.redeem;

import java.util.List;
import java.util.UUID;

public record GeneratedRedeemBatch(UUID batchId, List<String> codes) {
    public GeneratedRedeemBatch {
        codes = List.copyOf(codes);
    }
}
