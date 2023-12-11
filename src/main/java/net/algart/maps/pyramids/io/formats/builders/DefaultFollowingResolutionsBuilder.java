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

package net.algart.maps.pyramids.io.formats.builders;

import net.algart.arrays.*;
import net.algart.maps.pyramids.io.api.PlanePyramidSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DefaultFollowingResolutionsBuilder extends FollowingResolutionsBuilder {
    private final List<Matrix<? extends UpdatablePArray>> results = new ArrayList<Matrix<? extends UpdatablePArray>>();
    private List<MemoryModel> customMemoryModelsForNewResolutions = new ArrayList<MemoryModel>();

    public DefaultFollowingResolutionsBuilder(
        PlanePyramidSource source,
        int initialResolutionLevel,
        int compression)
    {
        super(source, initialResolutionLevel, compression);
    }

    public List<MemoryModel> getCustomMemoryModelsForNewResolutions() {
        return new ArrayList<MemoryModel>(customMemoryModelsForNewResolutions);
    }

    public void setCustomMemoryModelsForNewResolutions(List<MemoryModel> customMemoryModelsForNewResolutions) {
        Objects.requireNonNull(customMemoryModelsForNewResolutions,
                "Null customMemoryModelsForNewResolutions");
        customMemoryModelsForNewResolutions = new ArrayList<>(customMemoryModelsForNewResolutions);
        for (MemoryModel mm : customMemoryModelsForNewResolutions) {
            Objects.requireNonNull(mm, "Null memory model in the list");
        }
        this.customMemoryModelsForNewResolutions = customMemoryModelsForNewResolutions;
    }

    public List<Matrix<? extends UpdatablePArray>> getResults() {
        return new ArrayList<>(results);
    }

    @Override
    protected void allocateNewLayers(Class<?> elementType) {
        results.clear();
        long layerDimX = dimX;
        long layerDimY = dimY;
        for (int k = 0, n = getNumberOfNewResolutions(); k < n; k++) {
            layerDimX /= compression;
            layerDimY /= compression;
            MemoryModel mm = k < customMemoryModelsForNewResolutions.size() ?
                customMemoryModelsForNewResolutions.get(k) :
                Arrays.SMM;
            Matrix<UpdatablePArray> layer = mm.newMatrix(UpdatablePArray.class,
                elementType, bandCount, layerDimX, layerDimY);
            if (mm != Arrays.SMM) {
                layer = layer.tile(bandCount,
                    PlanePyramidSource.DEFAULT_TILE_DIM, PlanePyramidSource.DEFAULT_TILE_DIM);
            }
            results.add(layer);
        }
    }

    @Override
    protected void writeNewData(
        Matrix<? extends PArray> packedBands,
        int indexOfNewResolutionLevel,
        long positionX, long positionY)
    {
        results.get(indexOfNewResolutionLevel).subMatr(
            0, positionX, positionY, bandCount,
            packedBands.dim(1), packedBands.dim(2)).array().copy(packedBands.array());
    }
}
