package cn.cctstudio.cctsystem.redeem;

import java.util.concurrent.CompletionStage;

public interface RedeemCodeService {
    CompletionStage<GeneratedRedeemBatch> generate(GenerateRedeemCodesRequest request);

    CompletionStage<RedeemResult> redeem(RedeemRequest request);
}
