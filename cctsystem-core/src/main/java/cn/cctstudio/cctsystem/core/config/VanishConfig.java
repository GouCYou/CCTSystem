package cn.cctstudio.cctsystem.core.config;

/** CMI-compatible defaults for CCTSystem's persistent network vanish. */
public record VanishConfig(
    boolean damageToEntity,
    boolean playerDamage,
    boolean itemPickup,
    boolean mobAggro,
    boolean interaction,
    boolean noisyChest,
    boolean informOnLeave,
    boolean informOnJoin,
    boolean nightVision,
    boolean bossbar,
    boolean afkCommands,
    boolean privateMessages,
    boolean relogDisable,
    boolean noMessages,
    boolean fakeJoinLeave,
    boolean mobSpawning,
    boolean stopPlaytime,
    boolean sleepIgnore,
    boolean joinVanished,
    boolean deathMessages,
    boolean hookPlayers,
    boolean blockBreak,
    boolean blockPlace,
    boolean containerInteraction,
    boolean enderChestInteraction,
    boolean workstationInteraction,
    boolean entityInteraction,
    boolean physicalInteraction
) {
    public static VanishConfig cmiDefaults() {
        return new VanishConfig(
            false, false, false, false, false, false, false, false,
            true, true, false, false, false, false, false, false,
            true, true, false, false, false,
            true, true, true, true, true, false, false
        );
    }
}
