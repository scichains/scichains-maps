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

package net.algart.matrices.maps.pyramids.io.formats.common.recognition;

import net.algart.arrays.Arrays;
import net.algart.arrays.*;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.math.patterns.Patterns;
import net.algart.math.patterns.UniformGridPattern;
import net.algart.matrices.morphology.BasicMorphology;
import net.algart.matrices.morphology.IterativeErosion;

import java.util.*;

public class SquaresAtObject {
    private static boolean DEBUG_EROSION_OPTIMIZATION = false;
    private static final System.Logger LOG = System.getLogger(SquaresAtObject.class.getName());

    private final Matrix<? extends BitArray> sourceMatrix;
    private final Matrix<UpdatableBitArray> workMatrix;
    private final int dimCount;

    private final List<IRectangularArea> foundSquares = new ArrayList<IRectangularArea>();
    private volatile int maxNumberOfSquares = 10000;
    private volatile long fixedSquareSide = 100;
    private volatile long overlapOfSquares = 0;

    private SquaresAtObject(Matrix<? extends BitArray> sourceMatrix) {
        Objects.requireNonNull(sourceMatrix, "Null source matrix");
        this.sourceMatrix = sourceMatrix;
        this.workMatrix = Arrays.SMM.newBitMatrix(sourceMatrix.dimensions());
        this.dimCount = workMatrix.dimCount();
        long[] from = new long[dimCount];
        long[] to = new long[dimCount];
        for (int k = 0; k < dimCount; k++) {
            from[k] = 1;
            to[k] = workMatrix.dim(k) - 1;
            if (from[k] >= to[k]) {
                return;
                // to small matrix, less than 3x3x...
            }
        }
        workMatrix.subMatrix(from, to).array().copy(sourceMatrix.subMatrix(from, to).array());
        // - the edge of matrix stays zero for correct behaviour of the erosion
    }

    public static SquaresAtObject getInstance(Matrix<? extends BitArray> sourceMatrix) {
        return new SquaresAtObject(sourceMatrix);
    }

    public Matrix<? extends BitArray> getSourceMatrix() {
        return sourceMatrix;
    }

    public Matrix<UpdatableBitArray> getWorkMatrix() {
        return workMatrix;
    }

    public List<IRectangularArea> getFoundSquares() {
        return Collections.unmodifiableList(foundSquares);
    }

    public int getMaxNumberOfSquares() {
        return maxNumberOfSquares;
    }

    public void setMaxNumberOfSquares(int maxNumberOfSquares) {
        if (maxNumberOfSquares < 0) {
            throw new IllegalArgumentException("Negative maxNumberOfSquares");
        }
        this.maxNumberOfSquares = maxNumberOfSquares;
    }

    public long getFixedSquareSide() {
        return fixedSquareSide;
    }

    public void setFixedSquareSide(long fixedSquareSide) {
        if (fixedSquareSide <= 0) {
            throw new IllegalArgumentException("Zero or negative fixedSquareSide");
        }
        this.fixedSquareSide = fixedSquareSide;
    }

    public long getOverlapOfSquares() {
        return overlapOfSquares;
    }

    public void setOverlapOfSquares(long overlapOfSquares) {
        this.overlapOfSquares = overlapOfSquares;
    }

    public void findSquaresWithFixedSizes() {
        if (overlapOfSquares >= fixedSquareSide) {
            throw new IllegalStateException("Cannot find squares of fixed sizes with overlap " + overlapOfSquares
                + " >= square side" + fixedSquareSide);
        }
        long t1 = System.nanoTime();
        final BasicMorphology morphology = BasicMorphology.getInstance(null);
        final UniformGridPattern requiredSquarePattern = Patterns.newRectangularIntegerPattern(
            IPoint.valueOfEqualCoordinates(dimCount, 0),
            IPoint.valueOfEqualCoordinates(dimCount, fixedSquareSide - 1));
        // - the side of such pattern is fixedSquareSide
        final long reducedSide = fixedSquareSide - overlapOfSquares;
        Matrix<? extends UpdatablePArray> erosion = morphology.erosion(workMatrix, requiredSquarePattern);
        BitArray erosionArray = (BitArray) erosion.array();
        long t2 = System.nanoTime();
        int squareCount = 0;
        long lastNonZeroIndex = -1;
        IPoint leftTop = null;
        for (; squareCount < maxNumberOfSquares; squareCount++) {
            long tt1 = System.nanoTime();
            long index = -1;
            if (leftTop != null) {
                // try to find better position by shifting along the last coordinate
                final long[] coordinates = leftTop.coordinates();
                coordinates[coordinates.length - 1] += reducedSide;
                index = erosion.index(coordinates);
                if (index < 0 || index >= erosionArray.length() || !erosionArray.getBit(index)) {
                    index = -1;
                }
            }
            if (index == -1) {
                // not found empirically
                lastNonZeroIndex = ((BitArray) erosion.array()).indexOf(lastNonZeroIndex + 1, Long.MAX_VALUE, true);
                index = lastNonZeroIndex;
            }
            long tt2 = System.nanoTime();
            final IRectangularArea square;
            if (index != -1) {
                leftTop = IPoint.valueOf(workMatrix.coordinates(index, null));
                square = IRectangularArea.valueOf(
                    leftTop,
                    leftTop.add(IPoint.valueOfEqualCoordinates(dimCount, fixedSquareSide - 1)));
                // - Note: this square is an inversion of the pattern
                foundSquares.add(square);
                // Now we will clear the reduced square on the work matrix...
                if (!DEBUG_EROSION_OPTIMIZATION) {
                    // ...However, without actual correction of the work matrix, we perform the equivalent
                    // correction of the erosion: removing a square R from the source matrix
                    // leads to removing from the erosion a square R, expanded back to the erosion patter
                    final IRectangularArea reducedSquare = IRectangularArea.valueOf(
                        leftTop.add(IPoint.valueOfEqualCoordinates(dimCount, -fixedSquareSide + 1)),
                        leftTop.add(IPoint.valueOfEqualCoordinates(dimCount, reducedSide - 1)));
                    erosion.subMatrix(reducedSquare, Matrix.ContinuationMode.NULL_CONSTANT).array().fill(0L);
                } else {
                    // Reference algorithm
                    final IRectangularArea reducedSquare = IRectangularArea.valueOf(
                        leftTop,
                        leftTop.add(IPoint.valueOfEqualCoordinates(dimCount, reducedSide - 1)));
                    workMatrix.subMatrix(reducedSquare, Matrix.ContinuationMode.NULL_CONSTANT).array().fill(0L);
                    erosion = morphology.erosion(workMatrix, requiredSquarePattern);
                }
            } else {
                square = null;
            }
            long tt3 = System.nanoTime();
            debug(System.Logger.Level.TRACE,
                    "Square #%d/%d %s in %.3f ms (%.3f search + %.3f removing from search)",
                    squareCount + 1, maxNumberOfSquares,
                    square == null ? "not found" : "found (" + square + ")",
                    (tt3 - tt1) * 1e-6, (tt2 - tt1) * 1e-6, (tt3 - tt2) * 1e-6);
            if (square == null) {
                debug(System.Logger.Level.DEBUG,"Finishing loop: all squares are already found");
                break;
            }
        }
        long t3 = System.nanoTime();
        debug(System.Logger.Level.DEBUG,
                "%d squares found in %.3f ms (%.3f preprocessing + %.3f search)",
                squareCount, (t3 - t1) * 1e-6, (t2 - t1) * 1e-6, (t3 - t2) * 1e-6);
    }

