package net.shadowmage.ancientwarfare.npc.entity;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIMoveTowardsRestriction;
import net.minecraft.entity.ai.EntityAIOpenDoor;
import net.minecraft.entity.ai.EntityAIRestrictOpenDoor;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAIWander;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.ai.EntityAIWatchClosest2;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.shadowmage.ancientwarfare.core.config.AWLog;
import net.shadowmage.ancientwarfare.core.entity.AWEntityRegistry;
import net.shadowmage.ancientwarfare.core.interfaces.IOwnable;
import net.shadowmage.ancientwarfare.core.util.BlockPosition;
import net.shadowmage.ancientwarfare.npc.ai.NpcAIFollowPlayer;
import net.shadowmage.ancientwarfare.npc.item.AWNpcItemLoader;
import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;

public abstract class NpcBase extends EntityCreature implements IEntityAdditionalSpawnData, IOwnable
{
/**
 * user-set name for this NPC -- set via name tag items or other means
 */
protected String npcName = "";

protected String ownerName = "";//the owner of this NPC, used for checking teams

protected String followingPlayerName;//set/cleared onInteract from player if player.team==this.team

protected NpcLevelingStats levelingStats;

/**
 * the current/custom user set texture;
 */
protected ResourceLocation texture;

/**
 * the default texture for this entity type, should be set in entity constructor<br>
 * e.g. combat / worker / courier / hostile
 */
protected ResourceLocation defaultTexture;

private final ResourceLocation baseDefaultTexture;

public ItemStack ordersStack;

public NpcBase(World par1World)
  {
  super(par1World);
  baseDefaultTexture = new ResourceLocation("ancientwarfare:textures/entity/npc/npc_default.png");
  levelingStats = new NpcLevelingStats(this);
  
  /**
   * shared AI behaviors
   */
  this.getNavigator().setBreakDoors(true);
  this.getNavigator().setAvoidsWater(true);
  this.tasks.addTask(0, new EntityAISwimming(this));
  this.tasks.addTask(0, new EntityAIRestrictOpenDoor(this));
  this.tasks.addTask(0, new EntityAIOpenDoor(this, true));
  //1--attack command should go here -- noop for non-combat NPCs, needs implemented in combat and hostile npc types
  this.tasks.addTask(2, new NpcAIFollowPlayer(this, 1.d, 10.f, 2.f)); 
  //3--civilian/courier 'flee' command goes here, or combat/hostile 'flee-on-low-health'
  //4--used by player-owned for upkeep tasks
  //5--used by player-owned for upkeep tasks
  
  //post-100 -- used by delayed shared tasks (stay near home, look at random stuff, wander)
  this.tasks.addTask(100, new EntityAIMoveTowardsRestriction(this, 0.6D)); 
  this.tasks.addTask(101, new EntityAIWatchClosest2(this, EntityPlayer.class, 3.0F, 1.0F));
  this.tasks.addTask(102, new EntityAIWander(this, 0.625D));
  this.tasks.addTask(103, new EntityAIWatchClosest(this, EntityLiving.class, 8.0F));
  
  //target tasks
//  this.targetTasks.addTask(1, new EntityAIHurtByTarget(this, false));
//  this.targetTasks.addTask(2, new EntityAINearestAttackableTarget(this, EntityPlayer.class, 0, true));
  }

public abstract void readAdditionalItemData(NBTTagCompound tag);

public abstract void writeAddtionalItemData(NBTTagCompound tag);

public abstract boolean isValidOrdersStack(ItemStack stack);

public abstract void onOrdersInventoryChanged();

@Override
public void swingItem()
  {
  super.swingItem();
  }

public NpcLevelingStats getLevelingStats()
  {
  return levelingStats;
  }

public ResourceLocation getDefaultTexture()
  {
  return defaultTexture==null ? baseDefaultTexture : defaultTexture;
  }

public ItemStack getItemToSpawn()
  {
  ItemStack stack = new ItemStack(AWNpcItemLoader.npcSpawner);
  stack.setTagInfo("npcType", new NBTTagString(AWEntityRegistry.getRegistryNameFor(this.getClass())));
  return stack;
  }

@Override
public abstract void writeSpawnData(ByteBuf buffer);

@Override
public abstract void readSpawnData(ByteBuf additionalData);

@Override
public void onUpdate()
  {
  worldObj.theProfiler.startSection("AWNpcTick");
  updateArmSwingProgress();
  super.onUpdate();
  worldObj.theProfiler.endSection();
  }

@Override
protected boolean canDespawn()
  {
  return false;
  }

@Override
protected boolean isAIEnabled()
  {
  return true;
  }

@Override
protected void applyEntityAttributes()
  {
  super.applyEntityAttributes();
  //TODO figure out how to dynamically reset attack damage attribute based on weapon equipped
  this.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20.d);
  this.getEntityAttribute(SharedMonsterAttributes.followRange).setBaseValue(40.0D);
  this.getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.325D);
  this.getAttributeMap().registerAttribute(SharedMonsterAttributes.attackDamage);
  this.getEntityAttribute(SharedMonsterAttributes.attackDamage).setBaseValue(3.0D);
  }

