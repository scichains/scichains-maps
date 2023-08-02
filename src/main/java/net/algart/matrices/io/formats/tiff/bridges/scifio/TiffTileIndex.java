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

/**
 * TIFF tile index, based of row, column and IFD reference (not content).
 *
 * <p>Note: for hashing this object uses IFD identity hash code.
 * Of course, this  cannot provide a good universal hash,
 * but it is quite enough for our needs: usually we will not create new identical tile sets.
 *
 * @author Denial Alievsky
 */
public final class TiffTileIndex {
    private final TiffTileSet tileSet;
    private final DetailedIFD ifd;
    private final int ifdIdentity;
    private final int x;
    private final int y;

    /**
     * Creates new tile index.
     *
     * @param tileSet containing tile set.
     * @param x       x-index of the tile (0, 1, 2, ...).
     * @param y       y-index of the tile (0, 1, 2, ...).
     */
    public TiffTileIndex(TiffTileSet tileSet, int x, int y) {
        Objects.requireNonNull(tileSet, "Null containing tile set");
        if (x < 0) {
            throw new IllegalArgumentException("Negative x = " + x);
        }
        if (y < 0) {
            throw new IllegalArgumentException("Negative y = " + y);
        }
        // Note: we could check here also that x and y are in range of all tiles for this IFD, but it is a bad idea:
        // 1) while building new TIFF, we probably do not know actual images sizes until getting all tiles;
        // 2) number of tiles in column may be calculated in non-trivial way, if PLANAR_CONFIGURATION tag is 2
        this.tileSet = tileSet;
        this.ifd = tileSet.ifd();
        this.ifdIdentity = System.identityHashCode(ifd);
        // - not a universal solution, but suitable for our optimization needs:
        // usually we do not create new IFD instances without necessity
        this.x = x;
        this.y = y;
    }

    public TiffTileSet tileSet() {
        return tileSet;
    }

    public DetailedIFD ifd() {
        return tileSet.ifd();
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public TiffTile newTile() {
        return new TiffTile(this);
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
        final TiffTileIndex that = (TiffTileIndex) o;
        return x == that.x && y == that.y && ifd == that.ifd;
        // - Important! Comparing references to IFD, not content and not tile set!
        // Different tile sets may refer to the same IFD;
        // on the other hand, we usually do not need to create identical IFDs.
    }

    @Override
    public int hashCode() {
        int result = ifdIdentity;
        result = 31 * result + y;
        result = 31 * result + x;
        return result;
    }
}
