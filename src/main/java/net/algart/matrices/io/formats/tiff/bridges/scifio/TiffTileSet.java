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

import java.util.*;

public final class TiffTileSet {
    private final DetailedIFD ifd;
    private final Map<TiffTileIndex, TiffTile> tileMap = new LinkedHashMap<>();
    private final boolean resizable;
    private final boolean planarSeparated;
    private final int numberOfChannels;
    private final int numberOfSeparatedPlanes;
    private final int channelsPerPixel;
    private final int bytesPerSample;
    private final int tileSizeX;
    private final int tileSizeY;
    private final int sizeOfTileBasedOnBits;
    // - Note: we store here information about samples and tiles structure, but
    // SHOULD NOT store information about image sizes (like number of tiles):
    // it is probable that we do not know final sizes while creating tiles of the image!
    private volatile int sizeX = 0;
    private volatile int sizeY = 0;
    private volatile int tileCountX = 0;
    private volatile int tileCountY = 0;
    private volatile int numberOfTiles = 0;

    /**
     * Creates new tile set.
     *
     * <p>Note: you should not change the tags of the passed IFD, describing pixel type, number of samples
     * and tile sizes, after creating this object. The constructor saves this information in this object
     * (it is available via access methods) and will not be renewed automatically.
     *
     * @param ifd       IFD.
     * @param resizable whether maximal dimensions of this set will grow while adding new tiles,
     *                  or they are fixed and must be specified in IFD.
     */
    public TiffTileSet(DetailedIFD ifd, boolean resizable) {
        this.ifd = Objects.requireNonNull(ifd, "Null IFD");
        this.resizable = resizable;
        try {
            final boolean hasImageSizes = ifd.hasImageSizes();
            if (!hasImageSizes && !resizable) {
                throw new IllegalArgumentException("IFD sizes (ImageWidth and ImageLength) are not specified; " +
                        "it is not allowed for non-resizable tile set");
            }
            this.planarSeparated = ifd.isPlanarSeparated();
            this.numberOfChannels = ifd.getSamplesPerPixel();
            this.numberOfSeparatedPlanes = planarSeparated ? numberOfChannels : 1;
            this.channelsPerPixel = planarSeparated ? 1 : numberOfChannels;
            this.bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
            this.tileSizeX = ifd.getTileSizeX();
            this.tileSizeY = ifd.getTileSizeY();
            assert tileSizeX > 0 && tileSizeY > 0 : "non-positive tile sizes are not checked in IFD methods";
            if (hasImageSizes) {
                setSizes(ifd.getImageSizeX(), ifd.getImageSizeY(), false);
            }
            if ((long) tileSizeX * (long) tileSizeY > Integer.MAX_VALUE) {
                throw new FormatException("Very large tile " + tileSizeX + "x" + tileSizeY +
                        " >= 2^31 pixels is not supported");
                // - note that it is also checked deeper in the next operator
            }
            this.sizeOfTileBasedOnBits = ifd.sizeOfTile(this.bytesPerSample);
        } catch (FormatException e) {
            throw new IllegalArgumentException("Illegal IFD", e);
        }
    }

    public static TiffTileSet newImageGrid(DetailedIFD ifd) {
        final TiffTileSet tileSet = new TiffTileSet(ifd, false);
        return tileSet.putImageGrid();
    }

    public DetailedIFD ifd() {
        return ifd;
    }

    public Map<TiffTileIndex, TiffTile> tileMap() {
        return Collections.unmodifiableMap(tileMap);
    }

    public Collection<TiffTile> tiles() {
        return Collections.unmodifiableCollection(tileMap.values());
    }

    public boolean isResizable() {
        return resizable;
    }

    public boolean isPlanarSeparated() {
        return planarSeparated;
    }

    public int numberOfChannels() {
        return numberOfChannels;
    }

    public int numberOfSeparatedPlanes() {
        return numberOfSeparatedPlanes;
    }

    public int channelsPerPixel() {
        return channelsPerPixel;
    }

    public int bytesPerSample() {
        return bytesPerSample;
    }

    public int tileSizeX() {
        return tileSizeX;
    }

    public int tileSizeY() {
        return tileSizeY;
    }

