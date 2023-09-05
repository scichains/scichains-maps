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

package net.algart.matrices.maps.pyramids.io.api.sources;

import net.algart.arrays.Array;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.matrices.maps.pyramids.io.api.AbstractPlanePyramidSource;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidTools;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class DefaultPlanePyramidSource extends AbstractPlanePyramidSource implements PlanePyramidSource {
    private final List<Matrix<? extends PArray>> packedImagePyramid;
    private final Class<?> elementType;
    private final int bandCount;

    volatile boolean continuationEnabled = true;

    public DefaultPlanePyramidSource(List<? extends Matrix<? extends PArray>> packedImagePyramid) {
        Objects.requireNonNull(packedImagePyramid, "Null packedImagePyramid");
        packedImagePyramid = new ArrayList<>(packedImagePyramid);
        if (packedImagePyramid.isEmpty())
            throw new IllegalArgumentException("Empty packedImagePyramid");
        int firstNonNullIndex = -1;
        int bandCount = -1;
        for (int k = 0, n = packedImagePyramid.size(); k < n; k++) {
            Matrix<? extends PArray> m = packedImagePyramid.get(k);
            if (m == null) {
                continue;
            }
            if (m.dimCount() != 3)
                throw new IllegalArgumentException("Illegal number of dimensions (" + m.dimCount()
                    + ") in packedImagePyramid[" + k + "]: all matrices must be 3-dimensional");
            if (m.dim(0) > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Too large bandCount (>Integer.MAX_VALUE)");
            if (firstNonNullIndex == -1) {
                firstNonNullIndex = k;
                bandCount = (int) m.dim(0);
            } else if (m.dim(0) != bandCount)
                throw new IllegalArgumentException("Different lowest dimension packedImagePyramid[" + k + "].dim(0) = "
                    + m.dim(0) + " != packedImagePyramid[" + firstNonNullIndex + "].dim(0) = " + bandCount);
        }
        if (firstNonNullIndex == -1)
            throw new IllegalArgumentException("All elements in packedImagePyramid are null");
        this.packedImagePyramid = PlanePyramidTools.equalizePrecisionToTheBest(packedImagePyramid);
        this.elementType = this.packedImagePyramid.get(firstNonNullIndex).elementType();
        this.bandCount = bandCount;
    }

    public boolean isContinuationEnabled() {
        return continuationEnabled;
    }

    public DefaultPlanePyramidSource setContinuationEnabled(boolean continuationEnabled) {
        this.continuationEnabled = continuationEnabled;
        return this;
    }

    public int numberOfResolutions() {
        return packedImagePyramid.size();
    }

    public int bandCount() {
        return bandCount;
    }

    @Override
    public boolean isResolutionLevelAvailable(int resolutionLevel) {
        return packedImagePyramid.get(resolutionLevel) != null;
    }

    public long[] dimensions(int resolutionLevel) throws NoSuchElementException {
        Matrix<? extends PArray> m = packedImagePyramid.get(resolutionLevel);
        if (m == null)
            throw new NoSuchElementException("Resolution level #" + resolutionLevel + " is absent");
        return m.dimensions();
    }

    @Override
    public long dim(int resolutionLevel, int index) {
        Matrix<? extends PArray> m = packedImagePyramid.get(resolutionLevel);
        if (m == null) {
            throw new NoSuchElementException("Resolution level #" + resolutionLevel + " is absent");
        }
        if (index < 0 || index >= m.dimCount()) {
            throw new IndexOutOfBoundsException("Invalid index " + index);
        }
        return m.dim(index);
    }

    @Override
    public boolean isElementTypeSupported() {
        return true;
    }

    @Override
    public Class<?> elementType() {
        return this.elementType;
    }

    public boolean isFullMatrixSupported() {
        return true;
    }

    @Override
    public Matrix<? extends PArray> readSubMatrix(int resolutionLevel, long fromX, long fromY, long toX, long toY)
        throws NoSuchElementException
    {
        Matrix<? extends PArray> m = packedImagePyramid.get(resolutionLevel);
        if (m == null)
            throw new NoSuchElementException("Resolution level #" + resolutionLevel + " is absent");
        return m.subMatrix(
            0, fromX, fromY, bandCount(), toX, toY,
            continuationEnabled ? Matrix.ContinuationMode.NAN_CONSTANT : Matrix.ContinuationMode.NONE);
    }

    public Matrix<? extends PArray> readFullMatrix(int resolutionLevel)
        throws NoSuchElementException
    {
        Matrix<? extends PArray> m = packedImagePyramid.get(resolutionLevel);
        if (m == null)
            throw new NoSuchElementException("Resolution level #" + resolutionLevel + " is absent");
        return m;
    }

    public void freeResources(FlushMode flushMode) {
        super.freeResources(flushMode);
        for (Matrix<? extends PArray> m : packedImagePyramid) {
            if (m != null) {
                Array a = flushMode.dataMustBeFlushed() ? m.array() : m.array().subArray(0, 0);
                a.freeResources(null, flushMode.forcePhysicalWriting());
            }
        }
    }

    @Override
    protected Matrix<? extends PArray> readLittleSubMatrix(
            int resolutionLevel, long fromX, long fromY, long toX, long toY)
    {
        throw new AssertionError("Not used in this class!");
    }
}
