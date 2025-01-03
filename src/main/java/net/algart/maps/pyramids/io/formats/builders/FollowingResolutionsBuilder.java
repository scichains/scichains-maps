/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.maps.pyramids.io.formats.builders;

import net.algart.arrays.*;
import net.algart.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.maps.pyramids.io.api.PlanePyramidTools;

import java.util.ArrayList;
import java.util.List;

public abstract class FollowingResolutionsBuilder {
    public static final long RECOMMENDED_TILE_DIM_FOR_MAKING_FOLLOWING_RESOLUTIONS = Math.max(16,
            Arrays.SystemSettings.getLongProperty(
                    "net.algart.maps.pyramids.io.formats.common.builders" +
                            ".tileForMakingFollowingResolutions",
                    PlanePyramidSource.DEFAULT_TILE_DIM)
    );
    protected final PlanePyramidSource source;
    protected final int initialResolutionLevel;
    protected final int compression;
    protected final int bandCount;
    protected final long dimX;
    protected final long dimY;
    private final long totalNumberOfElements;

    private int numberOfNewResolutions = 0;
    private PlanePyramidSource.AveragingMode averagingMode = PlanePyramidSource.AveragingMode.DEFAULT;
    private long processingTileDim = RECOMMENDED_TILE_DIM_FOR_MAKING_FOLLOWING_RESOLUTIONS;

    FollowingResolutionsBuilder(
            PlanePyramidSource source,
            int initialResolutionLevel,
            int compression) {
        if (source == null)
            throw new NullPointerException("Null source");
        if (compression <= 1)
            throw new IllegalArgumentException("Invalid compression " + compression + " (must be 2 or greater)");
        if (initialResolutionLevel < 0 || initialResolutionLevel >= source.numberOfResolutions())
            throw new IndexOutOfBoundsException("Initial resolution level is out of range 0.."
                    + (source.numberOfResolutions() - 1));
        this.source = source;
        this.initialResolutionLevel = initialResolutionLevel;
        this.compression = compression;
        this.bandCount = source.bandCount();
        if (this.bandCount <= 0)
            throw new AssertionError("Invalid implementation of " + source.getClass()
                    + ": zero or negative bandCount = " + bandCount);
        long[] dimensions = source.dimensions(initialResolutionLevel);
        if (this.bandCount != dimensions[0])
            throw new AssertionError("Invalid implementation of " + source.getClass()
                    + ": bandCount != dimensions[0]");
        this.dimX = dimensions[1];
        this.dimY = dimensions[2];
        if (dimX <= 0 || dimY <= 0) // more strict requirement (>0) than the usual requirement for matrices (>=0)
            throw new IllegalArgumentException("Illegal initial layer dimensions " + dimX + "x" + dimY
                    + " (must be positive)");
        this.totalNumberOfElements = Arrays.longMul(dimensions);
        if (totalNumberOfElements == Long.MIN_VALUE)
            throw new TooLargeArrayException("Product of all dimensions >Long.MAX_VALUE");
        setMinimalNewResolutionLayerSize(PlanePyramidSource.DEFAULT_MINIMAL_PYRAMID_SIZE);
    }

    public final PlanePyramidSource getSource() {
        return source;
    }

    public final int getCompression() {
        return compression;
    }

    public int getBandCount() {
        return bandCount;
    }

    public long getDimX() {
        return dimX;
    }

    public long getDimY() {
        return dimY;
    }

    public long getTotalNumberOfElements() {
        return totalNumberOfElements;
    }

    public final int getInitialResolutionLevel() {
        return initialResolutionLevel;
    }

    public final int getNumberOfNewResolutions() {
        return numberOfNewResolutions;
    }

    public final void setNumberOfNewResolutions(int numberOfNewResolutions) {
        if (numberOfNewResolutions < 0)
            throw new IllegalArgumentException("Negative numberOfNewResolutions");
        this.numberOfNewResolutions = numberOfNewResolutions;
    }

    public final void setMinimalNewResolutionLayerSize(long minimalPyramidSize) {
        this.numberOfNewResolutions = Math.max(0, // to be on the safe side; really not necessary
                PlanePyramidTools.numberOfResolutions(dimX, dimY, compression, minimalPyramidSize) - 1);
    }

    public final PlanePyramidSource.AveragingMode getAveragingMode() {
        return averagingMode;
    }

    public final void setAveragingMode(PlanePyramidSource.AveragingMode averagingMode) {
        if (averagingMode == null)
            throw new NullPointerException("Null averagingMode");
        this.averagingMode = averagingMode;
    }

    public final long getProcessingTileDim() {
        return processingTileDim;
    }

    public final void setProcessingTileDim(long processingTileDim) {
        if (processingTileDim <= 0)
            throw new IllegalArgumentException("Zero or negative processingTileDim");
        this.processingTileDim = Math.max(16, processingTileDim);
    }

