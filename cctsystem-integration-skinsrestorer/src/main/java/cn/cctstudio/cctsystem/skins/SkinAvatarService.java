package cn.cctstudio.cctsystem.skins;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface SkinAvatarService {
    CompletionStage<SkinAvatar> avatar(UUID playerUuid, String playerName);
}
