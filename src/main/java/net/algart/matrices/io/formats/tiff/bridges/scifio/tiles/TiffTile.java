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

import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffTools;

import java.util.*;
import java.util.function.Consumer;

/**
 * TIFF tile: container for samples (encoded or decoded) with given {@link TiffTileIndex index}.
 *
 * @author Denial Alievsky
 */
public final class TiffTile {
    private final TiffMap map;
    private final int samplesPerPixel;
    private final int bytesPerSample;
    private final int bytesPerPixel;
    private final TiffTileIndex index;
    private int sizeX;
    private int sizeY;
    private int sizeInPixels;
    private int sizeInBytes;
    private boolean interleaved = false;
    private boolean encoded = false;
    private byte[] data = null;
    private long storedDataFileOffset = -1;
    private int storedDataLength = 0;
    private int storedNumberOfPixels = 0;

    /**
     * Creates new tile with given index.
     *
     * <p>Note: created tile <b>may</b> lie outside its map, it is not prohibited.
     * This condition is checked not here, but in {@link TiffMap#put(TiffTile)} and other {@link TiffMap} methods.
     *
     * @param index tile index.
     */
    public TiffTile(TiffTileIndex index) {
        this.index = Objects.requireNonNull(index, "Null tile index");
        this.map = index.map();
        this.samplesPerPixel = map.tileSamplesPerPixel();
        this.bytesPerSample = map.bytesPerSample();
        this.bytesPerPixel = samplesPerPixel * bytesPerSample;
        assert bytesPerPixel <= TiffMap.MAX_TOTAL_BYTES_PER_PIXEL :
                samplesPerPixel + "*" + bytesPerPixel + " were not checked in TiffMap!";
        assert index.ifd() == map.ifd() : "index retrieved ifd from its tile map!";
        setSizes(map.tileSizeX(), map.tileSizeY());
    }

    public TiffMap map() {
        return map;
    }

    public DetailedIFD ifd() {
        return map.ifd();
    }

    public TiffTileIndex index() {
        return index;
    }

    public boolean isPlanarSeparated() {
        return map.isPlanarSeparated();
    }

    public int samplesPerPixel() {
        return samplesPerPixel;
    }

    public int bytesPerSample() {
        return bytesPerSample;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
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

    /**
     * Reduces sizes of this tile so that it will completely lie inside map dimensions.
     *
     * <p>This operation can be useful for <i>stripped</i> TIFF image, especially while writing.
     * But you should not call this for <i>tiled</i> image (when {@link TiffMap#isTiled()} returns <tt>true</tt>).
     * For tiled image, TIFF file usually contains full-size encoded tiles even on image boundary;
     * they should be cropped after decoding by external means. You can disable attempt to reduce
     * tile in tiled image by passing <tt>nonTiledOnly=true</tt>.
     *
     * @param nonTiledOnly if <tt>true</tt>, this function will not do anything when the map
     *                     is {@link TiffMap#isTiled() tiled}. While using for reading/writing TIFF files,
     *                     this argument usually should be <tt>true</tt>.
     * @return a reference to this object.
     * @throws IllegalStateException if this tile is completely outside map dimensions.
     */
    public TiffTile cropToMap(boolean nonTiledOnly) {
        final int dimX = map.dimX();
        final int dimY = map.dimY();
        if (index.fromX() >= dimX || index.fromY() >= dimY) {
            throw new IllegalStateException("Tile is fully outside the map dimensions " + dimX + "x" + dimY +
                    " and cannot be cropped: " + this);
        }
        if (nonTiledOnly && map.isTiled()) {
            return this;
        } else {
            return setSizes(Math.min(sizeX, dimX - index.fromX()), Math.min(sizeY, dimY - index.fromY()));
        }
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
            throw new IllegalArgumentException("Very large TIFF tile " + sizeX + "x" + sizeY +
                    " >= 2^31 pixels is not supported");
        }
        final int sizeInPixels = sizeX * sizeY;
        if ((long) sizeInPixels * (long) bytesPerPixel > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Very large TIFF tile " + sizeX + "x" + sizeY +
                    ", " + samplesPerPixel + " channels per " + bytesPerSample +
                    " bytes >= 2^31 bytes is not supported");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeInPixels = sizeInPixels;
        this.sizeInBytes = sizeInPixels * bytesPerPixel;
        return this;
    }

    public int getSizeInPixels() {
        return sizeInPixels;
    }

