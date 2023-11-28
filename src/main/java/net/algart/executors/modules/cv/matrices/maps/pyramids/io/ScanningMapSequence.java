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

package net.algart.executors.modules.cv.matrices.maps.pyramids.io;

import net.algart.math.IPoint;

public enum ScanningMapSequence {
    ROWS_LEFT_TO_RIGHT() {
        @Override
        public DiagonalDirectionOnMap recommendedFrameExpansion(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return DiagonalDirectionOnMap.LEFT_UP;
        }

        @Override
        public IPoint framePosition(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return IPoint.valueOf(
                    lowFrameIndex * frameSizeX,
                    highFrameIndex * frameSizeY);
        }

        @Override
        public long xFrameIndex(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return lowFrameIndex;
        }

        @Override
        public long yFrameIndex(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return highFrameIndex;
        }

        @Override
        public long lowFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY) {
            return divideCeil(totalDimX, frameSizeX);
        }

        @Override
        public long highFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY) {
            return divideCeil(totalDimY, frameSizeY);
        }
    },
    ROWS_BY_SNAKE() {
        @Override
        public DiagonalDirectionOnMap recommendedFrameExpansion(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return (highFrameIndex & 1) == 0 ? DiagonalDirectionOnMap.LEFT_UP : DiagonalDirectionOnMap.RIGHT_UP;
        }

        @Override
        public IPoint framePosition(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            final long lowFrameCount = lowFrameCount(frameSizeX, frameSizeY, totalDimX, totalDimY);
            return IPoint.valueOf(
                    ((highFrameIndex & 1) == 0 ? lowFrameIndex : lowFrameCount - 1 - lowFrameIndex) * frameSizeX,
                    highFrameIndex * frameSizeY);
        }

        @Override
        public long xFrameIndex(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            final long lowFrameCount = lowFrameCount(frameSizeX, frameSizeY, totalDimX, totalDimY);
            return (highFrameIndex & 1) == 0 ? lowFrameIndex : lowFrameCount - 1 - lowFrameIndex;
        }

        @Override
        public long yFrameIndex(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return highFrameIndex;
        }

        @Override
        public long lowFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY) {
            return divideCeil(totalDimX, frameSizeX);
        }

        @Override
        public long highFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY) {
            return divideCeil(totalDimY, frameSizeY);
        }
    },
    COLUMNS_TOP_TO_BOTTOM() {
        @Override
        public DiagonalDirectionOnMap recommendedFrameExpansion(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return DiagonalDirectionOnMap.LEFT_UP;
        }

        @Override
        public IPoint framePosition(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return IPoint.valueOf(
                    highFrameIndex * frameSizeX,
                    lowFrameIndex * frameSizeY);
        }

        @Override
        public long xFrameIndex(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return highFrameIndex;
        }

        @Override
        public long yFrameIndex(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return lowFrameIndex;
        }

        @Override
        public long lowFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY) {
            return divideCeil(totalDimY, frameSizeY);
        }

        @Override
        public long highFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY) {
            return divideCeil(totalDimX, frameSizeX);
        }
    },
    COLUMNS_BY_SNAKE() {
        @Override
        public DiagonalDirectionOnMap recommendedFrameExpansion(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return (highFrameIndex & 1) == 0 ? DiagonalDirectionOnMap.LEFT_UP : DiagonalDirectionOnMap.LEFT_DOWN;
        }

        @Override
        public IPoint framePosition(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            final long lowFrameCount = lowFrameCount(frameSizeX, frameSizeY, totalDimX, totalDimY);
            return IPoint.valueOf(
                    highFrameIndex * frameSizeX,
                    ((highFrameIndex & 1) == 0 ? lowFrameIndex : lowFrameCount - 1 - lowFrameIndex) * frameSizeY);
        }

        @Override
        public long xFrameIndex(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            return highFrameIndex;
        }

        @Override
        public long yFrameIndex(
                long lowFrameIndex,
                long highFrameIndex,
                long frameSizeX,
                long frameSizeY,
                long totalDimX,
                long totalDimY) {
            final long lowFrameCount = lowFrameCount(frameSizeX, frameSizeY, totalDimX, totalDimY);
            return (highFrameIndex & 1) == 0 ? lowFrameIndex : lowFrameCount - 1 - lowFrameIndex;
        }

        @Override
        public long lowFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY) {
            return divideCeil(totalDimY, frameSizeY);
        }

        @Override
        public long highFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY) {
            return divideCeil(totalDimX, frameSizeX);
        }
    };

    public abstract DiagonalDirectionOnMap recommendedFrameExpansion(
            long lowFrameIndex,
            long highFrameIndex,
            long frameSizeX,
            long frameSizeY,
            long totalDimX,
            long totalDimY);

    public abstract IPoint framePosition(
            long lowFrameIndex,
            long highFrameIndex,
            long frameSizeX,
            long frameSizeY,
            long totalDimX,
            long totalDimY);

    public abstract long xFrameIndex(
            long lowFrameIndex,
            long highFrameIndex,
            long frameSizeX,
            long frameSizeY,
            long totalDimX,
            long totalDimY);

    public abstract long yFrameIndex(
            long lowFrameIndex,
            long highFrameIndex,
            long frameSizeX,
            long frameSizeY,
            long totalDimX,
            long totalDimY);

    public abstract long lowFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY);

    public abstract long highFrameCount(long frameSizeX, long frameSizeY, long totalDimX, long totalDimY);

    private static long divideCeil(long totalDim, long frameSize) {
        if (totalDim < 0) {
            throw new IllegalArgumentException("Negative total dimension = " + totalDim);
        }
        if (frameSize <= 0) {
            throw new IllegalArgumentException("Zero or negative frame size = " + frameSize);
        }
        final long result = totalDim / frameSize;
        return result * frameSize == totalDim ? result : result + 1;
    }
}
