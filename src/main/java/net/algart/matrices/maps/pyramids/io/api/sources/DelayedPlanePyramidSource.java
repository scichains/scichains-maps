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

import net.algart.arrays.*;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;

import java.nio.channels.NotYetConnectedException;
import java.util.*;

public final class DelayedPlanePyramidSource implements PlanePyramidSource {
    private final int bandCount;
    private final int compression;
    private final boolean[] resolutionLevelsAvailability;

    private final List<long[]> dimensions;

    private PlanePyramidSource parent = null;
    private final Object lock = new Object();

    private DelayedPlanePyramidSource(
            long[] zeroLevelDimensions,
            int compression,
            boolean[] resolutionLevelsAvailability) {
        if (zeroLevelDimensions == null) {
            throw new NullPointerException("Null array of zero-level dimensions");
        }
        if (resolutionLevelsAvailability == null) {
            throw new NullPointerException("Null array of availability of resolution levels");
        }
        if (zeroLevelDimensions.length != 3) {
            throw new IllegalArgumentException("Illegal length of zero-level dimensions array "
                    + zeroLevelDimensions.length);
        }
        long bandCount = zeroLevelDimensions[0];
        long dimX = zeroLevelDimensions[1];
        long dimY = zeroLevelDimensions[2];
        if (dimX <= 0 || dimY <= 0) // more strict requirement (>0) than the usual requirement for matrices (>=0)
        {
            throw new IllegalArgumentException("Illegal zero-level dimensions " + dimX + "x" + dimY
                    + " (must be positive)");
        }
        if (bandCount <= 0) {
            throw new IllegalArgumentException("Number of bands must be positive");
        }
        if (bandCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large number of bands (>Integer.MAX_VALUE)");
        }
        if (compression <= 1) {
            throw new IllegalArgumentException("Compression must be 2 or greater");
        }
        this.bandCount = (int) bandCount;
        this.compression = compression;
        this.resolutionLevelsAvailability = resolutionLevelsAvailability.clone();
        this.dimensions = new ArrayList<long[]>();
        for (int k = 0; k < resolutionLevelsAvailability.length; k++) {
            this.dimensions.add(new long[]{bandCount, dimX, dimY});
            dimX /= compression;
            dimY /= compression;
        }
    }

    public static DelayedPlanePyramidSource newInstance(
            long dimX,
            long dimY,
            int bandCount,
            int compression,
            boolean[] resolutionLevelsAvailability) {
        return newInstance(new long[]{dimX, dimY, bandCount}, compression, resolutionLevelsAvailability);
    }

    public static DelayedPlanePyramidSource newInstance(
            long[] zeroLevelDimensions,
            int compression,
            boolean[] resolutionLevelsAvailability) {
        return new DelayedPlanePyramidSource(zeroLevelDimensions, compression, resolutionLevelsAvailability);
    }

    public PlanePyramidSource getParent() {
        synchronized (lock) {
            return parent;
        }
    }

    public void setParent(PlanePyramidSource parent) {
        Objects.requireNonNull(parent, "Cannot change the parent back to null value");
        if (parent.numberOfResolutions() != numberOfResolutions()) {
            throw new IllegalArgumentException("parent.numberOfResolutions() != numberOfResolutions()");
        }
        if (parent.bandCount() != bandCount) {
            throw new IllegalArgumentException("parent.bandCount() != bandCount()");
        }
        for (int level = 0; level < resolutionLevelsAvailability.length; level++) {
            if (parent.isResolutionLevelAvailable(level) != resolutionLevelsAvailability[level]) {
                throw new IllegalArgumentException("parent.isResolutionLevelAvailable(" + level
                        + ") != resolutionLevelsAvailability[" + level + "]");
            }
            if (resolutionLevelsAvailability[level] &&
                    !java.util.Arrays.equals(parent.dimensions(level), dimensions.get(level))) {
                throw new IllegalArgumentException("parent.dimensions(" + level
                        + ") != dimensions(" + level + ")");
            }
        }
        synchronized (lock) {
            this.parent = parent;
        }
    }

