package cn.cctstudio.cctsystem.platform.paper;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;

/** Prevents command suggestions from exposing real names and resolves nicknames back to targets. */
final class PaperAnonymousCommandAdapter implements Listener {
    private static final Set<String> TARGET_COMMANDS = Set.of(
        "tpa", "tpahere", "msg", "tell", "w", "whisper", "pm", "m", "pay",
        "trade", "duel", "ignore", "unignore", "seen", "invsee", "enderchest"
    );

    private final PaperNicknameService nicknames;

    PaperAnonymousCommandAdapter(PaperNicknameService nicknames) {
        this.nicknames = nicknames;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncComplete(AsyncTabCompleteEvent event) {
        if (!event.isCommand() || !(event.getSender() instanceof Player)) return;
        event.setCompletions(publicCompletions(event.getCompletions()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onComplete(TabCompleteEvent event) {
        if (!event.isCommand() || !(event.getSender() instanceof Player)) return;
        event.setCompletions(publicCompletions(event.getCompletions()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().split(" ", -1);
        if (parts.length < 2) return;
        String command = commandName(parts[0]);
        int targetIndex = 1;
        if ("cmi".equals(command)) {
            if (parts.length < 3 || !TARGET_COMMANDS.contains(commandName(parts[1]))) return;
            targetIndex = 2;
        } else if (!TARGET_COMMANDS.contains(command)) {
            return;
        }
        String target = nicknames.commandTarget(parts[targetIndex]);
        if (target.equals(parts[targetIndex])) return;
        parts[targetIndex] = target;
        event.setMessage(String.join(" ", parts));
    }

    private List<String> publicCompletions(List<String> completions) {
        List<String> result = new ArrayList<>(completions.size());
        Set<String> seen = new HashSet<>();
        for (String completion : completions) {
            String visible = nicknames.publicCompletion(completion);
            if (seen.add(visible.toLowerCase(Locale.ROOT))) result.add(visible);
        }
        return result;
    }

    private static String commandName(String value) {
        String command = value.startsWith("/") ? value.substring(1) : value;
        int namespace = command.indexOf(':');
        if (namespace >= 0) command = command.substring(namespace + 1);
        return command.toLowerCase(Locale.ROOT);
    }
}
