package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;

public class CraftItemAction extends BaseAction {
    private String itemName;
    private int quantity;
    private int ticksRunning;

    public CraftItemAction(VasyanEntity vasyan, Task task) {
        super(vasyan, task);
    }

    @Override
    protected void onStart() {
        itemName = task.getStringParameter("item");
        quantity = task.getIntParameter("quantity", 1);
        ticksRunning = 0;
        
        // - Check if recipe exists
        // - Check if Vasyan has ingredients
        // - Navigate to crafting table if needed
        
        result = ActionResult.failure("Crafting not yet implemented", false);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
    }

    @Override
    protected void onCancel() {
        vasyan.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Craft " + quantity + " " + itemName;
    }
}

