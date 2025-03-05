package net.minecraft.world.level.chunk;

import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import org.apache.commons.lang3.Validate;

public class SingleValuePalette<T> implements Palette<T>, ca.spottedleaf.moonrise.patches.fast_palette.FastPalette<T> { // Paper - optimise palette reads
    private final IdMap<T> registry;
    @Nullable
    private T value;
    private final PaletteResize<T> resizeHandler;

    // Paper start - optimise palette reads
    private T[] rawPalette;

    @Override
    public final T[] moonrise$getRawPalette(final ca.spottedleaf.moonrise.patches.fast_palette.FastPaletteData<T> container) {
        if (this.rawPalette != null) {
            return this.rawPalette;
        }
        return this.rawPalette = (T[])new Object[] { this.value };
    }
    // Paper end - optimise palette reads

    public SingleValuePalette(IdMap<T> idList, PaletteResize<T> listener, List<T> entries) {
        this.registry = idList;
        this.resizeHandler = listener;
        if (entries.size() > 0) {
            Validate.isTrue(entries.size() <= 1, "Can't initialize SingleValuePalette with %d values.", (long)entries.size());
            this.value = entries.get(0);
        }
    }

    public static <A> Palette<A> create(int bitSize, IdMap<A> idList, PaletteResize<A> listener, List<A> entries) {
        return new SingleValuePalette<>(idList, listener, entries);
    }

    @Override
    public int idFor(T object) {
        if (this.value != null && this.value != object) {
            return this.resizeHandler.onResize(1, object);
        } else {
            this.value = object;
            // Paper start - optimise palette reads
            if (this.rawPalette != null) {
                this.rawPalette[0] = object;
            }
            // Paper end - optimise palette reads
            return 0;
        }
    }

    @Override
    public boolean maybeHas(Predicate<T> predicate) {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            return predicate.test(this.value);
        }
    }

    @Override
    public T valueFor(int id) {
        if (this.value != null && id == 0) {
            return this.value;
        } else {
            throw new IllegalStateException("Missing Palette entry for id " + id + ".");
        }
    }

    @Override
    public void read(FriendlyByteBuf buf) {
        this.value = this.registry.byIdOrThrow(buf.readVarInt());
        // Paper start - optimise palette reads
        if (this.rawPalette != null) {
            this.rawPalette[0] = this.value;
        }
        // Paper end - optimise palette reads
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            buf.writeVarInt(this.registry.getId(this.value));
        }
    }

    @Override
    public int getSerializedSize() {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            return VarInt.getByteSize(this.registry.getId(this.value));
        }
    }

    @Override
    public int getSize() {
        return 1;
    }

    @Override
    public Palette<T> copy(PaletteResize<T> resizeListener) {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            return this;
        }
    }
}
