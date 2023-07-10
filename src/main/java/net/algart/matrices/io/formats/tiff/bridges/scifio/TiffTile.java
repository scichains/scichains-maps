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

import java.util.Objects;

public class TiffTile {
    private final TiffTileIndex tileIndex;
    private final ExtendedIFD ifd;
    private boolean encoded = false;
    private int sizeX;
    private int sizeY;
    private int size;
    private byte[] data = null;

    public TiffTile(TiffTileIndex tileIndex) {
        this.tileIndex = Objects.requireNonNull(tileIndex);
        this.ifd = tileIndex.ifd();
        setSizes(tileIndex.tileSizeX(), tileIndex().tileSizeY());
    }

    public final TiffTileIndex tileIndex() {
        return tileIndex;
    }

    public final ExtendedIFD ifd() {
        return ifd;
    }

    public boolean isEncoded() {
        return encoded;
    }

    public TiffTile setEncoded(boolean encoded) {
        this.encoded = encoded;
        return this;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSize() {
        return size;
    }

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
        this.size = sizeX * sizeY;
        return this;
    }

    public boolean isEmpty() {
        return data == null;
    }

    public int getDataLength() {
        return data == null ? 0 : data.length;
    }

    public byte[] getData() {
        checkEmpty();
        return data;
    }

    public TiffTile setData(byte[] data) {
        Objects.requireNonNull(data, "Null data");
        this.data = data;
        return this;
    }

    public final byte[] getEncodedData() {
        checkEmpty();
        if (!isEncoded()) {
            throw new IllegalStateException("TIFF tile is not encoded: " + this);
        }
        return getData();
    }

    public final TiffTile setEncodedData(byte[] data) {
        return setData(data).setEncoded(true);
    }

    public final byte[] getDecodedData() {
        checkEmpty();
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile is not decoded: " + this);
        }
        return getData();
    }

    public final TiffTile setDecodedData(byte[] data) {
        return setData(data).setEncoded(false);
    }

    public TiffTile removeData(byte[] data) {
        this.data = null;
        return this;
    }

    protected void checkEmpty() {
        if (data == null) {
            throw new IllegalStateException("TIFF tile is still not filled by any data: " + this);
        }
    }

    @Override
    public String toString() {
        return "TIFF " + (encoded ? "encoded" : "decoded") + " tile "
                + tileIndex.tileSizeX() + "x" + tileIndex.tileSizeY() + " at " + tileIndex +
                (isEmpty() ? ", empty" : getDataLength() + " bytes");
    }
}
