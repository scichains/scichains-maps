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

package net.algart.executors.modules.maps.frames;

import net.algart.executors.modules.maps.frames.buffers.FrameObjectStitcher;
import net.algart.executors.modules.maps.frames.buffers.MapBuffer;
import net.algart.executors.modules.maps.frames.buffers.MapBufferKey;
import net.algart.executors.modules.maps.frames.joints.ObjectPairs;
import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.UpdatablePArray;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.multimatrix.MultiMatrix;
import net.algart.multimatrix.MultiMatrix2D;
import net.algart.executors.api.data.SScalar;
import net.algart.executors.modules.core.common.scalars.ScalarFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class ReadFromMapBuffer extends ScalarFilter {
    public static final String OUTPUT_DIM_X = "dim_x";
    public static final String OUTPUT_DIM_Y = "dim_y";
    public static final String EXPANDED_RECTANGLE = "expanded";

    private static final FrameObjectStitcher.JointingTooLargeObjects DEBUG_BOUNDARIES = null;
    private static final int DEBUG_BOUNDARIES_EXPANSION = 0;

    private int startX = 0;
    private int startY = 0;
    private int sizeX = 1;
    private int sizeY = 1;
    private int expansionX = 0;
    private Integer expansionY = null;
    private boolean reindexStitched = true;

    public ReadFromMapBuffer() {
        setDefaultInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        addInputNumbers(ChangeRectangleInsideMapBuffer.RECTANGLE);
        setDefaultOutputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        addOutputMat(DEFAULT_OUTPUT_PORT);
        addOutputNumbers(EXPANDED_RECTANGLE);
        addOutputScalar(OUTPUT_DIM_X);
        addOutputScalar(OUTPUT_DIM_Y);
        addOutputNumbers(ChangeRectangleInsideMapBuffer.CONTAINING_RECTANGLE);
    }

    public int getStartX() {
        return startX;
    }

    public ReadFromMapBuffer setStartX(int startX) {
        this.startX = startX;
        return this;
    }

    public int getStartY() {
        return startY;
    }

    public ReadFromMapBuffer setStartY(int startY) {
        this.startY = startY;
        return this;
    }

    public int getSizeX() {
        return sizeX;
    }

    public ReadFromMapBuffer setSizeX(int sizeX) {
        this.sizeX = positive(sizeX);
        return this;
    }

    public int getSizeY() {
        return sizeY;
    }

    public ReadFromMapBuffer setSizeY(int sizeY) {
        this.sizeY = positive(sizeY);
        return this;
    }

    public int expansionX() {
        return expansionX;
    }

    public ReadFromMapBuffer setExpansionX(int expansionX) {
        this.expansionX = expansionX;
        return this;
    }

    public Integer expansionY() {
        return expansionY;
    }

    public ReadFromMapBuffer setExpansionY(Integer expansionY) {
        this.expansionY = expansionY;
        return this;
    }

    public boolean isReindexStitched() {
        return reindexStitched;
    }

    public ReadFromMapBuffer setReindexStitched(boolean reindexStitched) {
        this.reindexStitched = reindexStitched;
        return this;
    }

    @Override
    public SScalar process(SScalar source) {
        final long mapBufferId = source.toLong();
        IRectangularArea area = getInputNumbers(ChangeRectangleInsideMapBuffer.RECTANGLE, true)
                .toIRectangularArea();
        if (area == null) {
            area = IRectangularArea.valueOf(startX, startY, startX + sizeX - 1, startY + sizeY - 1);
        }
        MultiMatrix result = process(mapBufferId, area);
        getMat(DEFAULT_OUTPUT_PORT).setTo(result);
        getScalar(OUTPUT_DIM_X).setTo(result.dim(0));
        getScalar(OUTPUT_DIM_Y).setTo(result.dim(1));
        return source;
    }

    public MultiMatrix process(long mapBufferId, IRectangularArea area) {
        final MapBufferKey mapBufferKey = MapBufferKey.getInstance(mapBufferId);
        final MapBuffer mapBuffer = mapBufferKey.reqMapBuffer();
        final ObjectPairs objectPairs = mapBuffer.objectPairs();
        final boolean reindex = mapBuffer.isStitchingLabels() && reindexStitched;
        long t1 = System.nanoTime();
        if (reindex) {
            objectPairs.resolveAllBases();
        }
        long t2 = System.nanoTime();
        final IRectangularArea expanded = mapBuffer.expandRectangleOnMap(
                area,
                IPoint.valueOf(expansionX, expansionY != null ? expansionY : expansionX),
                false);
        MultiMatrix result = reindex ?
                mapBuffer.readMatrixReindexedByObjectPairs(expanded, true) :
                mapBuffer.readMatrix(expanded);
        long t3 = System.nanoTime();
        logDebug(() -> String.format(Locale.US,
                "Reading %s at %s from %s (%s): %.3f ms = "
                        + "%.3f ms resolving disjoint set bases + %.3f ms reading%s",
                result, expanded, mapBufferKey, mapBuffer,
                (t3 - t1) * 1e-6, (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, reindex ? "/reindexing" : ""));

        getNumbers(EXPANDED_RECTANGLE).setTo(expanded);
        if (isOutputNecessary(ChangeRectangleInsideMapBuffer.CONTAINING_RECTANGLE)) {
            getNumbers(ChangeRectangleInsideMapBuffer.CONTAINING_RECTANGLE).setTo(mapBuffer.containingRectangle());
        }
        if (DEBUG_BOUNDARIES != null) {
            return drawBoundary(result.asMultiMatrix2D(), expanded, mapBuffer);
        }
        return result;
    }

    @Override
    public String visibleOutputPortName() {
        return DEFAULT_OUTPUT_PORT;
    }

    private static MultiMatrix2D drawBoundary(MultiMatrix2D matrix, IRectangularArea matrixArea, MapBuffer map) {
        final IRectangularArea container = matrixArea.dilate(DEBUG_BOUNDARIES_EXPANSION);
        final Collection<IRectangularArea> areas = container.intersection(map.allPositions());
        final Collection<IRectangularArea> internal = DEBUG_BOUNDARIES.internalBoundary(map, container);
        final Collection<IRectangularArea> external = MapBuffer.externalBoundary(areas, true);
        // - Note: for correct processing, JointingTooLargeObjects.internalBoundary method
        // MUST use straightOnly=true mode (in other case, some particles will be never completed).
        // The mode straightOnly=false is implemented only for this test.
        final List<Matrix<? extends PArray>> result = new ArrayList<>();
        final double externalFiller = matrix.maxPossibleValue();
        for (Matrix<? extends PArray> m : matrix.allChannels()) {
            final Matrix<? extends UpdatablePArray> r = Matrices.clone(m);
            for (IRectangularArea area : internal) {
                final IRectangularArea filledArea = area.shiftBack(matrixArea.min());
                final UpdatablePArray line = r.subMatrix(filledArea, Matrix.ContinuationMode.ZERO_CONSTANT).array();
                for (long k = 0, n = line.length(); k < n; k++) {
                    if (k % 4 == 0 || k == n - 1) {
                        line.setDouble(k, 0.0);
                    }
                }
            }
            for (IRectangularArea area : external) {
                final IRectangularArea filledArea = area.shiftBack(matrixArea.min());
                final UpdatablePArray line = r.subMatrix(filledArea, Matrix.ContinuationMode.ZERO_CONSTANT).array();
                for (long k = 0, n = line.length(); k < n; k++) {
                    if (k % 2 == 0 || k == n - 1) {
                        line.setDouble(k, externalFiller);
                    }
                }
            }
            result.add(r);
        }
        return MultiMatrix.valueOf2D(result);
    }
}
