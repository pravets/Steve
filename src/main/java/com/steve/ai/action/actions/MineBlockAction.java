package com.steve.ai.action.actions;

import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

/**
 * Mining by visible-target search. The old tunnel-digging behavior (mining in
 * one direction by the player's gaze) is removed: Steve now routes through the
 * area and mines only blocks he can see. Same logic as {@link GatherResourceAction}.
 */
public class MineBlockAction extends GatherResourceAction {

    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }
}
