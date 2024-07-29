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

import net.algart.arrays.Arrays;
import net.algart.arrays.*;
import net.algart.executors.modules.maps.frames.graph.MinimalCostLinkingOnStraight;
import net.algart.executors.modules.maps.frames.graph.ShortestPathFinder;
import net.algart.executors.modules.maps.frames.joints.ObjectPairs;
import net.algart.executors.modules.maps.frames.joints.QuickLabelsSet;
import net.algart.math.IPoint;
import net.algart.math.IRange;
import net.algart.math.IRectangularArea;
import net.algart.math.Range;
import net.algart.multimatrix.MultiMatrix;
import net.algart.multimatrix.MultiMatrix2D;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class FrameObjectStitcher {
    public static final int BACKGROUND_LABEL = 0;

    private static final boolean EARLY_REINDEX = false;
    // - should be false for good performance

    public enum Side {
        X_MINUS(0, true, false) {
            @Override
            public long coordinate(IRectangularArea rectangularArea) {
                return rectangularArea.min(0);
            }

            @Override
            public IRange secondCoordinateRange(IRectangularArea rectangularArea) {
                return rectangularArea.range(1);
            }

            @Override
            public Side oppositeSide() {
                return X_PLUS;
            }

            @Override
            public long indexInMatrixAlongSide(Matrix<?> matrix, long indexAlongSide) {
                return matrix.index(0, indexAlongSide);
            }
        },
        Y_MINUS(1, true, true) {
            @Override
            public long coordinate(IRectangularArea rectangularArea) {
                return rectangularArea.min(1);
            }

            @Override
            public IRange secondCoordinateRange(IRectangularArea rectangularArea) {
                return rectangularArea.range(0);
            }

            @Override
            public Side oppositeSide() {
                return Y_PLUS;
            }

            @Override
            public long indexInMatrixAlongSide(Matrix<?> matrix, long indexAlongSide) {
                return matrix.index(indexAlongSide, 0);
            }
        },
        X_PLUS(0, false, false) {
            @Override
            public long coordinate(IRectangularArea rectangularArea) {
                return rectangularArea.max(0);
            }

            @Override
            public IRange secondCoordinateRange(IRectangularArea rectangularArea) {
                return rectangularArea.range(1);
            }

            @Override
            public Side oppositeSide() {
                return X_MINUS;
            }

            @Override
            public long indexInMatrixAlongSide(Matrix<?> matrix, long indexAlongSide) {
                return matrix.index(matrix.dimX() - 1, indexAlongSide);
            }
        },
        Y_PLUS(1, false, true) {
            @Override
            public long coordinate(IRectangularArea rectangularArea) {
                return rectangularArea.max(1);
            }

            @Override
            public IRange secondCoordinateRange(IRectangularArea rectangularArea) {
                return rectangularArea.range(0);
            }

            @Override
            public Side oppositeSide() {
                return Y_MINUS;
            }

            @Override
            public long indexInMatrixAlongSide(Matrix<?> matrix, long indexAlongSide) {
                return matrix.index(indexAlongSide, matrix.dimY() - 1);
            }
        };

        private final boolean minimalCoordinate;
        private final boolean horizontal;
        private final int coordIndex;

        Side(int coordIndex, boolean minimalCoordinate, boolean horizontal) {
            this.minimalCoordinate = minimalCoordinate;
            this.coordIndex = coordIndex;
            this.horizontal = horizontal;
        }

        public boolean isMinimalCoordinate() {
            return minimalCoordinate;
        }

        public boolean isHorizontal() {
            return horizontal;
        }

        public boolean isVertical() {
            return !horizontal;
        }

        public int coordIndex() {
            return coordIndex;
        }

        public int secondCoordIndex() {
            return 1 - coordIndex;
        }

        public abstract long coordinate(IRectangularArea rectangularArea);

        public abstract IRange secondCoordinateRange(IRectangularArea rectangularArea);

        public abstract Side oppositeSide();

        public abstract long indexInMatrixAlongSide(Matrix<?> matrix, long indexAlongSide);
    }

    public enum JointingTooLargeObjects {
        SKIP() {
            List<IRectangularArea> internalBoundary(MapBuffer map, AtomicReference<IRectangularArea> largeRef) {
                final Collection<IRectangularArea> intersections = largeRef.get().intersection(map.allPositions());
                // - Internal boundary of intersections of large rectangle AND all frames indicates that
                // an object is adjacent to:
                //     * actual external (unknown) space or
                //     * areas, too large from the current frame - this object is too large and should be skipped.
                final IRectangularArea reducedLarge = IRectangularArea.minimalContainingArea(intersections);
                assert reducedLarge != null : "expanded rectangle must intersect, at least, the last frame";
                largeRef.set(reducedLarge);
                return MapBuffer.internalBoundary(intersections, true);
            }
        },
        RETAIN_LAST_PART() {
            @Override
            List<IRectangularArea> internalBoundary(MapBuffer map, AtomicReference<IRectangularArea> largeRef) {
                final IRectangularArea large = largeRef.get();
                final IRectangularArea dilatedLarge = large.dilate(1);
                final Collection<IRectangularArea> allFrames = map.allPositions().stream().filter(
                        area -> area.intersects(dilatedLarge)).collect(Collectors.toList());
                // - Internal boundary of ALL frames indicates that an object is adjacent to:
                //     * actual external (unknown) space.
                // But frames, that DOES NOT intersect dilated large rectangle, may be not analysed:
                // they do not affect result internal boundary, because in the last operator
                // it is intersected with (reduced) large rectangle.
                final IRectangularArea containing = IRectangularArea.minimalContainingArea(allFrames);
                assert containing != null : "expanded rectangle must intersect, at least, the last frame";
                final IRectangularArea reducedLarge = large.intersection(containing);
                largeRef.set(reducedLarge);
                final Collection<IRectangularArea> boundary = MapBuffer.internalBoundary(allFrames, true);
                return reducedLarge.intersection(boundary);
            }
        };

        abstract List<IRectangularArea> internalBoundary(MapBuffer map, AtomicReference<IRectangularArea> largeRef);

        public List<IRectangularArea> internalBoundary(MapBuffer map, IRectangularArea large) {
            return internalBoundary(map, new AtomicReference<>(large));
        }
    }

    private final MapBuffer map;
    private final ObjectPairs objectPairs;
    private final BitSet partialObjects;

    private boolean jointingAutoCrop = false;
    private JointingTooLargeObjects jointingTooLargeObjects = JointingTooLargeObjects.SKIP;

    private long timeInitializing;
    private long timeReadLargeArea;
    private long timeResolveAllBases;
    private long timeReindex;
    private long timeAnalyseCompleted;
    private long timeBoundaryLabelSet;
    private long timeCheckLabelSet;
    private long timeRetainOnlyCompleted;
    private IRectangularArea reducedLargeArea;

    private FrameObjectStitcher(MapBuffer map, BitSet partialObjects) {
        this.map = Objects.requireNonNull(map, "Null map");
        this.objectPairs = map.objectPairs();
        this.partialObjects = partialObjects;
    }

    public static FrameObjectStitcher getInstance(MapBuffer map, BitSet partialObject) {
        return new FrameObjectStitcher(map, partialObject);
    }

    public boolean isJointingAutoCrop() {
        return jointingAutoCrop;
    }

    public FrameObjectStitcher setJointingAutoCrop(boolean jointingAutoCrop) {
        this.jointingAutoCrop = jointingAutoCrop;
        return this;
    }

    public JointingTooLargeObjects getJointingTooLargeObjects() {
        return jointingTooLargeObjects;
    }

    public FrameObjectStitcher setJointingTooLargeObjects(JointingTooLargeObjects jointingTooLargeObjects) {
        this.jointingTooLargeObjects = Objects.requireNonNull(
                jointingTooLargeObjects, "Null tooLargeObjectProcessing");
        return this;
    }

    public MapBuffer map() {
        return map;
    }

    public void correlate(MapBuffer.Frame frame) {
        correlate(frame, true);
    }

    public void correlate(MapBuffer.Frame frame, boolean checkNonNegativeLabels) {
        Objects.requireNonNull(frame, "Null frame");
        map.checkFrameCompatibility(frame);
        checkLabels(frame.matrix(), checkNonNegativeLabels);
        for (Side side : Side.values()) {
            new SideStitcher(frame, side).readPixelsAndFindCorrelations();
        }
    }

    public MapBuffer.Frame jointCompletedObjectsOfLastFrame(IPoint expansion) {
        Objects.requireNonNull(expansion, "Null expansion");
        long t1 = System.nanoTime();
        final MapBuffer.Frame small = map.reqLastFrame();
        checkLabels(small.matrix(), false);

        final IRectangularArea smallArea = small.position();
        final IRectangularArea largeArea = smallArea.dilate(expansion);
        if (largeArea.sizeX() > Integer.MAX_VALUE
                || largeArea.sizeY() > Integer.MAX_VALUE
                || largeArea.sizeX() * largeArea.sizeY() > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("The expanded ares " + largeArea + " is too large for jointing: "
                    + "it contains >" + Integer.MAX_VALUE + " elements");
        }
        final AtomicReference<IRectangularArea> reducedLargeArea = new AtomicReference<>(largeArea);
        final Collection<IRectangularArea> boundary = jointingTooLargeObjects.internalBoundary(map, reducedLargeArea);
        final List<MapBuffer.Frame> largeAreaFrames = map.allIntersecting(reducedLargeArea.get());
        long t2 = System.nanoTime();
        timeInitializing = t2 - t1;
        this.reducedLargeArea = reducedLargeArea.get();
        // - copy for timing information

        final MapBuffer.Frame largeRawData = EARLY_REINDEX ? map.readFrame(reducedLargeArea.get()) : null;
        long t3 = System.nanoTime();
        timeReadLargeArea = t3 - t2;

        objectPairs.resolveAllBases();
        // This call allows to use below quick reindexing by using parentOrThis() instead of findBase().
        // Note, that while parallel execution (multithreading) it will not be more incorrect than
        // when we will use findBase() in the following operation!
        // But using more quick parentOrThis() provides much better performance.
        long t4 = System.nanoTime();
        timeResolveAllBases = t4 - t3;

        long t5 = t4;
        final MapBuffer.Frame reindexedLarge;
        if (EARLY_REINDEX) {
            reindexedLarge = objectPairs.reindex(largeRawData, true);
            assert reindexedLarge.matrix().elementType() == int.class;
            t5 = System.nanoTime();
        } else {
            reindexedLarge = null;
        }
        timeReindex = t5 - t4;

        final QuickLabelsSet reindexedBoundaryLabelSet = buildBoundaryLabelSet(largeAreaFrames, boundary);
        long t6 = System.nanoTime();
        timeBoundaryLabelSet = t6 - t5;

        final QuickLabelsSet reindexedCompleted = analyseCompleted(small, reindexedBoundaryLabelSet);
        long t7 = System.nanoTime();
        timeAnalyseCompleted = t7 - t6;

        for (int min = reindexedCompleted.min(), max = reindexedCompleted.max(), k = min; k <= max; k++) {
            assert !(reindexedCompleted.get(k) && reindexedBoundaryLabelSet.get(k));
        }
        long t8 = System.nanoTime();
        timeCheckLabelSet = t8 - t7;

        final MapBuffer.Frame result = EARLY_REINDEX ?
                retainCompleted(reindexedLarge, smallArea, reindexedCompleted, reindexedBoundaryLabelSet) :
                ReindexerAndRetainer.newInstance(
                                reducedLargeArea.get(),
                                largeAreaFrames,
                                smallArea,
                                objectPairs.dynamicDisjointSet(),
                                reindexedCompleted,
                                reindexedBoundaryLabelSet,
                                jointingAutoCrop)
                        .reindexAndRetainCompleted();
        long t9 = System.nanoTime();
        timeRetainOnlyCompleted = t9 - t8;
        return result;
    }

    public String jointTimeInfo() {
        return String.format(Locale.US,
                "%.3f ms initializing (%dx%d)%s " +
                        "+ %.3f resolving disjoint set bases%s " +
                        "+ %.3f ms build boundary + %.3f ms analyse completed + %.3f ms check label set " +
                        "+ %.3f ms retain completed",
                timeInitializing * 1e-6,
                reducedLargeArea.sizeX(), reducedLargeArea.sizeY(),
                EARLY_REINDEX ?
                        String.format(Locale.US, " + %.3f ms read", timeReadLargeArea * 1e-6) : "",
                timeResolveAllBases * 1e-6,
                EARLY_REINDEX ?
                        String.format(Locale.US, " + %.3f ms joint-reindex", timeReindex * 1e-6) : "",
                timeBoundaryLabelSet * 1e-6,
                timeAnalyseCompleted * 1e-6,
                timeCheckLabelSet * 1e-6,
                timeRetainOnlyCompleted * 1e-6);
    }

    public static void checkLabels(MultiMatrix matrix) {
        checkLabels(matrix, false);
    }

    private QuickLabelsSet analyseCompleted(MapBuffer.Frame smallFrame, QuickLabelsSet reindexedBoundaryWithOutside) {
        final QuickLabelsSet resultReindexedCompleted = QuickLabelsSet.newEmptyInstance();
        final Side[] sides = Side.values();
        SideStitcher[] sideStitchers = new SideStitcher[sides.length];
        for (int k = 0; k < sides.length; k++) {
            // 4 sides of the small frame
            sideStitchers[k] = new SideStitcher(smallFrame, sides[k]);
            sideStitchers[k].readPixels();
            sideStitchers[k].prepareCompleted(resultReindexedCompleted);
            // - correcting min..max range in the result - for labels in ADJACENT frames
        }
        resultReindexedCompleted.prepareToUse();
        for (int k = 0; k < sides.length; k++) {
            sideStitchers[k].findPartialAndCompleted(resultReindexedCompleted, reindexedBoundaryWithOutside);
            // - note: prepareCompleted and findPartialAndCompleted use objectPairs.quickReindex for translating
        }
        return resultReindexedCompleted;
    }

    private QuickLabelsSet buildBoundaryLabelSet(
            MapBuffer.Frame largeRawData,
            Collection<IRectangularArea> boundary) {
        final Matrix<? extends PArray> matrix = largeRawData.matrix().channel(0);
        for (final IRectangularArea area : boundary) {
            assert area.coordCount() == 2;
            assert area.sizeX() == 1 || area.sizeY() == 1 : "invalid internalBoundary() method";
        }
        final QuickLabelsSet result = QuickLabelsSet.newEmptyInstance();
        final IPoint origin = largeRawData.position().min();
        final PFixedArray array = (PFixedArray) matrix.array();
        for (final IRectangularArea area : boundary) {
//            final PFixedArray pixels = (PFixedArray) matrix.subMatrix(area.shiftBack(origin)).array();
//            for (int k = 0, length = (int) pixels.length(); k < length; k++) {
//                result.expand(objectPairs.reindex(pixels.getInt(k)));
//            }
            final int length = (int) (area.sizeX() * area.sizeY());
            final int step = area.sizeX() == 1 ? (int) matrix.dimX() : 1;
            long p = matrix.index(area.min().subtract(origin).coordinates());
            for (int k = 0; k < length; k++, p += step) {
                result.expand(objectPairs.quickReindex(array.getInt(p)));
            }
        }
        result.prepareToUse();
        for (final IRectangularArea area : boundary) {
//            final PFixedArray pixels = (PFixedArray) matrix.subMatrix(area.shiftBack(origin)).array();
//            for (int k = 0, length = (int) pixels.length(); k < length; k++) {
//                result.set(objectPairs.reindex(pixels.getInt(k)));
//            }
            final int length = (int) (area.sizeX() * area.sizeY());
            final int step = area.sizeX() == 1 ? (int) matrix.dimX() : 1;
            long p = matrix.index(area.min().subtract(origin).coordinates());
            for (int k = 0; k < length; k++, p += step) {
                result.set(objectPairs.quickReindex(array.getInt(p)));
            }
        }
        return result;
    }

    private QuickLabelsSet buildBoundaryLabelSet(List<MapBuffer.Frame> frames, Collection<IRectangularArea> boundary) {
        final List<int[]> labelsList = new ArrayList<>();
        for (final IRectangularArea area : boundary) {
            assert area.coordCount() == 2;
            assert area.sizeX() == 1 || area.sizeY() == 1 : "invalid internalBoundary() method";
            final int[] labels = map.readLabelsReindexedByObjectPairs(frames, area, true);
            labelsList.add(labels);
        }
        final QuickLabelsSet result = QuickLabelsSet.newEmptyInstance();
        for (final int[] labels : labelsList) {
            for (int label : labels) {
                result.expand(label);
            }
        }
        result.prepareToUse();
        for (final int[] labels : labelsList) {
            for (int label : labels) {
                result.set(label);
            }
        }
        return result;
    }

    private MapBuffer.Frame retainCompleted(
            MapBuffer.Frame reindexedLargeFrame,
            IRectangularArea smallFrameArea,
            QuickLabelsSet reindexedCompleted,
            QuickLabelsSet reindexedBoundaryWithOutside) {
        // We need to remove (fill by zero) in the large frame all objects, besides the following:
        // 1) fully contained in the small frame;
        // 2) objects, continued from already existing part of the map while the last stitching.
        // After this, we must also exclude all objects, touching outside space (containing pixels from the boundary):
        // we aren't sure that they are completed, and they will be probably continued while the following stitching.
        checkLabels(reindexedLargeFrame.matrix());
        // - to be on the safe side
        final MultiMatrix2D matrix = reindexedLargeFrame.matrix().asMultiMatrix2D();
        final int dimX = (int) matrix.dimX();
        final int dimY = (int) matrix.dimY();
        final int[] labels = matrix.channelToIntArray(0);
        // Deprecated variant (actually equivalent to the simple operator above):
        // final int[] labels = new LabelsAnalyser().setLabels(matrix.asMultiMatrix2D())
        //        .labelsWithCloningIfNecessary();
        final int smallMinX = (int) (smallFrameArea.min(0) - reindexedLargeFrame.position().min(0));
        final int smallMinY = (int) (smallFrameArea.min(1) - reindexedLargeFrame.position().min(1));
        final int smallMaxX = (int) (smallFrameArea.max(0) - reindexedLargeFrame.position().min(0));
        final int smallMaxY = (int) (smallFrameArea.max(1) - reindexedLargeFrame.position().min(1));
        // - overflow impossible: these are coordinates inside reindexedLargeFrame, checked by checkLabels() above
        assert smallMinX >= 0;
        assert smallMinY >= 0;
        assert smallMaxX < dimX;
        assert smallMaxY < dimY;
        final int[] minNonZeroX, maxNonZeroX;
        if (jointingAutoCrop) {
            minNonZeroX = new int[dimY];
            maxNonZeroX = new int[dimY];
        } else {
            minNonZeroX = maxNonZeroX = null;
        }
        if (jointingAutoCrop) {
            IntStream.range(0, dimY).parallel().forEach(y -> {
                final boolean insideSmallFrameByY = y >= smallMinY && y <= smallMaxY;
                int disp = y * dimX;
                int minX = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                if (insideSmallFrameByY) {
                    for (int x = 0; x < dimX; x++, disp++) {
                        final boolean insideSmallFrame = x >= smallMinX && x <= smallMaxX;
                        int label = labels[disp];
                        if ((!insideSmallFrame && !reindexedCompleted.get(label))
                                || reindexedBoundaryWithOutside.get(label)) {
                            label = labels[disp] = 0;
                        }
                        if (label != 0) {
                            if (minX == Integer.MAX_VALUE) {
                                minX = x;
                            }
                            maxX = x;
                        }
                    }
                } else {
                    for (int x = 0; x < dimX; x++, disp++) {
                        int label = labels[disp];
                        if ((!reindexedCompleted.get(label))
                                || reindexedBoundaryWithOutside.get(label)) {
                            label = labels[disp] = 0;
                        }
                        if (label != 0) {
                            if (minX == Integer.MAX_VALUE) {
                                minX = x;
                            }
                            maxX = x;
                        }
                    }
                }
                minNonZeroX[y] = minX;
                maxNonZeroX[y] = maxX;
            });
        } else {
            IntStream.range(0, dimY).parallel().forEach(y -> {
                final boolean insideSmallFrameByY = y >= smallMinY && y <= smallMaxY;
                int disp = y * dimX;
                if (insideSmallFrameByY) {
                    for (int x = 0; x < dimX; x++, disp++) {
                        final boolean insideSmallFrame = x >= smallMinX && x <= smallMaxX;
                        final int label = labels[disp];
                        if ((!insideSmallFrame && !reindexedCompleted.get(label))
                                || reindexedBoundaryWithOutside.get(label)) {
                            labels[disp] = 0;
                        }
                    }
                } else {
                    for (int x = 0; x < dimX; x++, disp++) {
                        final int label = labels[disp];
                        if ((!reindexedCompleted.get(label))
                                || reindexedBoundaryWithOutside.get(label)) {
                            labels[disp] = 0;
                        }
                    }
                }
            });
        }
        final MapBuffer.Frame result = reindexedLargeFrame.matrix(MultiMatrix.valueOf2DMono(
                SimpleMemoryModel.asMatrix(labels, matrix.dimensions())));
        return jointingAutoCrop ?
                ReindexerAndRetainer.crop(result, minNonZeroX, maxNonZeroX) :
                result;
    }

    // This version was used before
    private MapBuffer.Frame reindexAndRetainCompleted(
            MapBuffer.Frame largeFrame,
            IRectangularArea smallFrameArea,
            QuickLabelsSet reindexedCompleted,
            QuickLabelsSet reindexedBoundaryWithOutside) {
        // We need to remove (fill by zero) in the large frame all objects, besides the following:
        // 1) fully contained in the small frame;
        // 2) objects, continued from already existing part of the map while the last stitching.
        // After this, we must also exclude all objects, touching outside space (containing pixels from the boundary):
        // we aren't sure that they are completed, and they will be probably continued while the following stitching.
        checkLabels(largeFrame.matrix());
        // - to be on the safe side
        final MultiMatrix2D matrix = largeFrame.matrix().asMultiMatrix2D();
        final int dimX = (int) matrix.dimX();
        final int dimY = (int) matrix.dimY();
        final int[] labels = matrix.channelToIntArray(0);
        final int[] result = new int[labels.length];
        // - filled by zero by Java

//        final int[] labels = new LabelsAnalyser().setLabels(matrix.asMultiMatrix2D())
//                .labelsWithCloningIfNecessary();
//        - old solution with passing reindexedLarge

        final int smallMinX = (int) (smallFrameArea.min(0) - largeFrame.position().min(0));
        final int smallMinY = (int) (smallFrameArea.min(1) - largeFrame.position().min(1));
        final int smallMaxX = (int) (smallFrameArea.max(0) - largeFrame.position().min(0));
        final int smallMaxY = (int) (smallFrameArea.max(1) - largeFrame.position().min(1));
        // - overflow impossible: these are coordinates inside largeFrame, checked by checkLabels() above
        assert smallMinX >= 0;
        assert smallMinY >= 0;
        assert smallMaxX < dimX;
        assert smallMaxY < dimY;
        final int[] minNonZeroX, maxNonZeroX;
        if (jointingAutoCrop) {
            minNonZeroX = new int[dimY];
            maxNonZeroX = new int[dimY];
        } else {
            minNonZeroX = maxNonZeroX = null;
        }
        if (jointingAutoCrop) {
            IntStream.range(0, dimY).parallel().forEach(y -> {
                final boolean insideSmallFrameByY = y >= smallMinY && y <= smallMaxY;
                int disp = y * dimX;
                int minX = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                if (insideSmallFrameByY) {
                    for (int x = 0; x < dimX; x++, disp++) {
                        final boolean insideSmallFrame = x >= smallMinX && x <= smallMaxX;
//                        int label = labels[disp];
                        int label = objectPairs.quickReindex(labels[disp]);
                        if ((!insideSmallFrame && !reindexedCompleted.get(label))
                                || reindexedBoundaryWithOutside.get(label)) {
//                            label = labels[disp] = 0;
//                        }
                            label = 0;
                        } else {
                            result[disp] = label;
                        }
                        if (label != 0) {
                            if (minX == Integer.MAX_VALUE) {
                                minX = x;
                            }
                            maxX = x;
                        }
                    }
                } else {
                    for (int x = 0; x < dimX; x++, disp++) {
//                        int label = labels[disp];
                        // insideSmallFrame is false
                        int label = objectPairs.quickReindex(labels[disp]);
                        if ((!reindexedCompleted.get(label))
                                || reindexedBoundaryWithOutside.get(label)) {
//                            label = labels[disp] = 0;
//                        }
                            label = 0;
                        } else {
                            result[disp] = label;
                        }
                        if (label != 0) {
                            if (minX == Integer.MAX_VALUE) {
                                minX = x;
                            }
                            maxX = x;
                        }
                    }
                }
                minNonZeroX[y] = minX;
                maxNonZeroX[y] = maxX;
            });
        } else {
            IntStream.range(0, dimY).parallel().forEach(y -> {
                final boolean insideSmallFrameByY = y >= smallMinY && y <= smallMaxY;
                int disp = y * dimX;
                if (insideSmallFrameByY) {
                    for (int x = 0; x < dimX; x++, disp++) {
                        final boolean insideSmallFrame = x >= smallMinX && x <= smallMaxX;
//                        final int label = labels[disp];
//                        if ((!insideSmallFrame && !reindexedCompleted.get(label))
//                                || reindexedBoundaryWithOutside.get(label)) {
//                            labels[disp] = 0;
//                        }
                        final int label = objectPairs.quickReindex(labels[disp]);
                        if ((insideSmallFrame || reindexedCompleted.get(label))
                                && !reindexedBoundaryWithOutside.get(label)) {
                            result[disp] = label;
                        }
                    }
                } else {
                    for (int x = 0; x < dimX; x++, disp++) {
//                        final int label = labels[disp];
//                        if ((!reindexedCompleted.get(label))
//                                || reindexedBoundaryWithOutside.get(label)) {
//                            labels[disp] = 0;
//                        }
                        final int label = objectPairs.quickReindex(labels[disp]);
                        if ((reindexedCompleted.get(label))
                                && !reindexedBoundaryWithOutside.get(label)) {
                            result[disp] = label;
                        }
                    }
                }
            });
        }
        final MapBuffer.Frame resultFrame = largeFrame.matrix(MultiMatrix.valueOf2DMono(
                Matrices.matrix(SimpleMemoryModel.asUpdatableIntArray(result), matrix.dimensions())));
        return jointingAutoCrop ?
                ReindexerAndRetainer.crop(resultFrame, minNonZeroX, maxNonZeroX) :
                resultFrame;
    }

    private static void checkLabels(MultiMatrix matrix, boolean checkNonNegative) {
        if (!matrix.isMono() || matrix.isFloatingPoint() || matrix.bitsPerElement() > 32) {
            throw new IllegalArgumentException("Objects must be represented by 1-channel integer matrix "
                    + "with <=32 bits/element, but we have " + matrix);
            // 64-bit long cannot be processed by ObjectPairs
        }
        if (matrix.dimCount() != 2) {
            throw new IllegalArgumentException("Objects must be represented by 2-dimensional matrix "
                    + "(multidimensional matrices are not supported by this stitcher), but we have " + matrix);
        }
        if (matrix.size() == 0) {
            throw new IllegalArgumentException("Zero-size matrix cannot be stored as a frame: " + matrix);
        }
        if (matrix.dim(0) > Integer.MAX_VALUE || matrix.dim(1) > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("Matrices with sizes >" + Integer.MAX_VALUE
                    + " are not supported by object stitcher, but we have " + matrix);
        }
        if (checkNonNegative) {
            final Range range = Arrays.rangeOf(matrix.channelArray(0));
            if (range.min() < 0.0) {
                throw new IllegalArgumentException("Objects must be represented by zero or negative integers, "
                        + "but range of values is " + range + " (matrix " + matrix + ")");
            }
        }
    }

    private static int numberOfChanges(int[] labels) {
        int count = 2;
        // - from -1 to 0 and from labels.length-1 to labels.length
        for (int k = 1; k < labels.length; k++) {
            if (labels[k] != labels[k - 1]) {
                count++;
            }
        }
        return count;
    }

    private static void allChanges(int[] labels, double[] changePositions, int[] labelToRight) {
        assert labels.length > 0 : "int[0] labels should be checked before calling this method";
        changePositions[0] = 0;
        labelToRight[0] = labels[0];
        int count = 1;
        for (int k = 1; k < labels.length; k++) {
            if (labels[k] != labels[k - 1]) {
                changePositions[count] = k;
                labelToRight[count] = labels[k];
                count++;
            }
        }
        changePositions[count] = labels.length;
        labelToRight[count] = -1;
    }

    private class SideStitcher {
        private static final int NO_LABEL = -1;

        private final MapBuffer.Frame frame;
        private final Side side;
        private final Matrix<? extends PArray> frameMatrix;
        private final int length;
        private final int[] frameLabels;
        private final int[] adjacentLabels;
        // - adjacentPixels may contain NO_LABEL (no frames cover this position)

        SideStitcher(MapBuffer.Frame frame, Side side) {
            this.frame = Objects.requireNonNull(frame, "Null frame");
            this.side = Objects.requireNonNull(side, "Null side");
            this.frameMatrix = frame.matrix().channel(0);
            checkLabels(frame.matrix());
            // - to be on the safe side: should be checked outside
            this.length = (int) frameMatrix.dim(side.secondCoordIndex());
            assert length == frameMatrix.dim(side.secondCoordIndex()) : "matrix sizes were not checked by checkLabels";
            this.frameLabels = new int[length];
            this.adjacentLabels = new int[length];
            JArrays.fill(adjacentLabels, NO_LABEL);
        }

        void readPixelsAndFindCorrelations() {
            if (length == 0) {
                // - nothing to do
                return;
            }
            if (!readPixels()) {
                // - no adjacent frames: nothing to do more
                return;
            }

            final double[] framePositions = new double[numberOfChanges(frameLabels)];
            final int[] frameLabelToRight = new int[framePositions.length];
            allChanges(frameLabels, framePositions, frameLabelToRight);
            final double[] adjacentPositions = new double[numberOfChanges(adjacentLabels)];
            final int[] adjacentLabelToRight = new int[adjacentPositions.length];
            allChanges(adjacentLabels, adjacentPositions, adjacentLabelToRight);

            final MinimalCostLinkingOnStraight linking = MinimalCostLinkingOnStraight.newInstance(
                    ShortestPathFinder.Algorithm.FOR_SORTED_ACYCLIC, framePositions, adjacentPositions);
            linking.findBestLinks();

            findPairs(frameLabelToRight, adjacentLabelToRight, linking);
        }

        boolean readPixels() {
            final List<MapBuffer.Frame> adjacentFrames = findAdjacentFrames();
            readFrameLabelsAlongFrameSide();
            // - inside this frame along its side
            readAdjacentLabelsAlongFrameSide(adjacentFrames);
            // - outside this frame along the same side
            return !adjacentFrames.isEmpty();
        }

        void prepareCompleted(QuickLabelsSet resultReindexedCompleted) {
            for (int k = 0; k < length; k++) {
                final int adjacentLabel = adjacentLabels[k];
                if (adjacentLabel != NO_LABEL) {
                    resultReindexedCompleted.expand(objectPairs.quickReindex(adjacentLabel));
                }
            }
        }

        void findPartialAndCompleted(
                QuickLabelsSet resultReindexedCompleted,
                QuickLabelsSet reindexedBoundaryWithOutside) {
            for (int k = 0; k < length; k++) {
                final int frameLabel = frameLabels[k];
                final int adjacentLabel = adjacentLabels[k];
                final int frameReindexed = objectPairs.quickReindex(frameLabel);
                if (adjacentLabel != NO_LABEL) {
                    // - While correct stitching order, this adjacent label should be partial:
                    // when we processed containing frame, it was in boundary with outside (current frame)
                    final int adjacentReindexed = objectPairs.quickReindex(adjacentLabel);
                    if (!reindexedBoundaryWithOutside.get(adjacentReindexed)) {
                        resultReindexedCompleted.set(adjacentReindexed);
                        partialObjects.clear(adjacentLabel);
                    }
                }
                if (frameLabel != BACKGROUND_LABEL && reindexedBoundaryWithOutside.get(frameReindexed)) {
                    // - Of course, background is not a "partial object"
                    partialObjects.set(frameLabel);
                }
            }
        }

        private List<MapBuffer.Frame> findAdjacentFrames() {
            checkLabels(frame.matrix());
            map.checkFrameCompatibility(frame);
            final long sideCoordinate = side.coordinate(frame.position());
            return side.isMinimalCoordinate() ?
                    map.allFramesWithMaxCoordinate(side.coordIndex(), sideCoordinate - 1) :
                    map.allFramesWithMinCoordinate(side.coordIndex(), sideCoordinate + 1);
        }

        private void readFrameLabelsAlongFrameSide() {
            assert frameLabels.length == length;
            final Matrix<? extends PArray> frameMatrix = frame.matrix().channel(0);
            assert frameMatrix.array() instanceof PFixedArray : "matrix type was not checked by checkLabels";
            final PFixedArray frameArray = (PFixedArray) frameMatrix.array();
            for (int k = 0; k < length; k++) {
                frameLabels[k] = frameArray.getInt(side.indexInMatrixAlongSide(frameMatrix, k));
                assert frameLabels[k] >= 0 : "frame was not checked by checkLabels with checkNonNegative flag";
            }
        }

        private void readAdjacentLabelsAlongFrameSide(List<MapBuffer.Frame> adjacentFrames) {
            assert adjacentLabels.length == length;
            final IRange frameRange = side.secondCoordinateRange(frame.position());
            assert frameRange.size() == length;
            final Side oppositeSide = side.oppositeSide();
            for (MapBuffer.Frame adjacent : adjacentFrames) {
                checkLabels(adjacent.matrix());
                // -  we already checked the type in checkFrameCompatibility,
                // but, to be on the side, we also check dimensions (< Integer.MAX_VALUE)
                final Matrix<? extends PArray> adjacentMatrix = adjacent.matrix().channel(0);
                assert adjacentMatrix.array() instanceof PFixedArray : "matrix type was not checked by checkLabels";
                final PFixedArray adjacentArray = (PFixedArray) adjacentMatrix.array();
                final IRange adjacentRange = side.secondCoordinateRange(adjacent.position());
                final long min = Math.max(frameRange.min(), adjacentRange.min());
                final long max = Math.min(frameRange.max(), adjacentRange.max());
                if (min <= max) {
                    final int fromAtFrame = (int) (min - frameRange.min());
                    assert fromAtFrame == min - frameRange.min() : "impossible overflow for " + fromAtFrame;
                    final int fromAtAdjacent = (int) (min - adjacentRange.min());
                    assert fromAtAdjacent == min - adjacentRange.min() : "impossible overflow for " + fromAtAdjacent;
                    final int n = (int) (max - min + 1);
                    assert max - min + 1 <= length : "impossible overflow for " + min + ".." + max;
//                    System.out.printf("Testing line %d..%d | %d..%d of %d (%s) at %s and %s%n",
//                            min, max, fromAtAdjacent, fromAtAdjacent + n - 1, length, side, frame, adjacent);
                    for (int k = 0; k < n; k++) {
                        final int label = adjacentArray.getInt(
                                oppositeSide.indexInMatrixAlongSide(adjacentMatrix, fromAtAdjacent + k));
                        if (label < 0) {
                            throw new IllegalArgumentException("Map contain a frame with negative label " + label
                                    + "; such frames cannot be stitched");
                        }
                        adjacentLabels[fromAtFrame + k] = label;
                    }
                }
            }
        }

        private void findPairs(
                int[] frameLabelToRight,
                int[] adjacentLabelToRight,
                MinimalCostLinkingOnStraight linking) {
            int lastSource = -1;
            int lastTarget = -1;
            for (int k = 0, n = linking.getNumberOfLinks(); k < n; k++) {
                final int source = linking.getSourceIndex(k);
                final int target = linking.getTargetIndex(k);
                if (source != lastSource && target != lastTarget) {
                    assert source == lastSource + 1 : "Invalid behaviour of MinimalCostLinkingOnStraight";
                    assert target == lastTarget + 1 : "Invalid behaviour of MinimalCostLinkingOnStraight";
                    if (lastSource != -1) {
                        final int frameLabel = frameLabelToRight[lastSource];
                        final int adjacentLabel = adjacentLabelToRight[lastTarget];
                        assert frameLabel >= 0 : "was not checked in readFrameLabelsAlongFrameSide??";
                        assert adjacentLabel >= 0 || adjacentLabel == NO_LABEL :
                                "was not checked in readAdjacentLabelsAlongFrameSide??";
                        if (frameLabel != BACKGROUND_LABEL
                                && adjacentLabel != NO_LABEL
                                && adjacentLabel != BACKGROUND_LABEL) {
                            objectPairs.addPair(frameLabel, adjacentLabel);
//                            System.out.printf("!!! %d - %d; %d%n",
//                                    frameLabel, adjacentLabel, objectPairs.reindex(2272));
                        }
                    }
                }
                lastSource = source;
                lastTarget = target;
            }
        }
    }
}
