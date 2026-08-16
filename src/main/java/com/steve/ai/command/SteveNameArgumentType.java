package com.steve.ai.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Brigadier argument type for Steve names. Reads a single whitespace-free
 * token and accepts letters (any script, including Cyrillic), digits and the
 * extra characters {@code _ - . +}. Everything else is rejected, so names
 * like "Васян", "Steve_1" or "Майнер-2" work while quoted or multi-word
 * strings do not.
 */
public class SteveNameArgumentType implements ArgumentType<String> {

    private static final String ALLOWED_EXTRA = "_-.+";

    private static final DynamicCommandExceptionType INVALID_NAME = new DynamicCommandExceptionType(
        value -> Component.literal("Invalid Steve name: '" + value + "'"));

    SteveNameArgumentType() {
    }

    public static SteveNameArgumentType steveName() {
        return new SteveNameArgumentType();
    }

    public static String getName(CommandContext<CommandSourceStack> context, String name) {
        return context.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        String value = reader.getString().substring(start, reader.getCursor());
        if (value.isEmpty()) {
            throw INVALID_NAME.createWithContext(reader, value);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && ALLOWED_EXTRA.indexOf(c) < 0) {
                throw INVALID_NAME.createWithContext(reader, value);
            }
        }
        return value;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        SteveManager manager = SteveMod.getSteveManager();
        if (manager == null) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(manager.getSteveNames(), builder);
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("Васян", "Steve_1", "Майнер-2");
    }
}