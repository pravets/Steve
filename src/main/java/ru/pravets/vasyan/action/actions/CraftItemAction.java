package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

public class CraftItemAction extends BaseAction {
    private String itemName;
    private int quantity;
    private int ticksRunning;

    public CraftItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        itemName = task.getStringParameter("item");
        quantity = task.getIntParameter("quantity", 1);
        ticksRunning = 0;
        
        // - Check if recipe exists
        // - Check if Steve has ingredients
        // - Navigate to crafting table if needed
        
        result = ActionResult.failure("Crafting not yet implemented", false);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Craft " + quantity + " " + itemName;
    }
}

