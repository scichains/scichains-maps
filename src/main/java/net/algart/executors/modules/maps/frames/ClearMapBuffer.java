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

import net.algart.executors.api.data.SScalar;
import net.algart.executors.modules.core.common.scalars.ScalarFilter;
import net.algart.executors.modules.maps.frames.buffers.MapBuffer;
import net.algart.executors.modules.maps.frames.buffers.MapBufferKey;

public final class ClearMapBuffer extends ScalarFilter {
    public enum Stage {
        RESET,
        EXECUTE
    }

    private Stage stage = Stage.RESET;
    private boolean doAction = true;
    private boolean resetIndexing = true;

    public ClearMapBuffer() {
        setDefaultInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        setDefaultOutputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        addOutputScalar(InitializeMapBuffer.NUMBER_OF_OBJECTS);
    }

    public Stage getStage() {
        return stage;
    }

    public ClearMapBuffer setStage(Stage stage) {
        this.stage = nonNull(stage);
        return this;
    }

    public boolean isDoAction() {
        return doAction;
    }

    public ClearMapBuffer setDoAction(boolean doAction) {
        this.doAction = doAction;
        return this;
    }

    public boolean isResetIndexing() {
        return resetIndexing;
    }

    public ClearMapBuffer setResetIndexing(boolean resetIndexing) {
        this.resetIndexing = resetIndexing;
        return this;
    }

    @Override
    public void initialize() {
        if (doAction && stage == Stage.RESET) {
            final long mapBufferId = getInputScalar(InitializeMapBuffer.MAP_BUFFER_ID).toLong();
            final MapBuffer mapBuffer = MapBufferKey.getInstance(mapBufferId).getMapBuffer();
            clear(mapBuffer);
        }
    }

    @Override
    public SScalar process(SScalar source) {
        final long mapBufferId = source.toLong();
        final MapBuffer mapBuffer = MapBufferKey.getInstance(mapBufferId).getMapBuffer();
        if (doAction && stage == Stage.EXECUTE) {
            clear(mapBuffer);
        }
        getScalar(InitializeMapBuffer.NUMBER_OF_OBJECTS).setTo(mapBuffer.numberOfObjects());
        return source;
    }

    public void clear(MapBuffer mapBuffer) {
        if (mapBuffer != null) {
            mapBuffer.clear(resetIndexing);
        }
    }
}
