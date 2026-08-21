package ru.pravets.vasyan.client;

import com.mojang.blaze3d.systems.RenderSystem;
import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.network.ServerboundRequestInventoryPacket;
import ru.pravets.vasyan.network.ServerboundRequestVasyanListPacket;
import ru.pravets.vasyan.network.VasyanNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Side-mounted GUI panel for Vasyan agent interaction.
 * Inspired by Cursor's composer - slides in/out from the right side.
 * Now with scrollable message history!
 */
public class VasyanGUI {
    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_PADDING = 6;
    private static final int ANIMATION_SPEED = 20;
    private static final int MESSAGE_HEIGHT = 12;
    private static final int MAX_MESSAGES = 500;
    
    private static boolean isOpen = false;
    private static float slideOffset = PANEL_WIDTH; // Start fully hidden
    private static EditBox inputBox;
    private static List<String> commandHistory = new ArrayList<>();
    private static int historyIndex = -1;

    // Inventory view state (client-side copies from server packets)
    private static boolean showingInventory = false;
    private static List<String> vasyanNames = new ArrayList<>();
    private static String selectedVasyan = null;
    private static List<ItemStack> inventoryStacks = new ArrayList<>();

    // Message history and scrolling
    private static List<ChatMessage> messages = new ArrayList<>();
    private static int scrollOffset = 0;
    private static int maxScroll = 0;
    private static final int BACKGROUND_COLOR = 0x15202020; // Ultra transparent (15 = ~8% opacity)
    private static final int BORDER_COLOR = 0x40404040; // More transparent border
    private static final int HEADER_COLOR = 0x25252525; // More transparent header (~15% opacity)
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SLOT_COLOR = 0x33FFFFFF;
    private static final int SLOT_SIZE = 18;
    
    // Message bubble colors
    private static final int USER_BUBBLE_COLOR = 0xC04CAF50; // Green bubble for user
    private static final int VASYAN_BUBBLE_COLOR = 0xC02196F3; // Blue bubble for Vasyan
    private static final int SYSTEM_BUBBLE_COLOR = 0xC0FF9800; // Orange bubble for system

    private static class ChatMessage {
        String sender; // "You", "Vasyan", "Alex", "System", etc.
        String text;
        int bubbleColor;
        boolean isUser; // true if message from user
        
        ChatMessage(String sender, String text, int bubbleColor, boolean isUser) {
            this.sender = sender;
            this.text = text;
            this.bubbleColor = bubbleColor;
            this.isUser = isUser;
        }
    }

    public static void toggle() {
        isOpen = !isOpen;
        
        Minecraft mc = Minecraft.getInstance();
        
        if (isOpen) {
            initializeInputBox();
            mc.setScreen(new VasyanOverlayScreen());
            if (inputBox != null) {
                inputBox.setFocused(true);
            }
            requestVasyanList(); // Refresh Vasyan list so commands target real agents
        } else {
            if (inputBox != null) {
                inputBox = null;
            }
            if (mc.screen instanceof VasyanOverlayScreen) {
                mc.setScreen(null);
            }
        }
    }

    public static boolean isOpen() {
        return isOpen;
    }

    private static void initializeInputBox() {
        Minecraft mc = Minecraft.getInstance();
        if (inputBox == null) {
            inputBox = new EditBox(mc.font, 0, 0, PANEL_WIDTH - 20, 20, 
                Component.literal("Command"));
            inputBox.setMaxLength(256);
            inputBox.setHint(Component.literal("Tell Vasyan what to do..."));
            inputBox.setFocused(true);
        }
    }

