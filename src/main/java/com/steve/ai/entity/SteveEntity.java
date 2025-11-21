package com.steve.ai.entity;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.memory.SteveMemory;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

public class SteveEntity extends EntityCreature {
    private String steveName;
    private SteveMemory memory;
    private ActionExecutor actionExecutor;
    private boolean isFlying = false;
    private boolean isInvulnerable = true;
    
    public SteveEntity(World world) {
        super(world);
        this.steveName = "Steve";
        this.memory = new SteveMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        this.setAlwaysRenderNameTag(true);
        this.isImmuneToFire = true;
    }
    
    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20.0D);
        this.getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.25D);
        this.getEntityAttribute(SharedMonsterAttributes.followRange).setBaseValue(48.0D);
        
        // Attack damage attribute doesn't exist in 1.7.10 by default for EntityCreature
        // We'll handle it in combat actions if needed
    }
    
    @Override
    protected void entityInit() {
        super.entityInit();
        // DataWatcher for syncing steve name across client/server
        this.dataWatcher.addObject(20, this.steveName);
    }
    
    @Override
    protected void addRandomArmor() {
        // Override to prevent random armor spawning
    }
    
    @Override
    public void onUpdate() {
        super.onUpdate();
        
        if (!this.worldObj.isRemote) {
            actionExecutor.tick();
        }
    }
    
    public void setSteveName(String name) {
        this.steveName = name;
        this.dataWatcher.updateObject(20, name);
        this.setCustomNameTag(name);
    }
    
    public String getSteveName() {
        return this.steveName;
    }
    
    public SteveMemory getMemory() {
        return this.memory;
    }
    
    public ActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }
    
    @Override
    public void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);
        nbt.setString("SteveName", this.steveName);
        nbt.setBoolean("IsFlying", this.isFlying);
        
        NBTTagCompound memoryTag = new NBTTagCompound();
        this.memory.saveToNBT(memoryTag);
        nbt.setTag("Memory", memoryTag);
    }
    
    @Override
    public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);
        if (nbt.hasKey("SteveName")) {
            this.setSteveName(nbt.getString("SteveName"));
        }
        if (nbt.hasKey("IsFlying")) {
            this.setFlying(nbt.getBoolean("IsFlying"));
        }
        if (nbt.hasKey("Memory")) {
            this.memory.loadFromNBT(nbt.getCompoundTag("Memory"));
        }
    }
    
    public void sendChatMessage(String message) {
        if (this.worldObj.isRemote) return;
        
        ChatComponentText chatComponent = new ChatComponentText("<" + this.steveName + "> " + message);
        
        // Send to all players in the world
        for (Object obj : this.worldObj.playerEntities) {
            if (obj instanceof EntityPlayer) {
                ((EntityPlayer) obj).addChatMessage(chatComponent);
            }
        }
    }
    
    public void setFlying(boolean flying) {
        this.isFlying = flying;
        this.noClip = flying; // In 1.7.10, noClip allows passing through blocks
    }
    
    public boolean isFlying() {
        return this.isFlying;
    }
    
    @Override
    public boolean attackEntityFrom(net.minecraft.util.DamageSource source, float amount) {
        // Steve entities are invulnerable by default
        if (this.isInvulnerable) {
            return false;
        }
        return super.attackEntityFrom(source, amount);
    }
    
    @Override
    protected void fall(float distance) {
        // No fall damage when flying
        if (!this.isFlying) {
            super.fall(distance);
        }
    }
    
    @Override
    public void onLivingUpdate() {
        // Handle flying movement
        if (this.isFlying && !this.worldObj.isRemote) {
            // Disable gravity when flying
            this.motionY = Math.max(this.motionY, -0.1D);
        }
        
        super.onLivingUpdate();
    }
    
    @Override
    protected String getLivingSound() {
        return null; // Silent
    }
    
    @Override
    protected String getHurtSound() {
        return null; // Silent
    }
    
    @Override
    protected String getDeathSound() {
        return null; // Silent
    }
    
    @Override
    public boolean canDespawn() {
        return false; // Never despawn
    }
}
