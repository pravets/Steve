package com.steve.ai.entity;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.memory.SteveMemory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SteveEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> STEVE_NAME = 
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);

    private String steveName;
    private SteveMemory memory;
    private ActionExecutor actionExecutor;
    private SteveInventory inventory;
    private int tickCounter = 0;
    private int pickupCooldown = 0;
    private boolean isFlying = false;
    private boolean isInvulnerable = false;

    /** Pickup radius for items lying on the ground, in blocks. */
    private static final double PICKUP_RADIUS = 3.0;
    /** Pickup scan every N ticks (20 ticks = 1 second). */
    private static final int PICKUP_INTERVAL = 10;

    public SteveEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.steveName = "Steve";
        this.memory = new SteveMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        this.inventory = new SteveInventory();
        this.setCustomNameVisible(true);
        
        this.isInvulnerable = true;
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.ATTACK_DAMAGE, 8.0D)
            .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STEVE_NAME, "Steve");
    }

    @Override
    public void remove(RemovalReason reason) {
        // Drop the inventory into the world when Steve is killed or discarded
        // (/kill, /steve remove) instead of silently losing the contents.
        // Unloading/changing dimension must keep the inventory (it is in NBT).
        if (!this.level().isClientSide && !inventory.isEmpty()
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            for (ItemStack stack : inventory.takeAll()) {
                this.spawnAtLocation(stack);
            }
        }
        super.remove(reason);
    }

    @Override
    public void tick() {
        super.tick();
        
        if (!this.level().isClientSide) {
            actionExecutor.tick();
            tickPickup();
        }
    }

    /**
     * Periodically picks up nearby item entities into Steve's inventory.
     */
    private void tickPickup() {
        if (--pickupCooldown > 0) {
            return;
        }
        pickupCooldown = PICKUP_INTERVAL;

        if (!inventory.hasFreeSpace()) {
            return; // Inventory full - leave items on the ground
        }

        AABB searchBox = this.getBoundingBox().inflate(PICKUP_RADIUS);
        List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class, searchBox);
        for (ItemEntity item : items) {
            if (item.isRemoved() || !item.isAlive()) {
                continue;
            }
            // Respect vanilla pickup delay: items thrown by a player (Q), tossed
            // to teammates or dropped on death must not be vacuumed instantly
            if (item.hasPickUpDelay()) {
                continue;
            }
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remainder = inventory.addItem(stack);
            if (remainder.getCount() < stack.getCount()) {
                // We picked up at least part of the stack
                if (remainder.isEmpty()) {
                    item.discard();
                } else {
                    item.setItem(remainder);
                }
            }
            if (!inventory.hasFreeSpace()) {
                break; // Inventory full - stop picking up
            }
        }
    }

    public void setSteveName(String name) {
        this.steveName = name;
        this.entityData.set(STEVE_NAME, name);
        this.setCustomName(Component.literal(name));
    }

    public String getSteveName() {
        return this.steveName;
    }

    public SteveMemory getMemory() {
        return this.memory;
    }

    public SteveInventory getInventory() {
        return this.inventory;
    }

    public ActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("SteveName", this.steveName);
        
        CompoundTag memoryTag = new CompoundTag();
        this.memory.saveToNBT(memoryTag);
        tag.put("Memory", memoryTag);

        CompoundTag inventoryTag = new CompoundTag();
        this.inventory.saveToNBT(inventoryTag);
        tag.put("Inventory", inventoryTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SteveName")) {
            this.setSteveName(tag.getString("SteveName"));
        }
        
        if (tag.contains("Memory")) {
            this.memory.loadFromNBT(tag.getCompound("Memory"));
        }

        if (tag.contains("Inventory")) {
            this.inventory.loadFromNBT(tag.getCompound("Inventory"));
        }
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                       MobSpawnType spawnType, @Nullable SpawnGroupData spawnData,
                                       @Nullable CompoundTag tag) {
        spawnData = super.finalizeSpawn(level, difficulty, spawnType, spawnData, tag);
        return spawnData;
    }

    public void sendChatMessage(String message) {
        if (this.level().isClientSide) return;
        
        Component chatComponent = Component.literal("<" + this.steveName + "> " + message);
        this.level().players().forEach(player -> player.sendSystemMessage(chatComponent));
    }

    /**
     * Right-click on a Steve opens its inventory as a vanilla chest menu:
     * the player can selectively take items (and give items back).
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, p) -> new ChestMenu(
                    MenuType.GENERIC_9x4, containerId, playerInventory, this.inventory, 4),
                Component.literal(this.steveName + "'s Inventory")));
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.world.damagesource.DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
    }

    public void setFlying(boolean flying) {
        this.isFlying = flying;
        this.setNoGravity(flying);
        this.setInvulnerableBuilding(flying);
    }

    public boolean isFlying() {
        return this.isFlying;
    }

    /**
     * Set invulnerability for building (immune to ALL damage: fire, lava, suffocation, fall, etc.)
     */
    public void setInvulnerableBuilding(boolean invulnerable) {
        this.isInvulnerable = invulnerable;
        this.setInvulnerable(invulnerable); // Minecraft's built-in invulnerability
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        return true;
    }

    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        if (this.isFlying && !this.level().isClientSide) {
            double motionY = this.getDeltaMovement().y;
            
            if (this.getNavigation().isInProgress()) {
                super.travel(travelVector);
                
                // But add ability to move vertically freely
                if (Math.abs(motionY) < 0.1) {
                    // Small upward force to prevent falling
                    this.setDeltaMovement(this.getDeltaMovement().add(0, 0.05, 0));
                }
            } else {
                super.travel(travelVector);
            }
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float damageMultiplier, net.minecraft.world.damagesource.DamageSource source) {
        // No fall damage when flying
        if (this.isFlying) {
            return false;
        }
        return super.causeFallDamage(distance, damageMultiplier, source);
    }
}