    /**
     * Add a message to the chat history
     */
    public static void addMessage(String sender, String text, int bubbleColor, boolean isUser) {
        messages.add(new ChatMessage(sender, text, bubbleColor, isUser));
        if (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
        // Auto-scroll to bottom on new message
        scrollOffset = 0;
    }

    /**
     * Add a user command to the history
     */
    public static void addUserMessage(String text) {
        addMessage("You", text, USER_BUBBLE_COLOR, true);
    }

    /**
     * Add a Vasyan response to the history
     */
    public static void addVasyanMessage(String vasyanName, String text) {
        addMessage(vasyanName, text, VASYAN_BUBBLE_COLOR, false);
    }

    /**
     * Add a system message to the history
     */
    public static void addSystemMessage(String text) {
        addMessage("System", text, SYSTEM_BUBBLE_COLOR, false);
    }

    /**
     * Toggle between chat and inventory views.
     */
    public static void toggleView() {
        showingInventory = !showingInventory;
        if (showingInventory) {
            requestVasyanList();
        }
    }

    public static boolean isShowingInventory() {
        return showingInventory;
    }

    /**
     * Called from the network handler: the list of active Vasyans.
     */
    public static void setVasyanList(List<String> names) {
        vasyanNames = new ArrayList<>(names);
        if (selectedVasyan == null || !vasyanNames.contains(selectedVasyan)) {
            selectedVasyan = vasyanNames.isEmpty() ? null : vasyanNames.get(0);
        }
        if (selectedVasyan != null) {
            requestInventory(selectedVasyan);
        }
    }

    /**
     * Called from the network handler: a Vasyan's inventory contents.
     */
    public static void setInventoryView(String vasyanName, List<ItemStack> stacks) {
        if (vasyanName.equals(selectedVasyan)) {
            inventoryStacks = new ArrayList<>(stacks);
        }
    }

    private static void requestVasyanList() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            VasyanNetworking.CHANNEL.sendToServer(new ServerboundRequestVasyanListPacket());
        }
    }

    private static void requestInventory(String vasyanName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            VasyanNetworking.CHANNEL.sendToServer(new ServerboundRequestInventoryPacket(vasyanName));
        }
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay().id().toString().contains("hotbar")) {
            return; // Don't render over hotbar
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Global voice recording indicator: shown regardless of the panel
        if (VoiceRecorder.isRecording()) {
            GuiGraphics gfx = event.getGuiGraphics();
            int w = mc.getWindow().getGuiScaledWidth();
            gfx.fill(w - 70, 4, w - 4, 24, 0xAA000000);
            gfx.drawString(mc.font, "§c● REC §7(V: stop)", w - 64, 8, 0xFFFF4444);
        }

        if (isOpen && slideOffset > 0) {
            slideOffset = Math.max(0, slideOffset - ANIMATION_SPEED);
        } else if (!isOpen && slideOffset < PANEL_WIDTH) {
            slideOffset = Math.min(PANEL_WIDTH, slideOffset + ANIMATION_SPEED);
        }

        // Don't render if completely hidden
        if (slideOffset >= PANEL_WIDTH) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        
        int panelX = (int) (screenWidth - PANEL_WIDTH + slideOffset);
        int panelY = 0;
        int panelHeight = screenHeight;

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.blendFuncSeparate(
            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO
        );
        graphics.fillGradient(panelX, panelY, screenWidth, panelHeight, BACKGROUND_COLOR, BACKGROUND_COLOR);
        
        graphics.fillGradient(panelX - 2, panelY, panelX, panelHeight, BORDER_COLOR, BORDER_COLOR);

        int headerHeight = 35;
        graphics.fillGradient(panelX, panelY, screenWidth, headerHeight, HEADER_COLOR, HEADER_COLOR);
        graphics.drawString(mc.font, "§lVasyan AI", panelX + PANEL_PADDING, panelY + 6, TEXT_COLOR);
        // View tabs (clickable)
        String chatTab = showingInventory ? "§7[Chat]" : "§e[Chat]";
        String invTab = showingInventory ? "§e[Inv]" : "§7[Inv]";
        graphics.drawString(mc.font, chatTab + " " + invTab, panelX + PANEL_PADDING + 58, panelY + 6, TEXT_COLOR);
        // Voice recording indicator (push-to-talk, key V)
        if (VoiceRecorder.isRecording()) {
            graphics.drawString(mc.font, "§c● REC", panelX + PANEL_WIDTH - 48, panelY + 6, 0xFFFF4444);
        }
        graphics.drawString(mc.font, "§7ESC: close | Tab: view | Click name: select",
            panelX + PANEL_PADDING, panelY + 20, 0xFF888888);

        // Inventory view replaces the chat area entirely
        if (showingInventory) {
            renderInventoryView(graphics, mc, panelX, screenWidth, headerHeight);
            RenderSystem.disableBlend();
            return;
        }

        // Message history area
        int inputAreaY = screenHeight - 80;
        int messageAreaTop = headerHeight + 5;
        int messageAreaHeight = inputAreaY - messageAreaTop - 5;
        int messageAreaBottom = messageAreaTop + messageAreaHeight;

        int totalMessageHeight = 0;
        for (ChatMessage msg : messages) {
            int maxBubbleWidth = PANEL_WIDTH - (PANEL_PADDING * 3);
            String wrappedText = wrapText(mc.font, msg.text, maxBubbleWidth - 10);
            int bubbleHeight = MESSAGE_HEIGHT + 10; // bubble padding
            totalMessageHeight += bubbleHeight + 5 + 12; // message + spacing + name
        }
        maxScroll = Math.max(0, totalMessageHeight - messageAreaHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // Render messages (scrollable)
        int yPos = messageAreaTop + 5;
        
        // Clip rendering to message area
        graphics.enableScissor(panelX, messageAreaTop, screenWidth, messageAreaBottom);
        
        if (messages.isEmpty()) {
            graphics.drawString(mc.font, "§7No messages yet...", 
                panelX + PANEL_PADDING, yPos, 0xFF666666);
            graphics.drawString(mc.font, "§7Type a command below!", 
                panelX + PANEL_PADDING, yPos + 12, 0xFF555555);
        } else {
            int currentY = messageAreaBottom - 5; // Start from bottom
            
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                
                int maxBubbleWidth = PANEL_WIDTH - (PANEL_PADDING * 3); // Leave space on sides
                String wrappedText = wrapText(mc.font, msg.text, maxBubbleWidth - 10);
                int textWidth = mc.font.width(wrappedText);
                int textHeight = MESSAGE_HEIGHT;
                int bubbleWidth = Math.min(textWidth + 10, maxBubbleWidth);
                int bubbleHeight = textHeight + 10;
                
                int msgY = currentY - bubbleHeight + scrollOffset;
                
                if (msgY + bubbleHeight < messageAreaTop - 20 || msgY > messageAreaBottom + 20) {
                    currentY -= bubbleHeight + 5;
                    continue;
                }
                
                // Render message bubble based on sender
                if (msg.isUser) {
                    int bubbleX = screenWidth - bubbleWidth - PANEL_PADDING - 5;
                    
                    // Draw bubble background with gradient for alpha support
                    graphics.fillGradient(bubbleX - 3, msgY - 3, bubbleX + bubbleWidth + 3, msgY + bubbleHeight, msg.bubbleColor, msg.bubbleColor);
                    
                    // Draw sender name (small, above bubble)
                    graphics.drawString(mc.font, "§7" + msg.sender, bubbleX, msgY - 12, 0xFFCCCCCC);
                    
                    // Draw message text (white on colored bubble)
                    graphics.drawString(mc.font, wrappedText, bubbleX + 5, msgY + 5, 0xFFFFFFFF);
                    
                } else {
                    int bubbleX = panelX + PANEL_PADDING;
                    
                    // Draw bubble background with gradient for alpha support
                    graphics.fillGradient(bubbleX - 3, msgY - 3, bubbleX + bubbleWidth + 3, msgY + bubbleHeight, msg.bubbleColor, msg.bubbleColor);
                    
                    // Draw sender name (small, above bubble)
                    graphics.drawString(mc.font, "§l" + msg.sender, bubbleX, msgY - 12, TEXT_COLOR);
                    
                    // Draw message text (white on colored bubble)
                    graphics.drawString(mc.font, wrappedText, bubbleX + 5, msgY + 5, 0xFFFFFFFF);
                }
                
                currentY -= bubbleHeight + 5 + 12; // Extra space for sender name
            }
        }
        
        graphics.disableScissor();
        
        if (maxScroll > 0) {
            int scrollBarHeight = Math.max(20, (messageAreaHeight * messageAreaHeight) / (maxScroll + messageAreaHeight));
            int scrollBarY = messageAreaTop + (int)((messageAreaHeight - scrollBarHeight) * (1.0f - (float)scrollOffset / maxScroll));
            graphics.fill(screenWidth - 4, scrollBarY, screenWidth - 2, scrollBarY + scrollBarHeight, 0xFF888888);
        }

        // Command input area (bottom) with gradient for alpha support
        graphics.fillGradient(panelX, inputAreaY, screenWidth, screenHeight, HEADER_COLOR, HEADER_COLOR);
        graphics.drawString(mc.font, "§7Command:", panelX + PANEL_PADDING, inputAreaY + 10, 0xFF888888);

        if (inputBox != null && isOpen) {
            inputBox.setX(panelX + PANEL_PADDING);
            inputBox.setY(inputAreaY + 25);
            inputBox.setWidth(PANEL_WIDTH - (PANEL_PADDING * 2));
            inputBox.render(graphics, (int)mc.mouseHandler.xpos(), (int)mc.mouseHandler.ypos(), mc.getFrameTime());
        }

        graphics.drawString(mc.font, "§8Enter: Send | ↑↓: History | Scroll: Messages", 
            panelX + PANEL_PADDING, screenHeight - 15, 0xFF555555);
        
        RenderSystem.disableBlend();
    }

    /**
     * Renders the inventory view: a clickable list of Vasyans and the selected
     * Vasyan's inventory grid.
     */
    private static void renderInventoryView(GuiGraphics graphics, Minecraft mc,
                                            int panelX, int screenWidth, int headerHeight) {
        int y = headerHeight + 10;

        // Vasyan selector list
        if (vasyanNames.isEmpty()) {
            graphics.drawString(mc.font, "§7No Vasyans. Spawn one via chat:", panelX + PANEL_PADDING, y, 0xFFAAAAAA);
            graphics.drawString(mc.font, "§7/vasyan spawn <name>", panelX + PANEL_PADDING, y + 12, 0xFFAAAAAA);
            return;
        }

        graphics.drawString(mc.font, "§7Vasyans:", panelX + PANEL_PADDING, y, 0xFFAAAAAA);
        y += 12;
        for (String name : vasyanNames) {
            String line = name.equals(selectedVasyan) ? "§e> " + name : "§7  " + name;
            graphics.drawString(mc.font, line, panelX + PANEL_PADDING, y, TEXT_COLOR);
            y += 12;
        }

        y += 4;
        String header = selectedVasyan != null
            ? "§e" + selectedVasyan + "'s inventory§7 (" + inventoryStacks.size() + " stacks)"
            : "§7No Vasyan selected";
        graphics.drawString(mc.font, header, panelX + PANEL_PADDING, y, TEXT_COLOR);
        y += 12;

        // Inventory grid: 9 columns x 3 rows (27 slots, matches the default capacity)
        int gridX = panelX + PANEL_PADDING + 2;
        int cols = 9;
        int index = 0;
        for (ItemStack stack : inventoryStacks) {
            int row = index / cols;
            int col = index % cols;
            if (row >= 3) {
                break; // Only render 27 slots
            }
            int slotX = gridX + col * SLOT_SIZE;
            int slotY = y + row * SLOT_SIZE;
            graphics.fill(slotX, slotY, slotX + SLOT_SIZE - 2, slotY + SLOT_SIZE - 2, SLOT_COLOR);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slotX + 1, slotY + 1);
                graphics.renderItemDecorations(mc.font, stack, slotX + 1, slotY + 1);
            }
            index++;
        }
        graphics.drawString(mc.font, "§8Items: " + totalItems(), panelX + PANEL_PADDING, y + 3 * SLOT_SIZE + 4, 0xFF777777);
    }

    private static int totalItems() {
        int total = 0;
        for (ItemStack stack : inventoryStacks) {
            total += stack.getCount();
        }
        return total;
    }

    /**
     * Simple word wrap for text
     */
    private static String wrapText(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        // Simple truncation for now
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            result.append(text.charAt(i));
            if (font.width(result.toString() + "...") >= maxWidth) {
                return result.substring(0, result.length() - 3) + "...";
            }
        }
        return result.toString();
    }

    public static boolean handleKeyPress(int keyCode, int scanCode, int modifiers) {
        if (!isOpen || inputBox == null) return false;

        Minecraft mc = Minecraft.getInstance();
        
        // Tab - toggle chat / inventory view
        if (keyCode == 258) { // TAB
            toggleView();
            return true;
        }

        // Enter key - send command
        if (keyCode == 257) {
            String command = inputBox.getValue().trim();
            if (!command.isEmpty()) {
                sendCommand(command);
                inputBox.setValue("");
                historyIndex = -1;
            }
            return true;
        }

        // Arrow up - previous command
        if (keyCode == 265 && !commandHistory.isEmpty()) { // UP
            if (historyIndex < commandHistory.size() - 1) {
                historyIndex++;
                inputBox.setValue(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            }
            return true;
        }

        // Arrow down - next command
        if (keyCode == 264) { // DOWN
            if (historyIndex > 0) {
                historyIndex--;
                inputBox.setValue(commandHistory.get(commandHistory.size() - 1 - historyIndex));
            } else if (historyIndex == 0) {
                historyIndex = -1;
                inputBox.setValue("");
            }
            return true;
        }

        // Backspace, Delete, Home, End, Left, Right - pass to input box
        if (keyCode == 259 || keyCode == 261 || keyCode == 268 || keyCode == 269 || 
            keyCode == 263 || keyCode == 262) {
            inputBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }

        return true; // Consume all keys to prevent game controls
    }

    public static boolean handleCharTyped(char codePoint, int modifiers) {
        if (isOpen && inputBox != null) {
            inputBox.charTyped(codePoint, modifiers);
            return true; // Consumed
        }
        return false;
    }

    public static void handleMouseClick(double mouseX, double mouseY, int button) {
        if (!isOpen) return;

        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int panelX = (int) (screenWidth - PANEL_WIDTH + slideOffset);

        // Click on the header (title/tabs) toggles the view - only within the
        // panel's horizontal bounds (panelX..screenWidth), not anywhere on screen
        if (mouseY < 35 && mouseX >= panelX && mouseX < screenWidth) {
            toggleView();
            return;
        }

        if (showingInventory) {
            // Click on a Vasyan name selects it and requests its inventory
            int y = 35 + 10 + 12; // headerHeight + 10 + "Vasyans:" label
            for (String name : vasyanNames) {
                if (mouseY >= y && mouseY < y + 12 && mouseX >= panelX && mouseX < panelX + PANEL_WIDTH) {
                    if (!name.equals(selectedVasyan)) {
                        selectedVasyan = name;
                        inventoryStacks.clear();
                        requestInventory(name);
                    }
                    return;
                }
                y += 12;
            }
            return;
        }

        if (inputBox != null) {
            int inputAreaY = screenHeight - 80;
            if (mouseY >= inputAreaY + 25 && mouseY <= inputAreaY + 45) {
                inputBox.setFocused(true);
            } else {
                inputBox.setFocused(false);
            }
        }
    }

    public static void handleMouseScroll(double scrollDelta) {
        if (!isOpen) return;
        
        int scrollAmount = (int)(scrollDelta * 3 * MESSAGE_HEIGHT);
        scrollOffset -= scrollAmount;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private static void sendCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        
        commandHistory.add(command);
        if (commandHistory.size() > 50) {
            commandHistory.remove(0);
        }
        
        addUserMessage(command);

        if (command.toLowerCase().startsWith("spawn ")) {
            String name = command.substring(6).trim();
            if (name.isEmpty()) name = "Vasyan";
            if (mc.player != null) {
                mc.player.connection.sendCommand("vasyan spawn " + name);
                addSystemMessage("Spawning Vasyan agent: " + name);
            }
            return;
        }

        String commandLower = ru.pravets.vasyan.chat.ChatCommandParser.normalize(command);

        // Commands addressed to ALL Vasyans go through the server ("tell all"),
        // so the full, up-to-date Vasyan list is used - not the client-side cache.
        // The addressing prefix is stripped: "all stay" -> "stay", otherwise the
        // stay/stop trigger in deliverCommand would see "all" as the first word.
        if (ru.pravets.vasyan.chat.ChatCommandParser.isAllCommand(commandLower)) {
            if (mc.player != null) {
                String payload = ru.pravets.vasyan.chat.ChatCommandParser.stripAllPrefix(command);
                mc.player.connection.sendCommand("vasyan tell all " + payload);
                addSystemMessage("→ all Vasyans: " + payload);
            }
            return;
        }

        List<String> targetVasyans = parseTargetVasyans(command);
        
        if (targetVasyans.isEmpty()) {
            if (!vasyanNames.isEmpty()) {
                targetVasyans.add(vasyanNames.get(0));
            } else {
                // No Vasyans available (client-side list; refresh from server)
                addSystemMessage("No Vasyan agents found! Use 'spawn <name>' to create one.");
                requestVasyanList();
                return;
            }
        }

        // Send command to all targeted Vasyans
        if (mc.player != null) {
            for (String vasyanName : targetVasyans) {
                mc.player.connection.sendCommand("vasyan tell " + vasyanName + " " + command);
            }
            
            if (targetVasyans.size() > 1) {
                addSystemMessage("→ " + String.join(", ", targetVasyans) + ": " + command);
            } else {
                addSystemMessage("→ " + targetVasyans.get(0) + ": " + command);
            }
        }
    }
    
    private static List<String> parseTargetVasyans(String command) {
        List<String> targets = new ArrayList<>();
        String commandLower = command.toLowerCase();
        
        // All-commands normally leave via "tell all" above; this branch is a
        // defensive fallback (e.g. direct /vasyan tell with an all-prefix).
        if (ru.pravets.vasyan.chat.ChatCommandParser.isAllCommand(commandLower)) {
            return new ArrayList<>(vasyanNames);
        }
        
        String[] parts = command.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Name matching tolerates Russian transcriptions ("алекс" -> Alex,
            // "стиви" -> Vasyan) and case differences
            String firstWord = trimmed.split("\\s+", 2)[0];
            String matched = ru.pravets.vasyan.chat.NameMatcher.matchName(firstWord, vasyanNames);
            if (matched != null) {
                targets.add(matched);
            }
        }
        
        return targets;
    }

    public static void tick() {
        if (isOpen && inputBox != null) {
            inputBox.tick();
            // Auto-focus input box when panel is open
            if (!inputBox.isFocused()) {
                inputBox.setFocused(true);
            }
        }
    }
}
