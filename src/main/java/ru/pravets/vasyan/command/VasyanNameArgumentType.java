package ru.pravets.vasyan.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.entity.VasyanManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Brigadier argument type for Steve names. Accepts a quoted or unquoted string
 * and validates it against the allowed character set: letters (any script,
 * including Cyrillic), digits and the extra characters {@code _ - . +}.
 * Quoting (as with {@code StringArgumentType.string()}) is honoured so that
 * names entered in quotes still parse, while multi-word or other invalid
 * names are rejected.
 */
public final class VasyanNameArgumentType implements ArgumentType<String> {

    private static final Pattern VALID_NAME = Pattern.compile("[\\p{L}\\p{N}_\\-.+]+");

    private static final DynamicCommandExceptionType INVALID_NAME = new DynamicCommandExceptionType(
        value -> Component.translatable("argument.vasyan.vasyan_name.invalid", value));

    VasyanNameArgumentType() {
    }

    public static VasyanNameArgumentType steveName() {
        return new VasyanNameArgumentType();
    }

    public static String getName(CommandContext<CommandSourceStack> context, String name) {
        return context.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        String value = readName(reader);
        if (value.isEmpty() || !VALID_NAME.matcher(value).matches()) {
            throw INVALID_NAME.createWithContext(reader, value);
        }
        return value;
    }

    /**
     * Reads a Brigadier string, honouring quoted strings exactly like
     * {@code StringArgumentType.string()} so names previously typed in quotes
     * keep working. Unquoted input is read as a single whitespace-free token;
     * the {@link #VALID_NAME} pattern then rejects anything outside the
     * allowed character set (any-script letters, digits, {@code _ - . +}).
     */
    private static String readName(StringReader reader) throws CommandSyntaxException {
        if (!reader.canRead()) {
            return "";
        }
        if (StringReader.isQuotedStringStart(reader.peek())) {
            return reader.readQuotedString();
        }
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        VasyanManager manager = VasyanMod.getSteveManager();
        if (manager == null) {
            return builder.buildFuture();
        }
        List<String> names = manager.getSteveNames();
        if (names == null || names.isEmpty()) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("Васян", "Steve_1", "Майнер-2");
    }
}