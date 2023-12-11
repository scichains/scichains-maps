/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.algart.executors.modules.maps.frames.joints;

import net.algart.arrays.JArrays;

import java.util.Arrays;
import java.util.Objects;

public final class QuickLabelsSet {
    private int min;
    private int max;
    private boolean[] bits = JArrays.EMPTY_BOOLEANS;

    private QuickLabelsSet() {
        clear();
    }

    public static QuickLabelsSet newEmptyInstance() {
        return new QuickLabelsSet();
    }

    public static QuickLabelsSet newInstance(int min, int max) {
        return newEmptyInstance().setLabelsRange(min, max);
    }

    public int min() {
        checkEmpty();
        return min;
    }

    public int max() {
        checkEmpty();
        return max;
    }

    public boolean isEmpty() {
        return max == Integer.MIN_VALUE;
    }

    public QuickLabelsSet setLabelsRange(int min, int max) {
        if (min < 0) {
            throw new IllegalArgumentException("Negative min = " + min);
        }
        if (min > max) {
            throw new IllegalArgumentException("min > max: min = "
                    + min + ", max = " + max);
        }
        this.min = min;
        this.max = max;
        prepareToUse();
        return this;
    }

    public void clear() {
        min = Integer.MAX_VALUE;
        max = Integer.MIN_VALUE;
    }

    public void expand(int label) {
        if (label < 0) {
            throw new IllegalArgumentException("Negative label = " + label);
        }
        if (label < min) {
            min = label;
        }
        if (label > max) {
            max = label;
        }
    }

    public void prepareToUse() {
        if (isEmpty()) {
            min = max = 0;
        }
        ensureCapacityAndClear(max - min + 1);
        Arrays.fill(bits, 0, max - min + 1, false);
    }

    public void clearAndFreeResources() {
        clear();
        bits = JArrays.EMPTY_BOOLEANS;
    }

    public boolean get(int objectLabel) {
        return objectLabel >= min && objectLabel <= max && bits[objectLabel - min];
    }

    public void set(int objectLabel, boolean value) {
        if (objectLabel < min || objectLabel > max) {
            checkEmpty();
            throw new IndexOutOfBoundsException("Object's label " + objectLabel
                    + " is out of range " + min + ".." + max);
        }
        this.bits[objectLabel - min] = value;
    }

    public void set(int objectLabel) {
        if (objectLabel < min || objectLabel > max) {
            checkEmpty();
            throw new IndexOutOfBoundsException("Object's label " + objectLabel
                    + " is out of range " + min + ".." + max);
        }
        this.bits[objectLabel - min] = true;
    }

    public QuickLabelsSet reindex(DynamicDisjointSet disjointSet) {
        Objects.requireNonNull(disjointSet, "Null disjoint set");
        checkEmpty();
        int newMin = Integer.MAX_VALUE;
        int newMax = Integer.MIN_VALUE;
        final int n = max - min + 1;
        for (int k = 0; k < n; k++) {
            if (bits[k]) {
                final int base = disjointSet.findBase(min + k);
                if (base < newMin) {
                    newMin = base;
                }
                if (base > newMax) {
                    newMax = base;
                }
            }
        }
        if (newMax == Integer.MIN_VALUE) {
            newMin = newMax = 1;
        }
        final QuickLabelsSet result = newInstance(newMin, newMax);
        for (int k = 0; k < n; k++) {
            if (bits[k]) {
                final int base = disjointSet.findBase(min + k);
                result.set(base);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "quick labels set in range " + min + ".." + max;
    }

    private void checkEmpty() {
        if (isEmpty()) {
            throw new IllegalArgumentException(this + " is not initialized");
        }
    }

    private void ensureCapacityAndClear(final int newNumberOfObjects) {
        final int oldNumberOfObjects = bits.length;
        if (newNumberOfObjects > oldNumberOfObjects) {
            final int newLength = Math.max(16, Math.max(newNumberOfObjects,
                    (int) Math.min(Integer.MAX_VALUE, (long) (2.0 * oldNumberOfObjects))));
            this.bits = new boolean[newLength];
        }
    }
}
