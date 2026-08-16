package com.steve.ai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionExecutor;
import com.steve.ai.chat.ChatCommandParser;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.debug.AgentDebugBuffer;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveInventory;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.llm.LLMProviders;
import com.steve.ai.llm.async.OpenAICompatibleClient;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class SteveCommands {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("steve")
            .then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::spawnSteve)))
            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::removeSteve)))
            .then(Commands.literal("list")
                .executes(SteveCommands::listSteves))
            .then(Commands.literal("stop")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::stopSteve)))
            .then(Commands.literal("tell")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(SteveCommands::tellSteve))))
            .then(Commands.literal("providers")
                .executes(SteveCommands::listProviders))
            .then(Commands.literal("debug")
                .executes(SteveCommands::debugSteve))
            .then(Commands.literal("inventory")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::showInventory)))
            .then(Commands.literal("tp")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::tpSteve)))
        );
    }

    /**
     * /steve tp <name|all> - instantly teleports the named Steve (or all
     * Steves) to a safe spot near the commanding player.
     */
    private static int tpSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command must be run by a player"));
            return 0;
        }

        SteveManager manager = SteveMod.getSteveManager();
        AgentDebugBuffer.log(name, "COMMAND", "tp to " + player.getName().getString());

        if ("all".equalsIgnoreCase(name)) {
            List<String> names = manager.getSteveNames();
            if (names.isEmpty()) {
                source.sendFailure(Component.literal("§cNo Steves spawned. Use /steve spawn <name>"));
                return 0;
            }
            int teleported = 0;
            int wrongDimension = 0;
            int noSpot = 0;
            for (String steveName : names) {
                SteveEntity steve = manager.getSteve(steveName);
                if (steve == null) {
                    continue;
                }
                if (steve.level().dimension() != player.level().dimension()) {
                    wrongDimension++;
                } else if (steve.teleportToPlayer(player)) {
                    teleported++;
                } else {
                    noSpot++;
                }
            }
            if (teleported == 0) {
                String failure = "§cNo Steve teleported"
                    + (wrongDimension > 0 ? " (" + wrongDimension + " in another dimension" : "")
                    + (wrongDimension > 0 && noSpot > 0 ? ", " : "")
                    + (noSpot > 0 ? noSpot + " no safe spot" : "")
                    + (wrongDimension > 0 || noSpot > 0 ? ")" : "");
                source.sendFailure(Component.literal(failure));
                return 0;
            }
            String result = "§aTeleported " + teleported + "/" + names.size() + " Steve(s) to you";
            source.sendSuccess(() -> Component.literal(result), false);
            return 1;
        }

        SteveEntity steve = manager.getSteve(name);
        if (steve == null) {
            source.sendFailure(Component.literal("§cSteve not found: " + name));
            return 0;
        }
        if (steve.level().dimension() != player.level().dimension()) {
            source.sendFailure(Component.literal("§c" + name + " is in another dimension"));
            return 0;
        }
        if (!steve.teleportToPlayer(player)) {
            source.sendFailure(Component.literal("§cNo safe spot near you for " + name));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§a" + name + " teleported to you"), false);
        return 1;
    }

    private static int showInventory(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();

        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }

        SteveInventory inventory = steve.getInventory();
        source.sendSuccess(() -> Component.literal(
            "§e" + name + "'s inventory§7 (" + inventory.getStacksCount() + "/" + inventory.getMaxSize()
                + " stacks, " + inventory.getTotalCount() + " items): " + inventory.toDisplayString()),
            false);
        return 1;
    }

    private static int debugSteve(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveManager manager = SteveMod.getSteveManager();

        String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
        String base = LLMProviders.resolveBaseUrl(provider, SteveConfig.LLM_BASE_URL.get());
        String model = SteveConfig.LLM_MODEL.get();
        if (model == null || model.isEmpty()) {
            model = LLMProviders.resolveModel(provider, "");
        }
        String key = SteveConfig.LLM_API_KEY.get();
        boolean keyPresent = key != null && !key.isEmpty();
        boolean jsonMode = SteveConfig.LLM_JSON_MODE.get();
        String llmLine = "§eLLM: §f" + provider + "§7 (" + base + ") model=" + model
            + " key=" + (keyPresent ? "§aset" : "§cmissing")
            + " jsonMode=" + jsonMode;

        source.sendSuccess(() -> Component.literal(llmLine), false);

        // Provider health (async, 3s timeout)
        String providerId = provider;
        String baseUrl = base;
        String apiKey = SteveConfig.LLM_API_KEY.get();
        String modelOverride = SteveConfig.LLM_MODEL.get();
        new Thread(() -> {
            try {
                OpenAICompatibleClient client = OpenAICompatibleClient.forProvider(
                    providerId, baseUrl, apiKey, modelOverride,
                    SteveConfig.MAX_TOKENS.get(), SteveConfig.TEMPERATURE.get(),
                    SteveConfig.LLM_JSON_MODE.get(), SteveConfig.LLM_TIMEOUT_SECONDS.get());
                source.sendSuccess(() -> Component.literal(
                    "§eHealth: §f" + (client.checkHealth() ? "§aONLINE" : "§cUNREACHABLE")
                        + "§7 (GET " + baseUrl + "/models)"), false);
            } catch (Exception e) {
                source.sendSuccess(() -> Component.literal("§cHealth check error: " + e.getMessage()), false);
            }
        }, "steve-health-check").start();

        // Per-Steve state
        var steves = manager.getAllSteves();
        if (steves.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7No Steves spawned. Use /steve spawn <name>"), false);
        } else {
            for (SteveEntity steve : steves) {
                source.sendSuccess(() -> Component.literal(
                    "§e" + steve.getSteveName() + "§7: " + steve.getActionExecutor().getStateSummary()), false);
            }
        }

        // Recent debug events
        List<String> events = AgentDebugBuffer.getEvents(20);
        source.sendSuccess(() -> Component.literal("§eRecent events (" + events.size() + "):"), false);
        for (String event : events) {
            source.sendSuccess(() -> Component.literal("§7 " + event), false);
        }

        return 1;
    }

    private static int listProviders(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        String activeProvider = SteveConfig.AI_PROVIDER.get().toLowerCase();
        String activeBase = LLMProviders.resolveBaseUrl(activeProvider, SteveConfig.LLM_BASE_URL.get());
        String activeModel = SteveConfig.LLM_MODEL.get();
        if (activeModel == null || activeModel.isEmpty()) {
            activeModel = LLMProviders.resolveModel(activeProvider, "");
        }
        String activeKey = SteveConfig.LLM_API_KEY.get();
        boolean keyPresent = activeKey != null && !activeKey.isEmpty();
        String modelLine = "§eModel: §f" + activeModel + "§7 | key: " + (keyPresent ? "§aset" : "§cmissing");

        source.sendSuccess(() -> Component.literal(
            "§eActive provider: §f" + activeProvider + "§7 (" + activeBase + ")"), false);
        source.sendSuccess(() -> Component.literal(modelLine), false);

        // Live health check of the active provider (GET /models, 3s timeout)
        String providerId = activeProvider;
        String baseUrl = activeBase;
        String apiKey = SteveConfig.LLM_API_KEY.get();
        String modelOverride = SteveConfig.LLM_MODEL.get();
        new Thread(() -> {
            try {
                OpenAICompatibleClient client = OpenAICompatibleClient.forProvider(
                    providerId, baseUrl, apiKey, modelOverride,
                    SteveConfig.MAX_TOKENS.get(), SteveConfig.TEMPERATURE.get(),
                    SteveConfig.LLM_JSON_MODE.get(), SteveConfig.LLM_TIMEOUT_SECONDS.get());
                boolean healthy = client.checkHealth();
                source.sendSuccess(() -> Component.literal(
                    "§eHealth: " + (healthy ? "§aONLINE" : "§cUNREACHABLE") + " §7(GET " + baseUrl + "/models)"),
                    false);
            } catch (Exception e) {
                source.sendSuccess(() -> Component.literal("§cHealth check error: " + e.getMessage()), false);
            }
        }, "steve-health-check").start();

        // List all known providers
        source.sendSuccess(() -> Component.literal("§eAvailable providers:"), false);
        source.sendSuccess(() -> Component.literal(
            "§7 openai, groq, gemini, ollama, lmstudio, opencode-go, custom"), false);
        source.sendSuccess(() -> Component.literal(
            "§7 Set llm.provider in config/steve-common.toml to switch"), false);

        return 1;
    }

    private static int spawnSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        ServerLevel serverLevel = source.getLevel();
        if (serverLevel == null) {
            source.sendFailure(Component.literal("Command must be run on server"));
            return 0;
        }

        SteveManager manager = SteveMod.getSteveManager();
        
        Vec3 sourcePos = source.getPosition();
        if (source.getEntity() != null) {
            Vec3 lookVec = source.getEntity().getLookAngle();
            sourcePos = sourcePos.add(lookVec.x * 3, 0, lookVec.z * 3);
        } else {
            sourcePos = sourcePos.add(3, 0, 0);
        }
        Vec3 spawnPos = sourcePos;
        
        SteveEntity steve = manager.spawnSteve(serverLevel, spawnPos, name);
        if (steve != null) {
            source.sendSuccess(() -> Component.literal("Spawned Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to spawn Steve. Name may already exist or max limit reached."));
            return 0;
        }
    }

    private static int removeSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        if (manager.removeSteve(name, source.getServer())) {
            source.sendSuccess(() -> Component.literal("Removed Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    private static int listSteves(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveManager manager = SteveMod.getSteveManager();
        
        var names = manager.getSteveNames();
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active Steves"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Active Steves (" + names.size() + "): " + String.join(", ", names)), false);
        }
        return 1;
    }

    private static int stopSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity steve = manager.getSteve(name);
        
        if (steve != null) {
            steve.getActionExecutor().stopCurrentAction();
            steve.getActionExecutor().setStaying(true);
            steve.getMemory().clearTaskQueue();
            source.sendSuccess(() -> Component.literal("Stopped Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    private static int tellSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String command = StringArgumentType.getString(context, "command");
        CommandSourceStack source = context.getSource();

        // Single dispatch path (name matching, "all", nearest, stay-trigger)
        // shared with voice commands - see SteveCommandDispatcher.
        return SteveCommandDispatcher.dispatch(source, name + " " + command);
    }
}

