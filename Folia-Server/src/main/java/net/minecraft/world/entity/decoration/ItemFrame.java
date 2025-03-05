package net.minecraft.world.entity.decoration;

import javax.annotation.Nullable;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent; // Paper - Add PlayerItemFrameChangeEvent
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;

public class ItemFrame extends HangingEntity {

    public static final EntityDataAccessor<ItemStack> DATA_ITEM = SynchedEntityData.defineId(ItemFrame.class, EntityDataSerializers.ITEM_STACK);
    public static final EntityDataAccessor<Integer> DATA_ROTATION = SynchedEntityData.defineId(ItemFrame.class, EntityDataSerializers.INT);
    public static final int NUM_ROTATIONS = 8;
    private static final float DEPTH = 0.0625F;
    private static final float WIDTH = 0.75F;
    private static final float HEIGHT = 0.75F;
    public float dropChance;
    public boolean fixed;
    public @Nullable MapId cachedMapId; // Paper - Perf: Cache map ids on item frames

    public ItemFrame(EntityType<? extends ItemFrame> type, Level world) {
        super(type, world);
        this.dropChance = 1.0F;
    }

    public ItemFrame(Level world, BlockPos pos, Direction facing) {
        this(EntityType.ITEM_FRAME, world, pos, facing);
    }

