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
 * Verifies that all /vasyan commands accept Cyrillic Vasyan names. The default
 * Brigadier string argument is ASCII-only for unquoted tokens; the custom
 * VasyanNameArgumentType must accept letters of any script.
 */
class VasyanNameArgumentTypeTest {

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
        assertParses("vasyan spawn Васян");
        assertParses("vasyan remove Васян");
        assertParses("vasyan stop Васян");
        assertParses("vasyan tell Васян стой");
        assertParses("vasyan inventory Васян");
        assertParses("vasyan tp Васян");
    }

    @Test
    void acceptsLatinDigitsAndMixedNames() {
        assertParses("vasyan spawn Vasyan");
        assertParses("vasyan spawn 123");
        assertParses("vasyan spawn Васян_2-й");
        assertParses("vasyan spawn Miner.7+1");
    }

    @Test
    void acceptsQuotedNames() {
        assertParses("vasyan spawn \"Васян\"");
        assertParses("vasyan spawn 'Vasyan_1'");
        assertParses("vasyan tell \"Майнер\" стой");
    }

    @Test
    void rejectsInvalidNames() {
        assertRejected("vasyan spawn");
        assertRejected("vasyan spawn Васян!");
        assertRejected("vasyan spawn Васян#");
        assertRejected("vasyan spawn Васян Петров");
        assertRejected("vasyan spawn \"Васян Петров\"");
    }

    @Test
    void parsesNameArgumentValue() {
        ParseResults<CommandSourceStack> results = dispatcher.parse("vasyan spawn Васян", SOURCE);
        assertEquals("Васян", results.getContext().getArguments().get("name").getResult());
    }
}