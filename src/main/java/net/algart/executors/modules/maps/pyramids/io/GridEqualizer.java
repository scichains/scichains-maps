/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.executors.modules.maps.pyramids.io;

import java.util.Locale;
import java.util.Random;

public class GridEqualizer {
    private final long totalDimension;

    private long numberOfCells = -1;
    private long originalCellSize = -1;
    private long equalizedCellSize = -1;

    public GridEqualizer(long totalDimension) {
        if (totalDimension < 0) {
            throw new IllegalArgumentException("Negative total dimension = " + totalDimension);
        }
        this.totalDimension = totalDimension;
    }

    public GridEqualizer equalize(long cellSize) {
        this.originalCellSize = cellSize;
        if (totalDimension == 0) {
            equalizedCellSize = cellSize;
            numberOfCells = 0;
            // no attempts to equalize; allow cellSize==0 in this case
            return this;
        }
        if (cellSize <= 0) {
            throw new IllegalArgumentException("Negative or zero cell size = " + cellSize);
        }
        // Let
        //     s = cellSize (> 0)
        //     T = totalDimension (> 0),
        //     n = ceil(T/s) = number of cells,
        //     r = ceil(T/n) = equalizedCellSize of this function
        //     s' = n*s - T = s - (width of last cell with size s)
        //     r' = n*r - T = r - (width of last cell with size s)
        // Here s' and r' are the errors (excesses of a series of whole cells over the total grid):
        // zero value is ideal, large value means non-equalized cells.
        // Note that always 0 <= s' < s, 0 <= r' < n.
        //
        // We have:
        //       T = n*s - s',
        //       r = ceil(T/n) = s - floor(s'/n).
        // So, we have 1st important fact:
        //       r <= s
        //
        // If s' < n, then r=s and r'=s': we just return the original size.
        // If s' >= n, then r' < s' (because r' < n).
        // So, we have 2nd important fact:
        //       r' <= s' (we didn't increase the error)
        //
        // What about new number of cells, calculated in the same manner on the base of new cell size r,
        // i.e. m = ceil(T/r)? Can it differ from n = ceil(T/s)?
        // We have:
        //      m >= n (it is obvious, because r <= s)
        //      T = n*r - r' <= n*r, so, real value T/r <= n, so, m = ceil(T/r) <= n
        // (if some real value A <= integer I, then also ceil(A) <= I).
        // So, we have 3rd important fact:
        //      m = n (number of cells didn't change)
        numberOfCells = divideCeil(totalDimension, cellSize);
        final long error = cellSize * numberOfCells - totalDimension;
        // - s' above
        assert error >= 0 && error < cellSize : "illegal " + error + " for " + totalDimension + "/" + cellSize;
        if (error == 0) {
            // - cells are already equal: we don't need to change anything
            equalizedCellSize = cellSize;
            return this;
        }
        equalizedCellSize = divideCeil(totalDimension, numberOfCells);
        if (equalizedCellSize > cellSize) {
            // - "1st important fact" above
            throw new AssertionError("Illegal algorithm: new cell size " + equalizedCellSize
                    + " > original " + cellSize + " (" + this + ")");
        }
        final long resultError = equalizedCellSize * numberOfCells - totalDimension;
        // - r' above
        assert resultError >= 0 && resultError < numberOfCells :
                "illegal " + resultError + " for " + totalDimension + "/" + numberOfCells;
        if (resultError > error) {
            // - "2nd important fact" above
            throw new AssertionError("Illegal algorithm: new error " + resultError
                    + " > " + error + " (" + this + ")");
        }
        if (resultError >= equalizedCellSize) {
            // - quick check of "3rd important fact": if r' < r, then T = n*r - r' > n*r - r,
            // T/r > n-1, so, ceil(T/r) >= n
            throw new AssertionError("Illegal algorithm: number of cells " + numberOfCells
                    + " changed, because new error " + resultError + " is too large (" + this + ")");
        }
        return this;
    }

    public long totalDimension() {
        return totalDimension;
    }

    public long numberOfCells() {
        if (numberOfCells < 0) {
            throw new IllegalStateException("equalize() was not called yet");
        }
        return numberOfCells;
    }

    public long originalCellSize() {
        if (numberOfCells < 0) {
            throw new IllegalStateException("equalize() was not called yet");
        }
        return originalCellSize;
    }

    public long equalizedCellSize() {
        if (numberOfCells < 0) {
            throw new IllegalStateException("equalize() was not called yet");
        }
        return equalizedCellSize;
    }

