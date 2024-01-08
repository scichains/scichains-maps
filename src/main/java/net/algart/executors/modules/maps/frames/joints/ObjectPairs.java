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

import net.algart.arrays.*;
import net.algart.executors.modules.maps.frames.buffers.FrameObjectStitcher;
import net.algart.executors.modules.maps.frames.buffers.MapBuffer;
import net.algart.multimatrix.MultiMatrix;

import java.util.BitSet;
import java.util.Objects;
import java.util.stream.IntStream;

public final class ObjectPairs {
    private static final int MAX_NUMBER_OF_PAIRS = Integer.MAX_VALUE / 2;

    private final MutableIntArray pairs = Arrays.SMM.newEmptyIntArray();
    private final DynamicDisjointSet dynamicDisjointSet = DynamicDisjointSet.newInstance();

    private ObjectPairs() {
    }

    public static ObjectPairs newInstance() {
        return new ObjectPairs();
    }

    public DynamicDisjointSet dynamicDisjointSet() {
        return dynamicDisjointSet;
    }

    public static ObjectPairs valueOf(int[] pairsArray) {
        Objects.requireNonNull(pairsArray, "Null pairs array");
        if (pairsArray.length % 2 != 0) {
            throw new IllegalArgumentException("Odd length of pairs array: " + pairsArray.length);
        }
        final ObjectPairs result = new ObjectPairs();
        for (int k = 0; k < pairsArray.length; k += 2) {
            result.addPair(pairsArray[k], pairsArray[k + 1]);
        }
        return result;
    }

    public int numberOfPairs() {
        return (int) pairs.length() >> 1;
    }

    public void addPair(int object1, int object2) {
        if (object1 < 0) {
            throw new IllegalArgumentException("Negative object1 index");
        }
        if (object2 < 0) {
            throw new IllegalArgumentException("Negative object2 index");
        }
        if (pairs.length() >= (MAX_NUMBER_OF_PAIRS << 1)) {
            throw new TooLargeArrayException("Cannot store more than " + MAX_NUMBER_OF_PAIRS + " pairs");
        }
        if (object1 != object2) {
            // - we don't need to store identical pairs: it is senseless
            pairs.pushInt(object1);
            pairs.pushInt(object2);
            dynamicDisjointSet.jointObjects(object1, object2);
        }
    }

    public void clear() {
        pairs.length(0);
        dynamicDisjointSet.clear();
    }

    public int object1(int pairIndex) {
        return pairs.getInt(2 * pairIndex);
    }

    public int object2(int pairIndex) {
        return pairs.getInt(2 * pairIndex + 1);
    }

    public int reindexedObject(int pairIndex) {
        final int result = reindex(object1(pairIndex));
        assert result == reindex(object2(pairIndex)) : "Objects in the pair must have identical base in disjoint set";
        return result;
    }

    public int[] pairsArray() {
        return Arrays.toJavaArray(pairs);
    }

    public ObjectPairs resolveAllBases() {
        dynamicDisjointSet.resolveAllBases();
        return this;
    }

    public int[] reindexTable() {
        resolveAllBases();
        return dynamicDisjointSet.parent();
    }

    public int reindex(int objectIndex) {
        if (objectIndex < 0) {
            throw new IllegalArgumentException("Objects must be represented by zero or negative integers, "
                    + "but we try to reindex " + objectIndex);
        }
        if (objectIndex >= dynamicDisjointSet.count) {
            // - correct situation: internal objects, not intersecting frame boundaries,
            // are not added into this disjoint set
            return objectIndex;
        } else {
            return dynamicDisjointSet.findBase(objectIndex);
        }
    }

    public int quickReindex(int objectIndex) {
        return dynamicDisjointSet.parentOrThis(objectIndex);
    }

    public QuickLabelsSet reindex(QuickLabelsSet labelsSet) {
        return labelsSet.reindex(dynamicDisjointSet);
    }

    public BitSet reindexByAnd(BitSet bitSet) {
        final BitSet result = (BitSet) bitSet.clone();
        final int n = result.length();
        for (int k = 0; k < n; k++) {
            if (!result.get(k)) {
                final int reindexed = reindex(k);
                if (reindexed != k) {
                    result.clear(reindexed);
                }
            }
        }
        // - all bases are cleared to 0 if at least one from the descendants is 0
        for (int k = 0; k < n; k++) {
            final int reindexed = reindex(k);
            if (reindexed != k && !result.get(reindexed)) {
                result.clear(k);
            }
        }
        // - all descendants of 0 are cleared to 0
//        System.out.println("!!!" + bitSet + " -> " + result);
        return result;
    }

    public MapBuffer.Frame reindex(MapBuffer.Frame labels, boolean quickCallAfterResolveAllBases) {
        Objects.requireNonNull(labels, "Null labels");
        return labels.matrix(reindex(labels.matrix(), quickCallAfterResolveAllBases));
    }

    public MultiMatrix reindex(MultiMatrix labelsMatrix, boolean quickCallAfterResolveAllBases) {
        Objects.requireNonNull(labelsMatrix, "Null labels");
//        long t1 = System.nanoTime();
        FrameObjectStitcher.checkLabels(labelsMatrix);
        // - so, it is 2-dimensional
        final int[] labels = labelsMatrix.channelToIntArray(0);
        // Deprecated variant (high speed here is not necessary, because this method is usually not called at all):
        // final int[] labels = new LabelsAnalyser().setLabels(labelsMatrix.asMultiMatrix2D()).unsafeLabels();
        // - note: we don't modify labels below, so we can use unsafeLabels

        final int[] result = new int[labels.length];
//        long t2 = System.nanoTime();
        if (quickCallAfterResolveAllBases) {
//            for (int i = 0; i < labels.length; i++) {
//                result[i] = dynamicDisjointSet.parentOrThis(labels[i]);
//            }
            IntStream.range(0, (labels.length + 255) >>> 8).parallel().forEach(block -> {
                for (int i = block << 8, to = (int) Math.min((long) i + 256, labels.length); i < to; i++) {
                    result[i] = dynamicDisjointSet.parentOrThis(labels[i]);
                }
            });
        } else {
            // Note: parallel execution is anti-optimization here for large matrices, because reindex method
            // MODIFIES the parent[] array
            for (int i = 0; i < labels.length; i++) {
                result[i] = reindex(labels[i]);
            }
        }
//        long t3 = System.nanoTime();
//        System.out.printf("!!! %.3f + %.3f ms (%d pixels, %.2f ns/pixel) %s%n",
//                (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, result.length, (double) (t3 - t2) / result.length, labelsMatrix);
        return MultiMatrix.valueOf2DMono(
                Matrices.matrix(SimpleMemoryModel.asUpdatableIntArray(result), labelsMatrix.dimensions()));
    }

    @Override
    public String toString() {
        return "object pairs: " + pairs.length() / 2 + " pairs among " + dynamicDisjointSet.count() + " objects";
    }
}
