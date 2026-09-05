package cn.cctstudio.cctsystem.platform.paper;

final class PracticeVisibilityPolicy {
    private PracticeVisibilityPolicy() { }

    static boolean sameFight(Object viewerFight, Object targetFight) {
        return targetFight != null && viewerFight == targetFight;
    }
}
