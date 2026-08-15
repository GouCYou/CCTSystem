package cn.cctstudio.cctsystem.redeem;

import java.util.Locale;

public record RedeemReward(
    RedeemRewardType type,
    int points,
    String tierKey,
    int days
) {
    public RedeemReward {
        if (type == null) {
            throw new IllegalArgumentException("reward type is required");
        }
        tierKey = tierKey == null ? null : tierKey.trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case POINTS -> {
                if (points < 1 || tierKey != null || days != 0) {
                    throw new IllegalArgumentException("invalid points reward");
                }
            }
            case MEMBERSHIP -> {
                if (points != 0 || tierKey == null || tierKey.isBlank() || tierKey.length() > 64
                    || days < 1 || days > 3650) {
                    throw new IllegalArgumentException("invalid membership reward");
                }
            }
        }
    }

    public static RedeemReward points(int points) {
        return new RedeemReward(RedeemRewardType.POINTS, points, null, 0);
    }

    public static RedeemReward membership(String tierKey, int days) {
        return new RedeemReward(RedeemRewardType.MEMBERSHIP, 0, tierKey, days);
    }
}
