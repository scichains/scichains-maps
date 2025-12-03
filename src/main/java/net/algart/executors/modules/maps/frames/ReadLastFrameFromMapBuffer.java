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

package net.algart.executors.modules.maps.frames;

import net.algart.executors.api.data.SScalar;
import net.algart.executors.modules.core.common.scalars.ScalarFilter;
import net.algart.executors.modules.maps.frames.buffers.FrameObjectStitcher;
import net.algart.executors.modules.maps.frames.buffers.MapBuffer;
import net.algart.executors.modules.maps.frames.buffers.MapBufferKey;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.multimatrix.MultiMatrix;

import java.util.Locale;

public final class ReadLastFrameFromMapBuffer extends ScalarFilter {
    public static final String INPUT_CROPPING_CONTAINING_RECTANGLE = "cropping_containing_rectangle";
    public static final String OUTPUT_SEQUENTIAL_LABELS = "labels";
    public static final String OUTPUT_DIM_X = "dim_x";
    public static final String OUTPUT_DIM_Y = "dim_y";
    public static final String OUTPUT_RESTORING_TABLE = "restoring_table";

    private boolean jointCompletedObjects = false;
    private boolean jointExpansionInPercents = false;
    private Double jointExpansionX = null;
    private Double jointExpansionY = null;
    private boolean jointingAutoCrop = false;
    private int zeroPaddingX = 0;
    private Integer zeroPaddingY = null;
    private FrameObjectStitcher.JointingTooLargeObjects jointingTooLargeObjects =
            FrameObjectStitcher.JointingTooLargeObjects.SKIP;
    private boolean sequentiallyReindex = false;
    private boolean zeroBasedRestoringTable = false;

    public ReadLastFrameFromMapBuffer() {
        setDefaultInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        addInputNumbers(INPUT_CROPPING_CONTAINING_RECTANGLE);
        setDefaultOutputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        addOutputMat(DEFAULT_OUTPUT_PORT);
        addOutputMat(OUTPUT_SEQUENTIAL_LABELS);
        addOutputScalar(OUTPUT_DIM_X);
        addOutputScalar(OUTPUT_DIM_Y);
        addOutputNumbers(ChangeRectangleInsideMapBuffer.RECTANGLE);
        addOutputNumbers(OUTPUT_RESTORING_TABLE);
        addOutputNumbers(ChangeRectangleInsideMapBuffer.CONTAINING_RECTANGLE);
    }

    public boolean isJointCompletedObjects() {
        return jointCompletedObjects;
    }

    public ReadLastFrameFromMapBuffer setJointCompletedObjects(boolean jointCompletedObjects) {
        this.jointCompletedObjects = jointCompletedObjects;
        return this;
    }

    public boolean isJointExpansionInPercents() {
        return jointExpansionInPercents;
    }

    public ReadLastFrameFromMapBuffer setJointExpansionInPercents(boolean jointExpansionInPercents) {
        this.jointExpansionInPercents = jointExpansionInPercents;
        return this;
    }

    public Double getJointExpansionX() {
        return jointExpansionX;
    }

    public ReadLastFrameFromMapBuffer setJointExpansionX(Double jointExpansionX) {
        this.jointExpansionX = jointExpansionX;
        return this;
    }

    public Double getJointExpansionY() {
        return jointExpansionY;
    }

    public ReadLastFrameFromMapBuffer setJointExpansionY(Double jointExpansionY) {
        this.jointExpansionY = jointExpansionY;
        return this;
    }

    public boolean isJointingAutoCrop() {
        return jointingAutoCrop;
    }

    public ReadLastFrameFromMapBuffer setJointingAutoCrop(boolean jointingAutoCrop) {
        this.jointingAutoCrop = jointingAutoCrop;
        return this;
    }

    public int getZeroPaddingX() {
        return zeroPaddingX;
    }

    public ReadLastFrameFromMapBuffer setZeroPaddingX(int zeroPaddingX) {
        this.zeroPaddingX = zeroPaddingX;
        return this;
    }

    public Integer getZeroPaddingY() {
        return zeroPaddingY;
    }

    public ReadLastFrameFromMapBuffer setZeroPaddingY(Integer zeroPaddingY) {
        this.zeroPaddingY = zeroPaddingY;
        return this;
    }

    public FrameObjectStitcher.JointingTooLargeObjects getJointingTooLargeObjects() {
        return jointingTooLargeObjects;
    }

    public ReadLastFrameFromMapBuffer setJointingTooLargeObjects(
            FrameObjectStitcher.JointingTooLargeObjects jointingTooLargeObjects) {
        this.jointingTooLargeObjects = nonNull(jointingTooLargeObjects);
        return this;
    }

    public boolean isSequentiallyReindex() {
        return sequentiallyReindex;
    }

    public ReadLastFrameFromMapBuffer setSequentiallyReindex(boolean sequentiallyReindex) {
        this.sequentiallyReindex = sequentiallyReindex;
        return this;
    }

