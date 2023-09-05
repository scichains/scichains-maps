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

import net.algart.arrays.Arrays;
import net.algart.arrays.*;
import net.algart.external.MatrixToBufferedImageConverter;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.math.Range;
import net.algart.math.functions.LinearFunc;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.*;

public class ScalablePlanePyramidSource implements PlanePyramidSource {
    static final int TIME_ENFORCING_GC =
            Arrays.SystemSettings.getIntProperty(
                    "net.algart.matrices.maps.pyramids.io.api.timeEnforcingGc",
                    0);
    // - in milliseconds; not used if 0

    private static final System.Logger LOG = System.getLogger(ScalablePlanePyramidSource.class.getName());

    private final PlanePyramidSource parent;

    private final int numberOfResolutions;
    private final List<long[]> dimensions;
    private final long dimX;
    private final long dimY;
    private final int compression;
    private final int bandCount;

    private Class<?> elementType = null;
    private final Object elementTypeLock = new Object();

    private volatile AveragingMode averagingMode = AveragingMode.DEFAULT;
    private volatile Color backgroundColor = new Color(255, 255, 255, 0);
    // transparent if possible, white in other case

    private final SpeedInfo pyramidSourceSpeedInfo = new SpeedInfo();
    private final SpeedInfo readImageSpeedInfo = new SpeedInfo();
    private final SpeedInfo readBufferedImageSpeedInfo = new SpeedInfo();

    private ScalablePlanePyramidSource(final PlanePyramidSource parent) {
        Objects.requireNonNull(parent, "Null parent source");
        this.parent = parent;
        for (int level = 0, n = parent.numberOfResolutions(); level < n; level++) {
            if (!this.parent.isResolutionLevelAvailable(level)) {
                throw new IllegalArgumentException("Pyramid source must contain all resolution levels, "
                        + "but there is no level #" + level);
            }
        }
        this.compression = parent.compression();
        this.numberOfResolutions = parent.numberOfResolutions();
        this.bandCount = parent.bandCount();
        this.dimensions = new ArrayList<>();
        for (int k = 0; k < numberOfResolutions; k++) {
            this.dimensions.add(parent.dimensions(k));
        }
        this.dimX = dimensions.get(0)[DIM_WIDTH];
        this.dimY = dimensions.get(0)[DIM_HEIGHT];
    }

    public static ScalablePlanePyramidSource newInstance(final PlanePyramidSource parent) {
        return new ScalablePlanePyramidSource(parent);
    }

    @Override
    public int numberOfResolutions() {
        return numberOfResolutions;
    }

    @Override
    public int bandCount() {
        return bandCount;
    }

    @Override
    public boolean isResolutionLevelAvailable(int resolutionLevel) {
        return true;
    }

    @Override
    public boolean[] getResolutionLevelsAvailability() {
        boolean[] result = new boolean[numberOfResolutions()];
        JArrays.fillBooleanArray(result, true);
        return result;
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
        return true;
    }

    @Override
    public Class<?> elementType() {
        synchronized (elementTypeLock) {
            // volatile should be enough: following code always leads to the same results;
            // but SonarQube does not "understand" this
            Class<?> elementType = this.elementType;
            if (elementType == null) {
                if (parent.isElementTypeSupported()) {
                    this.elementType = elementType = parent.elementType();
                } else {
                    // the parent source does not "know" the element type:
                    // let's try to get a little matrix and get its element type
                    this.elementType = elementType =
                            parent.readSubMatrix(0, 0, 0, 1, 1).elementType();
                }
            }
            return elementType;
        }
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
        return parent.zeroLevelActualRectangles();
    }

    @Override
    public List<List<List<IPoint>>> zeroLevelActualAreaBoundaries() {
        return parent.zeroLevelActualAreaBoundaries();
    }

    @Override
    public Matrix<? extends PArray> readSubMatrix(
            final int resolutionLevel,
            final long fromX, final long fromY,
            final long toX, final long toY) {
        return new SubMatrixExtracting(resolutionLevel, fromX, fromY, toX, toY).extractSubMatrix().fullData;
    }

    @Override
    public boolean isFullMatrixSupported() {
        return parent.isFullMatrixSupported();
    }