    public void findSquaresWithDecreasingSizes() {
        long t1 = System.nanoTime();
        int squareCount = 0;
        for (; squareCount < maxNumberOfSquares; squareCount++) {
            long tt1 = System.nanoTime();
            final IterativeErosion iterativeErosion = IterativeErosion.getInstance(
                BasicMorphology.getInstance(null),
                UpdatableIntArray.class,
                // 31-bit precision guarangees correct work even for very large matrices;
                // in a very improbable case of overflow we just will not able to find the maximum exactly
                workMatrix,
                Patterns.newRectangularIntegerPattern(
                    IPoint.valueOfEqualCoordinates(dimCount, -1),
                    IPoint.valueOfEqualCoordinates(dimCount, 1)));
            long tt2 = System.nanoTime();
            iterativeErosion.process();
            long tt3 = System.nanoTime();
            final Arrays.MinMaxInfo minMaxInfo = new Arrays.MinMaxInfo();
            final UpdatablePIntegerArray resultArray = (UpdatablePIntegerArray) iterativeErosion.result().array();
            Arrays.rangeOf(null, resultArray, minMaxInfo);
            final IPoint center = IPoint.valueOf(workMatrix.coordinates(minMaxInfo.indexOfMax(), null));
            final long distanceToEdge = resultArray.getLong(minMaxInfo.indexOfMax());
            final IRectangularArea square = IRectangularArea.valueOf(
                center.add(IPoint.valueOfEqualCoordinates(dimCount, -distanceToEdge)),
                center.add(IPoint.valueOfEqualCoordinates(dimCount, distanceToEdge)));
            final long reducedSide = 2 * distanceToEdge + 1 - overlapOfSquares;
            long tt4 = System.nanoTime();
            debug(System.Logger.Level.TRACE,
                    "Square #%d/%d found (%s) in %.3f ms " +
                            "(%.3f preparig + %.3f erosion + %.3f finding center)",
                    squareCount + 1, maxNumberOfSquares, square,
                    (tt4 - tt1) * 1e-6, (tt2 - tt1) * 1e-6, (tt3 - tt2) * 1e-6, (tt4 - tt3) * 1e-6);
            if (reducedSide <= 1) {
                debug(System.Logger.Level.DEBUG,"Finishing loop: all squares are already found");
                break;
            }
            foundSquares.add(square);
            final IRectangularArea reducedSquare = IRectangularArea.valueOf(
                center.add(IPoint.valueOfEqualCoordinates(dimCount, -reducedSide / 2)),
                center.add(IPoint.valueOfEqualCoordinates(dimCount, -reducedSide / 2 + reducedSide - 1)));
            Matrix<UpdatableBitArray> squareSubMatrix = workMatrix.subMatrix(reducedSquare,
                Matrix.ContinuationMode.NULL_CONSTANT);
            // - contination mode is set only to be on the safe side:
            // correct algorithm should not go outside the matrix
            squareSubMatrix.array().fill(false);
        }
        long t2 = System.nanoTime();
        debug(System.Logger.Level.DEBUG, "%d squares found in %.3f ms", squareCount, (t2 - t1) * 1e-6);
    }

    private static void debug(System.Logger.Level level, String format, Object... args) {
        LOG.log(level, () -> String.format(Locale.US, format, args));
    }

}
