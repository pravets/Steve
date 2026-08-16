package com.steve.ai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that all /steve commands accept Cyrillic Steve names. The default
 * Brigadier string argument is ASCII-only for unquoted tokens; the custom
 * SteveNameArgumentType must accept letters of any script.
 */
class SteveNameArgumentTypeTest {

    private static final CommandSourceStack SOURCE = new CommandSourceStack(
        null, null, null, null, 0, "", Component.literal(""), null, null);

    private static CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void setUp() {
        dispatcher = new CommandDispatcher<>();
        SteveCommands.register(dispatcher);
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
        assertTrue(results.getContext().getCommand() == null || results.getReader().canRead(),
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
    void rejectsInvalidNames() {
        assertRejected("steve spawn");
        assertRejected("steve spawn Васян!");
        assertRejected("steve spawn Васян#");
        assertRejected("steve spawn Васян Петров");
        assertRejected("steve spawn \"Васян\"");
    }

    @Test
    void parsesNameArgumentValue() {
        ParseResults<CommandSourceStack> results = dispatcher.parse("steve spawn Васян", SOURCE);
        assertEquals("Васян", results.getContext().getArguments().get("name").getResult());
    }
}