    public int numberOfResolutions() {
        return resolutionLevelsAvailability.length;
    }

    public int compression() {
        return compression;
    }

    public int bandCount() {
        return bandCount;
    }

    public boolean isResolutionLevelAvailable(int resolutionLevel) {
        return resolutionLevelsAvailability[resolutionLevel];
    }

    public boolean[] getResolutionLevelsAvailability() {
        return resolutionLevelsAvailability.clone();
    }

    public long[] dimensions(int resolutionLevel) {
        return dimensions.get(resolutionLevel).clone();
    }

    @Override
    public long dim(int resolutionLevel, int index) {
        return dimensions.get(resolutionLevel)[index];
    }

    public boolean isElementTypeSupported() {
        PlanePyramidSource parent = getParent();
        return parent != null && parent.isElementTypeSupported();
    }

    public Class<?> elementType() throws UnsupportedOperationException {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new UnsupportedOperationException("elementType() method is not supported yet, "
                    + "because the parent is not set yet");
        }
        return parent.elementType();
    }

    @Override
    public OptionalDouble pixelSizeInMicrons() {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new UnsupportedOperationException("pixelSizeInMicrons() method is not supported yet, "
                    + "because the parent is not set yet");
        }
        return parent.pixelSizeInMicrons();
    }

    @Override
    public OptionalDouble magnification() {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new UnsupportedOperationException("magnification() method is not supported yet, "
                    + "because the parent is not set yet");
        }
        return parent.magnification();
    }

    public List<IRectangularArea> zeroLevelActualRectangles() {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new UnsupportedOperationException("zeroLevelActualRectangles() method is not supported yet, "
                    + "because the parent is not set yet");
        }
        return parent.zeroLevelActualRectangles();
    }

    @Override
    public List<List<List<IPoint>>> zeroLevelActualAreaBoundaries() {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new UnsupportedOperationException("zeroLevelActualAreaBoundaries() method is not supported yet, "
                    + "because the parent is not set yet");
        }
        return parent.zeroLevelActualAreaBoundaries();
    }

    public Matrix<? extends PArray> readSubMatrix(int resolutionLevel, long fromX, long fromY, long toX, long toY)
            throws NoSuchElementException, NotYetConnectedException {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new NotYetConnectedException();
        }
        return parent.readSubMatrix(resolutionLevel, fromX, fromY, toX, toY);
    }

    public boolean isFullMatrixSupported() {
        PlanePyramidSource parent = getParent();
        return parent != null && parent.isFullMatrixSupported();
    }

    public Matrix<? extends PArray> readFullMatrix(int resolutionLevel)
            throws NoSuchElementException, NotYetConnectedException, UnsupportedOperationException {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new NotYetConnectedException();
        }
        return parent.readFullMatrix(resolutionLevel);
    }

    public boolean isSpecialMatrixSupported(SpecialImageKind kind) {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new NotYetConnectedException();
        }
        return parent.isSpecialMatrixSupported(kind);
    }

    public Optional<Matrix<? extends PArray>> readSpecialMatrix(SpecialImageKind kind)
            throws NotYetConnectedException {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new NotYetConnectedException();
        }
        return parent.readSpecialMatrix(kind);
    }

    public boolean isDataReady() {
        PlanePyramidSource parent = getParent();
        return parent != null && parent.isDataReady();
    }

    public Optional<String> metadata() {
        PlanePyramidSource parent = getParent();
        if (parent == null) {
            throw new UnsupportedOperationException("metadata() method is not supported yet, "
                    + "because the parent is not set yet");
        }
        return parent.metadata();
    }

    public void loadResources() {
        PlanePyramidSource parent = getParent();
        if (parent != null) {
            parent.loadResources();
        }
    }

    public void freeResources(FlushMode flushMode) {
        PlanePyramidSource parent = getParent();
        if (parent != null) {
            parent.freeResources(flushMode);
        }
    }
}
