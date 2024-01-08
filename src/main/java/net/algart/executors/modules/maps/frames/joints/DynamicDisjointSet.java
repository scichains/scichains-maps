/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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
import net.algart.arrays.TooLargeArrayException;

import java.util.Arrays;
import java.util.stream.IntStream;

public final class DynamicDisjointSet implements Cloneable {
    private static final int MAX_NUMBER_OF_OBJECTS = Integer.MAX_VALUE - 1000;
    // - allows to freely add +1, +2, +255 to object index and something like this

    private int[] parent;
    private int[] cardinalities;
    // - Important that arrays are int[], not long[]!
    // It allow to guarantee correct executing in multithreading environment,
    // though multithreading may lead to slowing down.
    int count;

    private DynamicDisjointSet() {
        clear();
    }

    public static DynamicDisjointSet newInstance() {
        return new DynamicDisjointSet();
    }

    public int count() {
        return count;
    }

    public void expand(int objectIndex) {
        if (objectIndex < 0) {
            throw new IllegalArgumentException("Negative object index: " + objectIndex);
        }
        if (objectIndex >= count) {
            final long newNumberOfObjects = (long) objectIndex + 1;
            ensureCapacity(newNumberOfObjects);
            count = objectIndex + 1;
        }
    }

    public void clear() {
        count = 0;
        parent = JArrays.EMPTY_INTS;
        cardinalities = JArrays.EMPTY_INTS;
    }

    public int[] parent() {
        return Arrays.copyOf(parent, count);
    }

    public int parentOrThis(int objectIndex) {
        // - this function is recommended while multithreading
        return objectIndex >= count ? objectIndex : parent[objectIndex];
        // - objectIndex >= count is a correct situation: internal objects, not intersecting
        // frame boundaries, are not added into this disjoint set
    }

    public int findBase(int objectIndex) {
        int base = objectIndex;
        int newBase;
        while ((newBase = parent[base]) != base) {
            base = newBase;
        }
        parent[objectIndex] = base;
        // - path compression; correct even while multithreading, though may lead to slowing down
        return base;
    }

    public int jointBases(int base1, int base2) {
        if (base1 == base2) {
            return base1;
        }
        int cardinality1 = cardinalities[base1];
        int cardinality2 = cardinalities[base2];
        if (cardinality1 < cardinality2) {
            int temp = base1;
            base1 = base2;
            base2 = temp;
            cardinality2 = cardinality1;
        }
        parent[base2] = base1;
        // - joining smaller (base2) to larger (base1)
        cardinalities[base1] += cardinality2;
        // - note: we MUST correct cardinalities not only to guarantee normal performance
        // (path lengths are always short), but also to provide correct allCardinalities()
        // after reindexDifferentBases()
        return base1;
    }

    public void jointObjects(int object1, int object2) {
        expand(object1);
        expand(object2);
        final int o1 = findBase(object1);
        final int o2 = findBase(object2);
        jointBases(o1, o2);
    }

    public void resolveAllBases() {
        IntStream.range(0, count + 255 >>> 8).parallel().forEach(block -> {
            // note: splitting to blocks necessary for normal speed
            for (int i = block << 8, to = (int) Math.min((long) i + 256, count); i < to; i++) {
                findBase(i);
            }
        });
    }

    private void ensureCapacity(final long newNumberOfObjects) {
        if (newNumberOfObjects > MAX_NUMBER_OF_OBJECTS) {
            // - should not occur while usage in this package
            throw new TooLargeArrayException("Too large array required");
        }
        assert newNumberOfObjects == (int) newNumberOfObjects;
        final int oldNumberOfObjects = parent.length;
        if (newNumberOfObjects > oldNumberOfObjects) {
            final int newLength = Math.max(16, Math.max((int) newNumberOfObjects,
                    (int) Math.min(MAX_NUMBER_OF_OBJECTS, (long) (2.0 * oldNumberOfObjects))));
            parent = Arrays.copyOf(parent, newLength);
            cardinalities = Arrays.copyOf(cardinalities, newLength);
            for (int k = oldNumberOfObjects; k < parent.length; k++) {
                parent[k] = k;
                cardinalities[k] = 1;
            }
        }
    }
}