    /**
     * More quick equivalent of <code>new GridEqualizer(totalDimension).equalize(cellSize)</code>.
     */
    public static long equalizeGrid(long totalDimension, long cellSize) {
        if (totalDimension < 0) {
            throw new IllegalArgumentException("Negative total dimension = " + totalDimension);
        }
        if (totalDimension == 0) {
            return cellSize;
        }
        if (cellSize <= 0) {
            throw new IllegalArgumentException("Negative or zero cell size = " + cellSize);
        }
        final long numberOfCells = divideCeil(totalDimension, cellSize);
        final long error = cellSize * numberOfCells - totalDimension;
        // - s' above
        assert error >= 0 && error < cellSize : "illegal " + error + " for " + totalDimension + "/" + cellSize;
        if (error == 0) {
            // - cells are already equal: we don't need to change anything
            return cellSize;
        }
        final long equalizedCellSize = divideCeil(totalDimension, numberOfCells);
        if (equalizedCellSize > cellSize) {
            // - "1st important fact" above
            throw new AssertionError("Illegal algorithm: new cell size " + equalizedCellSize
                    + " > original " + cellSize);
        }
        final long resultError = equalizedCellSize * numberOfCells - totalDimension;
        // - r' above
        assert resultError >= 0 && resultError < numberOfCells :
                "illegal " + resultError + " for " + totalDimension + "/" + numberOfCells;
        if (resultError > error) {
            // - "2nd important fact" above
            throw new AssertionError("Illegal algorithm: new error " + resultError
                    + " > " + error);
        }
        if (resultError >= equalizedCellSize) {
            // - quick check of "3rd important fact": if r' < r, then T = n*r - r' > n*r - r,
            // T/r > n-1, so, ceil(T/r) >= n
            throw new AssertionError("Illegal algorithm: number of cells " + numberOfCells
                    + " changed, because new error " + resultError + " is too large");
        }
        return equalizedCellSize;

    }

    @Override
    public String toString() {
        return "GridEqualizer{" +
                "totalDimension=" + totalDimension +
                ", numberOfCells=" + numberOfCells +
                ", cellSize=" + originalCellSize +
                ", equalizedCellSize=" + equalizedCellSize +
                '}';
    }

    static long divideCeil(long a, long b) {
        assert a >= 0;
        assert b > 0;
        long result = a / b;
        return result * b == a ? result : result + 1;
    }

    public static void main(String[] args) {
        final int numberOfTests = 10000000;
        Random rnd = new Random();
        for (int k = 0; k < numberOfTests; k++) {
            if (k % 10000 == 0) {
                System.out.print("\r" + k + "...");
            }
            final long totalDimension = rnd.nextBoolean() ? rnd.nextInt(200) : rnd.nextLong() & 0xFFFFFFFFFFL;
            final long cellSize = 1 +
                    (rnd.nextBoolean() ? rnd.nextInt((int) Math.min(1L + totalDimension, Integer.MAX_VALUE)) :
                            rnd.nextBoolean() ? rnd.nextInt(200) : rnd.nextLong() & 0xFFFFFFFFFFL);
            final GridEqualizer equalizer = new GridEqualizer(totalDimension);
            equalizer.equalize(cellSize);
            final long newNumberOfCells = divideCeil(totalDimension, equalizer.equalizedCellSize());
            if (newNumberOfCells != equalizer.numberOfCells()) {
                // - "3rd important fact" above
                throw new AssertionError("ERROR: new number of cells " + newNumberOfCells
                        + " != original " + equalizer.numberOfCells() + " (" + equalizer + ")");
            }
            if (equalizeGrid(totalDimension, cellSize) != equalizer.equalizedCellSize()) {
                throw new AssertionError("ERROR: static method produces another result!");
            }
        }
        System.out.println("\r                  ");
        for (int test = 0; test < 5; test++) {
            final int totalDimension = rnd.nextInt(20000000);
            final int cellSize = rnd.nextInt(totalDimension + 1);
            long dummy = 0;

            long t1 = System.nanoTime();
            for (int k = 0; k < numberOfTests; k++) {
                dummy += new GridEqualizer(totalDimension).equalize(cellSize).equalizedCellSize();
            }
            long t2 = System.nanoTime();
            System.out.printf(Locale.US, "Creating + equalize(): %.3f ns/call%n",
                    (double) (t2 - t1) / numberOfTests);

            t1 = System.nanoTime();
            final GridEqualizer equalizer = new GridEqualizer(totalDimension);
            for (int k = 0; k < numberOfTests; k++) {
                dummy += equalizer.equalize(cellSize).equalizedCellSize();
            }
            t2 = System.nanoTime();
            System.out.printf(Locale.US, "equalize() method:     %.3f ns/call (%s)%n", (double)
                    (t2 - t1) / numberOfTests, dummy);

            t1 = System.nanoTime();
            for (int k = 0; k < numberOfTests; k++) {
                dummy += GridEqualizer.equalizeGrid(totalDimension, cellSize);
            }
            t2 = System.nanoTime();
            System.out.printf(Locale.US, "equalize() function:   %.3f ns/call (%s)%n%n", (double)
                    (t2 - t1) / numberOfTests, dummy);
        }
    }
}