    @Override
    public Matrix<? extends PArray> readFullMatrix(int resolutionLevel) {
        if (!isFullMatrixSupported()) {
            throw new UnsupportedOperationException("readFullMatrix method is not supported");
        }
        return parent.readFullMatrix(resolutionLevel);
    }

    @Override
    public boolean isSpecialMatrixSupported(SpecialImageKind kind) {
        return parent.isSpecialMatrixSupported(kind);
    }

    @Override
    public Optional<Matrix<? extends PArray>> readSpecialMatrix(SpecialImageKind kind) {
        return parent.readSpecialMatrix(kind);
    }

    @Override
    public boolean isDataReady() {
        return parent.isDataReady();
    }

    @Override
    public int compression() {
        return compression;
    }

    public PlanePyramidSource parent() {
        return parent;
    }

    public long dimX() {
        return dimX;
    }

    public long dimY() {
        return dimY;
    }

    public AveragingMode getAveragingMode() {
        return averagingMode;
    }

    public ScalablePlanePyramidSource setAveragingMode(AveragingMode averagingMode) {
        if (averagingMode == null) {
            throw new NullPointerException("Null averaging mode");
        }
        this.averagingMode = averagingMode;
        return this;
    }

    public Color getBackgroundColor() {
        return backgroundColor;
    }

    public ScalablePlanePyramidSource setBackgroundColor(Color backgroundColor) {
        if (backgroundColor == null) {
            throw new NullPointerException("Null background color");
        }
        this.backgroundColor = backgroundColor;
        return this;
    }

    // Recommended for viewers
    public void forceAveragingBits() {
        if (averagingMode == AveragingMode.DEFAULT) {
            averagingMode = AveragingMode.AVERAGING;
        }
    }

