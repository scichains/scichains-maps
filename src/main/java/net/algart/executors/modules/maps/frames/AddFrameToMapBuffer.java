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

import net.algart.executors.api.data.SNumbers;
import net.algart.executors.api.data.SScalar;
import net.algart.executors.modules.core.common.matrices.MultiMatrixFilter;
import net.algart.executors.modules.maps.frames.buffers.MapBuffer;
import net.algart.executors.modules.maps.frames.buffers.MapBufferKey;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.multimatrix.MultiMatrix;

import java.util.Locale;

public final class AddFrameToMapBuffer extends MultiMatrixFilter {
    public static final String POSITION = "position";
    public static final String RECTANGLE_TO_CROP = "rectangle_to_crop";
    public static final String EXPANDED_RECTANGLE = "expanded";

    private int startX = 0;
    private int startY = 0;
    private int expansionX = 0;
    private Integer expansionY = null;
    private boolean disableOverlapping = false;
    private boolean zerosReservedForBackground = true;

    public AddFrameToMapBuffer() {
        addInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        addInputNumbers(POSITION);
        addInputNumbers(RECTANGLE_TO_CROP);
        addOutputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        addOutputNumbers(EXPANDED_RECTANGLE);
        addOutputNumbers(ChangeRectangleInsideMapBuffer.RECTANGLE);
        addOutputNumbers(ChangeRectangleInsideMapBuffer.CONTAINING_RECTANGLE);
    }

    public int getStartX() {
        return startX;
    }

    public AddFrameToMapBuffer setStartX(int startX) {
        this.startX = startX;
        return this;
    }

    public int getStartY() {
        return startY;
    }

    public AddFrameToMapBuffer setStartY(int startY) {
        this.startY = startY;
        return this;
    }

    public int getExpansionX() {
        return expansionX;
    }

    public AddFrameToMapBuffer setExpansionX(int expansionX) {
        this.expansionX = expansionX;
        return this;
    }

    public Integer getExpansionY() {
        return expansionY;
    }

    public AddFrameToMapBuffer setExpansionY(Integer expansionY) {
        this.expansionY = expansionY;
        return this;
    }

    public boolean isDisableOverlapping() {
        return disableOverlapping;
    }

    public AddFrameToMapBuffer setDisableOverlapping(boolean disableOverlapping) {
        this.disableOverlapping = disableOverlapping;
        return this;
    }

    public boolean isZerosReservedForBackground() {
        return zerosReservedForBackground;
    }

    public AddFrameToMapBuffer setZerosReservedForBackground(boolean zerosReservedForBackground) {
        this.zerosReservedForBackground = zerosReservedForBackground;
        return this;
    }

    @Override
    public MultiMatrix process(MultiMatrix source) {
        SScalar id = getInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        final MultiMatrix result = process(id.toLong(), source);
        getScalar(InitializeMapBuffer.MAP_BUFFER_ID).setTo(id);
        return result;
    }

    public MultiMatrix process(long mapBufferId, MultiMatrix source) {
        final MapBufferKey mapBufferKey = MapBufferKey.getInstance(mapBufferId);
        final MapBuffer mapBuffer = mapBufferKey.reqMapBuffer();
        long t1 = System.nanoTime();
        final SNumbers position = getInputNumbers(POSITION, true);
        final boolean readingRequested = isOutputNecessary(DEFAULT_OUTPUT_PORT);
        IPoint originPoint = position.isProbableRectangularArea() ?
                position.toIRectangularArea().min() :
                position.toIPoint();
        if (originPoint == null) {
            originPoint = IPoint.of(startX, startY);
        }
        final IRectangularArea rectangleToCrop = getInputNumbers(
                RECTANGLE_TO_CROP, true).toIRectangularArea();
        long t2 = System.nanoTime();
        final MapBuffer.Frame frame = mapBuffer.addFrame(source, originPoint, rectangleToCrop, disableOverlapping);
        long t3 = System.nanoTime();
        final IRectangularArea expanded = mapBuffer.expandRectangleOnMap(
                frame.position(),
                IPoint.of(expansionX, expansionY != null ? expansionY : expansionX),
                true);
        getNumbers(ChangeRectangleInsideMapBuffer.RECTANGLE).setTo(frame.position());
        getNumbers(EXPANDED_RECTANGLE).setTo(expanded);
        if (isOutputNecessary(ChangeRectangleInsideMapBuffer.CONTAINING_RECTANGLE)) {
            getNumbers(ChangeRectangleInsideMapBuffer.CONTAINING_RECTANGLE).setTo(mapBuffer.containingRectangle());
        }
        long t4 = System.nanoTime();
        MultiMatrix result = null;
        if (readingRequested) {
            result = mapBuffer.readMatrix(expanded);
        }
        long t5 = System.nanoTime();
        logDebug(() -> String.format(Locale.US,
                "Adding %s to %s (%s): %.3f ms = "
                        + "%.3f ms initializing%s + %.3f ms adding + %.3f making outputs + %.3f reading matrix",
                frame, mapBufferKey, mapBuffer,
                (t5 - t1) * 1e-6,
                (t2 - t1) * 1e-6, rectangleToCrop != null ? "/cropping" : "",
                (t3 - t2) * 1e-6,
                (t4 - t3) * 1e-6,
                (t5 - t4) * 1e-6));
        return result;
    }

    @Override
    protected boolean resultRequired() {
        return false;
    }
}
