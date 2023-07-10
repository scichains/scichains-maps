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

import java.util.Objects;

/**
 * Tile index, based of row, column and IDF identity hash code.
 * Of course, identity hash code cannot provide a good universal cache,
 * but it is quite enough for optimizing usage of TiffParser:
 * usually we will not create new identical IFDs.
 */
public final class TiffTileIndex {
    private final ExtendedIFD ifd;
    private final int tileSizeX;
    private final int tileSizeY;
    private final int tileSize;
    private final int sizeOfTileBasedOnBits;
    private final int ifdIdentity;
    private final int x;
    private final int y;

    public TiffTileIndex(ExtendedIFD ifd, int x, int y) {
        Objects.requireNonNull(ifd, "Null ifd");
        if (x < 0) {
            throw new IllegalArgumentException("Negative x = " + x);
        }
        if (y < 0) {
            throw new IllegalArgumentException("Negative y = " + y);
        }
        // Note: we could check here also that x and y are in range of all tiles for this IFD, but it is a bad idea:
        // 1) while building new TIFF, we probably do not know actual images sizes until getting all tiles;
        // 2) number of tiles in column may be calculated in non-trivial way, if PLANAR_CONFIGURATION tag is 2
        this.ifd = ifd;
        this.ifdIdentity = System.identityHashCode(ifd);
        // - not a universal solution, but suitable for our optimization needs:
        // usually we do not create new IFD instances without necessity
        try {
            this.tileSizeX = ifd.getTileSizeX();
            this.tileSizeY = ifd.getTileSizeY();
            if ((long) tileSizeX * (long) tileSizeY > Integer.MAX_VALUE) {
                throw new FormatException("Very large tile " + tileSizeX + "x" + tileSizeY +
                        " >= 2^31 pixels is not supported");
                // - note that it is also checked deeper in the next operator
            }
            this.sizeOfTileBasedOnBits = ifd.sizeOfTileBasedOnBits();
            this.tileSize = tileSizeX * tileSizeY;
        } catch (FormatException e) {
            throw new IllegalArgumentException("Illegal IFD: cannot determine tile sizes", e);
        }
        this.x = x;
        this.y = y;
    }

    public ExtendedIFD ifd() {
        return ifd;
    }

    public int tileSizeX() {
        return tileSizeX;
    }

    public int tileSizeY() {
        return tileSizeY;
    }

    public int tileSize() {
        return tileSize;
    }

    public int sizeOfBasedOnBits() {
        return sizeOfTileBasedOnBits;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ") in IFD @" + Integer.toHexString(ifdIdentity);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TiffTileIndex tileIndex = (TiffTileIndex) o;
        return x == tileIndex.x && ifdIdentity == tileIndex.ifdIdentity && y == tileIndex.y;

    }

    @Override
    public int hashCode() {
        int result = ifdIdentity;
        result = 31 * result + y;
        result = 31 * result + x;
        return result;
    }
}
