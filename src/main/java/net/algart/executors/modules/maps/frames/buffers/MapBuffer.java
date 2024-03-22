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

package net.algart.executors.modules.maps.frames.buffers;

import net.algart.executors.modules.maps.frames.joints.DynamicDisjointSet;
import net.algart.executors.modules.maps.frames.joints.ObjectPairs;
import net.algart.arrays.Arrays;
import net.algart.arrays.*;
import net.algart.math.IPoint;
import net.algart.math.IRange;
import net.algart.math.IRectangularArea;
import net.algart.math.Range;
import net.algart.math.functions.AbstractFunc;
import net.algart.math.functions.Func;
import net.algart.math.functions.LinearFunc;
import net.algart.multimatrix.MultiMatrix;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class MapBuffer {
    private static final boolean OPTIMIZE_ADD_FRAME = true;
    // - should be true for good performance

    public static class Frame {
        private final IRectangularArea position;
        final long dimX;
        final long dimY;
        final long minX;
        final long maxX;
        final long minY;
        final long maxY;
        private final MultiMatrix matrix;
        final Matrix<? extends PArray> channel0;
        final int[] channel0Ints;
        private final boolean intMatrix;

        public Frame(IPoint leftTop, MultiMatrix matrix) {
            this(framePosition(leftTop, matrix), matrix);
        }

        private Frame(IRectangularArea position, MultiMatrix matrix) {
            assert position != null;
            assert matrix != null;
            this.position = position;
            this.matrix = matrix;
            this.channel0 = matrix.channel(0);
            final PArray channel0Array = this.channel0.array();
            this.intMatrix = channel0Array instanceof IntArray;
            int[] channel0Ints = null;
            if (this.intMatrix && channel0Array instanceof DirectAccessible) {
                final DirectAccessible directAccessible = (DirectAccessible) channel0Array;
                if (directAccessible.hasJavaArray() && directAccessible.javaArrayOffset() == 0) {
                    // - non-zero offset is very improbable here
                    channel0Ints = (int[]) directAccessible.javaArray();
                }
            }
            this.channel0Ints = channel0Ints;
            this.dimX = matrix.dim(0);
            this.minX = position.minX();
            this.maxX = this.minX + this.dimX - 1;
            if (matrix.dimCount() < 2) {
                this.dimY = 1;
                this.minY = this.maxY = 0;
            } else {
                this.dimY = matrix.dim(1);
                this.minY = position.minY();
                this.maxY = this.minY + this.dimY - 1;
            }
        }

        public static IRectangularArea framePosition(IPoint leftTop, MultiMatrix matrix) {
            Objects.requireNonNull(matrix, "Null matrix");
            return framePosition(leftTop, matrix.dimensions());
        }

        public static IRectangularArea framePosition(IPoint leftTop, long... matrixDimensions) {
            Objects.requireNonNull(matrixDimensions, "Null matrixDimensions");
            Objects.requireNonNull(leftTop, "Null leftTop position");
            if (leftTop.coordCount() != matrixDimensions.length) {
                throw new IllegalArgumentException("Different number of dimensions: " + leftTop.coordCount()
                        + "-dimensional point " + leftTop + " and " + matrixDimensions.length
                        + "-dimensional matrix");
            }
            final long[] max = new long[matrixDimensions.length];
            for (int k = 0; k < matrixDimensions.length; k++) {
                if (matrixDimensions[k] <= 0) {
                    throw new IllegalArgumentException("Matrix dimensions for a frame cannot be zero or negative: "
                            + JArrays.toString(matrixDimensions, "x", 256));
                }
                max[k] = Math.addExact(leftTop.coord(k), matrixDimensions[k] - 1);
            }
            return IRectangularArea.valueOf(leftTop, IPoint.valueOf(max));
        }

        public MultiMatrix matrix() {
            return matrix;
        }

        public IRectangularArea position() {
            return position;
        }

        public boolean isIntMatrix() {
            return intMatrix;
        }

        public Frame matrix(MultiMatrix newMatrix) {
            matrix.checkDimensionEquality(newMatrix,
                    "previous matrix in the frame", "new matrix");
            return new Frame(position.min(), newMatrix);
        }

        public Frame cloneMatrix() {
            return matrix(matrix.clone());
        }

        public Frame actualizeLazyMatrix() {
            return matrix(matrix.actualizeLazy());
        }

        public Frame subFrameWithZeroContinuation(IRectangularArea other) {
            if (other.equals(position)) {
                return this;
            }
            final IRectangularArea subRectangle = other.shiftBack(position.min());
            return new Frame(
                    other.min(),
                    matrix.mapChannels(m -> m.subMatrix(subRectangle, Matrix.ContinuationMode.ZERO_CONSTANT)));
        }

        public int nextIndexingBase(int currentIndexingBase, boolean zerosLabelReservedForBackground) {
            FrameObjectStitcher.checkLabels(matrix);
            final int maxLabel = (int) Arrays.rangeOf(channel0.array()).max();
            if (maxLabel < 0) {
                throw new IllegalStateException("Cannot auto-index: matrix contains negative label " + maxLabel);
            }
            if (maxLabel == Integer.MAX_VALUE) {
                throw new IllegalStateException("Cannot continue auto-indexing: maximal label is " + Integer.MAX_VALUE);
            }
            return Math.max(currentIndexingBase, zerosLabelReservedForBackground ? maxLabel : maxLabel + 1);
            // Math.max is necessary in a case, when zerosLabelReservedForBackground AND
            // there are no non-zero labels in current frame (already after addIndexingBase)
        }

        public Frame addIndexingBase(boolean zerosLabelReservedForBackground, int indexingBase) {
            FrameObjectStitcher.checkLabels(matrix);
            if (indexingBase == 0) {
                return actualizeLazyMatrix();
                // - the same behaviour for first and further calls
            }
            final Func adding = incrementingFunc(zerosLabelReservedForBackground, indexingBase);
            return matrix(MultiMatrix.valueOfMono(
                    Matrices.asFuncMatrix(adding, channel0.type(PArray.class), channel0).clone()));
        }

        public ReindexedFrame sequentiallyReindex(boolean includeReservedInRestoringTable) {
            return new ReindexedFrame(includeReservedInRestoringTable);
        }

        @Override
        public String toString() {
            return "frame " + position + " with " + matrix;
        }

        public final class ReindexedFrame {
            private final int[] restoringTable;
            private final Frame reindexed;

            private ReindexedFrame(boolean includeReservedInRestoringTable) {
                FrameObjectStitcher.checkLabels(matrix);
                final int[] labels = matrix.channelToIntArray(0);
                final UpdatableIntArray labelsArray = SimpleMemoryModel.asUpdatableIntArray(labels);
                final Range range = MultiMatrix.nonZeroRangeOf(labelsArray);
                // - double-precision result of nonZeroRangeOf always represent int values exactly
                final int minLabel = range == null ? 0 : (int) range.min();
                final int maxLabel = range == null ? 0 : (int) range.max();
                // - if range == null (no non-zero labels), we still need to create some restoringTable
                if (minLabel < 0) {
                    throw new IllegalStateException("Cannot sequentially reindex: matrix contains negative label "
                            + minLabel);
                }
                assert (long) maxLabel + 1 - (long) minLabel <= Integer.MAX_VALUE : "because minLabel >= 0";
                final int[] map = new int[maxLabel + 1 - minLabel];
                // zero-filled by Java
                IntStream.range(0, (labels.length + 255) >>> 8).parallel().forEach(block -> {
                    // note: splitting to blocks helps to provide normal speed
                    for (int i = block << 8, to = (int) Math.min((long) i + 256, labels.length); i < to; i++) {
                        int label = labels[i];
                        if (label != 0) {
                            map[label - minLabel] = -157;
                            // - any non-zero value
                        }
                    }
                });
                int count = 1;
                for (int labelMinusMin = 0; labelMinusMin < map.length; labelMinusMin++) {
                    if (map[labelMinusMin] != 0) {
                        map[labelMinusMin] = count++;
                    }
                }
                this.restoringTable = includeReservedInRestoringTable ?
                        buildRestoringTableWithReserved(map, count, 1, minLabel) :
                        buildRestoringTableWithoutReserved(map, count, 1, minLabel);
                IntStream.range(0, (labels.length + 255) >>> 8).parallel().forEach(block -> {
                    // note: splitting to blocks helps to provide normal speed
                    for (int i = block << 8, to = (int) Math.min((long) i + 256, labels.length); i < to; i++) {
                        final int label = labels[i];
                        if (label != 0) {
                            labels[i] = map[label - minLabel];
                        }
                    }
                });
                this.reindexed = matrix(MultiMatrix.valueOfMono(labelsArray.matrix(matrix.dimensions())));
            }

            // Note: table[0] = 0 always; for most applications you should remove this element
            public int[] sequentialResrotingTable() {
                return restoringTable;
            }

            public Frame reindexed() {
                return reindexed;
            }
        }
    }

    private int maximalNumberOfStoredFrames = 1;
    private boolean stitchingLabels = false;
    private boolean autoReindexLabels = false;
    private boolean zerosLabelReservedForBackground = true;
    private final Deque<Frame> frames;
    private final ObjectPairs objectPairs;
    private final BitSet rawPartialObjects;
    private IRectangularArea firstFramePosition;
    private int indexingBase;

    private MapBuffer() {
        this.frames = new LinkedList<>();
        this.objectPairs = ObjectPairs.newInstance();
        this.rawPartialObjects = new BitSet();
        this.firstFramePosition = null;
        this.indexingBase = 0;
    }

    public static MapBuffer newInstance() {
        return new MapBuffer();
    }

    public FrameObjectStitcher getFrameObjectStitcher() {
        return FrameObjectStitcher.getInstance(this, rawPartialObjects);
    }

    public int getMaximalNumberOfStoredFrames() {
        return maximalNumberOfStoredFrames;
    }

    public MapBuffer setMaximalNumberOfStoredFrames(int maximalNumberOfStoredFrames) {
        if (maximalNumberOfStoredFrames <= 0) {
            throw new IllegalArgumentException("Zero or negative maximal number of stored rows");
        }
        this.maximalNumberOfStoredFrames = maximalNumberOfStoredFrames;
        return this;
    }

    public boolean isStitchingLabels() {
        return stitchingLabels;
    }

    public MapBuffer setStitchingLabels(boolean stitchingLabels) {
        this.stitchingLabels = stitchingLabels;
        return this;
    }

    public boolean isAutoReindexLabels() {
        return autoReindexLabels;
    }

    public MapBuffer setAutoReindexLabels(boolean autoReindexLabels) {
        this.autoReindexLabels = autoReindexLabels;
        return this;
    }

    public boolean isZerosLabelReservedForBackground() {
        return zerosLabelReservedForBackground;
    }

    public MapBuffer setZerosLabelReservedForBackground(boolean zerosLabelReservedForBackground) {
        this.zerosLabelReservedForBackground = zerosLabelReservedForBackground;
        return this;
    }

    public int getIndexingBase() {
        return indexingBase;
    }

    public MapBuffer setIndexingBase(int indexingBase) {
        if (indexingBase < 0) {
            throw new IllegalArgumentException("Indexing base cannot be negative: " + indexingBase);
        }
        this.indexingBase = indexingBase;
        return this;
    }

    public ObjectPairs objectPairs() {
        return objectPairs;
    }

    public BitSet rawPartialObjects() {
        return rawPartialObjects;
    }

    public BitSet reindexPartialObjects() {
        return objectPairs.reindexByAnd(rawPartialObjects);
    }

    public int numberOfFrames() {
        return frames.size();
    }

    public int numberOfObjects() {
        return zerosLabelReservedForBackground ? indexingBase + 1 : indexingBase;
    }

    public void clear() {
        clear(true);
    }

    public void clear(boolean resetIndexing) {
        frames.clear();
        objectPairs.clear();
        rawPartialObjects.clear();
        firstFramePosition = null;
        if (resetIndexing) {
            indexingBase = 0;
        }
    }

    public void addFrame(Frame frame) {
        Objects.requireNonNull(frame, "Null frame");
        addFrame(frame.matrix, frame.position.min(), null, false);
    }

    public Frame addFrame(
            MultiMatrix matrix,
            IPoint leftTop,
            IRectangularArea rectangleToCrop,
            boolean disableOverlapping) {
        Objects.requireNonNull(matrix, "Null matrix");
        Objects.requireNonNull(leftTop, "Null leftTop");
        checkFrameCompatibility(matrix);
//        long t1 = System.nanoTime(), t2 = t1;
        Frame frame = tryToAddFrameWithReindexingOptimized(matrix, leftTop, rectangleToCrop, disableOverlapping);
        final boolean nonOptimized = frame == null;
        if (nonOptimized) {
            if (rectangleToCrop != null) {
                matrix = matrix.mapChannels(m -> cropMatrix(rectangleToCrop, m));
            }
            frame = new Frame(leftTop, matrix);
            if (disableOverlapping) {
                checkIntersected(frame);
            }
            if (autoReindexLabels) {
                frame = frame.addIndexingBase(zerosLabelReservedForBackground, indexingBase);
//                t2 = System.nanoTime();
                indexingBase = frame.nextIndexingBase(indexingBase, zerosLabelReservedForBackground);
            }
            frame = frame.actualizeLazyMatrix();
            // - does nothing if autoReindexLabels, but may be important if it is not so
        }
//        long t3 = System.nanoTime();
        if (stitchingLabels) {
            getFrameObjectStitcher().correlate(frame, nonOptimized);
            // - if we used optimized branch, there is no need to check non-negative labels
        }
//        if (stitchingLabels) {
//            long t4 = System.nanoTime();
//            System.out.printf("!!! %s to %s: %.3f = %.3f + %.3f + %.3f%n", frame, this,
//                    (t4 - t1) * 1e-6, (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6);
//        }
        if (firstFramePosition == null) {
            firstFramePosition = frame.position();
        }
        if (frames.size() >= maximalNumberOfStoredFrames) {
            // - important to do this after all previous operations over frame,
            // for a case of possible exceptions
            frames.remove();
        }
        frames.add(frame);
        return frame;
    }

    public IRectangularArea getFirstFramePosition() {
        return firstFramePosition;
    }

    public IRectangularArea reqFirstFramePosition() {
        final IRectangularArea result = getFirstFramePosition();
        if (result == null) {
            throw new IllegalStateException("No frames were added from last clearing to " + this);
        }
        return result;
    }

    public Frame getLastFrame() {
        return frames.peekLast();
    }

    public Frame reqLastFrame() {
        final Frame result = getLastFrame();
        if (result == null) {
            throw new IllegalStateException("No frames in " + this);
        }
        return result;
    }

    public Collection<Frame> allFrames() {
        return Collections.unmodifiableCollection(frames);
    }

    public List<Frame> allFramesWithMinCoordinate(int coordIndex, long coordinate) {
        return frames.stream().filter(
                frame -> frame.position.min(coordIndex) == coordinate).collect(Collectors.toList());
    }

    public List<Frame> allFramesWithMaxCoordinate(int coordIndex, long coordinate) {
        return frames.stream().filter(
                frame -> frame.position.max(coordIndex) == coordinate).collect(Collectors.toList());
    }

    public List<Frame> allIntersecting(IRectangularArea area) {
        Objects.requireNonNull(area, "Null area");
        return frames.stream().filter(frame -> area.intersects(frame.position)).collect(Collectors.toList());
    }

    public List<IRectangularArea> allPositions() {
        return frames.stream().map(frame -> frame.position).collect(Collectors.toList());
    }

    public void checkFrameCompatibility(Frame frame) {
        Objects.requireNonNull(frame, "Null frame");
        checkFrameCompatibility(frame.matrix);
    }

    public void checkFrameCompatibility(MultiMatrix matrix) {
        Objects.requireNonNull(matrix, "Null matrix");
        if (!frames.isEmpty()) {
            final MultiMatrix existing = frames.iterator().next().matrix;
            if (matrix.elementType() != existing.elementType()) {
                throw new IllegalArgumentException("The specified frame and existing frames " +
                        "have different element types: " + matrix + " and " + existing);
            }
            if (matrix.numberOfChannels() != existing.numberOfChannels()) {
                throw new IllegalArgumentException("The specified frame and existing frames " +
                        "have different number of channels: " + matrix + " and " + existing);
            }
            if (matrix.dimCount() != existing.dimCount()) {
                throw new IllegalArgumentException("The specified frame and existing frames " +
                        "have different number of dimensions: " + matrix + " and " + existing);
            }
        }
    }

    public boolean isCovered(IRectangularArea area) {
        Objects.requireNonNull(area, "Null rectangular area");
        if (frames.isEmpty()) {
            return false;
        }
        final IRectangularArea existing = frames.iterator().next().position;
        if (area.coordCount() != existing.coordCount()) {
            throw new IllegalArgumentException("The checked area and existing frames " +
                    "have different number of dimensions: " + area + " and " + existing);
        }
        return area.subtract(allPositions()).isEmpty();
    }

    public boolean isIntersected(IRectangularArea area) {
        Objects.requireNonNull(area, "Null rectangular area");
        for (Frame existing : frames) {
            if (area.coordCount() != existing.position.coordCount()) {
                throw new IllegalArgumentException("The checked area and existing frames " +
                        "have different number of dimensions: " + area + " and " + existing.position);
            }
            if (existing.position.intersects(area)) {
                return true;
            }
        }
        return false;
    }

    public void checkIntersected(Frame frame) {
        Objects.requireNonNull(frame, "Null frame");
        checkIntersected(frame.position);
    }

    public void checkIntersected(IRectangularArea framePosition) {
        Objects.requireNonNull(framePosition, "Null framePosition");
        for (Frame existing : frames) {
            if (framePosition.coordCount() != existing.position.coordCount()) {
                throw new IllegalArgumentException("The checked frame and existing frames " +
                        "have different number of dimensions: " + framePosition + " and " + existing.position);
            }
            if (existing.position.intersects(framePosition)) {
                throw new IllegalArgumentException("The specified position " + framePosition
                        + " overlaps with an existing " + existing);
            }
        }
    }

    public IRectangularArea containingRectangle() {
        return IRectangularArea.minimalContainingArea(allPositions());
    }

    public IRectangularArea changeRectangleOnMap(
            IRectangularArea originalArea,
            IRectangularArea changedArea,
            boolean originalAreaMustBeCovered) {
        Objects.requireNonNull(originalArea, "Null originalArea");
        Objects.requireNonNull(changedArea, "Null changedArea");
        if (changedArea.coordCount() != originalArea.coordCount()) {
            throw new IllegalArgumentException("Different number of dimensions of changedArea " + changedArea
                    + "and originalArea " + originalArea);
        }
        final boolean originalAreaCovered = isCovered(originalArea);
        if (originalAreaMustBeCovered && !originalAreaCovered) {
            throw new IllegalArgumentException("Original area " + originalArea + " is not inside " + this);
        }
        if (!originalArea.intersects(changedArea)) {
            // - in this case, the following loop will not work: it will produce at least 1 range with min > max
            return isCovered(changedArea) ? changedArea : originalArea;
        }
        IRange[] ranges = originalArea.ranges();
        for (int i = ranges.length - 1; i >= 0; i--) {
            IRange save = ranges[i];
            ranges[i] = IRange.valueOf(changedArea.min(i), save.max());
            if (!isCovered(IRectangularArea.valueOf(ranges))) {
                ranges[i] = save;
                // - failure to change: restoring
            }
            save = ranges[i];
            ranges[i] = IRange.valueOf(save.min(), changedArea.max(i));
            if (!isCovered(IRectangularArea.valueOf(ranges))) {
                ranges[i] = save;
                // - failure to change: restoring
            }
        }
        final IRectangularArea result = IRectangularArea.valueOf(ranges);
        if (originalAreaCovered && !isCovered(result)) {
            throw new AssertionError(result + " is not covered by " + this);
        }
        return result;
    }

    public IRectangularArea expandRectangleOnMap(
            IRectangularArea originalArea,
            IPoint expansion,
            boolean originalAreaMustBeCovered) {
        return changeRectangleOnMap(originalArea, originalArea.dilate(expansion), originalAreaMustBeCovered);
    }

    public Frame readFrame(IRectangularArea area) {
        final MultiMatrix matrix = readMatrix(area);
        return new Frame(area.min(), matrix);
    }

    public MultiMatrix readMatrix(IRectangularArea area) {
        Objects.requireNonNull(area, "Null area");
        if (frames.isEmpty()) {
            throw new IllegalStateException("No frames");
        }
        final MultiMatrix existing = frames.iterator().next().matrix;
        if (area.coordCount() != existing.dimCount()) {
            throw new IllegalArgumentException("The requested area and existing frames " +
                    "have different number of dimensions: " + area + " and " + existing);
        }
        final List<Matrix<? extends PArray>> resultChannels = new ArrayList<>();
        for (int c = 0; c < existing.numberOfChannels(); c++) {
            final Matrix<UpdatablePArray> resultMatrix = Arrays.SMM.newMatrix(
                    UpdatablePArray.class, existing.elementType(), area.sizes());
            final long resultDimX = resultMatrix.dimX();
            final UpdatablePArray resultArray = resultMatrix.array();
            final long areaMinX = area.minX();
            final long areaMaxX = area.maxX();
            final long areaMinY = area.minY();
            final long areaMaxY = area.maxY();
            for (Frame frame : frames) {
                final long intersectionMinX = Math.max(areaMinX, frame.minX);
                final long intersectionMaxX = Math.min(areaMaxX, frame.maxX);
                if (intersectionMinX > intersectionMaxX) {
                    continue;
                }
                final long intersectionMinY = Math.max(areaMinY, frame.minY);
                final long intersectionMaxY = Math.min(areaMaxY, frame.maxY);
                if (intersectionMinY > intersectionMaxY) {
                    continue;
                }
                final long length = intersectionMaxX - intersectionMinX + 1;
                final Matrix<? extends PArray> frameMatrix = frame.matrix.channel(c);
                final long frameDimX = frame.dimX;
                final PArray frameArray = frameMatrix.array();
                long y = intersectionMinY - areaMinY;
                final long maxY = intersectionMaxY - areaMinY;
                long frameY = intersectionMinY - frame.minY;
                long p = y * resultDimX + intersectionMinX - areaMinX;
                long frameP = frameY * frameDimX + intersectionMinX - frame.minX;
                for (; y <= maxY; y++, p += resultDimX, frameP += frameDimX) {
                    resultArray.subArr(p, length).copy(frameArray.subArr(frameP, length));
                }
//                    Previous code is little faster equivalent of the following:
//                    Matrix<UpdatablePArray> subResult = resultMatrix.subMatrix(
//                            intersection.shiftBack(area.min()));
//                    Matrix<? extends PArray> subFrame = frameMatrix.subMatrix(
//                            intersection.shiftBack(frame.position.min()));
//                    subResult.array().copy(subFrame.array());

            }
            resultChannels.add(resultMatrix);
        }
        return MultiMatrix.valueOf(resultChannels);
    }

    public MultiMatrix readMatrixReindexedByObjectPairs(IRectangularArea area, boolean quickCallAfterResolveAllBases) {
        if (frames.isEmpty()) {
            throw new IllegalStateException("No frames");
        }
        return readMatrixReindexedByObjectPairs(this.frames, area, quickCallAfterResolveAllBases);
    }

    @Override
    public String toString() {
        return "map buffer with " + numberOfFrames() + "/" + maximalNumberOfStoredFrames + " frames"
                + ", indexing base " + indexingBase
                + (autoReindexLabels ? ", auto-reindexing" : "")
                + (stitchingLabels ? ", auto-stitching" : ", no stitching")
                + (zerosLabelReservedForBackground ? ", zero-labels for background" : "");
    }

    public static Collection<IRectangularArea> externalBoundary(
            Collection<IRectangularArea> areas,
            boolean straightOnly) {
        final Collection<IRectangularArea> dilation = dilateBy1(areas, straightOnly);
        final Queue<IRectangularArea> result = IRectangularArea.subtractCollection(new ArrayDeque<>(dilation), areas);
        for (IRectangularArea area : result) {
            checkThinness(area);
        }
        return result;
    }

    public static List<IRectangularArea> internalBoundary(
            Collection<IRectangularArea> areas,
            boolean straightOnly) {
        final IRectangularArea container = IRectangularArea.minimalContainingArea(areas);
        if (container == null) {
            return Collections.emptyList();
        }
        final Collection<IRectangularArea> complement = container.dilate(1).subtract(areas);
        final Collection<IRectangularArea> boundary = externalBoundary(complement, straightOnly);
        return container.intersection(boundary);
    }

    MultiMatrix readMatrixReindexedByObjectPairs(
            Collection<Frame> frames,
            IRectangularArea area,
            boolean quickCallAfterResolveAllBases) {
        final int[] result = readLabelsReindexedByObjectPairs(frames, area, quickCallAfterResolveAllBases);
        final UpdatableIntArray resultArray = SimpleMemoryModel.asUpdatableIntArray(result);
        return MultiMatrix.valueOf2DMono(Matrices.matrix(resultArray, area.sizes()));
    }

    int[] readLabelsReindexedByObjectPairs(
            Collection<Frame> frames,
            IRectangularArea area,
            boolean quickCallAfterResolveAllBases) {
        return readLabelsReindexedByObjectPairs(
                null, 0, frames, area, quickCallAfterResolveAllBases);
    }

    int[] readLabelsReindexedByObjectPairs(
            int[] result,
            int resultOffset,
            Collection<Frame> frames,
            IRectangularArea area,
            boolean quickCallAfterResolveAllBases) {
        Objects.requireNonNull(area, "Null area");
        Objects.requireNonNull(frames, "Null frames");
        if (resultOffset < 0) {
            throw new IllegalArgumentException("Negative resultOffset = " + resultOffset);
        }
        long areaSize = 0;
        if (area.coordCount() == 2
                && (area.sizeX() >= Integer.MAX_VALUE
                || area.sizeY() >= Integer.MAX_VALUE
                || (areaSize = area.sizeX() * area.sizeY()) > Integer.MAX_VALUE - resultOffset)) {
            // IMPORTANT: we check >= instead of > ! It allows to guarantee that maxY+1 below will be still integer
            // Separate check of coordCount important to avoid exception in sizeY()
            throw new TooLargeArrayException("The requested ares " + area + " is too large for reindexing: "
                    + "it contains >=" + Integer.MAX_VALUE + " elements");
        }
        if (result == null) {
            result = new int[(int) (areaSize + resultOffset)];
        }
        if (frames.isEmpty()) {
            return result;
        }
        final int[] labels = result;
        final UpdatableIntArray labelsArray = SimpleMemoryModel.asUpdatableIntArray(labels);
        final MultiMatrix existing = frames.iterator().next().matrix;
        if (area.coordCount() != 2 || existing.dimCount() != 2) {
            throw new IllegalArgumentException("Objects must be represented by 2-dimensional matrix "
                    + "(multidimensional matrices are not supported by the stitcher), but we have area "
                    + area + " and existing frame " + existing);
        }
        FrameObjectStitcher.checkLabels(existing);
        // - reindexing is possible only for labels: 8/16/32-bit 2-dimensional integer matrix with 31-bit size;
        // only channel #0 will be used
        final long areaMinX = area.minX();
        final long areaMaxX = area.maxX();
        final long areaMinY = area.minY();
        final long areaMaxY = area.maxY();
        if (areaMinY == areaMaxY && quickCallAfterResolveAllBases) {
            readLabelsLineReindexedByObjectPairsAfterResolveAllBases(
                    result, resultOffset, objectPairs.dynamicDisjointSet(), frames, areaMinY, areaMinX, areaMaxX);
            return result;
        }
        final int resultDimX = (int) (areaMaxX - areaMinX + 1L);
        for (Frame frame : frames) {
            final long intersectionMinX = Math.max(areaMinX, frame.minX);
            final long intersectionMaxX = Math.min(areaMaxX, frame.maxX);
            if (intersectionMinX > intersectionMaxX) {
                continue;
            }
            final long intersectionMinY = Math.max(areaMinY, frame.minY);
            final long intersectionMaxY = Math.min(areaMaxY, frame.maxY);
            if (intersectionMinY > intersectionMaxY) {
                continue;
            }
            assert area.intersects(frame.position);
            final int length = (int) (intersectionMaxX - intersectionMinX + 1L);
            assert length >= 1 : "IRectangularArea cannot have zero sizes";
            final Matrix<? extends PArray> frameMatrix = frame.channel0;
            final PArray frameArray = frameMatrix.array();
            if (!(frameArray instanceof PFixedArray)) {
                // - necessary check: there is no guarantee that all elements of frames list have the same type
                throw new IllegalArgumentException("Objects must be represented by 1-channel integer matrix "
                        + "with <=32 bits/element, but we have " + frameMatrix);
            }
            assert frameMatrix.array() instanceof PFixedArray : "checkLabels didn't work properly: " + frameMatrix;
            final long frameDimX = frame.dimX;
            int minY = (int) (intersectionMinY - areaMinY);
            final int maxY = (int) (intersectionMaxY - areaMinY);
            assert maxY == intersectionMaxY - areaMinY : "impossible: area was already checked! "
                    + intersectionMinY + ".." + intersectionMaxY + ", " + area;
            final long differenceY = areaMinY - frame.minY;
            final int shiftX = (int) (intersectionMinX - areaMinX);
            final long frameShiftX = intersectionMinX - frame.minX;
            final DynamicDisjointSet dynamicDisjointSet = objectPairs.dynamicDisjointSet();
            if (!quickCallAfterResolveAllBases) {
                int p = minY * resultDimX + shiftX + resultOffset;
                long frameY = intersectionMinY - frame.minY;
                assert frameY == minY + differenceY;
                long frameP = frameY * frameDimX + frameShiftX;
                for (int y = minY; y <= maxY; y++, p += resultDimX, frameP += frameDimX) {
                    copyIoInts(labels, labelsArray, p, frameArray, frameP, length);
                    for (int i = p, to = p + length; i < to; i++) {
                        labels[i] = objectPairs.reindex(labels[i]);
                    }
                }
            } else if (resultDimX == 1) {
                // - important special case for reading vertical boundaries
                assert length == 1 : "intersection cannot be greater than area";
                final int n = maxY - minY + 1;
                IntStream.range(0, (n + 15) >>> 4).parallel().forEach(block -> {
                    int i = block << 4;
                    final int y = minY + i;
                    int p = y + shiftX + resultOffset;
                    long frameP = (y + differenceY) * frameDimX + frameShiftX;
                    final PFixedArray frameFixedArray = (PFixedArray) frameArray;
                    for (int to = (int) Math.min((long) i + 16, n); i < to; i++, p++, frameP += frameDimX) {
                        final int label = frameFixedArray.getInt(frameP);
                        labels[p] = dynamicDisjointSet.parentOrThis(label);
                    }
                });
            } else if (!(frameArray instanceof IntArray)) {
                IntStream.range(minY, maxY + 1).parallel().forEach(y -> {
                    // - be careful! maxY+1 <= Integer.MAX_VALUE, see above
                    int p = y * resultDimX + shiftX + resultOffset;
                    long frameP = (y + differenceY) * frameDimX + frameShiftX;
                    Arrays.applyFunc(null, Func.IDENTITY,
                            labelsArray.subArr(p, length), (PArray) frameArray.subArr(frameP, length));
                    for (int to = p + length; p < to; p++) {
                        labels[p] = dynamicDisjointSet.parentOrThis(labels[p]);
                    }
                });
            } else {
                IntStream.range(minY, maxY + 1).parallel().forEach(y -> {
                    // - be careful! maxY+1 <= Integer.MAX_VALUE, see above
                    int p = y * resultDimX + shiftX + resultOffset;
                    long frameP = (y + differenceY) * frameDimX + frameShiftX;
                    frameArray.getData(frameP, labels, p, length);
                    for (int to = p + length; p < to; p++) {
                        labels[p] = dynamicDisjointSet.parentOrThis(labels[p]);
                    }
                });
            }
// No sense to use following optimization: for direct accessible, copyToInts work very quickly,
// and previous variant provides better performance
//                else {
//                    final long frameShift = frameShiftX + da.javaArrayOffset();
//                    IntStream.range(minY, maxY + 1).parallel().forEach(y -> {
//                        int p = y * resultDimX + shiftX + resultOffset;
//                        long frameP = (y + differenceY) * frameDimX + frameShift;
//                        int q = (int) frameP;
//                        assert frameP == q;
//                        final int difference = q - p;
//                        for (int to = p + length; p < to; p++) {
//                            result[p] = dynamicDisjointSet.parentOrThis(frameInts[p + difference]);
//                        }
//                    });
//                }
        }
        return result;
    }

    // Note: frames must be labels (FrameObjectStitcher.checkLabels), but this function does not check it
    static void readLabelsLineReindexedByObjectPairsAfterResolveAllBases(
            final int[] result,
            int resultOffset,
            DynamicDisjointSet dynamicDisjointSet,
            Collection<Frame> frames,
            long areaY,
            long areaMinX,
            long areaMaxX) {
        assert result != null;
        assert resultOffset >= 0;
        final UpdatableIntArray labelsArray = SimpleMemoryModel.asUpdatableIntArray(result);
        for (Frame frame : frames) {
            if (frame.intMatrix) {
                readFrameToReindexedLine(
                        result, resultOffset, dynamicDisjointSet, frame, areaY, areaMinX, areaMaxX);
            } else {
                readFrameToReindexedLine(
                        result, resultOffset, dynamicDisjointSet, frame, areaY, areaMinX, areaMaxX, labelsArray);
            }
        }
    }

    private Frame tryToAddFrameWithReindexingOptimized(
            MultiMatrix matrix,
            IPoint leftTop,
            IRectangularArea rectangleToCrop,
            boolean disableOverlapping) {
        final PArray channel0Array = matrix.channel(0).array();
        if (OPTIMIZE_ADD_FRAME
                && autoReindexLabels
                && matrix.dimCount() == 2
                && channel0Array instanceof IntArray
                && channel0Array instanceof DirectAccessible) {
            // if autoReindexLabels, but dimCount() != 2, it will be checked in usual branch by frame.addIndexingBase
            final DirectAccessible directAccessible = (DirectAccessible) channel0Array;
            if (directAccessible.hasJavaArray() && directAccessible.javaArrayOffset() == 0) {
                // - non-zero offset is very improbable here
                int[] channel0Ints = (int[]) directAccessible.javaArray();
                return addFrameWithReindexingDirectAccessible(
                        channel0Ints,
                        (int) matrix.dim(0),
                        (int) matrix.dim(1),
                        leftTop,
                        rectangleToCrop,
                        disableOverlapping);
            }
        }
        return null;
    }

    private Frame addFrameWithReindexingDirectAccessible(
            int[] labels,
            int dimX,
            int dimY,
            IPoint leftTop,
            IRectangularArea rectangleToCrop,
            boolean disableOverlapping) {
        assert dimX >= 0 & dimY >= 0;
        assert (long) dimX * (long) dimY <= Integer.MAX_VALUE :
                "direct accessible matrix cannot be larger than Integer.MAX_VALUE";
        assert autoReindexLabels;
        final int fromX, fromY, toX, toY;
        if (rectangleToCrop == null) {
            fromX = 0;
            fromY = 0;
            toX = dimX;
            toY = dimY;
        } else {
            if (rectangleToCrop.minX() < 0 || rectangleToCrop.minY() < 0
                    || rectangleToCrop.maxX() >= dimX || rectangleToCrop.maxY() >= dimY) {
                throw new IllegalArgumentException("Rectangle to crop " + rectangleToCrop
                        + " extends beyond the borders of the labels matrix " + dimX + "x" + dimY);
            }
            fromX = (int) rectangleToCrop.minX();
            fromY = (int) rectangleToCrop.minY();
            toX = (int) rectangleToCrop.maxX() + 1;
            toY = (int) rectangleToCrop.maxY() + 1;
        }
        final int resultDimX = toX - fromX;
        final int resultDimY = toY - fromY;
        final IRectangularArea framePosition = Frame.framePosition(leftTop, resultDimX, resultDimY);
        // - checks possible overflows, zero sizes, leftTop.coordCount()
        assert toX > fromX && toY > fromY : "impossible after checks in framePosition()";
        if (disableOverlapping) {
            checkIntersected(framePosition);
        }
        final int[] result = new int[resultDimX * resultDimY];
        // - zero-filled by Java
        final int newIndexingBase = IntStream.range(0, resultDimY).parallel().map(
                zerosLabelReservedForBackground ?
                        y -> {
                            int offset = (fromY + y) * dimX + fromX;
                            return correctIndexingBaseWithZeroBackground(
                                    labels, offset, result, y * resultDimX, resultDimX);
                        } :
                        y -> {
                            int offset = (fromY + y) * dimX + fromX;
                            return correctIndexingBaseWithoutBackground(
                                    labels, offset, result, y * resultDimX, resultDimX);
                        }).max().orElse(-1);
        assert newIndexingBase != -1 : "no iterations instead of " + resultDimY;
        indexingBase = Math.max(indexingBase, newIndexingBase);
        // - max is important, like in Frame.nextIndexingBase
        return new Frame(leftTop, MultiMatrix.valueOfMono(Matrices.matrix(
                SimpleMemoryModel.asUpdatableIntArray(result), resultDimX, resultDimY)));
    }

    private int correctIndexingBaseWithZeroBackground(
            int[] labels,
            int offset,
            int[] result,
            int resultOffset,
            int length) {
        long increment = indexingBase;
        assert increment >= 0;
        int maxLabel = 0;
        for (final int offsetTo = offset + length; offset < offsetTo; offset++, resultOffset++) {
            long label = labels[offset];
            // The work of checkLabels inside addIndexingBase:
            if (label < 0) {
                throw new IllegalArgumentException("Objects must be represented by zero or negative integers, "
                        + "but it contains label " + label + " at offset " + offset);
            }
            // The work of addIndexingBase:
            if (label != 0) {
                // else result will stay zero
                label += increment;
                if (label >= Integer.MAX_VALUE) {
                    throw new IllegalStateException("Cannot continue auto-indexing: maximal label is " + label);
                }
                final int newLabel = (int) label;
                result[resultOffset] = newLabel;
                // The work of nextIndexingBase:
                if (newLabel > maxLabel) {
                    maxLabel = newLabel;
                }
            }
        }
        return maxLabel;
    }

    private int correctIndexingBaseWithoutBackground(
            int[] labels,
            int offset,
            int[] result,
            int resultOffset,
            int length) {
        long increment = indexingBase;
        assert increment >= 0;
        int maxLabel = 0;
        for (final int offsetTo = offset + length; offset < offsetTo; offset++, resultOffset++) {
            long label = labels[offset];
            // The work of checkLabels inside addIndexingBase:
            if (label < 0) {
                throw new IllegalArgumentException("Objects must be represented by zero or negative integers, "
                        + "but it contains label " + label + " at offset " + offset);
            }
            // The work of addIndexingBase:
            label += increment;
            if (label >= Integer.MAX_VALUE) {
                throw new IllegalStateException("Cannot continue auto-indexing: maximal label is " + label);
            }
            final int newLabel = (int) label;
            result[resultOffset] = newLabel;
            // The work of nextIndexingBase:
            if (newLabel > maxLabel) {
                maxLabel = newLabel;
            }
        }
        return maxLabel + 1;
    }

    private static void readFrameToReindexedLine(
            int[] result,
            int resultOffset,
            DynamicDisjointSet dynamicDisjointSet,
            Frame frame,
            long areaY,
            long areaMinX,
            long areaMaxX) {
        if (areaY < frame.minY || areaY > frame.maxY) {
            return;
        }
        final long intersectionMinX = Math.max(areaMinX, frame.minX);
        final long intersectionMaxX = Math.min(areaMaxX, frame.maxX);
        if (intersectionMinX > intersectionMaxX) {
            return;
        }
        final int length = (int) (intersectionMaxX - intersectionMinX + 1L);
        final PArray frameArray = frame.channel0.array();
        if (!(frameArray instanceof PFixedArray)) {
            throw new IllegalArgumentException("Objects must be represented by 1-channel integer matrix "
                    + "with <=32 bits/element, but we have " + frame.channel0);
        }
        final long frameDimX = frame.dimX;
        final int shiftX = (int) (intersectionMinX - areaMinX);
        final long frameShiftX = intersectionMinX - frame.minX;
        int p = shiftX + resultOffset;
        long frameP = (areaY - frame.minY) * frameDimX + frameShiftX;
        frameArray.getData(frameP, result, p, length);
        for (int to = p + length; p < to; p++) {
            result[p] = dynamicDisjointSet.parentOrThis(result[p]);
        }
    }

    private static void readFrameToReindexedLine(
            int[] result,
            int resultOffset,
            DynamicDisjointSet dynamicDisjointSet,
            Frame frame,
            long areaY,
            long areaMinX,
            long areaMaxX,
            UpdatableIntArray labelsArray) {
        if (areaY < frame.minY || areaY > frame.maxY) {
            return;
        }
        final long intersectionMinX = Math.max(areaMinX, frame.minX);
        final long intersectionMaxX = Math.min(areaMaxX, frame.maxX);
        if (intersectionMinX > intersectionMaxX) {
            return;
        }
        final int length = (int) (intersectionMaxX - intersectionMinX + 1L);
        final PArray frameArray = frame.channel0.array();
        if (!(frameArray instanceof PFixedArray)) {
            throw new IllegalArgumentException("Objects must be represented by 1-channel integer matrix "
                    + "with <=32 bits/element, but we have " + frame.channel0);
        }
        final long frameDimX = frame.dimX;
        final int shiftX = (int) (intersectionMinX - areaMinX);
        final long frameShiftX = intersectionMinX - frame.minX;
        int p = shiftX + resultOffset;
        long frameP = (areaY - frame.minY) * frameDimX + frameShiftX;
        Arrays.applyFunc(null, Func.IDENTITY,
                labelsArray.subArr(p, length), (PArray) frameArray.subArr(frameP, length));
        for (int to = p + length; p < to; p++) {
            result[p] = dynamicDisjointSet.parentOrThis(result[p]);
        }
    }

    private static void checkThinness(IRectangularArea area) {
        boolean thin = false;
        for (int k = 0, n = area.coordCount(); k < n; k++) {
            if (area.size(k) == 1) {
                thin = true;
            }
        }
        if (!thin) {
            throw new AssertionError(area + " is not thin!");
        }
    }

    private static List<IRectangularArea> dilateBy1(Collection<IRectangularArea> areas, boolean straightOnly) {
        return areas.isEmpty() ? Collections.emptyList() :
                IRectangularArea.dilate(areas,
                        IPoint.valueOfEqualCoordinates(areas.iterator().next().coordCount(), 1), straightOnly);
    }

    private static Func incrementingFunc(boolean zerosLabelReservedForBackground, double increment) {
        return zerosLabelReservedForBackground ?
                new AbstractFunc() {
                    @Override
                    public double get(double... x) {
                        return get(x[0]);
                    }

                    @Override
                    public double get(double x0) {
                        return x0 == 0.0 ? 0.0 : x0 + increment;
                    }
                } :
                LinearFunc.getInstance(increment, 1.0);
    }

    private static int[] buildRestoringTableWithReserved(int[] map, int count, int indexingBase, int minLabel) {
        assert count >= indexingBase;
        final int[] restoringTable = new int[count];
        for (int reserved = 0; reserved < indexingBase; reserved++) {
            restoringTable[reserved] = reserved;
        }
        for (int labelMinusMin = 0; labelMinusMin < map.length; labelMinusMin++) {
            final int newLabel = map[labelMinusMin];
            if (newLabel != 0) {
                restoringTable[newLabel] = minLabel + labelMinusMin;
            }
        }
        return restoringTable;
    }

    private static int[] buildRestoringTableWithoutReserved(int[] map, int count, int indexingBase, int minLabel) {
        assert count >= indexingBase;
        final int[] restoringTable = new int[count - indexingBase];
        for (int labelMinusMin = 0; labelMinusMin < map.length; labelMinusMin++) {
            final int newLabel = map[labelMinusMin];
            if (newLabel != 0) {
                restoringTable[newLabel - indexingBase] = minLabel + labelMinusMin;
            }
        }
        return restoringTable;
    }

    private static void copyIoInts(
            int[] result,
            UpdatableIntArray resultArray,
            int resultOffset,
            PArray source,
            long sourceOffset,
            int length) {
        if (source instanceof IntArray) {
            source.getData(sourceOffset, result, resultOffset, length);
        } else {
            Arrays.applyFunc(null, Func.IDENTITY,
                    resultArray.subArr(resultOffset, length), (PArray) source.subArr(sourceOffset, length));
        }
    }

    private Matrix<? extends PArray> cropMatrix(IRectangularArea rectangleToCrop, Matrix<? extends PArray> m) {
        try {
            return m.subMatrix(rectangleToCrop);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Rectangle to crop " + rectangleToCrop
                    + " extends beyond the borders of the " + m, e);
        }
    }
}
