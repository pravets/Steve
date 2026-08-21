package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;
import net.minecraft.core.BlockPos;

public class PathfindAction extends BaseAction {
    private BlockPos targetPos;
    private int ticksRunning;
    private static final int MAX_TICKS = 600; // 30 seconds timeout

    public PathfindAction(VasyanEntity vasyan, Task task) {
        super(vasyan, task);
    }

    @Override
    protected void onStart() {
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);
        
        targetPos = new BlockPos(x, y, z);
        ticksRunning = 0;
        
        vasyan.getNavigation().moveTo(x, y, z, 1.0);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (vasyan.blockPosition().closerThan(targetPos, 2.0)) {
            result = ActionResult.success("Reached target position");
            return;
        }
        
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Pathfinding timeout");
            return;
        }
        
        if (vasyan.getNavigation().isDone() && !vasyan.blockPosition().closerThan(targetPos, 2.0)) {
            vasyan.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
        }
    }

    @Override
    protected void onCancel() {
        vasyan.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Pathfind to " + targetPos;
    }
}

