package cn.cctstudio.cctsystem.exchange;

import java.time.Instant;
import java.time.LocalDate;

record WeeklyUsage(
    LocalDate weekStart,
    int completedPoints,
    int reservedPoints,
    int limitPoints,
    Instant resetsAt
) {
    int remainingPoints() {
        return Math.max(0, limitPoints - completedPoints - reservedPoints);
    }
}
