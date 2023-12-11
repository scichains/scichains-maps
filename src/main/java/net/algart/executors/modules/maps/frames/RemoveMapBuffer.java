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

package net.algart.executors.modules.maps.frames;

import net.algart.executors.modules.maps.frames.buffers.MapBufferKey;
import net.algart.executors.api.data.SScalar;
import net.algart.executors.modules.core.common.scalars.ScalarFilter;

public final class RemoveMapBuffer extends ScalarFilter {
    private boolean doAction = true;

    public RemoveMapBuffer() {
        setDefaultInputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
        setDefaultOutputScalar(InitializeMapBuffer.MAP_BUFFER_ID);
    }

    public boolean isDoAction() {
        return doAction;
    }

    public RemoveMapBuffer setDoAction(boolean doAction) {
        this.doAction = doAction;
        return this;
    }

    @Override
    public SScalar process(SScalar source) {
        if (doAction) {
            remove(source.toLong(), true);
            return source;
        } else {
            return SScalar.valueOf(null);
        }
    }

    public void remove(long mapBufferId, boolean requireExisting) {
        final MapBufferKey key = MapBufferKey.getInstance(mapBufferId);
        if (!key.removeMapBuffer() && requireExisting) {
            throw new IllegalStateException("No already created map buffer for " + key);
        }
    }
}
