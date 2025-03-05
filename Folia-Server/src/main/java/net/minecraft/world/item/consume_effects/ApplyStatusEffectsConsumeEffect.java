package net.minecraft.world.item.consume_effects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Iterator;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
// CraftBukkit start
import org.bukkit.event.entity.EntityPotionEffectEvent;
// CraftBukkit end

public record ApplyStatusEffectsConsumeEffect(List<MobEffectInstance> effects, float probability) implements ConsumeEffect {

    public static final MapCodec<ApplyStatusEffectsConsumeEffect> CODEC = RecordCodecBuilder.mapCodec((instance) -> {
        return instance.group(MobEffectInstance.CODEC.listOf().fieldOf("effects").forGetter(ApplyStatusEffectsConsumeEffect::effects), Codec.floatRange(0.0F, 1.0F).optionalFieldOf("probability", 1.0F).forGetter(ApplyStatusEffectsConsumeEffect::probability)).apply(instance, ApplyStatusEffectsConsumeEffect::new);
    });
    public static final StreamCodec<RegistryFriendlyByteBuf, ApplyStatusEffectsConsumeEffect> STREAM_CODEC = StreamCodec.composite(MobEffectInstance.STREAM_CODEC.apply(ByteBufCodecs.list()), ApplyStatusEffectsConsumeEffect::effects, ByteBufCodecs.FLOAT, ApplyStatusEffectsConsumeEffect::probability, ApplyStatusEffectsConsumeEffect::new);

    public ApplyStatusEffectsConsumeEffect(MobEffectInstance effect, float probability) {
        this(List.of(effect), probability);
    }

    public ApplyStatusEffectsConsumeEffect(List<MobEffectInstance> effects) {
        this(effects, 1.0F);
    }

    public ApplyStatusEffectsConsumeEffect(MobEffectInstance effect) {
        this(effect, 1.0F);
    }

    @Override
    public ConsumeEffect.Type<ApplyStatusEffectsConsumeEffect> getType() {
        return ConsumeEffect.Type.APPLY_EFFECTS;
    }

    @Override
    public boolean apply(Level world, ItemStack itemstack, LivingEntity entityliving, EntityPotionEffectEvent.Cause cause) { // CraftBukkit
        if (entityliving.getRandom().nextFloat() >= this.probability) {
            return false;
        } else {
            boolean flag = false;
            Iterator iterator = this.effects.iterator();

            while (iterator.hasNext()) {
                MobEffectInstance mobeffect = (MobEffectInstance) iterator.next();

                if (entityliving.addEffect(new MobEffectInstance(mobeffect), cause)) { // CraftBukkit
                    flag = true;
                }
            }

            return flag;
        }
    }
}
