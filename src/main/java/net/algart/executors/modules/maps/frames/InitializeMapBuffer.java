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

package net.algart.executors.modules.maps.frames;

import net.algart.executors.modules.maps.frames.buffers.MapBuffer;
import net.algart.executors.modules.maps.frames.buffers.MapBufferKey;
import net.algart.executors.api.Executor;

public final class InitializeMapBuffer extends Executor {
    public static final String MAP_BUFFER_ID = "map_buffer_id";
    public static final String NUMBER_OF_OBJECTS = "number_of_objects";

    private final MapBufferKey mapBufferKey;

    private boolean clearOnReset = false;
    private int numberOfStoredFrames = 1;
    private boolean stitchingLabels = false;
    private boolean autoReindexLabels = false;
    private boolean zerosLabelReservedForBackground = true;
    private boolean resetIndexing = true;

    public InitializeMapBuffer() {
        setDefaultOutputScalar(MAP_BUFFER_ID);
        addOutputScalar(InitializeMapBuffer.NUMBER_OF_OBJECTS);
        this.mapBufferKey = MapBufferKey.getUniqueInstance();
    }

    public boolean isClearOnReset() {
        return clearOnReset;
    }

    public InitializeMapBuffer setClearOnReset(boolean clearOnReset) {
        this.clearOnReset = clearOnReset;
        return this;
    }

    public int getNumberOfStoredFrames() {
        return numberOfStoredFrames;
    }

    public InitializeMapBuffer setNumberOfStoredFrames(int numberOfStoredFrames) {
        this.numberOfStoredFrames = positive(numberOfStoredFrames);
        return this;
    }

    public boolean isStitchingLabels() {
        return stitchingLabels;
    }

    public InitializeMapBuffer setStitchingLabels(boolean stitchingLabels) {
        this.stitchingLabels = stitchingLabels;
        return this;
    }

    public boolean isAutoReindexLabels() {
        return autoReindexLabels;
    }

    public InitializeMapBuffer setAutoReindexLabels(boolean autoReindexLabels) {
        this.autoReindexLabels = autoReindexLabels;
        return this;
    }

    public boolean isZerosLabelReservedForBackground() {
        return zerosLabelReservedForBackground;
    }

    public InitializeMapBuffer setZerosLabelReservedForBackground(boolean zerosLabelReservedForBackground) {
        this.zerosLabelReservedForBackground = zerosLabelReservedForBackground;
        return this;
    }

    public boolean isResetIndexing() {
        return resetIndexing;
    }

    public InitializeMapBuffer setResetIndexing(boolean resetIndexing) {
        this.resetIndexing = resetIndexing;
        return this;
    }

    @Override
    public void initialize() {
        if (clearOnReset) {
            final MapBuffer mapBuffer = this.mapBufferKey.getOrCreateMapBuffer();
            if (mapBuffer != null) {
                mapBuffer.clear(resetIndexing);
            }
        }
    }

    @Override
    public void process() {
        logDebug(() -> "Newly created " + mapBufferKey
                + "; " + MapBufferKey.numberOfStoredMapBuffers() + " existing map buffers");
        final MapBuffer mapBuffer = mapBufferKey.getOrCreateMapBuffer();
        mapBuffer.setMaximalNumberOfStoredFrames(numberOfStoredFrames);
        mapBuffer.setStitchingLabels(stitchingLabels);
        mapBuffer.setAutoReindexLabels(autoReindexLabels);
        mapBuffer.setZerosLabelReservedForBackground(zerosLabelReservedForBackground);
        getScalar(NUMBER_OF_OBJECTS).setTo(mapBuffer.numberOfObjects());
        getScalar().setTo(mapBufferKey.mapBufferId());
    }
}
