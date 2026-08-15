package com.steve.ai.command;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionExecutor;
import com.steve.ai.chat.ChatCommandParser;
import com.steve.ai.chat.NameMatcher;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Dispatches a natural-language command to Steves, shared by chat (panel K)
 * and voice commands.
 *
 * <p>Addressing rules (in order):
 * <ol>
 *   <li>command starts with a bot name ("alex ...", "Алекс ...") - the name is
 *       matched via {@link NameMatcher} (transliteration/dictionary aware) and
 *       stripped from the command;</li>
 *   <li>command is an all-command ("all ...", "все ...") - every Steve;</li>
 *   <li>otherwise - the Steve nearest to the speaker.</li>
 * </ol>
 */
public final class CommandDispatcher {

    private CommandDispatcher() {}

    /** Returns how many Steves received the command. */
    public static int dispatch(ServerPlayer speaker, String command) {
        SteveManager manager = SteveMod.getSteveManager();
        List<String> names = manager.getSteveNames();
        if (names.isEmpty()) {
            speaker.sendSystemMessage(Component.literal("§cNo Steves spawned. Use /steve spawn <name>"));
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
            SteveEntity steve = manager.getSteve(matched);
            if (steve != null) {
                String rest = trimmed.substring(firstWord.length()).trim();
                deliver(steve, rest.isEmpty() ? trimmed : rest, speaker);
                return 1;
            }
        }

        // 2. all-command
        if (ChatCommandParser.isAllCommand(lower)) {
            int count = 0;
            for (String name : names) {
                SteveEntity steve = manager.getSteve(name);
                if (steve != null) {
                    deliver(steve, trimmed, speaker);
                    count++;
                }
            }
            speaker.sendSystemMessage(Component.literal("§7Command sent to " + count + " Steve(s)"));
            return count;
        }

        // 3. nearest Steve to the speaker
        SteveEntity nearest = nearestSteve(speaker, manager);
        if (nearest != null) {
            deliver(nearest, trimmed, speaker);
            return 1;
        }
        return 0;
    }

    /**
     * Delivers a chat command to one Steve. Stay/stop commands are handled
     * deterministically (no LLM round-trip): the current action is cancelled,
     * navigation stops, and the Steve stays in place until the next command.
     */
    private static void deliver(SteveEntity steve, String command, ServerPlayer speaker) {
        String lower = ChatCommandParser.normalize(command);
        if (ChatCommandParser.isStayCommand(lower)) {
            ActionExecutor executor = steve.getActionExecutor();
            executor.stopCurrentAction();
            executor.setStaying(true);
            steve.getNavigation().stop();
            steve.getMemory().clearTaskQueue();
            speaker.sendSystemMessage(Component.literal("§7" + steve.getSteveName() + " stopped"));
            return;
        }

        new Thread(() -> {
            steve.getActionExecutor().processNaturalLanguageCommand(command);
        }, "steve-command-" + steve.getSteveName()).start();
    }

    private static SteveEntity nearestSteve(ServerPlayer speaker, SteveManager manager) {
        SteveEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (SteveEntity steve : manager.getAllSteves()) {
            double dist = steve.distanceToSqr(speaker);
            if (dist < best) {
                best = dist;
                nearest = steve;
            }
        }
        return nearest;
    }
}
