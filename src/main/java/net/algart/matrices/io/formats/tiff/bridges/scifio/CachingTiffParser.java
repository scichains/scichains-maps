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

package net.algart.matrices.io.formats.tiff.bridges.scifio;

import io.scif.FormatException;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.nio.file.Path;
import java.util.*;

public class CachingTiffParser extends TiffParser {
    public static final long DEFAULT_MAX_CACHING_MEMORY = Math.max(0, getLongProperty(
            "net.algart.matrices.libs.scifio.tiff.defaultMaxCachingMemory", 256 * 1048576L));
    // - 256 MB maximal cache by default

    private static final System.Logger LOG = System.getLogger(CachingTiffParser.class.getName());

    private volatile long maxCachingMemory = DEFAULT_MAX_CACHING_MEMORY;
    // - volatile is necessary for correct parallel work of the setter

    private final Map<TileIndex, CachedTile> tileMap = new HashMap<>();
    private final Queue<CachedTile> tileCache = new LinkedList<CachedTile>();
    private long currentCacheMemory = 0;
    private final Object tileCacheLock = new Object();

    public CachingTiffParser(Context context, Path file) throws IOException {
        super(context, file);
        this.setAutoUnpackUnusualPrecisions(true);
    }

    public CachingTiffParser(Context context, Location location) throws IOException {
        super(context, location);
        this.setAutoUnpackUnusualPrecisions(true);
    }

    public CachingTiffParser(Context context, DataHandle<Location> in) {
        super(context, in);
        this.setAutoUnpackUnusualPrecisions(true);
    }

    public long getMaxCachingMemory() {
        return maxCachingMemory;
    }

    public CachingTiffParser setMaxCachingMemory(long maxCachingMemory) {
        if (maxCachingMemory < 0) {
            throw new IllegalArgumentException("Negative maxCachingMemory = " + maxCachingMemory);
        }
        this.maxCachingMemory = maxCachingMemory;
        return this;
    }

    public CachingTiffParser disableCaching() {
        return setMaxCachingMemory(0);
    }

    @Override
    public byte[] getTile(ExtendedIFD ifd, byte[] samples, int row, int col) throws FormatException, IOException {
        if (maxCachingMemory == 0) {
            return getTileWithoutCache(ifd, samples, row, col);
        }
        return getTile(new TileIndex(ifd, row, col)).getData();
    }


    private byte[] getTileWithoutCache(ExtendedIFD ifd, byte[] buf, int row, int col)
            throws FormatException, IOException {
        return super.getTile(ifd, buf, row, col);
    }

    private CachedTile getTile(TileIndex tileIndex) {
        synchronized (tileCacheLock) {
            CachedTile tile = tileMap.get(tileIndex);
            if (tile == null) {
                tile = new CachedTile(tileIndex);
                tileMap.put(tileIndex, tile);
            }
            return tile;
            // So, we store (without ability to remove) all Tile objects in the global cache tileMap.
            // It is not a problem, because Tile is a very lightweight object.
            // In any case, ifdList already contains comparable amount of data: strip offsets and strip byte counts.
        }
    }

    private static long getLongProperty(String propertyName, long defaultValue) {
        try {
            return Long.getLong(propertyName, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    class CachedTile {
        private final TileIndex tileIndex;

        private final Object onlyThisTileLock = new Object();
        private Reference<byte[]> cachedData = null;
        // - we use SoftReference to be on the safe side in addition to our own memory control
        private long cachedDataSize;

        CachedTile(TileIndex tileIndex) {
            this.tileIndex = Objects.requireNonNull(tileIndex, "Null tileIndex");
        }

        public byte[] getData() throws FormatException, IOException {
            synchronized (onlyThisTileLock) {
                final var ifd = tileIndex.ifd;
                final byte[] cachedData = cachedData();
                if (cachedData != null) {
                    LOG.log(System.Logger.Level.TRACE, () -> "CACHED tile: " + tileIndex);
                    return cachedData;
                } else {
                    final byte[] result = getTileWithoutCache(ifd, null, tileIndex.row, tileIndex.col);
                    saveCache(result);
                    return result;
                }
            }
        }

        private byte[] cachedData() {
            synchronized (tileCacheLock) {
                if (cachedData == null) {
                    return null;
                }
                byte[] data = cachedData.get();
                if (data == null) {
                    LOG.log(System.Logger.Level.INFO,
                            () -> "CACHED tile is freed by garbage collector due to " +
                                    "insufficiency of memory: " + tileIndex);
                }
                return data;
            }
        }

        private void saveCache(byte[] data) {
            Objects.requireNonNull(data);
            synchronized (tileCacheLock) {
                if (maxCachingMemory > 0) {
                    this.cachedData = new SoftReference<>(data);
                    this.cachedDataSize = data.length;
                    currentCacheMemory += data.length;
                    tileCache.add(this);
                    LOG.log(System.Logger.Level.TRACE, () -> "STORING tile in cache: " + tileIndex);
                    while (currentCacheMemory > maxCachingMemory) {
                        CachedTile tile = tileCache.remove();
                        assert tile != null;
                        currentCacheMemory -= tile.cachedDataSize;
                        tile.cachedData = null;
                        Runtime runtime = Runtime.getRuntime();
                        LOG.log(System.Logger.Level.TRACE, () -> String.format(Locale.US,
                                "REMOVING tile from cache (limit %.1f MB exceeded, used memory %.1f MB): %s",
                                maxCachingMemory / 1048576.0,
                                (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0,
                                tile.tileIndex));
                    }
                }
            }
        }
    }

    /**
     * Tile index, based of row, column and IDF identity hash code.
     * Of course, identity hash code cannot provide a good universal cache,
     * but it is quite enough for optimizing usage of TiffParser:
     * usually we will not create new identical IFDs.
     */
    static final class TileIndex {
        private final ExtendedIFD ifd;
        private final int ifdIdentity;
        private final int row;
        private final int col;

        public TileIndex(ExtendedIFD ifd, int row, int col) {
            Objects.requireNonNull(ifd, "Null ifd");
            if (row < 0) {
                throw new IllegalArgumentException("Negative row = " + row);
            }
            if (col < 0) {
                throw new IllegalArgumentException("Negative col = " + col);
            }
            this.ifd = ifd;
            this.ifdIdentity = System.identityHashCode(ifd);
            // - not a universal solution, but suitable for our optimization needs:
            // usually we do not create new IFD instances without necessity
            this.row = row;
            this.col = col;
        }

        @Override
        public String toString() {
            return "IFD @" + Integer.toHexString(ifdIdentity) + ", row " + row + ", column " + col;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final TileIndex tileIndex = (TileIndex) o;
            return col == tileIndex.col && ifdIdentity == tileIndex.ifdIdentity && row == tileIndex.row;

        }

        @Override
        public int hashCode() {
            int result = ifdIdentity;
            result = 31 * result + row;
            result = 31 * result + col;
            return result;
        }
    }
}
