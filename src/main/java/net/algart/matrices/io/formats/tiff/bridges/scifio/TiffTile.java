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

import java.util.*;

/**
 * TIFF tile: container for samples (encoded or decoded) with given {@link TiffTileIndex index}.
 *
 * @author Denial Alievsky
 */
public final class TiffTile {
    private final TiffTileSet tileSet;
    private final TiffTileIndex tileIndex;
    private int sizeX;
    private int sizeY;
    private int numberOfPixels;
    private boolean interleaved = false;
    private boolean encoded = false;
    private byte[] data = null;
    private long storedDataFileOffset = -1;
    private int storedDataLength = 0;

    public TiffTile(TiffTileIndex tileIndex) {
        this.tileIndex = Objects.requireNonNull(tileIndex, "Null tile index");
        this.tileSet = tileIndex.tileSet();
        assert tileIndex.ifd() == tileSet.ifd() : "tileIndex retrieved ifd from its tileSet!";
        setSizes(tileSet.tileSizeX(), tileSet.tileSizeY());
    }

    public TiffTileSet tileSet() {
        return tileSet;
    }

    public DetailedIFD ifd() {
        return tileSet.ifd();
    }

    public TiffTileIndex tileIndex() {
        return tileIndex;
    }

    public boolean isPlanarSeparated() {
        return tileSet.isPlanarSeparated();
    }

    public int channelsPerPixel() {
        return tileSet.channelsPerPixel();
    }

    public int bytesPerSample() {
        return tileSet.bytesPerSample();
    }

    public int sizeOfTileBasedOnBits() {
        return tileSet.tileSizeInBytes();
    }

    public int getSizeX() {
        return sizeX;
    }

    public TiffTile setSizeX(int sizeX) {
        return setSizes(sizeX, this.sizeY);
    }

    public int getSizeY() {
        return sizeY;
    }

    public TiffTile setSizeY(int sizeY) {
        return setSizes(this.sizeX, sizeY);
    }

    public int getNumberOfPixels() {
        return numberOfPixels;
    }

    /**
     * Sets the sizes of this tile.
     *
     * <p>These are purely informational properties, not affecting processing the stored data
     * and supported for additional convenience of usage this object.
     *
     * @param sizeX the tile width.
     * @param sizeY the tile height.
     * @return a reference to this object.
     */
    public TiffTile setSizes(int sizeX, int sizeY) {
        if (sizeX < 0) {
            throw new IllegalArgumentException("Negative tile x-size: " + sizeX);
        }
        if (sizeY < 0) {
            throw new IllegalArgumentException("Negative tile y-size: " + sizeY);
        }
        if ((long) sizeX * (long) sizeY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Very large tile " + sizeX + "x" + sizeY +
                    " >= 2^31 pixels is not supported");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.numberOfPixels = sizeX * sizeY;
        return this;
    }

    public boolean isInterleaved() {
        return interleaved;
    }

    public boolean isSeparated() {
        return !interleaved;
    }

    /**
     * Sets the flag, are the stored pixel samples are interleaved (like RGBRGB...) or not (RRR...GGG...BBB...).
     * It doesn't matter in a case of monochrome images.
     *
     * <p>This is purely informational property, not affecting processing the stored data
     * and supported for additional convenience of usage this object.
     *
     * @param interleaved whether the data should be considered as interleaved.
     * @return a reference to this object.
     */
    public TiffTile setInterleaved(boolean interleaved) {
        this.interleaved = interleaved;
        return this;
    }

    public boolean isEncoded() {
        return encoded;
    }

    public TiffTile setEncoded(boolean encoded) {
        this.encoded = encoded;
        return this;
    }

    public boolean isEmpty() {
        return data == null;
    }

    public byte[] getData() {
        checkEmpty();
        return data;
    }

    public TiffTile setData(byte[] data) {
        Objects.requireNonNull(data, "Null data");
        this.data = data;
        this.storedDataLength = data.length;
        return this;
    }

    public byte[] getEncodedData() {
        checkEmpty();
        if (!isEncoded()) {
            throw new IllegalStateException("TIFF tile is not encoded: " + this);
        }
        return getData();
    }

    public TiffTile setEncodedData(byte[] data) {
        return setData(data).setEncoded(true);
    }

