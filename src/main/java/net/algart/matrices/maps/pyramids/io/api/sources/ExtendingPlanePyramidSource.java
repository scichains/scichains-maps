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
import net.algart.arrays.Arrays;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.matrices.maps.pyramids.io.api.AbstractPlanePyramidSource;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidTools;

import java.awt.*;
import java.nio.channels.NotYetConnectedException;
import java.util.*;
import java.util.List;

public final class ExtendingPlanePyramidSource extends AbstractPlanePyramidSource implements PlanePyramidSource {
    private static final System.Logger LOG = System.getLogger(ExtendingPlanePyramidSource.class.getName());

    private final PlanePyramidSource parent;
    private final List<long[]> dimensions;
    private final int compression;
    private final int bandCount;
    private final long extendedDimX;
    private final long extendedDimY;
    private final long positionXInExtendedMatrix;
    private final long positionYInExtendedMatrix;
    private final double[] backgroundColor;

    private int extendingBorderWidth = 0;
    private Color extendingBorderColor = Color.GRAY;

    private ExtendingPlanePyramidSource(
            PlanePyramidSource parent,
            long extendedDimX,
            long extendedDimY,
            long positionXInExtendedMatrix,
            long positionYInExtendedMatrix,
            double[] backgroundColor) {
        Objects.requireNonNull(parent, "Null parent");
        Objects.requireNonNull(backgroundColor, "Null backgroundColor");
        if (extendedDimX <= 0 || extendedDimY <= 0) {
            throw new IllegalArgumentException("Illegal extended dimensions " + extendedDimX + "x" + extendedDimY
                    + " (must be positive)");
        }
        if (backgroundColor.length == 0) {
            throw new IllegalArgumentException("Empty backgroundColor");
        }
        if (backgroundColor.length != 1 && backgroundColor.length != 3 && backgroundColor.length != 4) {
            throw new IllegalArgumentException("Illegal backgroundColor[" + backgroundColor.length
                    + "]: it must contain 1, 3 or 4 elements");
        }
        this.parent = parent;
        this.bandCount = Math.max(parent.bandCount(), backgroundColor.length);
        this.backgroundColor = new double[this.bandCount];
        for (int k = 0; k < this.bandCount; k++) {
            this.backgroundColor[k] = backgroundColor[Math.min(k, backgroundColor.length - 1)];
        }
        this.extendedDimX = extendedDimX;
        this.extendedDimY = extendedDimY;
        this.positionXInExtendedMatrix = positionXInExtendedMatrix;
        this.positionYInExtendedMatrix = positionYInExtendedMatrix;
        this.dimensions = new ArrayList<long[]>();
        this.compression = parent.compression();
        long lastDimX = this.extendedDimX;
        long lastDimY = this.extendedDimY;
        this.dimensions.add(new long[]{this.bandCount, lastDimX, lastDimY});
        for (int k = 1, n = parent.numberOfResolutions(); k < n; k++) {
            lastDimX /= this.compression;
            lastDimY /= this.compression;
            this.dimensions.add(new long[]{this.bandCount, lastDimX, lastDimY});
        }
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                "ExtendingPlanePyramidSource created on the base of %s: "
                        + "%dx%d, contains sub-image %dx%d at (%d,%d), %d bands, %d levels, compression in %d times",
                parent,
                extendedDimX, extendedDimY,
                parent.dim(0, DIM_WIDTH), parent.dim(0, DIM_HEIGHT),
                positionXInExtendedMatrix, positionYInExtendedMatrix,
                this.bandCount, this.dimensions.size(), this.compression));
    }

    public static ExtendingPlanePyramidSource newInstance(
            PlanePyramidSource parent,
            long extendedDimX,
            long extendedDimY,
            long positionXInExtendedMatrix,
            long positionYInExtendedMatrix,
            double[] backgroundColor) {
        return new ExtendingPlanePyramidSource(
                parent,
                extendedDimX, extendedDimY, positionXInExtendedMatrix, positionYInExtendedMatrix,
                backgroundColor);
    }

    public PlanePyramidSource parent() {
        return parent;
    }

    public long getExtendedDimX() {
        return extendedDimX;
    }

    public long getExtendedDimY() {
        return extendedDimY;
    }

    public long getPositionXInExtendedMatrix() {
        return positionXInExtendedMatrix;
    }

    public long getPositionYInExtendedMatrix() {
        return positionYInExtendedMatrix;
    }

    public int getExtendingBorderWidth() {
        if (extendingBorderWidth < 0) {
            throw new IllegalArgumentException("Negative extendingBorderWidth");
        }
        return extendingBorderWidth;
    }

    public void setExtendingBorderWidth(int extendingBorderWidth) {
        this.extendingBorderWidth = extendingBorderWidth;
    }

    public Color getExtendingBorderColor() {
        return extendingBorderColor;
    }

    public void setExtendingBorderColor(Color extendingBorderColor) {
        if (extendingBorderColor == null) {
            throw new NullPointerException("Null extendingBorderColor");
        }
        this.extendingBorderColor = extendingBorderColor;
    }

    @Override
    public int numberOfResolutions() {
        return this.dimensions.size();
    }

    @Override
    public int compression() {
        return compression;
    }

    @Override
    public int bandCount() {
        return this.bandCount;
    }

    @Override
    public boolean isResolutionLevelAvailable(int resolutionLevel) {
        return parent.isResolutionLevelAvailable(resolutionLevel);
    }

    @Override
    public boolean[] getResolutionLevelsAvailability() {
        return parent.getResolutionLevelsAvailability();
    }

    @Override
    public long[] dimensions(int resolutionLevel) {
        return dimensions.get(resolutionLevel).clone();
    }

    @Override
    public long dim(int resolutionLevel, int index) {
        return dimensions.get(resolutionLevel)[index];
    }

    @Override
    public boolean isElementTypeSupported() {
        return parent.isElementTypeSupported();
    }

    @Override
    public Class<?> elementType() throws UnsupportedOperationException {
        return parent.elementType();
    }

    @Override
    public OptionalDouble pixelSizeInMicrons() {
        return parent.pixelSizeInMicrons();
    }

    @Override
    public OptionalDouble magnification() {
        return parent.magnification();
    }

    @Override
    public List<IRectangularArea> zeroLevelActualRectangles() {
        List<IRectangularArea> parentRectangles = parent.zeroLevelActualRectangles();
        if (parentRectangles == null) {
            parentRectangles = defaultZeroLevelActualRectangles(parent);
            if (parentRectangles == null) {
                return null;
            }
            final var pr = parentRectangles;
            LOG.log(System.Logger.Level.DEBUG, () -> "Creating default zero-level actual rectangle: " + pr);
        }
        final IPoint shift = IPoint.valueOf(positionXInExtendedMatrix, positionYInExtendedMatrix);
        final List<IRectangularArea> result = new ArrayList<IRectangularArea>(parentRectangles.size());
        for (IRectangularArea parentRectangle : parentRectangles) {
            final IRectangularArea shiftedRectangle = parentRectangle.shift(shift);
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "Shifting zero-level actual rectangle %s by (%d,%d)",
                    parentRectangle, positionXInExtendedMatrix, positionYInExtendedMatrix));
            result.add(shiftedRectangle);
        }
        return result;
    }

    @Override
    public List<List<List<IPoint>>> zeroLevelActualAreaBoundaries() {
        final List<List<List<IPoint>>> boundaries = parent.zeroLevelActualAreaBoundaries();
        if (boundaries == null) {
            return super.zeroLevelActualAreaBoundaries();
        }
        final IPoint shift = IPoint.valueOf(positionXInExtendedMatrix, positionYInExtendedMatrix);
        final List<List<List<IPoint>>> result = new ArrayList<List<List<IPoint>>>();
        for (List<List<IPoint>> area : boundaries) {
            final List<List<IPoint>> shiftedArea = new ArrayList<List<IPoint>>();
            for (List<IPoint> boundary : area) {
                final List<IPoint> shiftedBoundary = new ArrayList<IPoint>();
                for (IPoint p : boundary) {
                    shiftedBoundary.add(p.add(shift));
                }
                LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                        "Shifting zero-level actual area boundary %s by (%d,%d)",
                        boundary, positionXInExtendedMatrix, positionYInExtendedMatrix));
                shiftedArea.add(shiftedBoundary);
            }
            result.add(shiftedArea);
        }
        return result;
    }

    @Override
    public boolean isSpecialMatrixSupported(SpecialImageKind kind) {
        return parent.isSpecialMatrixSupported(kind);
    }

    @Override
    public Optional<Matrix<? extends PArray>> readSpecialMatrix(SpecialImageKind kind)
            throws NotYetConnectedException {
        return parent.readSpecialMatrix(kind);
    }

    public boolean isDataReady() {
        return parent.isDataReady();
    }

    @Override
    public Optional<String> metadata() {
        return parent.metadata();
    }

    public void loadResources() {
        parent.loadResources();
        super.loadResources();
    }

    public void freeResources(FlushMode flushMode) {
        super.freeResources(flushMode);
        parent.freeResources(flushMode);
    }

    @Override
    protected Matrix<? extends PArray> readLittleSubMatrix(
            int resolutionLevel, long fromX, long fromY, long toX, long toY)
            throws NoSuchElementException, NotYetConnectedException {
        checkSubMatrixRanges(resolutionLevel, fromX, fromY, toX, toY, false);
        final long sizeX = toX - fromX;
        final long sizeY = toY - fromY;
        if (sizeX == 0 || sizeY == 0) {
            // Note: bandCount can be greater than in the parent!
            final Class<?> elementType = parent.readSubMatrix(resolutionLevel, 0, 0, sizeX, sizeY).elementType();
            return Arrays.SMM.newMatrix(UpdatablePArray.class, elementType, bandCount, sizeX, sizeY);
        }
        long x = positionXInExtendedMatrix;
        long y = positionYInExtendedMatrix;
        for (int k = 0; k < resolutionLevel; k++) {
            x /= compression;
            y /= compression;
        }
        final long[] parentDimensions = parent.dimensions(resolutionLevel);
        final long aMinX = x;
        final long aMinY = y;
        final long aMaxX = x + parentDimensions[1] - 1;
        final long aMaxY = y + parentDimensions[2] - 1;
        assert aMinX <= aMaxX && aMinY <= aMaxY : "Illegal implementation of " + parent;
        if (aMinX <= fromX && aMinY <= fromY && aMaxX + 1 >= toX && aMaxY + 1 >= toY
                && bandCount == parent.bandCount()) {
            return parent.readSubMatrix(
                    resolutionLevel, fromX - x, fromY - y, toX - x, toY - y);
        }
        final long partFromX = Math.max(fromX, aMinX);
        final long partFromY = Math.max(fromY, aMinY);
        final long partToX = Math.min(toX, aMaxX + 1);
        final long partToY = Math.min(toY, aMaxY + 1);
        final boolean hasActualData = partFromX < partToX && partFromY < partToY;
        final Matrix<? extends PArray> actual = hasActualData ?
                parent.readSubMatrix(resolutionLevel, partFromX - x, partFromY - y, partToX - x, partToY - y) :
                parent.readSubMatrix(resolutionLevel, 0, 0, 0, 0); // used for retrieving elementType only
        final long aBandCount = actual.dim(0);

        final Matrix<? extends UpdatablePArray> result = Arrays.SMM.newMatrix(
                UpdatablePArray.class, actual.elementType(), bandCount, sizeX, sizeY);
        PlanePyramidTools.fillMatrix(result, backgroundColor);
        if (hasActualData && bandCount == 4 && aBandCount < 4) { // the area under the actual data must be opaque
            PlanePyramidTools.fillMatrix(result,
                    partFromX - fromX, partFromY - fromY,
                    partToX - fromX, partToY - fromY,
                    new double[]{1.0, 1.0, 1.0, 1.0});
        }

        final long borderedMinX = aMinX - extendingBorderWidth;
        final long borderedMaxX = aMaxX + extendingBorderWidth;
        final long borderedMinY = aMinY - extendingBorderWidth;
        final long borderedMaxY = aMaxY + extendingBorderWidth;
        if (fromX > borderedMaxX || fromY > borderedMaxY || toX <= borderedMinX || toY <= borderedMinY) {
            return result; // out of bordered actual area
        }
        if (extendingBorderWidth > 0) {
            final long borderedPartFromX = Math.max(fromX, borderedMinX);
            final long borderedPartFromY = Math.max(fromY, borderedMinY);
            final long borderedPartToX = Math.min(toX, borderedMaxX + 1);
            final long borderedPartToY = Math.min(toY, borderedMaxY + 1);
            PlanePyramidTools.fillMatrix(result,
                    borderedPartFromX - fromX, borderedPartFromY - fromY,
                    borderedPartToX - fromX, borderedPartToY - fromY,
                    extendingBorderColor);
        }

        if (!hasActualData) {
            return result; // out of (non-bordered) actual area
        }
        result.subMatrix(0, partFromX - fromX, partFromY - fromY, aBandCount, partToX - fromX, partToY - fromY)
                .array().copy(actual.array());
        // this operator may be slow enough, if bandCount is greater than in the parent!
        if (aBandCount == 1 && bandCount >= 3) { // need to copy monochrome image also into G and B components
            result.subMatrix(1, partFromX - fromX, partFromY - fromY, 2, partToX - fromX, partToY - fromY)
                    .array().copy(actual.array());
            result.subMatrix(2, partFromX - fromX, partFromY - fromY, 3, partToX - fromX, partToY - fromY)
                    .array().copy(actual.array());
        }
        return result;
    }
}