    public boolean isZeroBasedRestoringTable() {
        return zeroBasedRestoringTable;
    }

    public ReadLastFrameFromMapBuffer setZeroBasedRestoringTable(boolean zeroBasedRestoringTable) {
        this.zeroBasedRestoringTable = zeroBasedRestoringTable;
        return this;
    }

    @Override
    public SScalar process(SScalar source) {
        final long mapBufferId = source.toLong();
        MultiMatrix result = process(
                mapBufferId,
                getInputNumbers(INPUT_CROPPING_CONTAINING_RECTANGLE, true).toIRectangularArea());
        getMat(DEFAULT_OUTPUT_PORT).setTo(result);
        getScalar(OUTPUT_DIM_X).setTo(result.dim(0));
        getScalar(OUTPUT_DIM_Y).setTo(result.dim(1));
        return source;
    }

    public MultiMatrix process(long mapBufferId, IRectangularArea croppingRectangle) {
        final MapBufferKey mapBufferKey = MapBufferKey.getInstance(mapBufferId);
        final MapBuffer mapBuffer = mapBufferKey.reqMapBuffer();
        MapBuffer.Frame result;
        final boolean jointing = mapBuffer.isStitchingLabels() && jointCompletedObjects;
        long t1 = System.nanoTime();
        // - not debugTime() to simplify debugging (maximal speed is not important here)
        final FrameObjectStitcher stitcher;
        IRectangularArea otherRectangle;
        if (jointing) {
            stitcher = mapBuffer.getFrameObjectStitcher();
            stitcher.setJointingAutoCrop(jointingAutoCrop);
            stitcher.setJointingTooLargeObjects(jointingTooLargeObjects);
            final IRectangularArea frameArea = mapBuffer.reqFirstFramePosition();
            final IPoint expansion;
            if (jointExpansionX == null) {
                expansion = frameArea.size();
            } else {
                double jointExpansionX = this.jointExpansionX;
                double jointExpansionY = this.jointExpansionY != null ? this.jointExpansionY : jointExpansionX;
                if (jointExpansionInPercents) {
                    jointExpansionX = jointExpansionX / 100.0 * frameArea.sizeX();
                    jointExpansionY = jointExpansionY / 100.0 * frameArea.sizeY();
                }
                expansion = IPoint.of(Math.round(jointExpansionX), Math.round(jointExpansionY));
            }
            result = stitcher.jointCompletedObjectsOfLastFrame(expansion);
            final IPoint padding = IPoint.of(zeroPaddingX, zeroPaddingY != null ? zeroPaddingY : zeroPaddingX);
            otherRectangle = result.position().dilate(padding);
        } else {
            stitcher = null;
            result = mapBuffer.reqLastFrame();
            otherRectangle = result.position();
        }
        if (croppingRectangle != null) {
            otherRectangle = otherRectangle.intersection(croppingRectangle);
            if (otherRectangle == null) {
                throw new IllegalArgumentException("The resulting frame rectangle " + otherRectangle
                        + " does not intersect containing cropping rectangle " + croppingRectangle);
            }
        }
        long t2 = System.nanoTime();
        result = result.subFrameWithZeroContinuation(otherRectangle).actualizeLazyMatrix();
        long t3 = System.nanoTime(), t4;
        if (sequentiallyReindex) {
            final MapBuffer.Frame.ReindexedFrame sequential = result.sequentiallyReindex(zeroBasedRestoringTable);
            t4 = System.nanoTime();
            getMat(OUTPUT_SEQUENTIAL_LABELS).setTo(sequential.reindexed().matrix());
            getNumbers(OUTPUT_RESTORING_TABLE).setTo(sequential.sequentialResrotingTable(), 1);
        } else {
            t4 = t3;
        }
        if (LOGGABLE_DEBUG) {
            logDebug(String.format(Locale.US,
                    "%s %s at %s from %s (%s): %.3f ms = %s + %.3f ms actualizing %s",
                    jointing ? "Jointing" : "Reading",
                    result.matrix(),
                    result.position(), mapBufferKey, mapBuffer,
                    (t4 - t1) * 1e-6,
                    stitcher == null ?
                            String.format(Locale.US, "%.3f ms reading", (t2 - t1) * 1e-6) :
                            stitcher.jointTimeInfo(),
                    (t3 - t2) * 1e-6,
                    sequentiallyReindex ?
                            String.format(Locale.US, "+ %.3f ms sequential reindex", (t4 - t3) * 1e-6) :
                            "(no reindex)"));
        }
        getNumbers(ChangeRectangleInsideMapBuffer.RECTANGLE).setTo(result.position());
        if (isOutputNecessary(ChangeRectangleInsideMapBuffer.CONTAINING_RECTANGLE)) {
            getNumbers(ChangeRectangleInsideMapBuffer.CONTAINING_RECTANGLE).setTo(mapBuffer.containingRectangle());
        }
        return result.matrix();
    }

    @Override
    public String visibleOutputPortName() {
        return DEFAULT_OUTPUT_PORT;
    }
}