    // Previous version had an argument ArrayContext; now we just ignore this.
    public void process() {
        if (numberOfNewResolutions == 0) {
            return;
        }
        long tileDim = compression;
        int nImmediatelyBuilt = 1;
        while (tileDim < processingTileDim) {
            tileDim *= compression;
            nImmediatelyBuilt++;
        }
        nImmediatelyBuilt = Math.min(nImmediatelyBuilt, numberOfNewResolutions);
        // tileDim is a power compression^m, m>=nImmediatelyBuilt>=1
        final boolean needToSeparatelyCompressLastLayers = nImmediatelyBuilt != numberOfNewResolutions;
        List<UpdatablePArray> buffers = new ArrayList<UpdatablePArray>();
        Matrix<? extends UpdatablePArray> lastLayer = null;
        final long tileXCount = (dimX - 1) / tileDim + 1;
        final long tileYCount = (dimY - 1) / tileDim + 1;
        Class<?> elementType = null; // will be known after getting the 1st tile
        for (long yIndex = 0; yIndex < tileYCount; yIndex++) {
            for (long xIndex = 0; xIndex < tileXCount; xIndex++) {
                long tileX = xIndex * tileDim;
                long tileY = yIndex * tileDim;
                long currentTileDimX = Math.min(tileDim, dimX - tileX);
                long currentTileDimY = Math.min(tileDim, dimY - tileY);
                currentTileDimX -= currentTileDimX % compression;
                currentTileDimY -= currentTileDimY % compression;
                // in other words, we prefer to lose 1-2 last pixels, but provide strict integer compression
                // for the first compression: AlgART libraries are optimized for this situation
                long tileToX = tileX + currentTileDimX;
                long tileToY = tileY + currentTileDimY;
                Matrix<? extends PArray> m = source.readSubMatrix(
                        initialResolutionLevel, tileX, tileY, tileToX, tileToY);
                if (m.dim(0) != bandCount || m.dim(1) != currentTileDimX || m.dim(2) != currentTileDimY)
                    throw new AssertionError("Invalid implementation of " + source.getClass()
                            + ".readSubMatrix (fromX = "
                            + tileX + ", fromY = " + tileY + ", toX = " + tileToX + ", toY = " + tileToY
                            + "): incorrect dimensions of the returned matrix " + m);
                if (elementType == null) {
                    // allocating results and buffers
                    elementType = m.elementType();
                    buffers.add((UpdatablePArray) Arrays.SMM.newUnresizableArray(
                            m.elementType(), bandCount * Math.min(tileDim, dimX) * Math.min(tileDim, dimY)));
                    allocateNewLayers(elementType);
                    long layerDimX = dimX;
                    long layerDimY = dimY;
                    long tDim = tileDim;
                    for (int k = 0; k < nImmediatelyBuilt; k++) {
                        layerDimX /= compression;
                        layerDimY /= compression;
                        tDim /= compression;
                        buffers.add((UpdatablePArray) Arrays.SMM.newUnresizableArray(
                                elementType, bandCount * Math.min(tDim, layerDimX) * Math.min(tDim, layerDimY)));
                    }
                    assert buffers.size() == nImmediatelyBuilt + 1;
                    if (needToSeparatelyCompressLastLayers) {
                        lastLayer = Arrays.SMM.newMatrix(
                                UpdatablePArray.class, elementType, bandCount, layerDimX, layerDimY);
                    }
                }
                Matrix<? extends UpdatablePArray> largeBuffer = Matrices.matrixAtSubArray(
                        buffers.get(0), 0,
                        bandCount, currentTileDimX, currentTileDimY);
                largeBuffer.array().copy(m.array());
                // unlike addImage method, here we scale only the part of full tile inside the image: here we have
                // no background correction and should not try to average extra pixels to avoid edge effects
                for (int level = 0; level < nImmediatelyBuilt; level++) {
                    assert tileX % compression == 0;
                    assert tileY % compression == 0;
                    tileX /= compression;
                    tileY /= compression;
                    tileToX /= compression;
                    tileToY /= compression;
                    Matrix<? extends UpdatablePArray> smallBuffer = Matrices.matrixAtSubArray(
                            buffers.get(level + 1), 0,
                            bandCount, tileToX - tileX, tileToY - tileY);
                    Matrices.resize(null, averagingMode.averagingMethod(largeBuffer), smallBuffer, largeBuffer);
                    writeNewData(smallBuffer, level, tileX, tileY);
                    largeBuffer = smallBuffer;
                }
                if (needToSeparatelyCompressLastLayers) {
                    lastLayer.subMatrix(0, tileX, tileY, bandCount, tileToX, tileToY)
                            .array().copy(largeBuffer.array());
                }
            }
        }
        assert elementType != null;
        if (needToSeparatelyCompressLastLayers) {
            long layerDimX = lastLayer.dim(1);
            long layerDimY = lastLayer.dim(2);
            for (int level = nImmediatelyBuilt, k = 0; level < numberOfNewResolutions; level++) {
                layerDimX /= compression;
                layerDimY /= compression;
                Matrix<? extends UpdatablePArray> newLayer = Arrays.SMM.newMatrix(
                        UpdatablePArray.class, elementType, bandCount, layerDimX, layerDimY);
                Matrices.resize(null, averagingMode.averagingMethod(lastLayer), newLayer, lastLayer);
                writeNewData(newLayer, level, 0, 0);
                lastLayer = newLayer;
            }
        }
    }

    protected abstract void allocateNewLayers(Class<?> elementType);

    protected abstract void writeNewData(
            Matrix<? extends PArray> packedBands,
            int indexOfNewResolutionLevel, // indexOfNewResolutionLevel=0 corresponds to initialResolutionLevel+1
            long positionX, long positionY);
}
