package net.minecraft.world.entity.player;

import com.google.common.annotations.VisibleForTesting;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.ObjectIterable;
import it.unimi.dsi.fastutil.objects.Reference2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap.Entry;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import javax.annotation.Nullable;

public class StackedContents<T> {
    public final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<T> amounts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>(); // Paper - Improve exact choice recipe ingredients (don't use "reference" map)

    boolean hasAtLeast(T input, int minimum) {
        return this.amounts.getInt(input) >= minimum;
    }

    void take(T input, int count) {
        int i = this.amounts.addTo(input, -count);
        if (i < count) {
            throw new IllegalStateException("Took " + count + " items, but only had " + i);
        }
    }

    void put(T input, int count) {
        this.amounts.addTo(input, count);
    }

    public boolean tryPick(List<? extends StackedContents.IngredientInfo<T>> ingredients, int quantity, @Nullable StackedContents.Output<T> itemCallback) {
        return new StackedContents.RecipePicker(ingredients).tryPick(quantity, itemCallback);
    }

    public int tryPickAll(List<? extends StackedContents.IngredientInfo<T>> ingredients, int max, @Nullable StackedContents.Output<T> itemCallback) {
        return new StackedContents.RecipePicker(ingredients).tryPickAll(max, itemCallback);
    }

    public void clear() {
        this.amounts.clear();
    }

    public void account(T input, int count) {
        this.put(input, count);
    }

    List<T> getUniqueAvailableIngredientItems(Iterable<? extends StackedContents.IngredientInfo<T>> ingredients) {
        List<T> list = new ArrayList<>();

        for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<T> entry : it.unimi.dsi.fastutil.objects.Object2IntMaps.fastIterable(this.amounts)) { // Paper - Improve exact choice recipe ingredients (don't use "reference" map)
            if (entry.getIntValue() > 0 && anyIngredientMatches(ingredients, entry.getKey())) {
                list.add(entry.getKey());
            }
        }

