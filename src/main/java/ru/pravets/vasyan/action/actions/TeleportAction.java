package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Teleports the Steve instantly to the commanding player.
 *
 * <p>Triggered by natural language like "come to me", "teleport to me",
 * "return to me" from the K-panel chat. Reuses the same primitive as the
 * /steve tp command ({@link VasyanEntity#teleportToPlayer}) so the auto-return
 * logic of Stage 3 shares one code path.</p>
 */
public class TeleportAction extends BaseAction {

    private String playerName;
    private Player targetPlayer;

    public TeleportAction(VasyanEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        playerName = task.getStringParameter("player");

        findPlayer();

        if (targetPlayer == null) {
            result = ActionResult.failure("Player not found: " + playerName);
            return;
        }

        if (!(targetPlayer instanceof ServerPlayer serverPlayer)) {
            result = ActionResult.failure("Target is not a server player");
            return;
        }

        if (steve.teleportToPlayer(serverPlayer)) {
            result = ActionResult.success("Teleported to " + targetPlayer.getName().getString());
        } else if (steve.level().dimension() != targetPlayer.level().dimension()) {
            result = ActionResult.failure("Player is in another dimension");
        } else {
            result = ActionResult.failure("No safe spot near " + targetPlayer.getName().getString());
        }
    }

    @Override
    protected void onTick() {
        // Teleport is instant - nothing to do on subsequent ticks
    }

    @Override
    protected void onCancel() {
        // Nothing to cancel - teleport already happened or failed instantly
    }

    @Override
    public String getDescription() {
        return "Teleport to player " + playerName;
    }

    private void findPlayer() {
        // Explicit names are resolved across ALL dimensions (a player in
        // another dimension must yield "another dimension", not "not found").
        if (playerName != null && !playerName.isEmpty()
            && !playerName.contains("PLAYER") && !playerName.contains("NAME")
            && !playerName.equalsIgnoreCase("me") && !playerName.equalsIgnoreCase("you")) {
            var server = steve.level().getServer();
            if (server != null) {
                ServerPlayer exact = server.getPlayerList().getPlayerByName(playerName);
                if (exact != null) {
                    targetPlayer = exact;
                    return;
                }
            }
        }

        // Implicit targets ("me", "you", placeholders, empty) - nearest player
        // in the Steve's dimension.
        List<? extends Player> players = steve.level().players();
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Player player : players) {
            double distance = steve.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        if (nearest != null) {
            targetPlayer = nearest;
            if (playerName == null || playerName.isEmpty()) {
                playerName = nearest.getName().getString();
            }
        }
    }
}
