package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;

/**
 * Makes the Vasyan stay in place until the next command: stops navigation,
 * clears pending tasks and disables idle-follow. The "staying" flag lives in
 * {@link ru.pravets.vasyan.action.ActionExecutor} - a new command wakes the Vasyan
 * up automatically.
 *
 * <p>Note: {@code stopCurrentAction()} must NOT be called from onStart -
 * {@code currentAction} already points at this action by then (self-cancel).</p>
 */
public class StayAction extends BaseAction {

    public StayAction(VasyanEntity vasyan, Task task) {
        super(vasyan, task);
    }

    @Override
    protected void onStart() {
        ActionExecutor executor = vasyan.getActionExecutor();
        executor.setStaying(true);
        // Drop pending tasks of a multi-task plan so the Vasyan does not
        // execute the next task right after this one (memory queue is NOT
        // the executor's queue).
        executor.clearTaskQueue();
        vasyan.getNavigation().stop();
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