    public ItemFrame(EntityType<? extends ItemFrame> type, Level world, BlockPos pos, Direction facing) {
        super(type, world, pos);
        this.dropChance = 1.0F;
        this.setDirection(facing);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ItemFrame.DATA_ITEM, ItemStack.EMPTY);
        builder.define(ItemFrame.DATA_ROTATION, 0);
    }

    @Override
    public void setDirection(Direction facing) {
        Validate.notNull(facing);
        this.direction = facing;
        if (facing.getAxis().isHorizontal()) {
            this.setXRot(0.0F);
            this.setYRot((float) (this.direction.get2DDataValue() * 90));
        } else {
            this.setXRot((float) (-90 * facing.getAxisDirection().getStep()));
            this.setYRot(0.0F);
        }

        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
        this.recalculateBoundingBox();
    }

    @Override
    protected AABB calculateBoundingBox(BlockPos pos, Direction side) {
        // CraftBukkit start - break out BB calc into own method
        return ItemFrame.calculateBoundingBoxStatic(pos, side);
    }

    public static AABB calculateBoundingBoxStatic(BlockPos blockposition, Direction enumdirection) {
        // CraftBukkit end
        float f = 0.46875F;
        Vec3 vec3d = Vec3.atCenterOf(blockposition).relative(enumdirection, -0.46875D);
        Direction.Axis enumdirection_enumaxis = enumdirection.getAxis();
        double d0 = enumdirection_enumaxis == Direction.Axis.X ? 0.0625D : 0.75D;
        double d1 = enumdirection_enumaxis == Direction.Axis.Y ? 0.0625D : 0.75D;
        double d2 = enumdirection_enumaxis == Direction.Axis.Z ? 0.0625D : 0.75D;

        return AABB.ofSize(vec3d, d0, d1, d2);
    }

    @Override
    public boolean survives() {
        if (this.fixed) {
            return true;
        } else if (!this.level().noCollision((Entity) this)) {
            return false;
        } else {
            BlockState iblockdata = this.level().getBlockState(this.pos.relative(this.direction.getOpposite()));

            return !iblockdata.isSolid() && (!this.direction.getAxis().isHorizontal() || !DiodeBlock.isDiode(iblockdata)) ? false : this.level().getEntities((Entity) this, this.getBoundingBox(), ItemFrame.HANGING_ENTITY).isEmpty();
        }
    }

    @Override
    public void move(MoverType type, Vec3 movement) {
        if (!this.fixed) {
            super.move(type, movement);
        }

    }

    @Override
    public void push(double deltaX, double deltaY, double deltaZ, @Nullable Entity pushingEntity) { // Paper - add push source entity param
        if (!this.fixed) {
            super.push(deltaX, deltaY, deltaZ, pushingEntity); // Paper - add push source entity param
        }

    }

    @Override
    public void kill(ServerLevel world) {
        this.removeFramedMap(this.getItem());
        super.kill(world);
    }

    private boolean shouldDamageDropItem(DamageSource damageSource) {
        return !damageSource.is(DamageTypeTags.IS_EXPLOSION) && !this.getItem().isEmpty();
    }

    private static boolean canHurtWhenFixed(DamageSource damageSource) {
        return damageSource.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || damageSource.isCreativePlayer();
    }

    @Override
    public boolean hurtClient(DamageSource source) {
        return this.fixed && !ItemFrame.canHurtWhenFixed(source) ? false : !this.isInvulnerableToBase(source);
    }

    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        if (!this.fixed) {
            if (this.isInvulnerableToBase(source)) {
                return false;
            } else if (this.shouldDamageDropItem(source)) {
                // CraftBukkit start - fire EntityDamageEvent
                if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, amount, false) || this.isRemoved()) {
                    return true;
                }
                // CraftBukkit end
                // Paper start - Add PlayerItemFrameChangeEvent
                if (source.getEntity() instanceof Player player) {
                    var event = new PlayerItemFrameChangeEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.ItemFrame) this.getBukkitEntity(), this.getItem().asBukkitCopy(), PlayerItemFrameChangeEvent.ItemFrameChangeAction.REMOVE);
                    if (!event.callEvent()) return true; // return true here because you aren't cancelling the damage, just the change
                    this.setItem(ItemStack.fromBukkitCopy(event.getItemStack()), false);
                }
                // Paper end - Add PlayerItemFrameChangeEvent
                this.dropItem(world, source.getEntity(), false);
                this.gameEvent(GameEvent.BLOCK_CHANGE, source.getEntity());
                this.playSound(this.getRemoveItemSound(), 1.0F, 1.0F);
                return true;
            } else {
                return super.hurtServer(world, source, amount);
            }
        } else {
            return ItemFrame.canHurtWhenFixed(source) && super.hurtServer(world, source, amount);
        }
    }

    public SoundEvent getRemoveItemSound() {
        return SoundEvents.ITEM_FRAME_REMOVE_ITEM;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double d1 = 16.0D;

        d1 *= 64.0D * getViewScale();
        return distance < d1 * d1;
    }

    @Override
    public void dropItem(ServerLevel world, @Nullable Entity breaker) {
        this.playSound(this.getBreakSound(), 1.0F, 1.0F);
        this.dropItem(world, breaker, true);
        this.gameEvent(GameEvent.BLOCK_CHANGE, breaker);
    }

    public SoundEvent getBreakSound() {
        return SoundEvents.ITEM_FRAME_BREAK;
    }

    @Override
    public void playPlacementSound() {
        this.playSound(this.getPlaceSound(), 1.0F, 1.0F);
    }

    public SoundEvent getPlaceSound() {
        return SoundEvents.ITEM_FRAME_PLACE;
    }

    private void dropItem(ServerLevel world, @Nullable Entity entity, boolean dropSelf) {
        if (!this.fixed) {
            ItemStack itemstack = this.getItem();

            this.setItem(ItemStack.EMPTY);
            if (!world.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
                if (entity == null) {
                    this.removeFramedMap(itemstack);
                }

            } else {
                if (entity instanceof Player) {
                    Player entityhuman = (Player) entity;

                    if (entityhuman.hasInfiniteMaterials()) {
                        this.removeFramedMap(itemstack);
                        return;
                    }
                }

                if (dropSelf) {
                    this.spawnAtLocation(world, this.getFrameItemStack());
                }

                if (!itemstack.isEmpty()) {
                    itemstack = itemstack.copy();
                    this.removeFramedMap(itemstack);
                    if (this.random.nextFloat() < this.dropChance) {
                        this.spawnAtLocation(world, itemstack);
                    }
                }

            }
        }
    }

    private void removeFramedMap(ItemStack stack) {
        MapId mapid = this.getFramedMapId(stack);

        if (mapid != null) {
            MapItemSavedData worldmap = MapItem.getSavedData(mapid, this.level());

            if (worldmap != null) {
                synchronized (worldmap) { // Folia - make map data thread-safe
                worldmap.removedFromFrame(this.pos, this.getId());
                } // Folia - make map data thread-safe
            }
        }

        stack.setEntityRepresentation((Entity) null);
    }

    public ItemStack getItem() {
        return (ItemStack) this.getEntityData().get(ItemFrame.DATA_ITEM);
    }

    // Paper start - Fix MC-123848 (spawn item frame drops above block)
    @Nullable
    @Override
    public net.minecraft.world.entity.item.ItemEntity spawnAtLocation(ServerLevel serverLevel, ItemStack stack) {
        return this.spawnAtLocation(serverLevel, stack, this.getDirection() == Direction.DOWN ? -0.6F : 0.0F);
    }
    // Paper end

    @Nullable
    public MapId getFramedMapId(ItemStack stack) {
        return (MapId) stack.get(DataComponents.MAP_ID);
    }

    public boolean hasFramedMap() {
        return this.getItem().has(DataComponents.MAP_ID);
    }

    public void setItem(ItemStack stack) {
        this.setItem(stack, true);
    }

    public void setItem(ItemStack value, boolean update) {
        // CraftBukkit start
        this.setItem(value, update, true);
    }

    public void setItem(ItemStack itemstack, boolean flag, boolean playSound) {
        // CraftBukkit end
        if (!itemstack.isEmpty()) {
            itemstack = itemstack.copyWithCount(1);
        }

        this.onItemChanged(itemstack);
        this.getEntityData().set(ItemFrame.DATA_ITEM, itemstack);
        if (!itemstack.isEmpty() && flag && playSound) { // CraftBukkit // Paper - only play sound when update flag is set
            this.playSound(this.getAddItemSound(), 1.0F, 1.0F);
        }

        if (flag && this.pos != null) {
            this.level().updateNeighbourForOutputSignal(this.pos, Blocks.AIR);
        }

    }

    public SoundEvent getAddItemSound() {
        return SoundEvents.ITEM_FRAME_ADD_ITEM;
    }

    @Override
    public SlotAccess getSlot(int mappedIndex) {
        return mappedIndex == 0 ? SlotAccess.of(this::getItem, this::setItem) : super.getSlot(mappedIndex);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        if (data.equals(ItemFrame.DATA_ITEM)) {
            this.onItemChanged(this.getItem());
        }

    }

    private void onItemChanged(ItemStack stack) {
        this.cachedMapId = stack.getComponents().get(net.minecraft.core.component.DataComponents.MAP_ID); // Paper - Perf: Cache map ids on item frames
        if (!stack.isEmpty() && stack.getFrame() != this) {
            stack.setEntityRepresentation(this);
        }

        this.recalculateBoundingBox();
    }

    public int getRotation() {
        return (Integer) this.getEntityData().get(ItemFrame.DATA_ROTATION);
    }

    public void setRotation(int value) {
        this.setRotation(value, true);
    }

    private void setRotation(int value, boolean updateComparators) {
        this.getEntityData().set(ItemFrame.DATA_ROTATION, value % 8);
        if (updateComparators && this.pos != null) {
            this.level().updateNeighbourForOutputSignal(this.pos, Blocks.AIR);
        }

    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        if (!this.getItem().isEmpty()) {
            nbt.put("Item", this.getItem().save(this.registryAccess()));
            nbt.putByte("ItemRotation", (byte) this.getRotation());
            nbt.putFloat("ItemDropChance", this.dropChance);
        }

        nbt.putByte("Facing", (byte) this.direction.get3DDataValue());
        nbt.putBoolean("Invisible", this.isInvisible());
        nbt.putBoolean("Fixed", this.fixed);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        ItemStack itemstack;

        if (nbt.contains("Item", 10)) {
            CompoundTag nbttagcompound1 = nbt.getCompound("Item");

            itemstack = (ItemStack) ItemStack.parse(this.registryAccess(), nbttagcompound1).orElse(ItemStack.EMPTY);
        } else {
            itemstack = ItemStack.EMPTY;
        }

        ItemStack itemstack1 = this.getItem();

        if (!itemstack1.isEmpty() && !ItemStack.matches(itemstack, itemstack1)) {
            this.removeFramedMap(itemstack1);
        }

        this.setItem(itemstack, false);
        if (!itemstack.isEmpty()) {
            this.setRotation(nbt.getByte("ItemRotation"), false);
            if (nbt.contains("ItemDropChance", 99)) {
                this.dropChance = nbt.getFloat("ItemDropChance");
            }
        }

        this.setDirection(Direction.from3DDataValue(nbt.getByte("Facing")));
        this.setInvisible(nbt.getBoolean("Invisible"));
        this.fixed = nbt.getBoolean("Fixed");
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        boolean flag = !this.getItem().isEmpty();
        boolean flag1 = !itemstack.isEmpty();

        if (this.fixed) {
            return InteractionResult.PASS;
        } else if (!player.level().isClientSide) {
            if (!flag) {
                if (flag1 && !this.isRemoved()) {
                    MapItemSavedData worldmap = MapItem.getSavedData(itemstack, this.level());

                    if (worldmap != null && worldmap.isTrackedCountOverLimit(256)) {
                        return InteractionResult.FAIL;
                    } else {
                        // Paper start - Add PlayerItemFrameChangeEvent
                        PlayerItemFrameChangeEvent event = new PlayerItemFrameChangeEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.ItemFrame) this.getBukkitEntity(), itemstack.asBukkitCopy(), PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE);
                        if (!event.callEvent()) {
                            return InteractionResult.FAIL;
                        }
                        this.setItem(ItemStack.fromBukkitCopy(event.getItemStack()));
                        // Paper end - Add PlayerItemFrameChangeEvent
                        this.gameEvent(GameEvent.BLOCK_CHANGE, player);
                        itemstack.consume(1, player);
                        return InteractionResult.SUCCESS;
                    }
                } else {
                    return InteractionResult.PASS;
                }
            } else {
                // Paper start - Add PlayerItemFrameChangeEvent
                PlayerItemFrameChangeEvent event = new PlayerItemFrameChangeEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.ItemFrame) this.getBukkitEntity(), this.getItem().asBukkitCopy(), PlayerItemFrameChangeEvent.ItemFrameChangeAction.ROTATE);
                if (!event.callEvent()) {
                    return InteractionResult.FAIL;
                }
                setItem(ItemStack.fromBukkitCopy(event.getItemStack()), false, false);
                // Paper end - Add PlayerItemFrameChangeEvent
                this.playSound(this.getRotateItemSound(), 1.0F, 1.0F);
                this.setRotation(this.getRotation() + 1);
                this.gameEvent(GameEvent.BLOCK_CHANGE, player);
                return InteractionResult.SUCCESS;
            }
        } else {
            return (InteractionResult) (!flag && !flag1 ? InteractionResult.PASS : InteractionResult.SUCCESS);
        }
    }

    public SoundEvent getRotateItemSound() {
        return SoundEvents.ITEM_FRAME_ROTATE_ITEM;
    }

    public int getAnalogOutput() {
        return this.getItem().isEmpty() ? 0 : this.getRotation() % 8 + 1;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entityTrackerEntry) {
        return new ClientboundAddEntityPacket(this, this.direction.get3DDataValue(), this.getPos());
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        this.setDirection(Direction.from3DDataValue(packet.getData()));
    }

    @Override
    public ItemStack getPickResult() {
        ItemStack itemstack = this.getItem();

        return itemstack.isEmpty() ? this.getFrameItemStack() : itemstack.copy();
    }

    protected ItemStack getFrameItemStack() {
        return new ItemStack(Items.ITEM_FRAME);
    }

    @Override
    public float getVisualRotationYInDegrees() {
        Direction enumdirection = this.getDirection();
        int i = enumdirection.getAxis().isVertical() ? 90 * enumdirection.getAxisDirection().getStep() : 0;

        return (float) Mth.wrapDegrees(180 + enumdirection.get2DDataValue() * 90 + this.getRotation() * 45 + i);
    }
}
