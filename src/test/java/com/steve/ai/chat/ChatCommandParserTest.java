package com.steve.ai.chat;

import org.junit.jupiter.api.Test;

import static com.steve.ai.chat.ChatCommandParser.isAllCommand;
import static com.steve.ai.chat.ChatCommandParser.isStayCommand;
import static com.steve.ai.chat.ChatCommandParser.normalize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCommandParserTest {

    // ---- isAllCommand: English ----

    @Test
    void englishAllPrefixes() {
        assertTrue(isAllCommand(normalize("all teleport to me")));
        assertTrue(isAllCommand(normalize("all steves come here")));
        assertTrue(isAllCommand(normalize("everyone go mine")));
        assertTrue(isAllCommand(normalize("everybody gather")));
    }

    @Test
    void russianAllPrefixes() {
        assertTrue(isAllCommand(normalize("все телепортируйтесь ко мне")));
        assertTrue(isAllCommand(normalize("всем ко мне")));
        assertTrue(isAllCommand(normalize("все боты сюда")));
        assertTrue(isAllCommand(normalize("Все идите копать")));
    }

    @Test
    void nonAllCommandsAreRejected() {
        assertFalse(isAllCommand(normalize("alex come to me")));
        assertFalse(isAllCommand(normalize("build a house")));
        assertFalse(isAllCommand(normalize("построй дом")));
        assertFalse(isAllCommand(normalize("stay here")));
        assertFalse(isAllCommand(normalize("вселенная опасна"))); // "все" без пробела не сработает
    }

    // ---- isStayCommand ----

    @Test
    void englishStayWords() {
        assertTrue(isStayCommand(normalize("stay")));
        assertTrue(isStayCommand(normalize("stay here")));
        assertTrue(isStayCommand(normalize("stop")));
        assertTrue(isStayCommand(normalize("wait for me")));
        assertTrue(isStayCommand(normalize("freeze")));
    }

    @Test
    void russianStayWords() {
        assertTrue(isStayCommand(normalize("стой")));
        assertTrue(isStayCommand(normalize("стой на месте")));
        assertTrue(isStayCommand(normalize("замри")));
        assertTrue(isStayCommand(normalize("остановись")));
        assertTrue(isStayCommand(normalize("стоп")));
        assertTrue(isStayCommand(normalize("стоять")));
        assertTrue(isStayCommand(normalize("жди меня")));
    }

    @Test
    void fillCommands() {
        assertTrue(ChatCommandParser.isFillCommand(normalize("добудь дерево до полного инвентаря")));
        assertTrue(ChatCommandParser.isFillCommand(normalize("заполни инвентарь деревом")));
        assertTrue(ChatCommandParser.isFillCommand(normalize("fill inventory with wood")));
        assertTrue(ChatCommandParser.isFillCommand(normalize("gather until full")));
        assertFalse(ChatCommandParser.isFillCommand(normalize("добудь 50 дерева")));
        assertFalse(ChatCommandParser.isFillCommand(normalize("stay")));
        assertFalse(ChatCommandParser.isFillCommand(null));
    }

    @Test
    void nonStayCommandsAreRejected() {
        assertFalse(isStayCommand(normalize("mine iron")));
        assertFalse(isStayCommand(normalize("иди копай")));
        assertFalse(isStayCommand(normalize("teleport to me")));
        assertFalse(isStayCommand(normalize("")));
    }
}
