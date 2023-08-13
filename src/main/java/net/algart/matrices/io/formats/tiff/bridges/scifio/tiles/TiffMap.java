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

package net.algart.matrices.io.formats.tiff.bridges.scifio.tiles;

import io.scif.FormatException;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffTools;

import java.util.*;

public final class TiffMap {
    /**
     * Maximal supported number of channels. Popular OpenCV library has the same limit.
     *
     * <p>This limit helps to avoid "crazy" or corrupted TIFF and also help to avoid arithmetic overflow.
     */
    public static final int MAX_NUMBER_OF_CHANNELS = 512;
    /**
     * Maximal supported length of pixel (including all channels).
     *
     * <p>This limit helps to avoid "crazy" or corrupted TIFF and also help to avoid arithmetic overflow.
     */
    public static final int MAX_TOTAL_BYTES_PER_PIXEL = 4096;

    /**
     * Maximal value of x/y-index of the tile.
     *
     * <p>This limit helps to avoid arithmetic overflow while operations with indexes.
     */
    public static final int MAX_TILE_INDEX = 1_000_000_000;

    private final DetailedIFD ifd;
    private final Map<TiffTileIndex, TiffTile> tileMap = new LinkedHashMap<>();
    private final boolean resizable;
    private final boolean planarSeparated;
    private final int numberOfChannels;
    private final int numberOfSeparatedPlanes;
    private final int tileSamplesPerPixel;
    private final int bytesPerSample;
    private final int tileBytesPerPixel;
    private final int totalBytesPerPixel;
    private final int pixelType;
    private final Class<?> elementType;
    private final boolean tiled;
    private final int tileSizeX;
    private final int tileSizeY;
    private final int tileSizeInPixels;
    private final int tileSizeInBytes;
    // - Note: we store here information about samples and tiles structure, but
    // SHOULD NOT store information about image sizes (like number of tiles):
    // it is probable that we do not know final sizes while creating tiles of the image!
    private volatile int dimX = 0;
    private volatile int dimY = 0;
    private volatile int gridTileCountX = 0;
    private volatile int gridTileCountY = 0;
    private volatile int numberOfGridTiles = 0;

