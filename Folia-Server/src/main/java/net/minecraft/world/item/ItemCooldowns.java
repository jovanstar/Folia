package net.minecraft.world.item;

import com.google.common.collect.Maps;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.component.UseCooldown;

public class ItemCooldowns {
    public final Map<ResourceLocation, ItemCooldowns.CooldownInstance> cooldowns = Maps.newHashMap();
    public int tickCount;

    public boolean isOnCooldown(ItemStack stack) {
        return this.getCooldownPercent(stack, 0.0F) > 0.0F;
    }

    public float getCooldownPercent(ItemStack stack, float tickDelta) {
        ResourceLocation resourceLocation = this.getCooldownGroup(stack);
        ItemCooldowns.CooldownInstance cooldownInstance = this.cooldowns.get(resourceLocation);
        if (cooldownInstance != null) {
            float f = (float)(cooldownInstance.endTime - cooldownInstance.startTime);
            float g = (float)cooldownInstance.endTime - ((float)this.tickCount + tickDelta);
            return Mth.clamp(g / f, 0.0F, 1.0F);
        } else {
            return 0.0F;
        }
    }

    public void tick() {
        this.tickCount++;
        if (!this.cooldowns.isEmpty()) {
            Iterator<Entry<ResourceLocation, ItemCooldowns.CooldownInstance>> iterator = this.cooldowns.entrySet().iterator();

            while (iterator.hasNext()) {
                Entry<ResourceLocation, ItemCooldowns.CooldownInstance> entry = iterator.next();
                if (entry.getValue().endTime <= this.tickCount) {
                    iterator.remove();
                    this.onCooldownEnded(entry.getKey());
                }
            }
        }
    }

    public ResourceLocation getCooldownGroup(ItemStack stack) {
        UseCooldown useCooldown = stack.get(DataComponents.USE_COOLDOWN);
        ResourceLocation resourceLocation = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return useCooldown == null ? resourceLocation : useCooldown.cooldownGroup().orElse(resourceLocation);
    }

    public void addCooldown(ItemStack stack, int duration) {
        this.addCooldown(this.getCooldownGroup(stack), duration);
    }

    public void addCooldown(ResourceLocation groupId, int duration) {
        // Paper start - Item cooldown events
        this.addCooldown(groupId, duration, true);
    }

    public void addCooldown(ResourceLocation groupId, int duration, boolean callEvent) {
        // Event called in server override
        // Paper end - Item cooldown events
        this.cooldowns.put(groupId, new ItemCooldowns.CooldownInstance(this.tickCount, this.tickCount + duration));
        this.onCooldownStarted(groupId, duration);
    }

    public void removeCooldown(ResourceLocation groupId) {
        this.cooldowns.remove(groupId);
        this.onCooldownEnded(groupId);
    }

    protected void onCooldownStarted(ResourceLocation groupId, int duration) {
    }

    protected void onCooldownEnded(ResourceLocation groupId) {
    }

    public static record CooldownInstance(int startTime, int endTime) {
    }
}
