package net.minecraft.world.item;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class TridentItem extends Item implements ProjectileItem {

    public static final int THROW_THRESHOLD_TIME = 10;
    public static final float BASE_DAMAGE = 8.0F;
    public static final float PROJECTILE_SHOOT_POWER = 2.5F;

    public TridentItem(Item.Properties settings) {
        super(settings);
    }

    public static ItemAttributeModifiers createAttributes() {
        return ItemAttributeModifiers.builder().add(Attributes.ATTACK_DAMAGE, new AttributeModifier(TridentItem.BASE_ATTACK_DAMAGE_ID, 8.0D, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).add(Attributes.ATTACK_SPEED, new AttributeModifier(TridentItem.BASE_ATTACK_SPEED_ID, -2.9000000953674316D, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build();
    }

    public static Tool createToolProperties() {
        return new Tool(List.of(), 1.0F, 2);
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level world, BlockPos pos, Player miner) {
        return !miner.isCreative();
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.SPEAR;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return 72000;
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level world, LivingEntity user, int remainingUseTicks) {
        if (user instanceof Player entityhuman) {
            int j = this.getUseDuration(stack, user) - remainingUseTicks;

            if (j < 10) {
                return false;
            } else {
                float f = EnchantmentHelper.getTridentSpinAttackStrength(stack, entityhuman);

                if (f > 0.0F && !entityhuman.isInWaterOrRain()) {
                    return false;
                } else if (stack.nextDamageWillBreak()) {
                    return false;
                } else {
                    Holder<SoundEvent> holder = (Holder) EnchantmentHelper.pickHighestLevel(stack, EnchantmentEffectComponents.TRIDENT_SOUND).orElse(SoundEvents.TRIDENT_THROW);

                    entityhuman.awardStat(Stats.ITEM_USED.get(this));
                    if (world instanceof ServerLevel) {
                        ServerLevel worldserver = (ServerLevel) world;

                        // itemstack.hurtWithoutBreaking(1, entityhuman); // CraftBukkit - moved down
                        if (f == 0.0F) {
                            // Paper start - PlayerLaunchProjectileEvent
                            Projectile.Delayed<ThrownTrident> tridentDelayed = Projectile.spawnProjectileFromRotationDelayed(ThrownTrident::new, worldserver, stack, entityhuman, 0.0F, 2.5F, 1.0F);
                            // Paper start - PlayerLaunchProjectileEvent
                            com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) entityhuman.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack), (org.bukkit.entity.Projectile) tridentDelayed.projectile().getBukkitEntity());
                            if (!event.callEvent() || !tridentDelayed.attemptSpawn()) {
                                // CraftBukkit start
                            // Paper end - PlayerLaunchProjectileEvent
                                if (entityhuman instanceof net.minecraft.server.level.ServerPlayer) {
                                    ((net.minecraft.server.level.ServerPlayer) entityhuman).getBukkitEntity().updateInventory();
                                }
                                return false;
                            }
                            ThrownTrident entitythrowntrident = tridentDelayed.projectile(); // Paper - PlayerLaunchProjectileEvent
                            if (event.shouldConsume()) stack.hurtWithoutBreaking(1, entityhuman); // Paper - PlayerLaunchProjectileEvent
                            entitythrowntrident.pickupItemStack = stack.copy(); // SPIGOT-4511 update since damage call moved
                            // CraftBukkit end

                            if (entityhuman.hasInfiniteMaterials()) {
                                entitythrowntrident.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
                            } else if (event.shouldConsume()) { // Paper - PlayerLaunchProjectileEvent
                                entityhuman.getInventory().removeItem(stack);
                            }

                            world.playSound((Player) null, (Entity) entitythrowntrident, (SoundEvent) holder.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
                            return true;
                            // CraftBukkit start - SPIGOT-5458 also need in this branch :(
                        } else {
                            stack.hurtWithoutBreaking(1, entityhuman);
                            // CraftBukkkit end
                        }
                    }

                    if (f > 0.0F) {
                        float f1 = entityhuman.getYRot();
                        float f2 = entityhuman.getXRot();
                        float f3 = -Mth.sin(f1 * 0.017453292F) * Mth.cos(f2 * 0.017453292F);
                        float f4 = -Mth.sin(f2 * 0.017453292F);
                        float f5 = Mth.cos(f1 * 0.017453292F) * Mth.cos(f2 * 0.017453292F);
                        float f6 = Mth.sqrt(f3 * f3 + f4 * f4 + f5 * f5);

                        f3 *= f / f6;
                        f4 *= f / f6;
                        f5 *= f / f6;
                        org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerRiptideEvent(entityhuman, stack, f3, f4, f5); // CraftBukkit
                        entityhuman.push((double) f3, (double) f4, (double) f5);
                        entityhuman.startAutoSpinAttack(20, 8.0F, stack);
                        if (entityhuman.onGround()) {
                            float f7 = 1.1999999F;

                            entityhuman.move(MoverType.SELF, new Vec3(0.0D, 1.1999999284744263D, 0.0D));
                        }

                        world.playSound((Player) null, (Entity) entityhuman, (SoundEvent) holder.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        } else {
            return false;
        }
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack itemstack = user.getItemInHand(hand);

        if (itemstack.nextDamageWillBreak()) {
            return InteractionResult.FAIL;
        } else if (EnchantmentHelper.getTridentSpinAttackStrength(itemstack, user) > 0.0F && !user.isInWaterOrRain()) {
            return InteractionResult.FAIL;
        } else {
            user.startUsingItem(hand);
            return InteractionResult.CONSUME;
        }
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        return true;
    }

    @Override
    public void postHurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
    }

    @Override
    public Projectile asProjectile(Level world, Position pos, ItemStack stack, Direction direction) {
        ThrownTrident entitythrowntrident = new ThrownTrident(world, pos.x(), pos.y(), pos.z(), stack.copyWithCount(1));

        entitythrowntrident.pickup = AbstractArrow.Pickup.ALLOWED;
        return entitythrowntrident;
    }
}
