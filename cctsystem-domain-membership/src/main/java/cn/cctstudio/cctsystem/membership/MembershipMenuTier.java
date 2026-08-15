package cn.cctstudio.cctsystem.membership;

public record MembershipMenuTier(
    MembershipTier tier,
    MembershipQuote quote,
    String unavailableCode
) {
    public boolean available() {
        return quote != null && unavailableCode == null;
    }
}