    public int sizeOfTileBasedOnBits() {
        return sizeOfTileBasedOnBits;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getTileCountX() {
        return tileCountX;
    }

    public int getTileCountY() {
        return tileCountY;
    }

    public int getNumberOfTiles() {
        return numberOfTiles;
    }

    public int linearIndex(int separatedPlaneIndex, int xIndex, int yIndex) {
        if (separatedPlaneIndex < 0 || separatedPlaneIndex >= numberOfSeparatedPlanes) {
            throw new IndexOutOfBoundsException("Separated plane index " + separatedPlaneIndex +
                    " is out of range 0.." + (numberOfSeparatedPlanes - 1));
        }
        int tileCountX = this.tileCountX;
        int tileCountY = this.tileCountY;
        if (xIndex < 0 || xIndex >= tileCountX) {
            throw new IndexOutOfBoundsException("X-index " + xIndex + " is out of range 0.." + (tileCountX - 1));
        }
        if (yIndex < 0 || yIndex >= tileCountY) {
            throw new IndexOutOfBoundsException("X-index " + xIndex + " is out of range 0.." + (tileCountY - 1));
        }
        // - if the tile is out of bounds, it means that we do not know actual grid dimensions
        // (even it is resizable): there is no way to calculate correct linear index
        return (separatedPlaneIndex * tileCountY + yIndex) * tileCountX + xIndex;
        // - overflow impossible: setSizes checks that tileCountX * tileCountY * numberOfSeparatedPlanes < 2^31
    }

    public TiffTileIndex chunkedTileIndex(int x, int y) {
        return new TiffTileIndex(this, 0, x, y);
    }

    public TiffTileIndex tileIndex(int separatedPlaneIndex, int x, int y) {
        return new TiffTileIndex(this, separatedPlaneIndex, x, y);
    }

    public TiffTile newChunkedTile(int x, int y) {
        return chunkedTileIndex(x, y).newTile();
    }

    public TiffTile newTile(int separatedPlaneIndex, int x, int y) {
        return tileIndex(separatedPlaneIndex, x, y).newTile();
    }


    public TiffTileSet setSizes(int sizeX, int sizeY) {
        setSizes(sizeX, sizeY, true);
        return this;
    }

    public TiffTile get(TiffTileIndex tileIndex) {
        Objects.requireNonNull(tileIndex, "Null tileIndex");
        return tileMap.get(tileIndex);
    }

    public TiffTileSet put(TiffTile tile) {
        Objects.requireNonNull(tile, "Null tile");
        final TiffTileIndex tileIndex = tile.tileIndex();
        if (tileIndex.ifd() != this.ifd) {
            // - check references, not content!
            throw new IllegalArgumentException("Tile set cannot store tiles from different IFDs");
        }
        if (resizable) {
            final int toX = tileIndex.toX();
            final int toY = tileIndex.toY();
            if (toX > -sizeX || toY > sizeY) {
                setSizes(Math.max(sizeX, toX), Math.max(sizeY, toY));
                // - checks correctness (no overflow) before any modifications
            }
        } else {
            if (tileIndex.fromX() > sizeX - 1 || tileIndex.fromY() > sizeY - 1) {
                // sizeX-1: tile MAY be partially outside the image, but it MUST have at least 1 pixel inside it
                throw new IndexOutOfBoundsException("New tile is completely outside the image " +
                        "(out of maximal tileset sizes) " + sizeX + "x" + sizeY + ": " + tileIndex);
            }
        }
        tileMap.put(tileIndex, tile);
        return this;
    }

    public TiffTileSet putAll(Collection<TiffTile> tiles) {
        Objects.requireNonNull(tiles, "Null tiles");
        tiles.forEach(this::put);
        return this;
    }

    public TiffTileSet putImageGrid() {
        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            for (int y = 0; y < tileCountY; y++) {
                for (int x = 0; x < tileCountX; x++) {
                    put(tileIndex(p, x, y).newTile());
                }
            }
        }
        return this;
    }

    public TiffTileSet clear() {
        tileMap.clear();
        return this;
    }

    @Override
    public String toString() {
        return "set of " + tileMap.size() + " TIFF tiles (grid " + tileCountX + "x" + tileCountY +
                ") at the image " + ifd.toString(false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TiffTileSet that = (TiffTileSet) o;
        return ifd == that.ifd &&
                resizable == that.resizable &&
                sizeX == that.sizeX && sizeY == that.sizeY &&
                Objects.equals(tileMap, that.tileMap) &&
                planarSeparated == that.planarSeparated &&
                numberOfChannels == that.numberOfChannels &&
                bytesPerSample == that.bytesPerSample &&
                tileSizeX == that.tileSizeX && tileSizeY == that.tileSizeY &&
                sizeOfTileBasedOnBits == that.sizeOfTileBasedOnBits;
        // - Important! Comparing references to IFD, not content!
        // Moreover, it makes sense to compare fields, calculated ON THE BASE of IFD:
        // they may change as a result of changing the content of the same IFD.

    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(ifd), tileMap, resizable, sizeX, sizeY);
    }

    private void setSizes(int sizeX, int sizeY, boolean checkResizable) {
        if (checkResizable && !resizable) {
            throw new IllegalArgumentException("Cannot change sizes of non-resizable tile set");
        }
        if (sizeX < 0) {
            throw new IllegalArgumentException("Negative x-size: " + sizeX);
        }
        if (sizeY < 0) {
            throw new IllegalArgumentException("Negative y-size: " + sizeY);
        }
        final int tileCountX = (int) ((long) sizeX + (long) tileSizeX - 1) / tileSizeX;
        final int tileCountY = (int) ((long) sizeY + (long) tileSizeY - 1) / tileSizeY;
        if ((long) tileCountX * (long) tileCountY > Integer.MAX_VALUE / numberOfSeparatedPlanes) {
            throw new IllegalArgumentException("Too large number of tiles/strips: " +
                    (numberOfSeparatedPlanes > 1 ? numberOfSeparatedPlanes + " separated planes * " : "") +
                    tileCountX + " * " + tileCountY + " > 2^31-1");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.tileCountX = tileCountX;
        this.tileCountY = tileCountY;
        this.numberOfTiles = tileCountX * tileCountY * numberOfSeparatedPlanes;
    }
}
