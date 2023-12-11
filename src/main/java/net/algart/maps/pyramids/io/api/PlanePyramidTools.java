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

package net.algart.maps.pyramids.io.api;

import net.algart.arrays.*;
import net.algart.arrays.Arrays;
import net.algart.math.Range;
import net.algart.math.functions.AbstractFunc;
import net.algart.math.functions.ConstantFunc;
import net.algart.math.functions.Func;
import net.algart.math.functions.LinearFunc;

import java.awt.*;
import java.io.IOException;
import java.nio.channels.NotYetConnectedException;
import java.util.*;
import java.util.List;

public class PlanePyramidTools {
    public static final int MIN_PYRAMID_LEVEL_SIDE = 256;

    private static final System.Logger LOG = System.getLogger(PlanePyramidTools.class.getName());

    private PlanePyramidTools() {
    }

    public static Matrix<? extends PArray> readSpecialOrSmallestMatrix(
            PlanePyramidSource source,
            PlanePyramidSource.SpecialImageKind kind)
            throws NotYetConnectedException {
        Objects.requireNonNull(source, "Null source");
        Objects.requireNonNull(kind, "Null kind");
        Optional<Matrix<? extends PArray>> matrix = source.readSpecialMatrix(kind);
        if (matrix.isEmpty()) {
            matrix = source.readSpecialMatrix(PlanePyramidSource.SpecialImageKind.SMALLEST_LAYER);
            if (matrix.isEmpty()) {
                throw new AssertionError("Invalid implementation of readSpecialMatrix in " +
                        source.getClass() + ": empty result for " +
                        PlanePyramidSource.SpecialImageKind.SMALLEST_LAYER);
            }
        }
        return matrix.get();
    }

    public static boolean isDimensionsRelationCorrect(
        long[] dimOfSomeLevel,
        long[] dimOfNextLevel,
        int compression)
    {
        if (dimOfSomeLevel.length != 3 || dimOfNextLevel.length != 3) {
            throw new IllegalArgumentException("Illegal number of dimensions: must be 3");
        }
        if (compression <= 1) {
            throw new IllegalArgumentException("Invalid compression " + compression + " (must be 2 or greater)");
        }
        final long predictedNextWidth = dimOfSomeLevel[1] / compression;
        final long predictedNextHeight = dimOfSomeLevel[2] / compression;
        return dimOfNextLevel[0] == dimOfSomeLevel[0]
            && (dimOfNextLevel[1] == predictedNextWidth || dimOfNextLevel[1] == predictedNextWidth + 1)
            && (dimOfNextLevel[2] == predictedNextHeight || dimOfNextLevel[2] == predictedNextHeight + 1);
    }

    public static int findCompression(long[] dimensionsOfSomeLevel, long[] dimensionsOnNextLevel) {
        for (int probeCompression = 2; probeCompression <= 32; probeCompression++) {
            if (isDimensionsRelationCorrect(
                dimensionsOfSomeLevel, dimensionsOnNextLevel, probeCompression))
            {
                return probeCompression;
            }
        }
        return 0;
    }

    /**
     * Returns the number of resolution in the pyramid with the largest (zero) level
     * <tt>dimX</tt>&nbsp;x&nbsp;<tt>dimY</tt>,
     * with given compression between neighbour levels and with the last level less than
     * <tt>minimalPyramidSize</tt>&nbsp;x&nbsp;<tt>minimalPyramidSize</tt>.
     * But the result is never less than 1, even if the specified <tt>dimX</tt> and <tt>dimY</tt>
     * are already less than <tt>minimalPyramidSize</tt> (in this case 1 returned).
     *
     * @param dimX               width of zero-level (largest) level.
     * @param dimY               height of zero-level (largest) level.
     * @param compression        compression between neighbour levels, usually 2 or 4.
     * @param minimalPyramidSize minimal size of resulting levels.
     * @return the number of resolutions in the resulting pyramid, always &ge;1.
     */
    public static int numberOfResolutions(long dimX, long dimY, int compression, long minimalPyramidSize) {
        if (dimX <= 0 || dimY <= 0) {
            // more strict requirement (>0) than the usual requirement for matrices (>=0)
            throw new IllegalArgumentException("Illegal area dimensions " + dimX + "x" + dimY
                + " (must be positive)");
        }
        if (compression <= 1) {
            throw new IllegalArgumentException("Invalid compression " + compression + " (must be 2 or greater)");
        }
        if (minimalPyramidSize <= 0) {
            throw new IllegalArgumentException("Minimal pyramid size must be positive");
        }
        int count = 0;
        do {
            dimX /= compression;
            dimY /= compression;
            count++;
        } while (dimX >= minimalPyramidSize || dimY >= minimalPyramidSize);
        return count;
    }

