package net.minecraft.world.entity;

import javax.annotation.Nullable;
import net.minecraft.world.scores.PlayerTeam;

public record ConversionParams(ConversionType type, boolean keepEquipment, boolean preserveCanPickUpLoot, @Nullable PlayerTeam team) {
    public static ConversionParams single(Mob entity, boolean keepEquipment, boolean preserveCanPickUpLoot) {
        return new ConversionParams(ConversionType.SINGLE, keepEquipment, preserveCanPickUpLoot, entity.getTeam());
    }

    @FunctionalInterface
    public interface AfterConversion<T extends Mob> {
        void finalizeConversion(T convertedEntity);
    }

    // Paper start - entity zap event - allow conversion to be cancelled during finalization
    @FunctionalInterface
    public interface CancellingAfterConversion<T extends Mob> {
        boolean finalizeConversionOrCancel(final T convertedEntity);
    }
    // Paper start - entity zap event - allow conversion to be cancelled during finalization
}
