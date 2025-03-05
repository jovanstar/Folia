package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import org.bukkit.craftbukkit.CraftLootTable;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;

public abstract class CraftMinecartContainer extends CraftMinecart implements com.destroystokyo.paper.loottable.PaperLootableEntityInventory { // Paper

    public CraftMinecartContainer(CraftServer server, AbstractMinecart entity) {
        super(server, entity);
    }

    // Folia start - region threading
    @Override
    public AbstractMinecartContainer getHandleRaw() {
        return (AbstractMinecartContainer)this.entity;
    }
    // Folia end - region threading

    @Override
    public AbstractMinecartContainer getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (AbstractMinecartContainer) this.entity;
    }

    // Paper - moved loot table logic to PaperLootableEntityInventory
}
