package net.minecraft.world.level.block.state.properties;

import com.google.common.base.MoreObjects;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.state.StateHolder;

public abstract class Property<T extends Comparable<T>> implements ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.PropertyAccess<T> { // Paper - optimise blockstate property access
    private final Class<T> clazz;
    private final String name;
    @Nullable
    private Integer hashCode;
    private final Codec<T> codec = Codec.STRING
        .comapFlatMap(
            value -> this.getValue(value)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unable to read property: " + this + " with value: " + value)),
            this::getName
        );
    private final Codec<Property.Value<T>> valueCodec = this.codec.xmap(this::value, Property.Value::value);

    // Paper start - optimise blockstate property access
    private static final java.util.concurrent.atomic.AtomicInteger ID_GENERATOR = new java.util.concurrent.atomic.AtomicInteger();
    private final int id;
    private T[] byId;

    @Override
    public final int moonrise$getId() {
        return this.id;
    }

    @Override
    public final T moonrise$getById(final int id) {
        final T[] byId = this.byId;
        return id < 0 || id >= byId.length ? null : this.byId[id];
    }

    @Override
    public final void moonrise$setById(final T[] byId) {
        if (this.byId != null) {
            throw new IllegalStateException();
        }
        this.byId = byId;
    }

    @Override
    public abstract int moonrise$getIdFor(final T value);
    // Paper end - optimise blockstate property access

    protected Property(String name, Class<T> type) {
        this.clazz = type;
        this.name = name;
        this.id = ID_GENERATOR.getAndIncrement(); // Paper - optimise blockstate property access
    }

    public Property.Value<T> value(T value) {
        return new Property.Value<>(this, value);
    }

    public Property.Value<T> value(StateHolder<?, ?> state) {
        return new Property.Value<>(this, state.getValue(this));
    }

    public Stream<Property.Value<T>> getAllValues() {
        return this.getPossibleValues().stream().map(this::value);
    }

    public Codec<T> codec() {
        return this.codec;
    }

    public Codec<Property.Value<T>> valueCodec() {
        return this.valueCodec;
    }

    public String getName() {
        return this.name;
    }

    public Class<T> getValueClass() {
        return this.clazz;
    }

    public abstract List<T> getPossibleValues();

    public abstract String getName(T value);

    public abstract Optional<T> getValue(String name);

    public abstract int getInternalIndex(T value);

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", this.name).add("clazz", this.clazz).add("values", this.getPossibleValues()).toString();
    }

    @Override
    public boolean equals(Object object) {
        return this == object; // Paper - Perf: Optimize hashCode/equals
    }

    @Override
    public final int hashCode() {
        if (this.hashCode == null) {
            this.hashCode = this.generateHashCode();
        }

        return this.hashCode;
    }

    public int generateHashCode() {
        return 31 * this.clazz.hashCode() + this.name.hashCode();
    }

    public <U, S extends StateHolder<?, S>> DataResult<S> parseValue(DynamicOps<U> ops, S state, U input) {
        DataResult<T> dataResult = this.codec.parse(ops, input);
        return dataResult.<S>map(property -> state.setValue(this, property)).setPartial(state);
    }

    public static record Value<T extends Comparable<T>>(Property<T> property, T value) {
        public Value(Property<T> property, T value) {
            if (!property.getPossibleValues().contains(value)) {
                throw new IllegalArgumentException("Value " + value + " does not belong to property " + property);
            } else {
                this.property = property;
                this.value = value;
            }
        }

        @Override
        public String toString() {
            return this.property.getName() + "=" + this.property.getName(this.value);
        }
    }
}
