package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;

/**
 * Mining by visible-target search. The old tunnel-digging behavior (mining in
 * one direction by the player's gaze) is removed: Steve now routes through the
 * area and mines only blocks he can see. Same logic as {@link GatherResourceAction}.
 */
public class MineBlockAction extends GatherResourceAction {

    public MineBlockAction(VasyanEntity steve, Task task) {
        super(steve, task);
    }
}
