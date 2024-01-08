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
import net.algart.executors.modules.maps.frames.joints.QuickLabelsSet;
import net.algart.arrays.*;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.math.functions.Func;
import net.algart.multimatrix.MultiMatrix;

import java.util.List;
import java.util.stream.IntStream;

class ReindexerAndRetainer {
    // We need to remove (fill by zero) in the large frame all objects, besides the following:
    // 1) fully contained in the small frame;
    // 2) objects, continued from already existing part of the map while the last stitching.
    // After this, we must also exclude all objects, touching outside space (containing pixels from the boundary):
    // we aren't sure that they are completed, and they will be probably continued while the following stitching.

    final IRectangularArea largeArea;
    final List<MapBuffer.Frame> frames;
    final DynamicDisjointSet dynamicDisjointSet;
    final QuickLabelsSet reindexedCompleted;
    final QuickLabelsSet reindexedBoundaryWithOutside;
    final long largeAreaMinX;
    final long largeAreaMaxX;
    final long largeAreaMinY;
    final int smallMinX;
    final int smallMinY;
    final int smallMaxX;
    final int smallMaxY;
    final int dimX;
    final int dimY;
    final int[] labels;
    final UpdatableIntArray labelsArray;
    final int[] minNonZeroX, maxNonZeroX;
    final boolean jointingAutoCrop;

    private ReindexerAndRetainer(
            IRectangularArea largeArea,
            List<MapBuffer.Frame> frames,
            IRectangularArea smallFrameArea,
            DynamicDisjointSet dynamicDisjointSet,
            QuickLabelsSet reindexedCompleted,
            QuickLabelsSet reindexedBoundaryWithOutside,
            boolean jointingAutoCrop) {
        this.largeArea = largeArea;
        this.frames = frames;
        this.dynamicDisjointSet = dynamicDisjointSet;
        this.reindexedCompleted = reindexedCompleted;
        this.reindexedBoundaryWithOutside = reindexedBoundaryWithOutside;
        this.largeAreaMinX = largeArea.minX();
        this.largeAreaMaxX = largeArea.maxX();
        this.largeAreaMinY = largeArea.minY();
        this.dimX = (int) largeArea.sizeX();
        this.dimY = (int) largeArea.sizeY();
        this.smallMinX = (int) (smallFrameArea.minX() - largeAreaMinX);
        this.smallMinY = (int) (smallFrameArea.minY() - largeAreaMinY);
        this.smallMaxX = (int) (smallFrameArea.maxX() - largeAreaMinX);
        this.smallMaxY = (int) (smallFrameArea.maxY() - largeAreaMinY);
        // - overflow impossible: these are coordinates inside largeFrame, checked by checkLabels() above
        assert smallMinX >= 0;
        assert smallMinY >= 0;
        assert smallMaxX < dimX;
        assert smallMaxY < dimY;
        final long labelsLength = largeArea.sizeX() * largeArea.sizeY();
        assert labelsLength == (int) labelsLength : "sizes of result area were not checked before";
        this.labels = new int[(int) labelsLength];
        // - filled by zero by Java
        this.labelsArray = SimpleMemoryModel.asUpdatableIntArray(labels);
        this.jointingAutoCrop = jointingAutoCrop;
        if (jointingAutoCrop) {
            this.minNonZeroX = new int[dimY];
            this.maxNonZeroX = new int[dimY];
        } else {
            this.minNonZeroX = null;
            this.maxNonZeroX = null;
        }
    }

    public static ReindexerAndRetainer newInstance(
            IRectangularArea largeArea,
            List<MapBuffer.Frame> frames,
            IRectangularArea smallFrameArea,
            DynamicDisjointSet dynamicDisjointSet,
            QuickLabelsSet reindexedCompleted,
            QuickLabelsSet reindexedBoundaryWithOutside,
            boolean jointingAutoCrop) {
        if (frames.isEmpty() || !frames.iterator().next().isIntMatrix()) {
            return new ReindexerAndRetainerNonInt(
                    largeArea,
                    frames,
                    smallFrameArea,
                    dynamicDisjointSet,
                    reindexedCompleted,
                    reindexedBoundaryWithOutside,
                    jointingAutoCrop);
        } else if (frames.stream().allMatch(frame -> frame.channel0Ints != null)) {
            return new ReindexerAndRetainerDirectAccessible(
                    largeArea,
                    frames,
                    smallFrameArea,
                    dynamicDisjointSet,
                    reindexedCompleted,
                    reindexedBoundaryWithOutside,
                    jointingAutoCrop);
        } else {
            return new ReindexerAndRetainer(
                    largeArea,
                    frames,
                    smallFrameArea,
                    dynamicDisjointSet,
                    reindexedCompleted,
                    reindexedBoundaryWithOutside,
                    jointingAutoCrop);
        }
    }

