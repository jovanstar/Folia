package net.minecraft.world.item.consume_effects;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Iterator;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
// CraftBukkit start
import org.bukkit.event.entity.EntityPotionEffectEvent;
// CraftBukkit end

public record RemoveStatusEffectsConsumeEffect(HolderSet<MobEffect> effects) implements ConsumeEffect {

    public static final MapCodec<RemoveStatusEffectsConsumeEffect> CODEC = RecordCodecBuilder.mapCodec((instance) -> {
        return instance.group(RegistryCodecs.homogeneousList(Registries.MOB_EFFECT).fieldOf("effects").forGetter(RemoveStatusEffectsConsumeEffect::effects)).apply(instance, RemoveStatusEffectsConsumeEffect::new);
    });
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveStatusEffectsConsumeEffect> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.holderSet(Registries.MOB_EFFECT), RemoveStatusEffectsConsumeEffect::effects, RemoveStatusEffectsConsumeEffect::new);

    public RemoveStatusEffectsConsumeEffect(Holder<MobEffect> effect) {
        this((HolderSet) HolderSet.direct(effect));
    }

    @Override
    public ConsumeEffect.Type<RemoveStatusEffectsConsumeEffect> getType() {
        return ConsumeEffect.Type.REMOVE_EFFECTS;
    }

    @Override
    public boolean apply(Level world, ItemStack itemstack, LivingEntity entityliving, EntityPotionEffectEvent.Cause cause) { // CraftBukkit
        boolean flag = false;
        Iterator iterator = this.effects.iterator();

        while (iterator.hasNext()) {
            Holder<MobEffect> holder = (Holder) iterator.next();

            if (entityliving.removeEffect(holder, cause)) { // CraftBukkit
                flag = true;
            }
        }

        return flag;
    }
}