    public int getSizeInBytes() {
        return sizeInBytes;
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

    //Note: there is no setEncoded() method, it could violate invariants provided by setDecodedData
    public boolean isEncoded() {
        return encoded;
    }

    public boolean isEmpty() {
        return data == null;
    }

    public byte[] getData() {
        checkEmpty();
        return data;
    }

    public byte[] getEncoded() {
        checkEmpty();
        if (!isEncoded()) {
            throw new IllegalStateException("TIFF tile is not encoded: " + this);
        }
        return data;
    }

    public TiffTile setEncoded(byte[] data) {
        return setData(data, true);
    }

    public byte[] getDecodedOrNew(Consumer<TiffTile> initializer) {
        return getDecodedOrNew(sizeInBytes, initializer);
    }

    public byte[] getDecodedOrNew(int dataLength, Consumer<TiffTile> initializer) {
        if (dataLength < 0) {
            throw new IllegalArgumentException("Negative length of data array: " + dataLength);
        }
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile data are encoded and " +
                    "cannot be updated in decoded form: " + this);
        }
        if (isEmpty()) {
            byte[] data = new byte[dataLength];
            setDecoded(data);
            if (initializer != null) {
                initializer.accept(this);
            }
        }
        return getDecoded();
    }

    public byte[] getDecoded() {
        checkEmpty();
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile data are not decoded and cannot be retrieved: " + this);
        }
        return data;
    }

    public TiffTile setDecoded(byte[] data) {
        return setData(data, false);
    }

    public TiffTile free() {
        this.data = null;
        return this;
    }

    /**
     * Return the length of the last non-null {@link #getData() data array}, stored in this tile,
     * or 0 after creating this object.
     *
     * <p>Immediately after reading tile from file, as well as
     * immediately before/after writing it into file, this method returns the number of encoded bytes,
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

    public boolean hasStoredDataFileOffset() {
        return storedDataFileOffset >= 0;
    }

    public long getStoredDataFileOffsetOrZero() {
        return storedDataFileOffset < 0 ? 0 : storedDataFileOffset;
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

    public TiffTile removeStoredDataFileOffset() {
        storedDataFileOffset = -1;
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

    /**
     * Returns the number of pixels, actually stored in the {@link #getData() data array} in this tile
     * in the decoded form, or 0 after creating this object.
     *
     * <p>Note: that this method throws <tt>IllegalStateException</tt> if the data are {@link #isEncoded() encoded},
     * for example, immediately after reading tile from file. If the tile is {@link #isEmpty() empty} (no data),
     * the exception is not thrown, though usually there is no sense to call this method in this situation.</p>
     *
     * <p>If the data are not {@link #isEncoded() encoded}, the following equality is always true:</p>
     *
     * <pre>{@link #getStoredDataLength()} == {@link #getStoredNumberOfPixels()} * {@link #bytesPerPixel()}</pre>
     *
     * <p><b>Warning:</b> the stored number of pixels, returned by this method, may <b>differ</b> from the tile size
     * {@link #getSizeX()} * {@link #getSizeY()}! Usually it occurs after decoding encoded tile, when the
     * decoding method returns only sequence of pixels and does not return information about the size.
     * In this situation, the external code sets the tile sizes from a priory information, but the decoded tile
     * may be actually less; for example, it takes place for the last strip in non-tiled TIFF format.
     * You can check, does the actual number of stored pixels equal to tile size, via
     * {@link #checkStoredNumberOfPixels()} method.
     *
     * @return the number of pixels in the last non-null data array, which was stored in this object.
     */
    @SuppressWarnings("JavadocDeclaration")
    public int getStoredNumberOfPixels() {
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile data are not decoded, number of pixels is unknown: " + this);
        }
        return storedNumberOfPixels;
    }

    public TiffTile checkStoredNumberOfPixels() throws IllegalStateException {
        checkEmpty();
        int storedNumberOfPixels = getStoredNumberOfPixels();
        if (storedNumberOfPixels != sizeX * sizeY) {
            throw new IllegalStateException("Number of stored pixels " + storedNumberOfPixels +
                    " does not match tile sizes " + sizeX + "x" + sizeY + " = " + (sizeX * sizeY));
        }
        assert storedNumberOfPixels * bytesPerPixel == storedDataLength;
        return this;
    }

    public TiffTile adjustNumberOfPixels(boolean allowDecreasing) {
        return changeNumberOfPixels(sizeX * sizeY, allowDecreasing);
    }

    public TiffTile changeNumberOfPixels(int newNumberOfPixels, boolean allowDecreasing) {
        if (newNumberOfPixels < 0) {
            throw new IllegalArgumentException("Negative new number of pixels = " + newNumberOfPixels);
        }
        final byte[] data = getDecoded();
        // - performs all necessary state checks
        int numberOfPixels = storedNumberOfPixels;
        if (numberOfPixels == newNumberOfPixels) {
            return this;
        }
        if ((long) newNumberOfPixels * (long) bytesPerPixel > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large requested number of pixels in tile: " + newNumberOfPixels +
                    " pixels * " + samplesPerPixel + " samples/pixel * " + bytesPerSample + " bytes/sample >= 2^31");
        }
        if (newNumberOfPixels < numberOfPixels && !allowDecreasing) {
            throw new IllegalArgumentException("The new number of pixels " + newNumberOfPixels +
                    " is less than actually stored " + numberOfPixels + "; this is not allowed: data may be lost");
        }
        final int newLength = newNumberOfPixels * bytesPerPixel;
        byte[] newData;
        if (interleaved) {
            newData = Arrays.copyOf(data, newLength);
        } else {
            newData = new byte[newLength];
            // - zero-filled by Java
            final int size = numberOfPixels * bytesPerSample;
            final int newSize = newNumberOfPixels * bytesPerSample;
            final int sizeToCopy = Math.min(size, newSize);
            for (int s = 0, disp = 0, newDisp = 0; s < samplesPerPixel; s++, disp += size, newDisp += newSize) {
                System.arraycopy(data, disp, newData, newDisp, sizeToCopy);
            }
        }
        return setDecoded(newData);
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
        byte[] data = getDecoded();
        if (isInterleaved()) {
            throw new IllegalStateException("TIFF tile is already interleaved: " + this);
        }
        data = TiffTools.toInterleavedSamples(data, samplesPerPixel(), bytesPerSample(), getStoredNumberOfPixels());
        setInterleaved(true);
        setDecoded(data);
        return this;
    }

    public TiffTile separateSamples() {
        byte[] data = getDecoded();
        if (!isInterleaved()) {
            throw new IllegalStateException("TIFF tile is already separated: " + this);
        }
        data = TiffTools.toSeparatedSamples(data, samplesPerPixel(), bytesPerSample(), getStoredNumberOfPixels());
        setInterleaved(false);
        setDecoded(data);
        return this;
    }

    @Override
    public String toString() {
        return "TIFF " +
                (encoded ? "encoded" : "non-encoded") +
                (interleaved ? " interleaved" : "") +
                " tile" +
                (isEmpty() ?
                        ", empty" :
                        ", actual sizes " + sizeX + "x" + sizeY + " (" +
                                storedNumberOfPixels + " pixels, " + storedDataLength + " bytes)") +
                ", index " + index +
                (hasStoredDataFileOffset() ? " at file offset " + storedDataFileOffset : "");
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
                interleaved == tiffTile.interleaved && encoded == tiffTile.encoded &&
                samplesPerPixel == tiffTile.samplesPerPixel && bytesPerSample == tiffTile.bytesPerSample &&
                storedDataFileOffset == tiffTile.storedDataFileOffset &&
                storedDataLength == tiffTile.storedDataLength &&
                Objects.equals(index, tiffTile.index) &&
                Arrays.equals(data, tiffTile.data);
        // Note: doesn't check "map" to avoid infinite recursion!
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(index, sizeX, sizeY,
                interleaved, encoded, storedDataFileOffset, storedDataLength);
        result = 31 * result + Arrays.hashCode(data);
        return result;
        // Note: doesn't check this.map to avoid infinite recursion!
    }

    private TiffTile setData(byte[] data, boolean encoded) {
        Objects.requireNonNull(data, "Null " + (encoded ? "encoded" : "decoded") + " data");
        final int storedNumberOfPixels = data.length / bytesPerPixel;
        if (!encoded && storedNumberOfPixels * bytesPerPixel != data.length) {
            throw new IllegalArgumentException("Invalid length of decoded data " + data.length +
                    ": it must be a multiple of the pixel length " +
                    bytesPerPixel + " = " + samplesPerPixel + " * " + bytesPerSample +
                    " (channels per pixel * bytes per channel sample)");
        }
        this.data = data;
        this.storedDataLength = data.length;
        this.storedNumberOfPixels = storedNumberOfPixels;
        this.encoded = encoded;
        if (!encoded) {
            removeStoredDataFileOffset();
            // - data file offset has no sense for decoded data
        }
        return this;
    }

    private void checkEmpty() {
        if (data == null) {
            throw new IllegalStateException("TIFF tile is still not filled by any data: " + this);
        }
    }

    private void checkStoredFilePosition() {
        if (storedDataFileOffset < 0) {
            throw new IllegalStateException("File offset of the TIFF tile is not set yet: " + this);
        }
    }
}
