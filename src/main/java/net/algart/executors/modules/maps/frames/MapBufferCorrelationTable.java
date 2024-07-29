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

package net.algart.executors.modules.maps.frames;

import net.algart.executors.api.data.SScalar;
import net.algart.executors.modules.core.common.numbers.IndexingBase;
import net.algart.executors.modules.core.common.scalars.ScalarFilter;
import net.algart.executors.modules.maps.frames.buffers.MapBuffer;
import net.algart.executors.modules.maps.frames.buffers.MapBufferKey;
import net.algart.executors.modules.maps.frames.joints.ObjectPairs;

import java.util.Arrays;
import java.util.BitSet;

public final class MapBufferCorrelationTable extends ScalarFilter {
    public static final String STITCHING_MAP = "stitching_map";
    public static final String PARTIAL_SET = "partial_set";
    public static final String RAW_PARTIAL_SET = "raw_partial_set";
    public static final String OBJECT_PAIRS = "object_pairs";

    private IndexingBase indexingBase = IndexingBase.ONE_BASED;

    public MapBufferCorrelationTable() {
        setDefaultInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        setDefaultOutputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        addOutputNumbers(STITCHING_MAP);
        addOutputNumbers(PARTIAL_SET);
        addOutputNumbers(RAW_PARTIAL_SET);
        addOutputNumbers(OBJECT_PAIRS);
        addOutputScalar(InitializeMapBuffer.NUMBER_OF_OBJECTS);
    }

    public IndexingBase getIndexingBase() {
        return indexingBase;
    }

    public MapBufferCorrelationTable setIndexingBase(IndexingBase indexingBase) {
        this.indexingBase = nonNull(indexingBase);
        return this;
    }

    @Override
    public SScalar process(SScalar source) {
        final MapBuffer mapBuffer = MapBufferKey.getInstance(source.toLong()).reqMapBuffer();
        final ObjectPairs objectPairs = mapBuffer.objectPairs();
        final int[] stitchingMap = stitchingMap(objectPairs.reindexTable());
        getNumbers(STITCHING_MAP).setTo(stitchingMap, 1);
        getNumbers(OBJECT_PAIRS).setTo(objectPairs.pairsArray(), 2);
        final int numberOfObjects = mapBuffer.numberOfObjects();
        getScalar(InitializeMapBuffer.NUMBER_OF_OBJECTS).setTo(numberOfObjects);
        if (isOutputNecessary(PARTIAL_SET) || isOutputNecessary(RAW_PARTIAL_SET)) {
            getNumbers(RAW_PARTIAL_SET).setTo(toBytes(mapBuffer.rawPartialObjects(), numberOfObjects), 1);
            getNumbers(PARTIAL_SET).setTo(toBytes(mapBuffer.reindexPartialObjects(), numberOfObjects), 1);
        }
        return source;
    }

    @Override
    public String visibleOutputPortName() {
        return STITCHING_MAP;
    }

    private static byte[] toBytes(BitSet bitSet, int length) {
        final byte[] result = new byte[length];
        for (int k = 0; k < result.length; k++) {
            result[k] = bitSet.get(k) ? (byte) 1 : (byte) 0;
        }
        return result;
    }

    private int[] stitchingMap(int[] reindexTable) {
        if (indexingBase.start >= reindexTable.length) {
            return new int[0];
        }
        return Arrays.copyOfRange(reindexTable, indexingBase.start, reindexTable.length);
    }
}
