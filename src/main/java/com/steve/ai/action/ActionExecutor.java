package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.ai.LLMClient;
import java.util.Queue;
import java.util.LinkedList;

public class ActionExecutor {
    private final SteveEntity steve;
    private final Queue<String> commandQueue;
    private boolean isExecuting;
    private String lastMessage;

    public ActionExecutor(SteveEntity steve) {
        this.steve = steve;
        this.commandQueue = new LinkedList<String>();
        this.isExecuting = false;
        this.lastMessage = null;
    }

    public void processNaturalLanguageCommand(String command) {
        commandQueue.offer(command);
        SteveMod.LOGGER.info("Steve '{}' queued command: {}", steve.getSteveName(), command);
    }

    public void tick() {
        if (!isExecuting && !commandQueue.isEmpty()) {
            String command = commandQueue.poll();
            isExecuting = true;
            new Thread(() -> {
                LLMClient llm = new LLMClient();
                String llmAnswer = llm.requestLLM(command);
                String msg;
                if (llmAnswer == null || llmAnswer.startsWith("[ERROR]")) {
                    msg = "[Steve] Не удалось получить ответ от LLM!";
                } else {
                    msg = "[Steve] " + llmAnswer;
                }
                steve.sendChatMessage(msg);
                lastMessage = msg;
                isExecuting = false;
            }).start();
        }
    }

    public boolean isExecuting() {
        return isExecuting;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void stopCurrentAction() {
        commandQueue.clear();
        isExecuting = false;
        lastMessage = null;
    }
}
