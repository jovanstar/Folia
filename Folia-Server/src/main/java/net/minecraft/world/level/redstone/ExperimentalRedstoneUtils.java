package net.minecraft.world.level.redstone;

import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;

public class ExperimentalRedstoneUtils {
    @Nullable
    public static Orientation initialOrientation(Level world, @Nullable Direction up, @Nullable Direction front) {
        if (world.enabledFeatures().contains(FeatureFlags.REDSTONE_EXPERIMENTS)) {
            Orientation orientation = Orientation.random(world.random).withSideBias(Orientation.SideBias.LEFT);
            if (front != null) {
                orientation = orientation.withUp(front);
            }

            if (up != null) {
                orientation = orientation.withFront(up);
            }
            // Paper start - Optimize redstone (Alternate Current) - use default front instead of random
            else if (world.paperConfig().misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT) {
                orientation = orientation.withFront(Direction.WEST);
            }
            // Paper end - Optimize redstone (Alternate Current)

            return orientation;
        } else {
            return null;
        }
    }

    @Nullable
    public static Orientation withFront(@Nullable Orientation orientation, Direction direction) {
        return orientation == null ? null : orientation.withFront(direction);
    }
}
