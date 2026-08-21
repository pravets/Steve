package ru.pravets.vasyan.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies that all /steve commands accept Cyrillic Steve names. The default
 * Brigadier string argument is ASCII-only for unquoted tokens; the custom
 * VasyanNameArgumentType must accept letters of any script.
 */
class SteveNameArgumentTypeTest {

    private static final CommandSourceStack SOURCE = mock(CommandSourceStack.class);

    private static CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void setUp() {
        dispatcher = new CommandDispatcher<>();
        VasyanCommands.register(dispatcher);
    }

    private static void assertParses(String command) {
        ParseResults<CommandSourceStack> results = dispatcher.parse(command, SOURCE);
        assertNotNull(results.getContext().getCommand(),
            "Command should parse successfully: " + command);
        assertFalse(results.getReader().canRead(),
            "Command should consume all input: " + command);
    }

    private static void assertRejected(String command) {
        ParseResults<CommandSourceStack> results = dispatcher.parse(command, SOURCE);
        assertTrue(results.getReader().canRead()
                || !results.getExceptions().isEmpty()
                || results.getContext().getCommand() == null,
            "Command should fail to parse: " + command);
    }

    @Test
    void acceptsCyrillicNames() {
        assertParses("steve spawn Васян");
        assertParses("steve remove Васян");
        assertParses("steve stop Васян");
        assertParses("steve tell Васян стой");
        assertParses("steve inventory Васян");
        assertParses("steve tp Васян");
    }

    @Test
    void acceptsLatinDigitsAndMixedNames() {
        assertParses("steve spawn Steve");
        assertParses("steve spawn 123");
        assertParses("steve spawn Васян_2-й");
        assertParses("steve spawn Miner.7+1");
    }

    @Test
    void acceptsQuotedNames() {
        assertParses("steve spawn \"Васян\"");
        assertParses("steve spawn 'Steve_1'");
        assertParses("steve tell \"Майнер\" стой");
    }

    @Test
    void rejectsInvalidNames() {
        assertRejected("steve spawn");
        assertRejected("steve spawn Васян!");
        assertRejected("steve spawn Васян#");
        assertRejected("steve spawn Васян Петров");
        assertRejected("steve spawn \"Васян Петров\"");
    }

    @Test
    void parsesNameArgumentValue() {
        ParseResults<CommandSourceStack> results = dispatcher.parse("steve spawn Васян", SOURCE);
        assertEquals("Васян", results.getContext().getArguments().get("name").getResult());
    }
}