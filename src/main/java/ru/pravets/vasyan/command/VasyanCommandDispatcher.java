package ru.pravets.vasyan.command;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.chat.ChatCommandParser;
import ru.pravets.vasyan.chat.NameMatcher;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dispatches a natural-language command to Steves, shared by the /steve tell
 * command (panel K) and voice commands.
 *
 * <p>Addressing rules (in order):
 * <ol>
 *   <li>command starts with a bot name ("alex ...", "Алекс ...") - the name is
 *       matched via {@link NameMatcher} (transliteration/dictionary aware) and
 *       stripped from the command;</li>
 *   <li>command is an all-command ("all ...", "все ...") - every Steve;</li>
 *   <li>otherwise - the Steve nearest to the speaker (same dimension only).</li>
 * </ol>
 */
public final class VasyanCommandDispatcher {

    /** Shared executor for LLM command processing (bounded, daemon). */
    private static final ExecutorService COMMAND_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "vasyan-command");
        t.setDaemon(true);
        return t;
    });

    private VasyanCommandDispatcher() {}

    /** Returns how many Steves received the command. */
    public static int dispatch(CommandSourceStack source, String command) {
        VasyanManager manager = VasyanMod.getSteveManager();
        List<String> names = manager.getSteveNames();
        if (names.isEmpty()) {
            source.sendFailure(Component.literal("§cNo Steves spawned. Use /steve spawn <name>"));
            return 0;
        }

        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        String lower = ChatCommandParser.normalize(trimmed);

        // 1. bot name prefix ("alex ...", "алекс ...")
        String firstWord = trimmed.split("\\s+", 2)[0];
        String matched = NameMatcher.matchName(firstWord, names);
        if (matched != null) {
            VasyanEntity steve = manager.getSteve(matched);
            if (steve != null) {
                String rest = trimmed.substring(firstWord.length()).trim();
                deliver(steve, rest.isEmpty() ? trimmed : rest, source);
                return 1;
            }
        }

        // 2. all-command
        if (ChatCommandParser.isAllCommand(lower)) {
            int count = 0;
            for (String name : names) {
                VasyanEntity steve = manager.getSteve(name);
                if (steve != null) {
                    deliver(steve, trimmed, source);
                    count++;
                }
            }
            final int sent = count;
            source.sendSuccess(() -> Component.literal("§7Command sent to " + sent + " Steve(s)"), false);
            return count;
        }

        // 3. nearest Steve to the speaker (same dimension)
        VasyanEntity nearest = nearestSteve(source, manager);
        if (nearest != null) {
            deliver(nearest, trimmed, source);
            return 1;
        }
        return 0;
    }

    /**
     * Delivers a chat command to one Steve. Stay/stop commands are handled
     * deterministically (no LLM round-trip): the current action is cancelled,
     * navigation stops, and the Steve stays in place until the next command.
     */
    private static void deliver(VasyanEntity steve, String command, CommandSourceStack source) {
        String lower = ChatCommandParser.normalize(command);
        if (ChatCommandParser.isStayCommand(lower)) {
            ActionExecutor executor = steve.getActionExecutor();
            executor.stopCurrentAction();
            executor.setStaying(true);
            steve.getNavigation().stop();
            steve.getMemory().clearTaskQueue();
            source.sendSuccess(() -> Component.literal("§7" + steve.getSteveName() + " stopped"), false);
            return;
        }

        COMMAND_EXECUTOR.execute(() -> {
            try {
                steve.getActionExecutor().processNaturalLanguageCommand(command);
            } catch (Exception e) {
                VasyanMod.LOGGER.warn("Command processing failed for {}: {}", steve.getSteveName(), e.toString());
            }
        });
    }

    private static VasyanEntity nearestSteve(CommandSourceStack source, VasyanManager manager) {
        if (!(source.getEntity() instanceof ServerPlayer speaker)) {
            return null; // console: no nearest Steve
        }
        VasyanEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (VasyanEntity steve : manager.getAllSteves()) {
            if (!steve.level().dimension().equals(speaker.level().dimension())) {
                continue; // cross-dimension bots are never "nearest"
            }
            double dist = steve.distanceToSqr(speaker);
            if (dist < best) {
                best = dist;
                nearest = steve;
            }
        }
        return nearest;
    }
}
