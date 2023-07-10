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
    private byte[] data = null;
    private boolean encoded = false;

    public TiffTile(TiffTileIndex tileIndex) {
        this.tileIndex = Objects.requireNonNull(tileIndex);
        this.ifd = tileIndex.ifd();
    }

    public TiffTile(TiffTileIndex tileIndex, boolean encoded) {
        this(tileIndex);
        setEncoded(encoded);
    }

    public TiffTile(ExtendedIFD ifd, int xIndex, int yIndex) {
        this(new TiffTileIndex(ifd, xIndex, yIndex));
    }

    public TiffTileIndex tileIndex() {
        return tileIndex;
    }

    public ExtendedIFD ifd() {
        return ifd;
    }

    public int xIndex() {
        return tileIndex.x();
    }

    public int yIndex() {
        return tileIndex.y();
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
        return this;
    }

    public TiffTile removeData(byte[] data) {
        this.data = null;
        return this;
    }

    public int getDataLength() {
        return data == null ? 0 : data.length;
    }

    public boolean isEncoded() {
        return encoded;
    }

    public TiffTile setEncoded(boolean encoded) {
        this.encoded = encoded;
        return this;
    }

    protected void checkEmpty() {
        if (data == null) {
            throw new IllegalStateException("TIFF tile is still not filled by any data");
        }
    }

    @Override
    public String toString() {
        return "TIFF " + (encoded ? "encoded" : "decoded") + " tile "
                + tileIndex.sizeX() + "x" + tileIndex.sizeY() + " at " + tileIndex +
                (isEmpty() ? ", empty" : getDataLength() + " bytes");
    }
}
