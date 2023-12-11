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

package net.algart.executors.modules.maps.frames.buffers;

import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MapBufferKey {
    private static final AtomicLong CURRENT_KEY = new AtomicLong(0x6D * 1000000L);
    // 6D - character 'm'. Zero value is also correct, but some non-zero value helps
    // to recognize this keys while testing.

    private static final WeakHashMap<MapBufferKey, MapBuffer> mapBuffers = new WeakHashMap<>();

    private final long mapBufferId;

    private MapBufferKey(long mapBufferId) {
        this.mapBufferId = mapBufferId;
    }

    public static MapBufferKey getInstance(long mapBufferId) {
        return new MapBufferKey(mapBufferId);
    }

    public static MapBufferKey getUniqueInstance() {
        return new MapBufferKey(CURRENT_KEY.getAndIncrement());
    }

    public static int numberOfStoredMapBuffers() {
        synchronized (mapBuffers) {
            return mapBuffers.size();
        }
    }

    public long mapBufferId() {
        return mapBufferId;
    }

    public MapBuffer getOrCreateMapBuffer() {
        synchronized (mapBuffers) {
            return mapBuffers.computeIfAbsent(this, k -> MapBuffer.newInstance());
        }
    }

    public MapBuffer getMapBuffer() {
        synchronized (mapBuffers) {
            return mapBuffers.get(this);
        }
    }

    public MapBuffer reqMapBuffer() {
        final MapBuffer result = getMapBuffer();
        if (result == null) {
            throw new IllegalStateException("No already created map buffer for " + this);
        }
        return result;
    }

    public boolean removeMapBuffer() {
        synchronized (mapBuffers) {
            return mapBuffers.remove(this) != null;
        }
    }

    @Override
    public String toString() {
        return "map buffer key @@" + mapBufferId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MapBufferKey that = (MapBufferKey) o;
        return mapBufferId == that.mapBufferId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mapBufferId);
    }
}