    /**
     * Creates new tile map.
     *
     * <p>Note: you should not change the tags of the passed IFD, describing pixel type, number of samples
     * and tile sizes, after creating this object. The constructor saves this information in this object
     * (it is available via access methods) and will not be renewed automatically.
     *
     * @param ifd       IFD.
     * @param resizable whether maximal dimensions of this set will grow while adding new tiles,
     *                  or they are fixed and must be specified in IFD.
     */
    public TiffMap(DetailedIFD ifd, boolean resizable) {
        this.ifd = Objects.requireNonNull(ifd, "Null IFD");
        this.resizable = resizable;
        try {
            final boolean hasImageDimensions = ifd.hasImageDimensions();
            if (!hasImageDimensions && !resizable) {
                throw new IllegalArgumentException("TIFF image sizes (ImageWidth and ImageLength tags) " +
                        "are not specified; it is not allowed for non-resizable tile map");
            }
            this.tiled = ifd.hasTileInformation();
            if (resizable && !tiled) {
                throw new IllegalArgumentException("TIFF image is not tiled (TileWidth and TileLength tags " +
                        "are not specified); it is not allowed for resizable tile map: any processing " +
                        "TIFF image, such as writing its fragments, requires either knowing its final fixed sizes, " +
                        "or splitting image into tiles with known fixed sizes");
            }
            this.planarSeparated = ifd.isPlanarSeparated();
            this.numberOfChannels = ifd.getSamplesPerPixel();
            this.numberOfSeparatedPlanes = planarSeparated ? numberOfChannels : 1;
            this.tileSamplesPerPixel = planarSeparated ? 1 : numberOfChannels;
            this.bytesPerSample = ifd.equalBytesPerSample();
            if (numberOfChannels > MAX_NUMBER_OF_CHANNELS) {
                throw new FormatException("Very large number of channels " + numberOfChannels + " > " +
                        MAX_NUMBER_OF_CHANNELS + " is not supported");
            }
            if ((long) numberOfChannels * (long) bytesPerSample > MAX_TOTAL_BYTES_PER_PIXEL) {
                throw new FormatException("Very large number of bytes per pixel " + numberOfChannels + " * " +
                        bytesPerSample + " > " + MAX_TOTAL_BYTES_PER_PIXEL + " is not supported");
            }
            this.tileBytesPerPixel = tileSamplesPerPixel * bytesPerSample;
            this.totalBytesPerPixel = numberOfChannels * bytesPerSample;
            this.pixelType = ifd.getPixelType();
            this.elementType = TiffTools.pixelTypeToElementType(pixelType);
            this.tileSizeX = ifd.getTileSizeX();
            this.tileSizeY = ifd.getTileSizeY();
            assert tileSizeX > 0 && tileSizeY > 0 : "non-positive tile sizes are not checked in IFD methods";
            if (hasImageDimensions) {
                setDimensions(ifd.getImageDimX(), ifd.getImageDimY(), false);
            }
            if ((long) tileSizeX * (long) tileSizeY > Integer.MAX_VALUE) {
                throw new FormatException("Very large TIFF tile " + tileSizeX + "x" + tileSizeY +
                        " >= 2^31 pixels is not supported");
                // - note that it is also checked deeper in the next operator
            }
            this.tileSizeInPixels = tileSizeX * tileSizeY;
            if ((long) tileSizeInPixels * (long) tileBytesPerPixel > Integer.MAX_VALUE) {
                throw new FormatException("Very large TIFF tile " + tileSizeX + "x" + tileSizeY +
                        ", " + tileSamplesPerPixel + " channels per " + bytesPerSample +
                        " bytes >= 2^31 bytes is not supported");
            }
            this.tileSizeInBytes = tileSizeInPixels * tileBytesPerPixel;
        } catch (FormatException e) {
            throw new IllegalArgumentException("Illegal IFD: " + e.getMessage(), e);
        }
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

    public boolean isReadyForWriting() {
        return ifd.isReadyForWriting();
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

    public int tileSamplesPerPixel() {
        return tileSamplesPerPixel;
    }

    public int bytesPerSample() {
        return bytesPerSample;
    }

    public int tileBytesPerPixel() {
        return tileBytesPerPixel;
    }

    public int totalBytesPerPixel() {
        return totalBytesPerPixel;
    }

    public int pixelType() {
        return pixelType;
    }

    public Class<?> elementType() {
        return elementType;
    }

    public int tileSizeX() {
        return tileSizeX;
    }

    public int tileSizeY() {
        return tileSizeY;
    }

    public int tileSizeInPixels() {
        return tileSizeInPixels;
    }

    public int tileSizeInBytes() {
        return tileSizeInBytes;
    }

    public int dimX() {
        return dimX;
    }

    public int dimY() {
        return dimY;
    }

    public void setDimensions(int dimX, int dimY) {
        setDimensions(dimX, dimY, true);
    }

    /**
     * Replaces total image sizes to maximums from their current values and <tt>newMinimalSizeX/Y</tt>.
     *
     * <p>Note: if both new x/y-sizes are not greater than existing ones, this method does nothing
     * and can be called even if not {@link #isResizable()}.
     *
     * @param newMinimalSizeX new minimal value for {@link #dimX() sizeX}.
     * @param newMinimalSizeY new minimal value for {@link #dimY() sizeY}.
     */
    public void expandSizes(int newMinimalSizeX, int newMinimalSizeY) {
        if (newMinimalSizeX < 0) {
            throw new IllegalArgumentException("Negative new minimal x-size: " + newMinimalSizeX);
        }
        if (newMinimalSizeY < 0) {
            throw new IllegalArgumentException("Negative new minimal y-size: " + newMinimalSizeY);
        }
        if (newMinimalSizeX > dimX || newMinimalSizeY > dimY) {
            setDimensions(Math.max(dimX, newMinimalSizeX), Math.max(dimY, newMinimalSizeY));
        }
    }

    public int gridTileCountX() {
        return gridTileCountX;
    }

    public int gridTileCountY() {
        return gridTileCountY;
    }

    /**
     * Replaces tile x/y-count to maximums from their current values and <tt>newMinimalTileCountX/Y</tt>.
     *
     * <p>Note: the arguments are the desired minimal tile <i>counts</i>, not tile <i>indexes</i>.
     * So, you can freely specify zero arguments, and this method will do nothing in this case.
     *
     * <p>Note: if both new x/y-counts are not greater than existing ones, this method does nothing
     * and can be called even if not {@link #isResizable()}.
     *
     * <p>Note: this method is called automatically while changing total image sizes.
     *
     * @param newMinimalTileCountX new minimal value for {@link #gridTileCountX()}.
     * @param newMinimalTileCountY new minimal value for {@link #gridTileCountY()}.
     */
    public void expandGrid(int newMinimalTileCountX, int newMinimalTileCountY) {
        expandGrid(newMinimalTileCountX, newMinimalTileCountY, true);
    }

    public int getNumberOfGridTiles() {
        return numberOfGridTiles;
    }

    public int linearIndex(int separatedPlaneIndex, int xIndex, int yIndex) {
        if (separatedPlaneIndex < 0 || separatedPlaneIndex >= numberOfSeparatedPlanes) {
            throw new IndexOutOfBoundsException("Separated plane index " + separatedPlaneIndex +
                    " is out of range 0.." + (numberOfSeparatedPlanes - 1));
        }
        int tileCountX = this.gridTileCountX;
        int tileCountY = this.gridTileCountY;
        if (xIndex < 0 || xIndex >= tileCountX || yIndex < 0 || yIndex >= tileCountY) {
            throw new IndexOutOfBoundsException("One of X/Y-indexes (" + xIndex + ", " + yIndex +
                    ") of the tile is out of ranges 0.." + (tileCountX - 1) + ", 0.." + (tileCountY - 1));
        }
        // - if the tile is out of bounds, it means that we do not know actual grid dimensions
        // (even it is resizable): there is no way to calculate correct linear index
        return (separatedPlaneIndex * tileCountY + yIndex) * tileCountX + xIndex;
        // - overflow impossible: setDimensions checks that tileCountX * tileCountY * numberOfSeparatedPlanes < 2^31
    }

    public TiffTileIndex index(int x, int y) {
        return new TiffTileIndex(this, 0, x, y);
    }

    public TiffTileIndex multiplaneIndex(int separatedPlaneIndex, int x, int y) {
        return new TiffTileIndex(this, separatedPlaneIndex, x, y);
    }

    public void checkTileIndex(TiffTileIndex tileIndex) {
        Objects.requireNonNull(tileIndex, "Null tile index");
        if (tileIndex.ifd() != this.ifd) {
            // - Checking references, not content!
            // Checking IFD, not reference to map ("this"): there is no sense to disable creating new map
            // and copying there the tiles from the given map.
            throw new IllegalArgumentException("Illegal tile index: tile map cannot process tiles from different IFD");
        }
    }

    public int size() {
        return tileMap.size();
    }

    public Collection<TiffTile> all() {
        return Collections.unmodifiableCollection(tileMap.values());
    }

    public TiffTile getOrNew(int x, int y) {
        return getOrNew(index(x, y));
    }

    public TiffTile getOrNewMultiplane(int separatedPlaneIndex, int x, int y) {
        return getOrNew(multiplaneIndex(separatedPlaneIndex, x, y));
    }

    public TiffTile getOrNew(TiffTileIndex tileIndex) {
        TiffTile result = get(tileIndex);
        if (result == null) {
            result = new TiffTile(tileIndex);
            put(result);
        }
        return result;
    }

    public TiffTile get(TiffTileIndex tileIndex) {
        checkTileIndex(tileIndex);
        return tileMap.get(tileIndex);
    }

    public void put(TiffTile tile) {
        Objects.requireNonNull(tile, "Null tile");
        final TiffTileIndex tileIndex = tile.tileIndex();
        checkTileIndex(tileIndex);
        if (resizable) {
            expandGrid(tileIndex.xIndex() + 1, tileIndex.yIndex() + 1);
        } else {
            if (tileIndex.xIndex() >= gridTileCountX || tileIndex.yIndex() >= gridTileCountY) {
                // sizeX-1: tile MAY be partially outside the image, but it MUST have at least 1 pixel inside it
                throw new IndexOutOfBoundsException("New tile is completely outside the image " +
                        "(out of maximal tilemap sizes) " + dimX + "x" + dimY + ": " + tileIndex);
            }
        }
        tileMap.put(tileIndex, tile);
    }

    public void putAll(Collection<TiffTile> tiles) {
        Objects.requireNonNull(tiles, "Null tiles");
        tiles.forEach(this::put);
    }

    public void completeImageGrid() {
        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            for (int y = 0; y < gridTileCountY; y++) {
                for (int x = 0; x < gridTileCountX; x++) {
                    getOrNewMultiplane(p, x, y);
                }
            }
        }
    }

    public void clear(boolean clearDimensions) {
        tileMap.clear();
        if (clearDimensions) {
            setDimensions(0, 0);
            gridTileCountX = 0;
            gridTileCountY = 0;
            numberOfGridTiles = 0;
            // - note: this is the only way to reduce tileCountX/Y!
        }
    }

    @Override
    public String toString() {
        return "set of " + tileMap.size() + " TIFF tiles (grid " + gridTileCountX + "x" + gridTileCountY +
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
        TiffMap that = (TiffMap) o;
        return ifd == that.ifd &&
                resizable == that.resizable &&
                dimX == that.dimX && dimY == that.dimY &&
                Objects.equals(tileMap, that.tileMap) &&
                planarSeparated == that.planarSeparated &&
                numberOfChannels == that.numberOfChannels &&
                bytesPerSample == that.bytesPerSample &&
                tileSizeX == that.tileSizeX && tileSizeY == that.tileSizeY &&
                tileSizeInBytes == that.tileSizeInBytes;
        // - Important! Comparing references to IFD, not content!
        // Moreover, it makes sense to compare fields, calculated ON THE BASE of IFD:
        // they may change as a result of changing the content of the same IFD.

    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(ifd), tileMap, resizable, dimX, dimY);
    }