    public byte[] getDecodedData() {
        checkEmpty();
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile is not decoded: " + this);
        }
        return getData();
    }

    public TiffTile setDecodedData(byte[] data) {
        return setData(data).setEncoded(false);
    }

    public TiffTile free() {
        this.data = null;
        return this;
    }

    /**
     * Return the length of the last non-null {@link #getData() data array}, stored in this tile,
     * or 0 after creating this object. Immediately before/after reading tile from file, as well as
     * immediately before/after writing it into file, this method returns the number of bytes,
     * which are actually stored in the file for this tile.
     *
     * <p>Note: {@link #free()} method does not change this value! So, you can know the stored data size
     * even after freeing data inside this object.
     *
     * @return the length of the last non-null data array, which was stored in this object.
     */
    public int getStoredDataLength() {
        return storedDataLength;
    }

    public TiffTile setStoredDataLength(int storedDataLength) {
        if (storedDataLength < 0) {
            throw new IllegalArgumentException("Negative storedDataLength = " + storedDataLength);
        }
        this.storedDataLength = storedDataLength;
        return this;
    }

    public boolean hasStoredDataFileOffset() {
        return storedDataFileOffset >= 0;
    }

    public long getStoredDataFileOffset() {
        checkStoredFilePosition();
        return storedDataFileOffset;
    }

    public TiffTile setStoredDataFileOffset(long storedDataFileOffset) {
        if (storedDataFileOffset < 0) {
            throw new IllegalArgumentException("Negative storedDataFileOffset = " + storedDataFileOffset);
        }
        this.storedDataFileOffset = storedDataFileOffset;
        return this;
    }

    public TiffTile setStoredDataFileRange(long storedDataFileOffset, int storedDataLength) {
        if (storedDataFileOffset < 0) {
            throw new IllegalArgumentException("Negative storedDataFileOffset = " + storedDataFileOffset);
        }
        if (storedDataLength < 0) {
            throw new IllegalArgumentException("Negative storedDataLength = " + storedDataLength);
        }
        this.storedDataLength = storedDataLength;
        this.storedDataFileOffset = storedDataFileOffset;
        return this;
    }

    public TiffTile removeStoredDataFileOffset() {
        storedDataFileOffset = -1;
        return this;
    }

    public TiffTile interleaveSamplesIfNecessary() {
        if (!isInterleaved()) {
            interleaveSamples();
        }
        return this;
    }

    public TiffTile separateSamplesIfNecessary() {
        if (isInterleaved()) {
            separateSamples();
        }
        return this;
    }

    public TiffTile interleaveSamples() {
        byte[] data = getDecodedData();
        if (isInterleaved()) {
            throw new IllegalStateException("TIFF tile is already interleaved: " + this);
        }
        data = TiffTools.toInterleavedSamples(data, channelsPerPixel(), bytesPerSample(), getNumberOfPixels());
        setInterleaved(true);
        setDecodedData(data);
        return this;
    }

    public TiffTile separateSamples() {
        byte[] data = getDecodedData();
        if (!isInterleaved()) {
            throw new IllegalStateException("TIFF tile is already separated: " + this);
        }
        data = TiffTools.toSeparatedSamples(data, channelsPerPixel(), bytesPerSample(), getNumberOfPixels());
        setInterleaved(false);
        setDecodedData(data);
        return this;
    }

    @Override
    public String toString() {
        return "TIFF " +
                (encoded ? "encoded" : "decoded") +
                (interleaved ? " interleaved" : "") +
                " tile at " + tileIndex +
                (isEmpty() ? ", empty" : ", " + storedDataLength + " bytes") +
                (hasStoredDataFileOffset() ? " at file offset " + storedDataFileOffset : "") +
                ", stored in " + tileSet;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TiffTile tiffTile = (TiffTile) o;
        return sizeX == tiffTile.sizeX && sizeY == tiffTile.sizeY &&
                numberOfPixels == tiffTile.numberOfPixels &&
                interleaved == tiffTile.interleaved && encoded == tiffTile.encoded &&
                storedDataFileOffset == tiffTile.storedDataFileOffset &&
                storedDataLength == tiffTile.storedDataLength &&
                Objects.equals(tileIndex, tiffTile.tileIndex) &&
                Arrays.equals(data, tiffTile.data);
        // Note: doesn't check containingSet to avoid infinite recursion!
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(tileIndex, sizeX, sizeY, numberOfPixels,
                interleaved, encoded, storedDataFileOffset, storedDataLength);
        result = 31 * result + Arrays.hashCode(data);
        return result;
        // Note: doesn't check containingSet to avoid infinite recursion!
    }

    private void checkEmpty() {
        if (data == null) {
            throw new IllegalStateException("TIFF tile is still not filled by any data: " + this);
        }
    }

    private void checkStoredFilePosition() {
        if (storedDataFileOffset < 0) {
            throw new IllegalStateException("File offset of this TIFF tile is not set yet: " + this);
        }
    }
}
