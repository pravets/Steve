package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

/**
 * Makes the Steve stay in place until the next command: stops navigation,
 * clears pending tasks and disables idle-follow. The "staying" flag lives in
 * {@link com.steve.ai.action.ActionExecutor} - a new command wakes the Steve
 * up automatically.
 *
 * <p>Note: {@code stopCurrentAction()} must NOT be called from onStart -
 * {@code currentAction} already points at this action by then (self-cancel).</p>
 */
public class StayAction extends BaseAction {

    public StayAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        steve.getActionExecutor().setStaying(true);
        steve.getNavigation().stop();
        steve.getMemory().clearTaskQueue();
        result = ActionResult.success("Staying in place");
    }

    @Override
    protected void onTick() {
        // Instant action - nothing to do
    }

    @Override
    protected void onCancel() {
        // Nothing to cancel
    }

    @Override
    public String getDescription() {
        return "Stay in place";
    }
}
