package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;

public class PathfindAction extends BaseAction {
    private BlockPos targetPos;
    private int ticksRunning;
    private static final int MAX_TICKS = 600; // 30 seconds timeout

    public PathfindAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);
        
        targetPos = new BlockPos(x, y, z);
        ticksRunning = 0;
        
        steve.getNavigation().moveTo(x, y, z, 1.0);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (steve.blockPosition().closerThan(targetPos, 2.0)) {
            result = ActionResult.success("Reached target position");
            return;
        }
        
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Pathfinding timeout");
            return;
        }
        
        if (steve.getNavigation().isDone() && !steve.blockPosition().closerThan(targetPos, 2.0)) {
            steve.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Pathfind to " + targetPos;
    }
}

