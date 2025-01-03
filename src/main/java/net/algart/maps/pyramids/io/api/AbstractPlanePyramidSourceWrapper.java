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

package net.algart.maps.pyramids.io.api;

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;

import java.nio.channels.NotYetConnectedException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalDouble;

public abstract class AbstractPlanePyramidSourceWrapper implements PlanePyramidSource {
    protected abstract PlanePyramidSource parent();

    public int numberOfResolutions() {
        return parent().numberOfResolutions();
    }

    public int compression() {
        return parent().compression();
    }

    public int bandCount() {
        return parent().bandCount();
    }

    public boolean isResolutionLevelAvailable(int resolutionLevel) {
        return parent().isResolutionLevelAvailable(resolutionLevel);
    }

    public boolean[] getResolutionLevelsAvailability() {
        return parent().getResolutionLevelsAvailability();
    }

    public long[] dimensions(int resolutionLevel) throws NoSuchElementException {
        return parent().dimensions(resolutionLevel);
    }

    public boolean isElementTypeSupported() {
        return parent().isElementTypeSupported();
    }

    public Class<?> elementType() throws UnsupportedOperationException {
        return parent().elementType();
    }

    @Override
    public OptionalDouble pixelSizeInMicrons() {
        return parent().pixelSizeInMicrons();
    }

    @Override
    public OptionalDouble magnification() {
        return parent().magnification();
    }

    public List<IRectangularArea> zeroLevelActualRectangles() {
        return parent().zeroLevelActualRectangles();
    }

    public List<List<List<IPoint>>> zeroLevelActualAreaBoundaries() {
        return parent().zeroLevelActualAreaBoundaries();
    }

    public Matrix<? extends PArray> readSubMatrix(
            int resolutionLevel, long fromX, long fromY, long toX, long toY)
            throws NoSuchElementException, NotYetConnectedException {
        return parent().readSubMatrix(resolutionLevel, fromX, fromY, toX, toY);
    }

    public boolean isFullMatrixSupported() {
        return parent().isFullMatrixSupported();
    }

    public Matrix<? extends PArray> readFullMatrix(int resolutionLevel)
            throws NoSuchElementException, NotYetConnectedException, UnsupportedOperationException {
        return parent().readFullMatrix(resolutionLevel);
    }

    public boolean isSpecialMatrixSupported(SpecialImageKind kind) {
        return parent().isSpecialMatrixSupported(kind);
    }

    public Optional<Matrix<? extends PArray>> readSpecialMatrix(SpecialImageKind kind)
            throws NotYetConnectedException {
        return parent().readSpecialMatrix(kind);
    }

    public boolean isDataReady() {
        return parent().isDataReady();
    }

    public Optional<String> metadata() {
        return parent().metadata();
    }

    public void loadResources() {
        parent().loadResources();
    }

    public void freeResources(FlushMode flushMode) {
        parent().freeResources(flushMode);
    }
}