    private void setDimensions(int dimX, int dimY, boolean checkResizable) {
        if (checkResizable && !resizable) {
            throw new IllegalArgumentException("Cannot change dimensions of a non-resizable tile map");
        }
        if (dimX < 0) {
            throw new IllegalArgumentException("Negative x-dimension: " + dimX);
        }
        if (dimY < 0) {
            throw new IllegalArgumentException("Negative y-dimension: " + dimY);
        }
        final int tileCountX = (int) ((long) dimX + (long) tileSizeX - 1) / tileSizeX;
        final int tileCountY = (int) ((long) dimY + (long) tileSizeY - 1) / tileSizeY;
        expandGrid(tileCountX, tileCountY, checkResizable);
        this.dimX = dimX;
        this.dimY = dimY;
    }

    private void expandGrid(int newMinimalTileCountX, int newMinimalTileCountY, boolean checkResizable) {
        if (newMinimalTileCountX < 0) {
            throw new IllegalArgumentException("Negative new minimal tiles x-count: " + newMinimalTileCountX);
        }
        if (newMinimalTileCountY < 0) {
            throw new IllegalArgumentException("Negative new minimal tiles y-count: " + newMinimalTileCountY);
        }
        if (newMinimalTileCountX <= gridTileCountX && newMinimalTileCountY <= gridTileCountY) {
            return;
            // - even in a case !resizable
        }
        if (checkResizable && !resizable) {
            throw new IllegalArgumentException("Cannot expand tile counts in a non-resizable tile map");
        }
        final int tileCountX = Math.max(this.gridTileCountX, newMinimalTileCountX);
        final int tileCountY = Math.max(this.gridTileCountY, newMinimalTileCountY);
        if ((long) tileCountX * (long) tileCountY > Integer.MAX_VALUE / numberOfSeparatedPlanes) {
            throw new IllegalArgumentException("Too large number of tiles/strips: " +
                    (numberOfSeparatedPlanes > 1 ? numberOfSeparatedPlanes + " separated planes * " : "") +
                    tileCountX + " * " + tileCountY + " > 2^31-1");
        }
        this.gridTileCountX = tileCountX;
        this.gridTileCountY = tileCountY;
        this.numberOfGridTiles = tileCountX * tileCountY * numberOfSeparatedPlanes;
    }

}