public int getFoodRemaining()
  {
  return 0;
  }

public BlockPosition getUpkeepPoint()
  {
  return null;
  }

public int getUpkeepDimensionId()
  {
  return 0;
  }

public boolean requiresUpkeep()
  {
  return false;
  }

@Override
public void setOwnerName(String name)
  {
  ownerName = name;
  }

@Override
public String getOwnerName()
  {
  return ownerName;
  }

@Override
public Team getTeam()
  {
  return worldObj.getScoreboard().getPlayersTeam(ownerName);
  }

public boolean isHostileTowards(Team team)
  {
  Team a = getTeam();
  if(a!=null && team!=null && a!=team){return true;}
  return false;
  }

public EntityLivingBase getFollowingEntity()
  {
  if(followingPlayerName==null){return null;}
  return worldObj.getPlayerEntityByName(followingPlayerName);
  }

public void setFollowingEntity(EntityLivingBase entity)
  {
  if(entity instanceof EntityPlayer)
    {
    this.followingPlayerName = entity.getCommandSenderName();        
    }
  }

@Override
public boolean allowLeashing()
  {
  return false;
  }

public void repackEntity(EntityPlayer player)
  {
  ItemStack item = this.getItemToSpawn();
  NBTTagCompound tag = new NBTTagCompound();
  writeAddtionalItemData(tag);
  item.setTagInfo("npcStoredData", tag);
  //TODO add to player inventory or drop into world
  //TODO add repack button to npc-gui
  }

@Override
public void readEntityFromNBT(NBTTagCompound tag)
  {
  super.readEntityFromNBT(tag);
  ownerName = tag.getString("owner");
  npcName = tag.getString("npcName");
  if(tag.hasKey("ordersItem")){ordersStack=ItemStack.loadItemStackFromNBT(tag.getCompoundTag("ordersItem"));}
  if(getHomePosition()!=null)
    {
    ChunkCoordinates cc = getHomePosition();
    int[] ccia = new int[]{cc.posX,cc.posY,cc.posZ,(int)func_110174_bM()};
    tag.setIntArray("home", ccia);
    }
  //TODO
  }

@Override
public void writeEntityToNBT(NBTTagCompound tag)
  {
  super.writeEntityToNBT(tag);
  tag.setString("owner", ownerName);
  tag.setString("npcName", npcName);
  if(ordersStack!=null){tag.setTag("ordersItem", ordersStack.writeToNBT(new NBTTagCompound()));}
  if(tag.hasKey("home"))
    {
    int[] ccia = tag.getIntArray("home");
    setHomeArea(ccia[0], ccia[1], ccia[2], ccia[3]);
    }
  //TODO
  }

public final void setTexture(ResourceLocation texture)
  {
  this.texture = texture;
  }

public final ResourceLocation getTexture()
  {  
  return texture==null ? getDefaultTexture() : texture;
  }

}
