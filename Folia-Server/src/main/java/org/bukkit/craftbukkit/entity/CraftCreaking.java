package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.monster.creaking.Creaking;
import org.bukkit.craftbukkit.CraftServer;

public class CraftCreaking extends CraftMonster implements org.bukkit.entity.Creaking {

    public CraftCreaking(CraftServer server, Creaking entity) {
        super(server, entity);
    }

    // Folia start - region threading
    @Override
    public Creaking getHandleRaw() {
        return (Creaking)this.entity;
    }
    // Folia end - region threading

    @Override
    public Creaking getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (Creaking) this.entity;
    }

    @Override
    public String toString() {
        return "CraftCreaking";
    }
}
