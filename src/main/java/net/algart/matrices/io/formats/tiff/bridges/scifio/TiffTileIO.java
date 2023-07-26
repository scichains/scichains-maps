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

import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;

import java.io.IOException;
import java.util.Objects;

public class TiffTileIO {
    private TiffTileIO() {
    }

    public static void read(TiffTile tile, DataHandle<?> in, long filePosition, int dataLength) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(in, "Null input stream");
        tile.setStoredDataFileRange(filePosition, dataLength);
        read(tile, in);
    }

    public static void writeAndRemove(TiffTile tile, DataHandle<? extends Location> out) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(out, "Null output stream");
        tile.setStoredDataFileOffset(out.length());
        write(tile, out, true);
    }

    public static void read(TiffTile tile, DataHandle<?> in) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(in, "Null input stream");
        final long filePosition = tile.getStoredDataFileOffset();
        byte[] data = new byte[tile.getStoredDataLength()];
        in.seek(filePosition);
        final int result = in.read(data);
        if (result < data.length) {
            throw new IOException("File exhausted at " + filePosition +
                    ": loaded " + result + " bytes instead of " + data.length +
                    " (" + in.get() + ")");
        }
        tile.setEncodedData(data);
    }

    public static void write(TiffTile tile, DataHandle<?> out, boolean removeAfterSave) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(out, "Null output stream");
        final long filePosition = tile.getStoredDataFileOffset();
        byte[] encodedData = tile.getEncodedData();
        out.seek(filePosition);
        out.write(encodedData);
        if (removeAfterSave) {
            tile.removeData();
        }
    }
}
