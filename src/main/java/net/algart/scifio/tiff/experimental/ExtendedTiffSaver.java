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

package net.algart.scifio.tiff.experimental;

import io.scif.FormatException;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDType;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;

import java.io.IOException;
import java.nio.file.Path;

public class ExtendedTiffSaver extends TiffSaver {
    private static final boolean AVOID_LONG8_FOR_ACTUAL_32_BITS = true;
    // - If was necessary for some old programs (like Aperio Image Viewer), which
    // did not understand LONG8 values for some popular tags like image sizes.
    // In any case, real BigTIFF files usually store most tags in standard LONG type (32 bits), not in LONG8.

    public ExtendedTiffSaver(Context context, Path file) throws IOException {
        this(context, new FileLocation(file.toFile()));
    }

    public ExtendedTiffSaver(Context context, Location location) throws IOException {
        super(context, location);
    }

    @Override
    public void writeIFDValue(DataHandle<Location> extraOut, long offset, int tag, Object value)
            throws FormatException, IOException {
        final var out = getStream();
        final int dataLength = isBigTiff() ? 8 : 4;
        if (value instanceof byte[] q) {
            prepareWritingIFD(extraOut, tag, IFDType.UNDEFINED.getCode());
            // - Most probable type. Maybe in future we will support here some algorithm,
            // determining necessary type on the base of the tag value.
            writeIntValue(out, q.length);
            if (q.length <= dataLength) {
                for (byte b : q) {
                    out.writeByte(b);
                }
                for (int i = q.length; i < dataLength; i++)
                    out.writeByte(0);
            } else {
                writeIntValue(out, offset + extraOut.length());
                extraOut.write(q);
            }
            return;
        }
        if (AVOID_LONG8_FOR_ACTUAL_32_BITS && isBigTiff()) {
            if (value instanceof long[] longs && longs.length == 1) {
                value = longs[0];
                // - inside TIFF, long[1] is saved in the same way as Long: we have a difference in Java only
            }
            if (value instanceof Long) {
                final long v = (Long) value;
                if (v == (int) v) {
                    // - it is very probable for the following tags
                    switch (tag) {
                        case IFD.IMAGE_WIDTH,
                                IFD.IMAGE_LENGTH,
                                IFD.TILE_WIDTH,
                                IFD.TILE_LENGTH,
                                IFD.ROWS_PER_STRIP,
                                IFD.NEW_SUBFILE_TYPE -> {
                            prepareWritingIFD(extraOut, tag, IFDType.LONG.getCode());
                            // - TiffSaver class saves LONG8 in this case, but some programs
                            // (like Aperio Image Viewer) do not understand such values
                            out.writeLong(1L);
                            out.writeInt((int) v);
                            out.writeInt(0);
                            // - 4 bytes of padding until full length 20 bytes
                            return;
                        }
                    }
                }
            }
        }
        super.writeIFDValue(extraOut, offset, tag, value);
    }

    // A clone of starting code of super.writeIFDValue
    private void prepareWritingIFD(DataHandle<Location> extraOut, int tag, int code) throws IOException {
        extraOut.setLittleEndian(isLittleEndian());
        final var out = getStream();
        out.writeShort(tag);
        out.writeShort(code);
    }

    // - clone of the private method of the superclass
    //!! (it is much better to make that method public)
    private void writeIntValue(final DataHandle<Location> handle, final long value) throws IOException {
        if (isBigTiff()) {
            handle.writeLong(value);
        } else {
            handle.writeInt((int) value);
        }
    }

}