    public MapBuffer.Frame reindexAndRetainCompleted() {
        IntStream.range(0, dimY).parallel().forEach(this::processSingleLine);
        final MapBuffer.Frame resultFrame = new MapBuffer.Frame(
                largeArea.min(),
                MultiMatrix.valueOf2DMono(
                        Matrices.matrix(SimpleMemoryModel.asUpdatableIntArray(labels), largeArea.sizes()))
        );
        return jointingAutoCrop ?
                crop(resultFrame, minNonZeroX, maxNonZeroX) :
                resultFrame;
    }

    void processSingleLine(int i) {
        final boolean crop = jointingAutoCrop;
        final boolean insideSmallFrameByY = i >= smallMinY && i <= smallMaxY;
        final long y = largeAreaMinY + i;
        int disp = i * dimX;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (MapBuffer.Frame frame : frames) {
            if (y < frame.minY || y > frame.maxY) {
                continue;
            }
            final long intersectionMinX = Math.max(largeAreaMinX, frame.minX);
            final long intersectionMaxX = Math.min(largeAreaMaxX, frame.maxX);
            if (intersectionMinX > intersectionMaxX) {
                continue;
            }
            final int length = (int) (intersectionMaxX - intersectionMinX + 1L);
            final PArray frameArray = frame.channel0.array();
            final long frameDimX = frame.dimX;
            int x = (int) (intersectionMinX - largeAreaMinX);
            final long frameX = intersectionMinX - frame.minX;
            int p = x + disp;
            long frameP = (largeAreaMinY + i - frame.minY) * frameDimX + frameX;
            readFrameLine(frameArray, p, frameP, length);
            if (insideSmallFrameByY) {
                for (int to = p + length; p < to; x++, p++) {
                    final int label = dynamicDisjointSet.parentOrThis(labels[p]);
                    final boolean insideSmallFrame = x >= smallMinX && x <= smallMaxX;
                    final boolean insideSmallOrCompleted = insideSmallFrame || reindexedCompleted.get(label);
                    if (!insideSmallOrCompleted || reindexedBoundaryWithOutside.get(label)) {
                        labels[p] = 0;
                    } else {
                        labels[p] = label;
                        if (crop && label != 0) {
                            if (minX == Integer.MAX_VALUE) {
                                minX = x;
                            }
                            maxX = x;
                        }
                    }
                }
            } else {
                for (int to = p + length; p < to; x++, p++) {
                    final int label = dynamicDisjointSet.parentOrThis(labels[p]);
                    final boolean completed = reindexedCompleted.get(label);
                    if (!completed || reindexedBoundaryWithOutside.get(label)) {
                        labels[p] = 0;
                    } else {
                        labels[p] = label;
                        if (crop && label != 0) {
                            if (minX == Integer.MAX_VALUE) {
                                minX = x;
                            }
                            maxX = x;
                        }
                    }
                }
            }
        }
        if (crop) {
            minNonZeroX[i] = minX;
            maxNonZeroX[i] = maxX;
        }
    }

    void readFrameLine(PArray frameArray, int p, long frameP, int length) {
        frameArray.getData(frameP, labels, p, length);
    }