    public static boolean areVeryLittleSizes(long dimX, long dimY) {
        return dimX < 1 || dimY < 1 || (dimX < MIN_PYRAMID_LEVEL_SIDE && dimY < MIN_PYRAMID_LEVEL_SIDE);
    }

    public static IOException rmiSafeWrapper(Exception nonStandardException) {
        final IOException exception = new IOException(nonStandardException.toString());
        exception.setStackTrace(nonStandardException.getStackTrace());
        return exception;
    }

    public static int defaultCompression(PlanePyramidSource source) {
        if (source.numberOfResolutions() <= 1
            || !source.isResolutionLevelAvailable(0)
            || !source.isResolutionLevelAvailable(1))
        {
            return PlanePyramidSource.DEFAULT_COMPRESSION;
        }
        int compression = findCompression(source.dimensions(0), source.dimensions(1));
        return compression == 0 ? PlanePyramidSource.DEFAULT_COMPRESSION : compression;
    }

    public static List<Matrix<? extends PArray>> equalizePrecisionToTheBest(
        List<? extends Matrix<? extends PArray>> matrices)
    {
        if (matrices == null) {
            throw new NullPointerException("Null matrices");
        }
        ArrayList<Matrix<? extends PArray>> result = new ArrayList<Matrix<? extends PArray>>(matrices);
        Class<?> bestElementType = null;
        for (Matrix<? extends PArray> m : result) {
            if (m != null) {
                if (bestElementType == null || precision(m.elementType()) > precision(bestElementType)) {
                    bestElementType = m.elementType();
                }
            }
        }
        if (bestElementType == null) {
            return result;
        }
        for (int k = 0, n = result.size(); k < n; k++) {
            Matrix<? extends PArray> m = result.get(k);
            if (m != null && m.elementType() != bestElementType) {
                final Class<PArray> destType = Arrays.type(PArray.class, bestElementType);
                final Range srcRange = Range.valueOf(0.0, m.array().maxPossibleValue(1.0));
                final Range destRange = Range.valueOf(0.0, Arrays.maxPossibleValue(destType, 1.0));
                result.set(k, Matrices.asFuncMatrix(LinearFunc.getInstance(destRange, srcRange), destType, m));
            }
        }
        return result;
    }

    public static Matrix<? extends PArray> asBackground(
        Class<?> elementType,
        long dimX, long dimY,
        double[] backgroundColor)
    {
        if (backgroundColor == null) {
            throw new NullPointerException("Null background color");
        }
        final int bandCount = backgroundColor.length;
        if (bandCount <= 0) {
            throw new IllegalArgumentException("Number of color components must be positive");
        }
        final Class<PArray> arrayType = Arrays.type(PArray.class, elementType);
        final double scale = Arrays.maxPossibleValue(arrayType, 1.0);
        final double[] filler = new double[bandCount];
        for (int k = 0; k < bandCount; k++) {
            filler[k] = backgroundColor[k] * scale;
        }
        boolean identical = true;
        for (double v : filler) {
            identical &= v == filler[0];
        }
        final Func constantFunc = identical ?
            ConstantFunc.getInstance(filler[0]) : // maybe work little faster
            new AbstractFunc() {
                @Override
                public double get(double... x) {
                    return filler[(int) x[0]];
                }

                @Override
                public double get(double x0, double x1, double x2) {
                    return filler[(int) x0];
                }
            };
        return Matrices.asCoordFuncMatrix(constantFunc, arrayType, bandCount, dimX, dimY);
    }

    public static void fillMatrix(
        Matrix<? extends UpdatablePArray> m,
        long fromX, long fromY, long toX, long toY,
        Color color)
    {
        fillMatrix(m.subMatrix(0, fromX, fromY, m.dim(0), toX, toY), color);
    }

    public static void fillMatrix(
        Matrix<? extends UpdatablePArray> m,
        long fromX, long fromY, long toX, long toY,
        double[] color)
    {
        fillMatrix(m.subMatrix(0, fromX, fromY, m.dim(0), toX, toY), color);
    }

    public static void fillMatrix(Matrix<? extends UpdatablePArray> m, Color color) {
        final long bandCount = m.dim(0);
        if (bandCount != 1 && bandCount != 3 && bandCount != 4) {
            return; // strange call
        }
        double[] a = new double[(int) bandCount];
        switch (a.length) {
            case 1:
                a[0] = (0.3 * color.getRed() + 0.59 * color.getGreen() + 0.11 * color.getBlue()) / 255.0;
                break;
            case 4:
                a[3] = color.getAlpha() / 255.0;
            case 3:
                a[0] = color.getRed() / 255.0;
                a[1] = color.getGreen() / 255.0;
                a[2] = color.getBlue() / 255.0;
                break;
        }
        fillMatrix(m, a);
    }