    public double compression(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("Negative level");
        }
        double c = 1.0;
        for (int k = 0; k < level; k++) {
            c *= compression;
        }
        return c;
    }

    public int maxLevel(double compression) {
        if (compression < 1.0) {
            throw new IllegalArgumentException("Compression must not be less than 1.0");
        }
        int level = 0;
        for (double c = this.compression; c <= compression; c *= this.compression) {
            level++;
        }
        return level;
    }

    @Override
    public Optional<String> metadata() {
        return parent.metadata();
    }

    @Override
    public void loadResources() {
        parent.loadResources();
    }

    @Override
    public void freeResources(FlushMode flushMode) {
        parent.freeResources(flushMode);
    }

    public Matrix<? extends PArray> readImage(
            final double compression,
            final long zeroLevelFromX,
            final long zeroLevelFromY,
            final long zeroLevelToX,
            final long zeroLevelToY) {
        checkFromAndTo(zeroLevelFromX, zeroLevelFromY, zeroLevelToX, zeroLevelToY);
        long t1 = System.nanoTime();
        final int level = Math.min(maxLevel(compression), numberOfResolutions - 1);
        // - this call also checks that compression >= 1
        final ImageScaling scaling = new ImageScaling(
                level, compression, zeroLevelFromX, zeroLevelFromY, zeroLevelToX, zeroLevelToY);
        long t2 = System.nanoTime();
        final Matrix<? extends PArray> result = scaling.scaleImage();
        long t3 = System.nanoTime();
        final String averageSpeed = readImageSpeedInfo.update(Matrices.sizeOf(result), t3 - t1, true);
        Runtime runtime = Runtime.getRuntime();
        LOG.log(System.Logger.Level.DEBUG, scaling::scaleImageTiming);
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                "%s has read image (%d-bit, %d CPU for AlgART, used memory %.3f/%.3f MB, compression %.2f): "
                        + "%d..%d x %d..%d (%d x %d%s) in %.3f ms (%.3f init + %.3f scaled reading), "
                        + "%.3f MB/sec, average %s (source: %s)",
                ScalablePlanePyramidSource.class.getSimpleName(),
                Arrays.SystemSettings.isJava32() ? 32 : 64,
                Arrays.SystemSettings.cpuCount(),
                (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0, runtime.maxMemory() / 1048576.0,
                compression, zeroLevelFromX, zeroLevelToX, zeroLevelFromY, zeroLevelToY,
                zeroLevelToX - zeroLevelFromX, zeroLevelToY - zeroLevelFromY,
                Arrays.isNCopies(result.array()) ? ", CONSTANT" : "",
                (t3 - t1) * 1e-6, (t2 - t1) * 1e-6, (t3 - t2) * 1e-6,
                Matrices.sizeOf(result) / 1048576.0 / ((t3 - t1) * 1e-9), averageSpeed,
                parent.getClass().getSimpleName()));
        return result;
    }

    public BufferedImage readBufferedImage(
            final double compression,
            final long zeroLevelFromX,
            final long zeroLevelFromY,
            final long zeroLevelToX,
            final long zeroLevelToY,
            final MatrixToBufferedImageConverter converter) {
        Objects.requireNonNull(converter, "Null converter");
        checkFromAndTo(zeroLevelFromX, zeroLevelFromY, zeroLevelToX, zeroLevelToY);
        long t1 = System.nanoTime();
        final int level = Math.min(maxLevel(compression), numberOfResolutions - 1);
        // - this call also checks that compression >= 1
        final ImageScaling scaling = new ImageScaling(
                level, compression, zeroLevelFromX, zeroLevelFromY, zeroLevelToX, zeroLevelToY);
        long t2 = System.nanoTime();
        Matrix<? extends PArray> m = scaling.scaleImage();
        long t3 = System.nanoTime();
        if (converter.byteArrayRequired() && m.elementType() != byte.class) {
            double max = m.array().maxPossibleValue(1.0);
            m = Matrices.asFuncMatrix(LinearFunc.getInstance(0.0, 255.0 / max), ByteArray.class, m);
        }
        if (m.size() == 0) {
            m = m.subMatr(0, 0, 0, m.dim(0), Math.max(1, m.dim(1)), Math.max(1, m.dim(2)),
                    Matrix.ContinuationMode.ZERO_CONSTANT);
            // BufferedImage cannot be empty
        }
        final int width = converter.getWidth(m); // must be after conversion to byte, to avoid IllegalArgumentException
        final int height = converter.getHeight(m);
        final Collection<IRectangularArea> backgroundAreas =
                getBackgroundAreasInRectangle(zeroLevelFromX, zeroLevelFromY, zeroLevelToX, zeroLevelToY);
        java.awt.image.DataBuffer dataBuffer = converter.toDataBuffer(m);
        if (!backgroundAreas.isEmpty()) {
            final IPoint shift = IPoint.valueOf(-zeroLevelFromX, -zeroLevelFromY);
            for (int bankIndex = 0; bankIndex < dataBuffer.getNumBanks(); bankIndex++) {
                Matrix<? extends UpdatablePArray> bankMatrix = Matrices.matrix(
                        (UpdatablePArray) SimpleMemoryModel.asUpdatableArray(
                                MatrixToBufferedImageConverter.getDataArray(dataBuffer, bankIndex)),
                        width, height);
                long filler = converter.colorValue(m, backgroundColor, bankIndex);
                for (IRectangularArea a : backgroundAreas) {
                    fillBackgroundInMatrix2DWithCompression(
                            bankMatrix, a, shift, compression, level == 0, scaling.needAdditionalCompression, filler);
                }
            }
        }
        final BufferedImage bufferedImage = converter.toBufferedImage(m, dataBuffer);

        long t4 = System.nanoTime();
        final long sizeOfMatrix = Matrices.sizeOf(m);
        final String averageSpeed = readBufferedImageSpeedInfo.update(sizeOfMatrix, t4 - t1, true);
        Runtime runtime = Runtime.getRuntime();
        LOG.log(System.Logger.Level.DEBUG, scaling::scaleImageTiming);
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                "%s has read buffered image (%d-bit, %d CPU for AlgART, "
                        + "used memory %.3f/%.3f MB, compression %.2f): "
                        + "%d..%d x %d..%d (%d x %d) in %.3f ms "
                        + "(%.3f init + %.3f scaled reading + %.3f conversion), "
                        + "%.3f MB/sec, average %s (source: %s)",
                ScalablePlanePyramidSource.class.getSimpleName(),
                Arrays.SystemSettings.isJava32() ? 32 : 64,
                Arrays.SystemSettings.cpuCount(),
                (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0, runtime.maxMemory() / 1048576.0,
                compression, zeroLevelFromX, zeroLevelToX, zeroLevelFromY, zeroLevelToY,
                zeroLevelToX - zeroLevelFromX, zeroLevelToY - zeroLevelFromY,
                (t4 - t1) * 1e-6, (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6,
                sizeOfMatrix / 1048576.0 / ((t4 - t1) * 1e-9), averageSpeed,
                parent.getClass().getSimpleName()
        ));
        return bufferedImage;
    }

    public String toString() {
        return "ScalablePlanePyramid " + bandCount + "x" + dimX() + "x" + dimY()
                + ", " + numberOfResolutions + " levels, compression " + compression
                + ", based on " + parent;
    }

    private Matrix<? extends PArray> callAndCheckParentReadSubMatrix(
            int resolutionLevel,
            long fromX,
            long fromY,
            long toX,
            long toY) {
        long t1 = System.nanoTime();
        Matrix<? extends PArray> m = parent.readSubMatrix(resolutionLevel, fromX, fromY, toX, toY);
        long t2 = System.nanoTime();
        if (m == null || m.dimCount() != 3 || m.dim(0) != bandCount
                || m.dim(1) != toX - fromX || m.dim(2) != toY - fromY) {
            throw new AssertionError("Invalid implementation of " + parent.getClass()
                    + ".readSubMatrix (fromX = "
                    + fromX + ", fromY = " + fromY + ", toX = " + toX + ", toY = " + toY
                    + "): incorrect dimensions of the returned matrix " + m);
        }
        final String averageSpeed = pyramidSourceSpeedInfo.update(Matrices.sizeOf(m), t2 - t1);
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                "%s.callAndCheckParentReadSubMatrix timing (level %d, %d..%d x %d..%d (%d x %d%s): "
                        + "%.3f ms, %.3f MB/sec, average %s (source: %s)",
                ScalablePlanePyramidSource.class.getSimpleName(),
                resolutionLevel, fromX, toX, fromY, toY, toX - fromX, toY - fromY,
                Arrays.isNCopies(m.array()) ? ", CONSTANT" : "",
                (t2 - t1) * 1e-6, Matrices.sizeOf(m) / 1048576.0 / ((t2 - t1) * 1e-9), averageSpeed,
                parent.getClass().getSimpleName()
        ));
        return m;
    }

    private Matrix<UpdatablePArray> newResultMatrix(Class<?> elementType, long dimX, long dimY) {
        Matrix<UpdatablePArray> result = Arrays.SMM.newMatrix(
                Arrays.SystemSettings.maxTempJavaMemory(),
                UpdatablePArray.class,
                elementType,
                bandCount(), dimX, dimY);
        if (!SimpleMemoryModel.isSimpleArray(result.array())) {
            result = result.tile(result.dim(0), 1024, 1024);
        }
        return result;
    }

    private Collection<IRectangularArea> getBackgroundAreasInRectangle(
            long zeroLevelFromX, long zeroLevelFromY, long zeroLevelToX, long zeroLevelToY) {
        List<IRectangularArea> result = new ArrayList<>();
        if (zeroLevelToX <= zeroLevelFromX || zeroLevelToY <= zeroLevelFromY) {
            return result;
        }
        final IRectangularArea area = IRectangularArea.valueOf(
                IPoint.valueOf(zeroLevelFromX, zeroLevelFromY),
                IPoint.valueOf(zeroLevelToX - 1, zeroLevelToY - 1));
        final IRectangularArea allArea = IRectangularArea.valueOf(
                IPoint.valueOf(0, 0),
                IPoint.valueOf(dimX - 1, dimY - 1));
        area.difference(result, allArea);
        return result;
    }

    private void fillBackgroundInMatrix2DWithCompression(
            Matrix<? extends UpdatablePArray> filledMatrix,
            IRectangularArea filledArea,
            IPoint shift,
            double compression,
            boolean compressionFromZeroLevel,
            boolean dataWasScaled,
            long filler) {
        if (filledMatrix == null) {
            throw new NullPointerException("Null filled matrix");
        }
        if (filledArea == null) {
            throw new NullPointerException("Null filled area");
        }
        if (shift == null) {
            throw new NullPointerException("Null shift");
        }
        if (filledMatrix.dimCount() != 2) {
            throw new IllegalArgumentException("The filled matrix must be 2-dimensional");
        }
        if (filledArea.coordCount() != 2) {
            throw new IllegalArgumentException("The filled area must be 2-dimensional");
        }
        filledArea = filledArea.shift(shift);
        final long c = (long) compression;
        final boolean integerCompression = c == compression;
        final boolean powerOfTwo = integerCompression && (c & (c - 1)) == 0
                && (this.compression & (this.compression - 1)) == 0;
        final double deltaFrom = powerOfTwo ? 0.0 : compressionFromZeroLevel ? 0.000001 : 1.000001;
        final double deltaTo = powerOfTwo ? 0.0 : compressionFromZeroLevel ? 0.000001 : 2.000001;
//        final double shiftDeltaX = !dataWasScaled || integerCompression && shift.coord(0) % c == 0 ? 0 : 1;
//        final double shiftDeltaY = !dataWasScaled || integerCompression && shift.coord(1) % c == 0 ? 0 : 1;
//      - Obsolete solution, leading to "eating" boundary pixels, that is especially bad when the picture
//      has borders. We prefer to mix the boundary pixels with WHITE background in this case.
        final double shiftDeltaX = integerCompression ? 0 : 1;
        final double shiftDeltaY = integerCompression ? 0 : 1;
        // (End of delta calculation: for preprocessing)
        // delta=0.0 when precise 2^k: in this case all calculations will be precise
        // In other (rare) cases, it usually does not affect, but to be on the safe side
        // we prefer to mistake with an extra background pixel instead of an incorrect edge.
//        System.out.println("powerOfTwo = " + powerOfTwo + "; " +  filledArea.min(0) / compression + ", "
//            + filledArea.min(1) / compression + ", "
//            + filledArea.max(0) / compression + ", "
//            + filledArea.max(1) / compression);
        // After one compression in B times (POLYLINEAR_AVERAGING mode), the resulting pixel with coordinate X
        // depends on original (interpolated) real pixels X'1<=X'<=X'2,
        //     X'1 = X*B
        //     X'2 = (X+1)*B - d (here d is a little positive number ~1/round(B))
        // or, so, on integer pixels N1<=N<=N2,
        //     N1 = floor(X'1) = floor(X*B)
        //     N2 = ceil(X'2) = ceil((X+1)*B)
        // On the other hand, background filledArea X'a..X'b (integer) affects to pixels X1<=X<=X2,
        //     X1 = floor(X'a/B)
        // (really, if X<=X1-1, then the corresponding X'2<(X+1)*B<=X1*B<=X'a, i.e. X'2 < X'a and N2 < X'a)
        //     X2 = ceil((X'b+1)/B)-1
        // (really, if X>=X2+1, then the corresponding X'1=X*B>=(X2+1)*B>=X'b+1, i.e. X'1 >= X'b+1 and N1 > X'b)

        // But if !compressionFromZeroLevel, it means that this method is called for correction of double compression
        // (scaling): first compression in A times to the pyramid layer (usually a power of "compression" field
        // of this object) and second compression in B times from the nearest pyramid layer
        // (usually <this.compression). In this case, the dependence filledArea (real) X'1<=X'<=X'2 is
        //     X'1 = N1*A = floor(X*B)*A > (X*B-1)*A > (X-1)*AB
        //     X'2 = (N2+1)*A = (ceil((X+1)*B)+1)*A < ((X+1)*B+2)*A < (X+3)*AB
        // In other words, we need subtract 1 from X1 and add 2 to X2.
        // In a case of power of two, no any gaps are necessary (special case)
        final long fromX = (long) Math.floor(filledArea.min(0) / compression - deltaFrom - shiftDeltaX); // = X1
        final long fromY = (long) Math.floor(filledArea.min(1) / compression - deltaFrom - shiftDeltaY); // = Y1
        final long toX = (long) Math.ceil((filledArea.max(0) + 1) / compression + deltaTo + shiftDeltaX); // = X2+1
        final long toY = (long) Math.ceil((filledArea.max(1) + 1) / compression + deltaTo + shiftDeltaY); // = Y2+1
//        System.out.printf(Locale.US, "Drawing %d..%d x %d..%d, %.3f, %s%n",
//            fromX, toX - 1, fromY, toY - 1, compression, filledArea);
        //[[Repeat.IncludeEnd]]
        filledMatrix.subMatrix(fromX, fromY, toX, toY, Matrix.ContinuationMode.NULL_CONSTANT).array().fill(filler);
    }

    private static void checkFromAndTo(long fromX, long fromY, long toX, long toY) {
        if (fromX > toX || fromY > toY) {
            throw new IndexOutOfBoundsException("Illegal fromX..toX=" + fromX + ".." + toX
                    + " or fromY..toY=" + fromY + ".." + toY + ": must be fromX<=toX, fromY<=toY");
        }
    }

    private class ImageScaling {
        // The following fields are filled by the constructor:
        final int level;
        final double totalCompression;
        final long roundedTotalCompression;
        final long levelCompression;
        final long zeroLevelFromX;
        final long zeroLevelFromY;
        final long zeroLevelToX;
        final long zeroLevelToY;
        final long levelFromX;
        final long levelFromY;
        long levelToX;
        long levelToY;
        long newDimX;
        long newDimY;
        final double additionalCompression;
        final boolean needAdditionalCompression;
        final boolean additionalCompressionIsInteger;

        private long scaleImageExtractingTime = 0;
        private long scaleImageCompressionTime = 0;

        ImageScaling(
                final int level,
                final double compression,
                final long zeroLevelFromX,
                final long zeroLevelFromY,
                final long zeroLevelToX,
                final long zeroLevelToY) {
            assert zeroLevelFromX <= zeroLevelToX;
            assert zeroLevelFromY <= zeroLevelToY;
            this.zeroLevelFromX = zeroLevelFromX;
            this.zeroLevelFromY = zeroLevelFromY;
            this.zeroLevelToX = zeroLevelToX;
            this.zeroLevelToY = zeroLevelToY;
            assert level >= 0;
            assert compression > 0;
            this.level = level;
            final double levelCompression = compression(level);
            assert levelCompression > 0 && levelCompression <= compression;
            this.totalCompression = compression;
            this.roundedTotalCompression = StrictMath.round(compression);
            this.levelFromX = safeFloor(zeroLevelFromX / levelCompression);
            this.levelFromY = safeFloor(zeroLevelFromY / levelCompression);
            this.levelToX = safeFloor(zeroLevelToX / levelCompression);
            this.levelToY = safeFloor(zeroLevelToY / levelCompression);
            this.additionalCompression = compression / levelCompression;
            this.levelCompression = Math.round(levelCompression);
            // - Math.round returns the same number, excepting a case of 63-bit overflow (levelCompression > 2^63)
            this.needAdditionalCompression = Math.abs(additionalCompression - 1.0) > 1e-4;
            this.newDimX = levelToX - levelFromX;
            this.newDimY = levelToY - levelFromY;
            if (needAdditionalCompression) {
                // we provide a guarantee that the sizes of the scaled matrix are always newDimX * newDimY;
                // so, we must retain previous newDimX and newDimY if we are not going to additionally scale
                newDimX = Math.min(newDimX, safeFloor(newDimX / additionalCompression));
                newDimY = Math.min(newDimY, safeFloor(newDimY / additionalCompression));
                // - "min" here is to be on the safe side
            }
            if (needAdditionalCompression) {
                assert additionalCompression > 1.0;
                final long intAdditionalCompression = Math.round(additionalCompression);
                this.additionalCompressionIsInteger =
                        Math.abs(additionalCompression - intAdditionalCompression) < 1e-7;
                if (additionalCompressionIsInteger) {
                    levelToX = Math.min(levelToX, levelFromX + intAdditionalCompression * newDimX);
                    levelToY = Math.min(levelToY, levelFromY + intAdditionalCompression * newDimY);
                    // in other words, we prefer to lose 1-2 last pixels, but provide strict
                    // integer compression: AlgART libraries are optimized for this situation
//                System.out.printf("Correcting area: ..%d x ..%d%n", this.levelToX, this.levelToY);
                }
            } else {
                this.additionalCompressionIsInteger = false;
            }
            LOG.log(System.Logger.Level.TRACE, () -> String.format(Locale.US,
                    "Resizing %d..%d x %d..%d (level %d) into %dx%d, additional compression %.3f"
                            + " (%.3f/X, %.3f/Y, %snecessary%s)",
                    levelFromX, levelToX - 1, levelFromY, levelToY - 1, level, newDimX, newDimY,
                    additionalCompression,
                    (double) (levelToX - levelFromX) / newDimX, (double) (levelToY - levelFromY) / newDimY,
                    needAdditionalCompression ? "" : "NOT ",
                    additionalCompressionIsInteger ? " and is integer" : ""));
        }

        Matrix<? extends PArray> scaleImage() {
            long t1 = System.nanoTime();
            Matrix<? extends PArray> sourceData = readSubMatrix(
                    level, levelFromX, levelFromY, levelToX, levelToY);
            long t2 = System.nanoTime();
            scaleImageExtractingTime = t2 - t1;
            if (needAdditionalCompression) {
                final boolean convertBitToByte;
                synchronized (elementTypeLock) {
                    convertBitToByte = needAdditionalCompression
                            && averagingMode == AveragingMode.AVERAGING
                            && elementType == boolean.class;
                }
                if (convertBitToByte) {
                    Range srcRange = Range.valueOf(0.0, sourceData.array().maxPossibleValue(1.0));
                    Range destRange = Range.valueOf(0.0, Arrays.maxPossibleIntegerValue(ByteArray.class));
                    sourceData = Matrices.asFuncMatrix(LinearFunc.getInstance(destRange, srcRange),
                            ByteArray.class, sourceData);
                }
                Matrix<? extends UpdatablePArray> resized = newResultMatrix(
                        sourceData.elementType(), newDimX, newDimY);
                doResize(resized, sourceData);
                long t3 = System.nanoTime();
                scaleImageCompressionTime = t3 - t2;
//                System.out.printf("Additional compression in %.2f times: %.3f ms%n",
//                    additionalCompression, (t3 - t2) * 1e-6);
                return resized;
            } else {
                assert sourceData.dim(DIM_WIDTH) == newDimX;
                assert sourceData.dim(DIM_HEIGHT) == newDimY;
                return sourceData;
            }
        }

        String scaleImageTiming() {
            return String.format(Locale.US,
                    "%s.scaleImage timing: "
                            + "%.3f ms = %.3f extracting data + %.3f compression",
                    getClass().getSimpleName(),
                    (scaleImageExtractingTime + scaleImageCompressionTime) * 1e-6,
                    scaleImageExtractingTime * 1e-6,
                    scaleImageCompressionTime * 1e-6
            ) + (
                    needAdditionalCompression ?
                            String.format(Locale.US, " (additional compressing %dx%d to %dx%d in %.3f times)",
                                    levelToX - levelFromX,
                                    levelToY - levelFromY,
                                    newDimX,
                                    newDimY,
                                    additionalCompression) :
                            " (additional compression skipped)");
        }

        private void doResize(
                Matrix<? extends UpdatablePArray> resized,
                Matrix<? extends PArray> source) {
//            System.out.println("Resizing to " + resized + " from " + source);
            final Matrices.ResizingMethod resizingMethod = averagingMode.averagingMethod(source);
            if (additionalCompressionIsInteger) {
                Matrices.resize(null, resizingMethod, resized, source);
            } else {
                final double scale = 1.0 / additionalCompression;
                Matrices.copy(null, resized, Matrices.asResized(resizingMethod,
                        source, resized.dimensions(),
                        new double[]{1.0, scale, scale}),
                        0,
                        false);
            }
        }


        private long safeFloor(double value) {
            double result = Math.floor(value);
            if (Math.abs(value - (result + 1.0)) < 1e-7) {
                // almost integer: we provide better behaviour in a case of little rounding errors
                return (long) (result + 1.0);
            }
            return (long) result;
        }
    }

    // Simplified version in comparison with original PlanePyramid: compression of areas
    // outside the image is not optimized
    private class SubMatrixExtracting {
        // The following fields are filled by the constructor:
        final int level;
        final long levelDimX;
        final long levelDimY;
        final long levelFromX;
        final long levelFromY;
        final long levelToX;
        final long levelToY;
        final long actualFromX;
        final long actualFromY;
        final long actualToX;
        final long actualToY;
        final boolean allDataActual;

        // The following fields are filled by extractSubMatrix:
        Matrix<? extends PArray> fullData = null;
        Matrix<? extends PArray> actualData = null;

        SubMatrixExtracting(
                final int level,
                final long levelFromX,
                final long levelFromY,
                final long levelToX,
                final long levelToY) {
            if (levelFromX > levelToX) {
                throw new IndexOutOfBoundsException("fromX = " + levelFromX + " > toX = " + levelToX);
            }
            if (levelFromY > levelToY) {
                throw new IndexOutOfBoundsException("fromY = " + levelFromY + " > toY = " + levelToY);
            }
            if (levelFromX < -Long.MAX_VALUE / 4 || levelToX > Long.MAX_VALUE / 4 - compression) {
                throw new IllegalArgumentException("Too large absolute values of fromX="
                        + levelFromX + " or toX=" + levelToX);
            }
            if (levelFromY < -Long.MAX_VALUE / 4 || levelToY > Long.MAX_VALUE / 4 - compression) {
                throw new IllegalArgumentException("Too large absolute values of fromX="
                        + levelFromY + " or toX=" + levelToY);
            }
            // - little restriction for abnormally large values allows to guarantee, that there will be no overflows
            this.level = level;
            this.levelFromX = levelFromX;
            this.levelFromY = levelFromY;
            this.levelToX = levelToX;
            this.levelToY = levelToY;
            long[] dimensions = ScalablePlanePyramidSource.this.dimensions(level);
            this.levelDimX = dimensions[DIM_WIDTH];
            this.levelDimY = dimensions[DIM_HEIGHT];
            this.actualFromX = levelFromX < 0 ? 0 : levelFromX > levelDimX ? levelDimX : levelFromX;
            this.actualFromY = levelFromY < 0 ? 0 : levelFromY > levelDimY ? levelDimY : levelFromY;
            this.actualToX = levelToX < 0 ? 0 : levelToX > levelDimX ? levelDimX : levelToX;
            this.actualToY = levelToY < 0 ? 0 : levelToY > levelDimY ? levelDimY : levelToY;
            this.allDataActual = actualFromX == levelFromX && actualFromY == levelFromY
                    && actualToX == levelToX && actualToY == levelToY;
        }

        SubMatrixExtracting extractSubMatrix() {
            this.actualData = callAndCheckParentReadSubMatrix(level, actualFromX, actualFromY, actualToX, actualToY);
            extendActual();
            return this;
        }

        private void extendActual() {
            assert actualData != null;
            if (allDataActual) {
//                System.out.println("!!!actual");
                fullData = actualData;
            } else {
                // providing better behaviour than the parent source: allowing an area outside the matrix
//                System.out.println("!!!extending");
                fullData = actualData.subMatr(
                        0, levelFromX - actualFromX, levelFromY - actualFromY,
                        bandCount, levelToX - levelFromX, levelToY - levelFromY,
                        Matrix.ContinuationMode.getConstantMode(Arrays.maxPossibleValue(actualData.type(), 1.0)));
                // - white color by default (maxPossibleValue) is better for most applications
            }
        }
    }

    static class SpeedInfo {
        double totalMemory = 0.0;
        double elapsedTime = 0.0;
        long lastGcTime = System.currentTimeMillis();

        public String update(long memory, long time) {
            return update(memory, time, false);
        }

        public String update(long memory, long time, boolean enforceGc) {
            final String result;
            boolean doGc = false;
            synchronized (this) {
                totalMemory += memory;
                elapsedTime += time;
                final long t = System.currentTimeMillis();
                if (enforceGc) {
                    doGc = TIME_ENFORCING_GC > 0
                            && (t - lastGcTime) > TIME_ENFORCING_GC;
                    if (doGc) {
                        lastGcTime = t;
                    }
                }
                result = String.format(Locale.US,
                        "%.1f MB / %.3f sec = %.3f MB/sec%s",
                        totalMemory / 1048576.0,
                        elapsedTime * 1e-9,
                        totalMemory / 1048576.0 / (elapsedTime * 1e-9),
                        doGc ? " [GC enforced by " + this + "]" : "");
            }
            if (doGc) {
                System.gc();
            }
            return result;
        }
    }
}