    static MapBuffer.Frame crop(MapBuffer.Frame frame, int[] minNonZeroX, int[] maxNonZeroX) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = 0; y < minNonZeroX.length; y++) {
            minX = Math.min(minX, minNonZeroX[y]);
            maxX = Math.max(maxX, maxNonZeroX[y]);
            if (maxNonZeroX[y] >= 0) {
                if (minY == Integer.MAX_VALUE) {
                    minY = y;
                }
                maxY = y;
            }
        }
        final Matrix<? extends PArray> m = frame.matrix().channel(0);
        if (minX == 0 && minY == 0 && maxX == m.dimX() - 1 && maxY == m.dimY() - 1) {
            return frame;
        }
        if (maxY < 0) {
            minX = 0;
            minY = 0;
            maxX = (int) Math.min(1, m.dimX()) - 1;
            maxY = (int) Math.min(1, m.dimY()) - 1;
            // - "Math.min" to be on the safe side: such matrices cannot not used in frames
        }
        return new MapBuffer.Frame(
                frame.position().min().add(IPoint.valueOf(minX, minY)),
                MultiMatrix.valueOf2DMono(m.subMatrix(minX, minY, maxX + 1, maxY + 1)));
    }

    private static class ReindexerAndRetainerNonInt extends ReindexerAndRetainer {
        private ReindexerAndRetainerNonInt(
                IRectangularArea largeArea,
                List<MapBuffer.Frame> frames,
                IRectangularArea smallFrameArea,
                DynamicDisjointSet dynamicDisjointSet,
                QuickLabelsSet reindexedCompleted,
                QuickLabelsSet reindexedBoundaryWithOutside,
                boolean jointingAutoCrop) {
            super(
                    largeArea,
                    frames,
                    smallFrameArea,
                    dynamicDisjointSet,
                    reindexedCompleted,
                    reindexedBoundaryWithOutside,
                    jointingAutoCrop);
        }

        @Override
        void readFrameLine(PArray frameArray, int p, long frameP, int length) {
            Arrays.applyFunc(null, Func.IDENTITY,
                    labelsArray.subArr(p, length),
                    (PArray) frameArray.subArr(frameP, length));
        }
    }

    private static class ReindexerAndRetainerDirectAccessible extends ReindexerAndRetainer {
        private ReindexerAndRetainerDirectAccessible(
                IRectangularArea largeArea,
                List<MapBuffer.Frame> frames,
                IRectangularArea smallFrameArea,
                DynamicDisjointSet dynamicDisjointSet,
                QuickLabelsSet reindexedCompleted,
                QuickLabelsSet reindexedBoundaryWithOutside,
                boolean jointingAutoCrop) {
            super(
                    largeArea,
                    frames,
                    smallFrameArea,
                    dynamicDisjointSet,
                    reindexedCompleted,
                    reindexedBoundaryWithOutside,
                    jointingAutoCrop);
        }

        @Override
        void processSingleLine(int i) {
            final boolean crop = jointingAutoCrop;
            final boolean insideSmallFrameByY = i >= smallMinY && i <= smallMaxY;
            final long y = largeAreaMinY + i;
            int disp = i * dimX;
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            for (MapBuffer.Frame frame : frames) {
                if (y < frame.minY || y > frame.maxY) {
                    continue;
                }
                final long intersectionMinX = Math.max(largeAreaMinX, frame.minX);
                final long intersectionMaxX = Math.min(largeAreaMaxX, frame.maxX);
                if (intersectionMinX > intersectionMaxX) {
                    continue;
                }
                final int length = (int) (intersectionMaxX - intersectionMinX + 1L);
                final int[] frameArray = frame.channel0Ints;
                assert frameArray != null;
                final long frameDimX = frame.dimX;
                int x = (int) (intersectionMinX - largeAreaMinX);
                final long frameX = intersectionMinX - frame.minX;
                final int p = x + disp;
                int frameP = (int) ((largeAreaMinY + i - frame.minY) * frameDimX + frameX);
                final int difference = p - frameP;
                if (insideSmallFrameByY) {
                    for (int to = frameP + length; frameP < to; x++, frameP++) {
                        final int label = dynamicDisjointSet.parentOrThis(frameArray[frameP]);
                        final boolean insideSmallFrame = x >= smallMinX && x <= smallMaxX;
                        final boolean insideSmallOrCompleted = insideSmallFrame || reindexedCompleted.get(label);
                        if (insideSmallOrCompleted && !reindexedBoundaryWithOutside.get(label)) {
                            // else labels[p] stays to be 0
                            labels[frameP + difference] = label;
                            if (crop && label != 0) {
                                if (minX == Integer.MAX_VALUE) {
                                    minX = x;
                                }
                                maxX = x;
                            }
                        }
                    }
                } else {
                    final int differenceMinusDisp = difference - disp;
                    assert differenceMinusDisp == x - frameP;
                    for (int to = frameP + length; frameP < to; frameP++) {
                        final int label = dynamicDisjointSet.parentOrThis(frameArray[frameP]);
                        final boolean completed = reindexedCompleted.get(label);
                        if (completed && !reindexedBoundaryWithOutside.get(label)) {
                            // else labels[p] stays to be 0
                            labels[frameP + difference] = label;
                            // frameP + difference = p
                            if (crop && label != 0) {
                                maxX = frameP + differenceMinusDisp;
                                // - i.e. p - disp = x
                                if (minX == Integer.MAX_VALUE) {
                                    minX = maxX;
                                }
                            }
                        }
                    }
                }
            }
            if (crop) {
                minNonZeroX[i] = minX;
                maxNonZeroX[i] = maxX;
            }
        }
    }
}