    public static void fillMatrix(Matrix<? extends UpdatablePArray> m, double[] color) {
        final long bandCount = m.dim(0);
        final UpdatablePArray array = m.array();
        if (bandCount > 32) {
            throw new IllegalArgumentException("This method should be not used for true 3D matrices");
        }
        if (bandCount != color.length) {
            if (bandCount == 1) {
                if (color.length >= 3) {
                    color = new double[] {0.3 * color[0] + 0.59 * color[1] + 0.11 * color[2]};
                } else {
                    color = new double[] {color[0]};
                }
            } else {
                double[] newColor = new double[(int) bandCount];
                for (int k = 0; k < newColor.length; k++) {
                    newColor[k] = k < color.length ? color[k] : color[color.length - 1];
                }
                color = newColor;
            }
        }
        assert bandCount == color.length;
        final PArray pattern = asBackground(m.elementType(), m.dim(1), m.dim(2), color).array();
        assert pattern.length() % color.length == 0;
        if (Arrays.isNCopies(pattern)) {
            array.copy(pattern);
        } else {
            final long n = pattern.length();
            final int blockLen = 1024 * color.length;
            final int initialLen = (int) Math.min(n, blockLen);
            if (initialLen == n) {
                array.copy(pattern);
            } else {
                // Current version of asBackground makes not too efficient matrix,
                // so an attempt to copy the whole pattern could work relatively slow.
                final Object buffer = pattern.newJavaArray(initialLen);
                pattern.getData(0, buffer, 0, initialLen);
                // Note: we should not try to use, as a work buffer, the beginning of the resulting array itself.
                // Some forms of arrays are "write-only", for example, underlying arrays of large submatrices,
                // which do not fully lie inside the containing matrix: we may write to the elements outside
                // the original matrix, but this will not have an effect.
                for (long p = 0; p < n; p += blockLen) {
                    array.setData(p, buffer, 0, (int) Math.min(blockLen, n - p));
                }
            }
        }
    }

    public static List<Matrix<? extends PArray>> buildPyramid(Matrix<? extends PArray> matrix) {
        return buildPyramid(matrix, 2);
        // Default compression 2 is suitable for all real formats
    }

    public static List<Matrix<? extends PArray>> buildPyramid(Matrix<? extends PArray> matrix, int compression) {
        long t1 = System.nanoTime();
        List<Matrix<? extends PArray>> result = new ArrayList<Matrix<? extends PArray>>();
        result.add(matrix);
        long dimX = matrix.dim(PlanePyramidSource.DIM_WIDTH) / compression;
        long dimY = matrix.dim(PlanePyramidSource.DIM_HEIGHT) / compression;
        while (!(areVeryLittleSizes(dimX, dimY))) {
            final Matrix<UpdatablePArray> compressed = Arrays.SMM.newMatrix(
                UpdatablePArray.class, matrix.elementType(), matrix.dim(0), dimX, dimY);
            if (matrix.dim(PlanePyramidSource.DIM_WIDTH) != dimX * compression
                || matrix.dim(PlanePyramidSource.DIM_HEIGHT) != dimY * compression)
            {
                matrix = matrix.subMatr(0, 0, 0, matrix.dim(0), dimX * compression, dimY * compression);
                // in other words, we prefer to lose 1 last pixels, but provide strict
                // integer compression: AlgART libraries are optimized for this situation
            }
            Matrices.resize(null, Matrices.ResizingMethod.AVERAGING, compressed, matrix);
            matrix = compressed;
            result.add(matrix);
            dimX /= compression;
            dimY /= compression;
        }
        long t2 = System.nanoTime();
        final var m = matrix;
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                "Building pyramid in memory from %d x %d until %d x %d: %.3f ms",
                result.get(0).dim(PlanePyramidSource.DIM_WIDTH),
                result.get(0).dim(PlanePyramidSource.DIM_HEIGHT),
                m.dim(PlanePyramidSource.DIM_WIDTH),
                m.dim(PlanePyramidSource.DIM_HEIGHT),
                (t2 - t1) * 1e-6));
        return result;
    }

    // This method can be used under debugger while debugging memory usage
    public static double usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0;
    }

    private static long precision(Class<?> elementType) {
        long bits = Arrays.bitsPerElement(elementType);
        return elementType == float.class || elementType == double.class ? bits + 1024 : bits;
    }
}
