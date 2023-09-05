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
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidTools;

import java.nio.channels.NotYetConnectedException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

public final class CombinedPlanePyramidSource implements PlanePyramidSource {
    private PlanePyramidSource mainParent;
    private PlanePyramidSource overridingParent;
    private final int numberOfResolutions;

    private CombinedPlanePyramidSource(
            PlanePyramidSource mainParent,
            PlanePyramidSource overridingParent) {
        if (mainParent == null)
            throw new NullPointerException("Null mainParent");
        if (overridingParent == null)
            throw new NullPointerException("Null overridingParent");
        if (mainParent.bandCount() != overridingParent.bandCount())
            throw new IllegalArgumentException("bandCount mismatch in mainParent ("
                    + mainParent.bandCount() + ") and overridingParent ("
                    + overridingParent.bandCount() + ")");
        this.mainParent = mainParent;
        this.overridingParent = overridingParent;
        this.numberOfResolutions = Math.max(mainParent.numberOfResolutions(), overridingParent.numberOfResolutions());
    }

    public static PlanePyramidSource newInstance(
            PlanePyramidSource mainParent,
            PlanePyramidSource overridingParent) {
        if (mainParent == null && overridingParent == null)
            throw new NullPointerException("Both mainParent and overridingParent are null");
        if (overridingParent == null) {
            return mainParent;
        }
        if (mainParent == null) {
            return overridingParent;
        }
        return new CombinedPlanePyramidSource(mainParent, overridingParent);
    }

    public int numberOfResolutions() {
        return numberOfResolutions;
    }

    public int compression() {
        return PlanePyramidTools.defaultCompression(this);
    }

    public int bandCount() {
        return mainParent.bandCount();
    }

    public boolean isResolutionLevelAvailable(int resolutionLevel) {
        return existInMain(resolutionLevel) || existInOverriding(resolutionLevel);
    }

    public boolean[] getResolutionLevelsAvailability() {
        boolean[] result = new boolean[numberOfResolutions()];
        for (int k = 0; k < result.length; k++) {
            result[k] = isResolutionLevelAvailable(k);
        }
        return result;
    }

    public long[] dimensions(int resolutionLevel) {
        return existInOverriding(resolutionLevel) ?
                overridingParent.dimensions(resolutionLevel) :
                mainParent.dimensions(resolutionLevel);
    }

    public boolean isElementTypeSupported() {
        return mainParent.isElementTypeSupported() || overridingParent.isElementTypeSupported();
    }

    public Class<?> elementType() throws UnsupportedOperationException {
        return mainParent.isElementTypeSupported() ? mainParent.elementType() : overridingParent.elementType();
    }

    @Override
    public OptionalDouble pixelSizeInMicrons() {
        return existInOverriding(0) ?
                overridingParent.pixelSizeInMicrons() :
                mainParent.pixelSizeInMicrons();
    }

    @Override
    public OptionalDouble magnification() {
        return existInOverriding(0) ?
                overridingParent.magnification() :
                mainParent.magnification();
    }

    @Override
    public List<IRectangularArea> zeroLevelActualRectangles() {
        return existInOverriding(0) ?
                overridingParent.zeroLevelActualRectangles() :
                mainParent.zeroLevelActualRectangles();
    }

    @Override
    public List<List<List<IPoint>>> zeroLevelActualAreaBoundaries() {
        return existInOverriding(0) ?
                overridingParent.zeroLevelActualAreaBoundaries() :
                mainParent.zeroLevelActualAreaBoundaries();
    }

    public Matrix<? extends PArray> readSubMatrix(int resolutionLevel, long fromX, long fromY, long toX, long toY) {
        return existInOverriding(resolutionLevel) ?
                overridingParent.readSubMatrix(resolutionLevel, fromX, fromY, toX, toY) :
                mainParent.readSubMatrix(resolutionLevel, fromX, fromY, toX, toY);
    }

    public boolean isFullMatrixSupported() {
        return mainParent.isFullMatrixSupported()
                && overridingParent.isFullMatrixSupported();
    }

    public Matrix<? extends PArray> readFullMatrix(int resolutionLevel)
            throws UnsupportedOperationException {
        return existInOverriding(resolutionLevel) ?
                overridingParent.readFullMatrix(resolutionLevel) :
                mainParent.readFullMatrix(resolutionLevel);
    }

    public boolean isSpecialMatrixSupported(SpecialImageKind kind) {
        int resolutionLevel = numberOfResolutions() - 1;
        return existInOverriding(resolutionLevel) ?  // why not?
                overridingParent.isSpecialMatrixSupported(kind) :
                mainParent.isSpecialMatrixSupported(kind);
    }

    public Optional<Matrix<? extends PArray>> readSpecialMatrix(SpecialImageKind kind)
            throws NotYetConnectedException {
        int resolutionLevel = numberOfResolutions() - 1;
        return existInOverriding(resolutionLevel) ?  // why not?
                overridingParent.readSpecialMatrix(kind) :
                mainParent.readSpecialMatrix(kind);
    }

    public boolean isDataReady() {
        return mainParent.isDataReady() && overridingParent.isDataReady();
    }

    public Optional<String> metadata() {
        return existInOverriding(0) ?
                overridingParent.metadata() :
                mainParent.metadata();
    }

    public void loadResources() {
        mainParent.loadResources();
        overridingParent.loadResources();
    }

    public void freeResources(FlushMode flushMode) {
        mainParent.freeResources(flushMode);
        overridingParent.freeResources(flushMode);
    }

    private boolean existInMain(int level) {
        return level < mainParent.numberOfResolutions() && mainParent.isResolutionLevelAvailable(level);
    }

    private boolean existInOverriding(int level) {
        return level < overridingParent.numberOfResolutions() && overridingParent.isResolutionLevelAvailable(level);
    }
}
