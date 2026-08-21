package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;

public abstract class BaseAction {
    protected final VasyanEntity vasyan;
    protected final Task task;
    protected ActionResult result;
    protected boolean started = false;
    protected boolean cancelled = false;

    public BaseAction(VasyanEntity vasyan, Task task) {
        this.vasyan = vasyan;
        this.task = task;
    }

    public void start() {
        if (started) return;
        started = true;
        onStart();
    }

    public void tick() {
        if (!started || isComplete()) return;
        onTick();
    }

    public void cancel() {
        cancelled = true;
        result = ActionResult.failure("Action cancelled");
        onCancel();
    }

    public boolean isComplete() {
        return result != null || cancelled;
    }

    public ActionResult getResult() {
        return result;
    }

    protected abstract void onStart();
    protected abstract void onTick();
    protected abstract void onCancel();
    
    public abstract String getDescription();
}

