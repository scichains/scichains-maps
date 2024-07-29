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

public final class SetMapBufferCapacity extends ScalarFilter {
    private int numberOfStoredFrames = 1;

    public SetMapBufferCapacity() {
        setDefaultInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        setDefaultOutputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
    }

    public int getNumberOfStoredFrames() {
        return numberOfStoredFrames;
    }

    public SetMapBufferCapacity setNumberOfStoredFrames(int numberOfStoredFrames) {
        this.numberOfStoredFrames = positive(numberOfStoredFrames);
        return this;
    }

    public SScalar process(SScalar source) {
        final MapBuffer mapBuffer = MapBufferKey.getInstance(source.toLong()).getMapBuffer();
        mapBuffer.setMaximalNumberOfStoredFrames(numberOfStoredFrames);
        return source;
    }
}
