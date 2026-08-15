package cn.cctstudio.cctsystem.points;

import java.util.List;

public record PointsLedgerPage(
    List<PointsLedgerEntry> items,
    int page,
    int pageSize,
    long totalItems,
    int totalPages
) {
    public PointsLedgerPage {
        items = List.copyOf(items);
    }
}