        return list;
    }

    private static <T> boolean anyIngredientMatches(Iterable<? extends StackedContents.IngredientInfo<T>> ingredients, T item) {
        for (StackedContents.IngredientInfo<T> ingredientInfo : ingredients) {
            if (ingredientInfo.acceptsItem(item)) {
                return true;
            }
        }

        return false;
    }

    @VisibleForTesting
    public int getResultUpperBound(List<? extends StackedContents.IngredientInfo<T>> ingredients) {
        int i = Integer.MAX_VALUE;
        ObjectIterable<it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<T>> objectIterable = it.unimi.dsi.fastutil.objects.Object2IntMaps.fastIterable(this.amounts); // Paper - Improve exact choice recipe ingredients (don't use "reference" map)

        label31:
        for (StackedContents.IngredientInfo<T> ingredientInfo : ingredients) {
            int j = 0;

            for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<T> entry : objectIterable) { // Paper - Improve exact choice recipe ingredients (don't use "reference" map)
                int k = entry.getIntValue();
                if (k > j) {
                    if (ingredientInfo.acceptsItem(entry.getKey())) {
                        j = k;
                    }

                    if (j >= i) {
                        continue label31;
                    }
                }
            }

            i = j;
            if (j == 0) {
                break;
            }
        }

        return i;
    }

    @FunctionalInterface
    public interface IngredientInfo<T> {
        boolean acceptsItem(T entry);
    }

    @FunctionalInterface
    public interface Output<T> {
        void accept(T item);
    }

    class RecipePicker {
        private final List<? extends StackedContents.IngredientInfo<T>> ingredients;
        private final int ingredientCount;
        private final List<T> items;
        private final int itemCount;
        private final BitSet data;
        private final IntList path = new IntArrayList();

        public RecipePicker(final List<? extends StackedContents.IngredientInfo<T>> ingredients) {
            this.ingredients = ingredients;
            this.ingredientCount = ingredients.size();
            this.items = StackedContents.this.getUniqueAvailableIngredientItems(ingredients);
            this.itemCount = this.items.size();
            this.data = new BitSet(
                this.visitedIngredientCount() + this.visitedItemCount() + this.satisfiedCount() + this.connectionCount() + this.residualCount()
            );
            this.setInitialConnections();
        }

        private void setInitialConnections() {
            for (int i = 0; i < this.ingredientCount; i++) {
                StackedContents.IngredientInfo<T> ingredientInfo = (StackedContents.IngredientInfo<T>)this.ingredients.get(i);

                for (int j = 0; j < this.itemCount; j++) {
                    if (ingredientInfo.acceptsItem(this.items.get(j))) {
                        this.setConnection(j, i);
                    }
                }
            }
        }

        public boolean tryPick(int quantity, @Nullable StackedContents.Output<T> itemCallback) {
            if (quantity <= 0) {
                return true;
            } else {
                int i = 0;

                while (true) {
                    IntList intList = this.tryAssigningNewItem(quantity);
                    if (intList == null) {
                        boolean bl = i == this.ingredientCount;
                        boolean bl2 = bl && itemCallback != null;
                        this.clearAllVisited();
                        this.clearSatisfied();

                        for (int q = 0; q < this.ingredientCount; q++) {
                            for (int r = 0; r < this.itemCount; r++) {
                                if (this.isAssigned(r, q)) {
                                    this.unassign(r, q);
                                    StackedContents.this.put(this.items.get(r), quantity);
                                    if (bl2) {
                                        itemCallback.accept(this.items.get(r));
                                    }
                                    break;
                                }
                            }
                        }

                        assert this.data.get(this.residualOffset(), this.residualOffset() + this.residualCount()).isEmpty();

                        return bl;
                    }

                    int j = intList.getInt(0);
                    StackedContents.this.take(this.items.get(j), quantity);
                    int k = intList.size() - 1;
                    this.setSatisfied(intList.getInt(k));
                    i++;

                    for (int l = 0; l < intList.size() - 1; l++) {
                        if (isPathIndexItem(l)) {
                            int m = intList.getInt(l);
                            int n = intList.getInt(l + 1);
                            this.assign(m, n);
                        } else {
                            int o = intList.getInt(l + 1);
                            int p = intList.getInt(l);
                            this.unassign(o, p);
                        }
                    }
                }
            }
        }

        private static boolean isPathIndexItem(int index) {
            return (index & 1) == 0;
        }

        @Nullable
        private IntList tryAssigningNewItem(int min) {
            this.clearAllVisited();

            for (int i = 0; i < this.itemCount; i++) {
                if (StackedContents.this.hasAtLeast(this.items.get(i), min)) {
                    IntList intList = this.findNewItemAssignmentPath(i);
                    if (intList != null) {
                        return intList;
                    }
                }
            }

            return null;
        }

        @Nullable
        private IntList findNewItemAssignmentPath(int itemIndex) {
            this.path.clear();
            this.visitItem(itemIndex);
            this.path.add(itemIndex);

            while (!this.path.isEmpty()) {
                int i = this.path.size();
                if (isPathIndexItem(i - 1)) {
                    int j = this.path.getInt(i - 1);

                    for (int k = 0; k < this.ingredientCount; k++) {
                        if (!this.hasVisitedIngredient(k) && this.hasConnection(j, k) && !this.isAssigned(j, k)) {
                            this.visitIngredient(k);
                            this.path.add(k);
                            break;
                        }
                    }
                } else {
                    int l = this.path.getInt(i - 1);
                    if (!this.isSatisfied(l)) {
                        return this.path;
                    }

                    for (int m = 0; m < this.itemCount; m++) {
                        if (!this.hasVisitedItem(m) && this.isAssigned(m, l)) {
                            assert this.hasConnection(m, l);

                            this.visitItem(m);
                            this.path.add(m);
                            break;
                        }
                    }
                }

                int n = this.path.size();
                if (n == i) {
                    this.path.removeInt(n - 1);
                }
            }

            return null;
        }

        private int visitedIngredientOffset() {
            return 0;
        }

        private int visitedIngredientCount() {
            return this.ingredientCount;
        }

        private int visitedItemOffset() {
            return this.visitedIngredientOffset() + this.visitedIngredientCount();
        }

        private int visitedItemCount() {
            return this.itemCount;
        }

        private int satisfiedOffset() {
            return this.visitedItemOffset() + this.visitedItemCount();
        }

        private int satisfiedCount() {
            return this.ingredientCount;
        }

        private int connectionOffset() {
            return this.satisfiedOffset() + this.satisfiedCount();
        }

        private int connectionCount() {
            return this.ingredientCount * this.itemCount;
        }

        private int residualOffset() {
            return this.connectionOffset() + this.connectionCount();
        }

        private int residualCount() {
            return this.ingredientCount * this.itemCount;
        }

        private boolean isSatisfied(int itemId) {
            return this.data.get(this.getSatisfiedIndex(itemId));
        }

        private void setSatisfied(int itemId) {
            this.data.set(this.getSatisfiedIndex(itemId));
        }

        private int getSatisfiedIndex(int itemId) {
            assert itemId >= 0 && itemId < this.ingredientCount;

            return this.satisfiedOffset() + itemId;
        }

        private void clearSatisfied() {
            this.clearRange(this.satisfiedOffset(), this.satisfiedCount());
        }

        private void setConnection(int itemIndex, int ingredientIndex) {
            this.data.set(this.getConnectionIndex(itemIndex, ingredientIndex));
        }

        private boolean hasConnection(int itemIndex, int ingredientIndex) {
            return this.data.get(this.getConnectionIndex(itemIndex, ingredientIndex));
        }

        private int getConnectionIndex(int itemIndex, int ingredientIndex) {
            assert itemIndex >= 0 && itemIndex < this.itemCount;

            assert ingredientIndex >= 0 && ingredientIndex < this.ingredientCount;

            return this.connectionOffset() + itemIndex * this.ingredientCount + ingredientIndex;
        }

        private boolean isAssigned(int itemIndex, int ingredientIndex) {
            return this.data.get(this.getResidualIndex(itemIndex, ingredientIndex));
        }

        private void assign(int itemIndex, int ingredientIndex) {
            int i = this.getResidualIndex(itemIndex, ingredientIndex);

            assert !this.data.get(i);

            this.data.set(i);
        }

        private void unassign(int itemIndex, int ingredientIndex) {
            int i = this.getResidualIndex(itemIndex, ingredientIndex);

            assert this.data.get(i);

            this.data.clear(i);
        }

        private int getResidualIndex(int itemIndex, int ingredientIndex) {
            assert itemIndex >= 0 && itemIndex < this.itemCount;

            assert ingredientIndex >= 0 && ingredientIndex < this.ingredientCount;

            return this.residualOffset() + itemIndex * this.ingredientCount + ingredientIndex;
        }

        private void visitIngredient(int index) {
            this.data.set(this.getVisitedIngredientIndex(index));
        }

        private boolean hasVisitedIngredient(int index) {
            return this.data.get(this.getVisitedIngredientIndex(index));
        }

        private int getVisitedIngredientIndex(int index) {
            assert index >= 0 && index < this.ingredientCount;

            return this.visitedIngredientOffset() + index;
        }

        private void visitItem(int index) {
            this.data.set(this.getVisitiedItemIndex(index));
        }

        private boolean hasVisitedItem(int index) {
            return this.data.get(this.getVisitiedItemIndex(index));
        }

        private int getVisitiedItemIndex(int index) {
            assert index >= 0 && index < this.itemCount;

            return this.visitedItemOffset() + index;
        }

        private void clearAllVisited() {
            this.clearRange(this.visitedIngredientOffset(), this.visitedIngredientCount());
            this.clearRange(this.visitedItemOffset(), this.visitedItemCount());
        }

        private void clearRange(int start, int offset) {
            this.data.clear(start, start + offset);
        }

        public int tryPickAll(int max, @Nullable StackedContents.Output<T> itemCallback) {
            int i = 0;
            int j = Math.min(max, StackedContents.this.getResultUpperBound(this.ingredients)) + 1;

            while (true) {
                int k = (i + j) / 2;
                if (this.tryPick(k, null)) {
                    if (j - i <= 1) {
                        if (k > 0) {
                            this.tryPick(k, itemCallback);
                        }

                        return k;
                    }

                    i = k;
                } else {
                    j = k;
                }
            }
        }
    }
}
