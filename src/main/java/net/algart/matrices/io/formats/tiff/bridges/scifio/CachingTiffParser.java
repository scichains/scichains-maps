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
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.location.Location;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.nio.file.Path;
import java.util.*;

/**
 * Analog of {@link TiffParser} with built-in caching all loaded tiles.
 *
 * @author Denial Alievsky
 */
public class CachingTiffParser extends TiffParser {
    public static final long DEFAULT_MAX_CACHING_MEMORY = Math.max(0, getLongProperty(
            "net.algart.matrices.libs.scifio.tiff.defaultMaxCachingMemory", 256 * 1048576L));
    // - 256 MB maximal cache by default

    private static final System.Logger LOG = System.getLogger(CachingTiffParser.class.getName());

    private volatile long maxCachingMemory = DEFAULT_MAX_CACHING_MEMORY;
    // - volatile is necessary for correct parallel work of the setter

    private final Map<TiffTileIndex, CachedTile> tileMap = new HashMap<>();
    private final Queue<CachedTile> tileCache = new LinkedList<CachedTile>();
    private long currentCacheMemory = 0;
    private final Object tileCacheLock = new Object();

    public CachingTiffParser(Context context, Location location) throws IOException {
        this(context, context.getService(DataHandleService.class).create(location));
    }

    public CachingTiffParser(Context context, DataHandle<Location> in) throws IOException {
        super(context, in, true);
        this.setAutoUnpackUnusualPrecisions(true);
        this.setExtendedCodec(true);
    }

    public static CachingTiffParser getInstance(Context context, Location location) throws IOException {
        return new CachingTiffParser(context, location);

    }

    public static CachingTiffParser getInstance(final Context context, Path file) throws IOException {
        return getInstance(context, TiffTools.existingFileToLocation(file));
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
    public TiffTile readTile(TiffTileIndex tileIndex) throws FormatException, IOException {
        if (maxCachingMemory == 0) {
            return readTileWithoutCache(tileIndex);
        }
        return getCached(tileIndex).readIfNecessary();
    }


    private TiffTile readTileWithoutCache(TiffTileIndex tileIndex) throws FormatException, IOException {
        return super.readTile(tileIndex);
    }

    private CachedTile getCached(TiffTileIndex tileIndex) {
        synchronized (tileCacheLock) {
            CachedTile tile = tileMap.get(tileIndex);
            if (tile == null) {
                tile = new CachedTile(tileIndex);
                tileMap.put(tileIndex, tile);
            }
            return tile;
            // So, we store (without ability to remove) all Tile objects in the cache tileMap.
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
        private final TiffTileIndex tileIndex;

        private final Object onlyThisTileLock = new Object();
        private Reference<TiffTile> cachedTile = null;
        // - we use SoftReference to be on the safe side in addition to our own memory control
        private long cachedDataLength;

        CachedTile(TiffTileIndex tileIndex) {
            this.tileIndex = Objects.requireNonNull(tileIndex, "Null tileIndex");
        }

        TiffTile readIfNecessary() throws FormatException, IOException {
            synchronized (onlyThisTileLock) {
                final var cachedData = cached();
                if (cachedData != null) {
                    LOG.log(System.Logger.Level.TRACE, () -> "CACHED tile: " + tileIndex);
                    return cachedData;
                } else {
                    final var result = readTileWithoutCache(tileIndex);
                    saveCache(result);
                    return result;
                }
            }
        }

        private TiffTile cached() {
            synchronized (tileCacheLock) {
                if (cachedTile == null) {
                    return null;
                }
                var tile = cachedTile.get();
                if (tile == null) {
                    LOG.log(System.Logger.Level.DEBUG,
                            () -> "CACHED tile is freed by garbage collector due to " +
                                    "insufficiency of memory: " + tileIndex);
                }
                return tile;
            }
        }

        private void saveCache(TiffTile tile) {
            Objects.requireNonNull(tile);
            synchronized (tileCacheLock) {
                if (maxCachingMemory > 0) {
                    this.cachedTile = new SoftReference<>(tile);
                    this.cachedDataLength = tile.lastDataLength();
                    currentCacheMemory += this.cachedDataLength;
                    tileCache.add(this);
                    LOG.log(System.Logger.Level.TRACE, () -> "STORING tile in cache: " + tileIndex);
                    while (currentCacheMemory > maxCachingMemory) {
                        CachedTile cached = tileCache.remove();
                        assert cached != null;
                        currentCacheMemory -= cached.cachedDataLength;
                        cached.cachedTile = null;
                        Runtime runtime = Runtime.getRuntime();
                        LOG.log(System.Logger.Level.TRACE, () -> String.format(Locale.US,
                                "REMOVING tile from cache (limit %.1f MB exceeded, used memory %.1f MB): %s",
                                maxCachingMemory / 1048576.0,
                                (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0,
                                cached.tileIndex));
                    }
                }
            }
        }
    }

}
