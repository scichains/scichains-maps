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

package net.algart.matrices.io.formats.tiff.bridges.scifio.compatibility;

import io.scif.FormatException;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDList;
import io.scif.formats.tiff.TiffConstants;
import io.scif.formats.tiff.TiffIFDEntry;
import io.scif.util.FormatTools;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffWriter;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.handle.DataHandles;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;

import java.io.IOException;
import java.util.Objects;

/**
 * Legacy version of {@link TiffWriter} with some deprecated method.
 * Should be replaced with {@link TiffWriter}.
 */
public class TiffSaver extends TiffWriter {
    private final DataHandleService dataHandleService;


    @Deprecated
    public TiffSaver(final Context ctx, final String filename) throws IOException {
        this(ctx, new FileLocation(filename));
    }

    @Deprecated
    public TiffSaver(final Context ctx, final Location loc) {
        super(Objects.requireNonNull(ctx, "Null context"),
                ctx.getService(DataHandleService.class).create(loc));
        this.dataHandleService = ctx.getService(DataHandleService.class);
        // Disable new features of TiffWriter for compatibility:
        this.setWritingSequentially(false);
        this.setAutoInterleave(false);
        this.setExtendedCodec(false);
    }

    /**
     * Constructs a new TIFF saver from the given output source.
     *
     * @param out   Output stream to save TIFF data to.
     * @param bytes In memory byte array handle that we may use to create extra
     *              input or output streams as required.
     */
    @Deprecated
    public TiffSaver(final DataHandle<Location> out, final BytesLocation bytes) {
        this(new Context(), (Location) out);
        // Note: it the old SCIFIO TiffSaver class, "bytes" parameter was stored in a field,
        // but logic of the algorithm did not allow to use it - it was an obvious bug.
    }

    /**
     * Writes the TIFF file header.
     *
     * <p>Use {@link #startWritingFile()} instead.
     */
    @Deprecated
    public void writeHeader() throws IOException {
        final DataHandle<Location> out = getStream();
        final boolean bigTiff = isBigTiff();

        // write endianness indicator
        synchronized (this) {
            out.seek(0);
            if (isLittleEndian()) {
                out.writeByte(TiffConstants.LITTLE);
                out.writeByte(TiffConstants.LITTLE);
            } else {
                out.writeByte(TiffConstants.BIG);
                out.writeByte(TiffConstants.BIG);
            }
            // write magic number
            if (bigTiff) {
                out.writeShort(TiffConstants.BIG_TIFF_MAGIC_NUMBER);
            } else out.writeShort(TiffConstants.MAGIC_NUMBER);

            // write the offset to the first IFD

            // for vanilla TIFFs, 8 is the offset to the first IFD
            // for BigTIFFs, 8 is the number of bytes in an offset
            if (bigTiff) {
                out.writeShort(8);
                out.writeShort(0);

                // write the offset to the first IFD for BigTIFF files
                out.writeLong(16);
            } else {
                out.writeInt(8);
            }
        }
    }



    /**
     * Please use code like inside {@link #startWritingFile()}.
     */
    @Deprecated
    public void overwriteLastIFDOffset(final DataHandle<Location> handle)
            throws FormatException, IOException {
        if (handle == null) throw new FormatException("Output cannot be null");
        final io.scif.formats.tiff.TiffParser parser = new io.scif.formats.tiff.TiffParser(getContext(), handle);
        parser.getIFDOffsets();
        final DataHandle<Location> out = getStream();
        out.seek(handle.offset() - (isBigTiff() ? 8 : 4));
        writeIntValue(out, 0);
    }


    /**
     * Surgically overwrites an existing IFD value with the given one. This method
     * requires that the IFD directory entry already exist. It intelligently
     * updates the count field of the entry to match the new length. If the new
     * length is longer than the old length, it appends the new data to the end of
     * the file and updates the offset field; if not, or if the old data is
     * already at the end of the file, it overwrites the old data in place.
     */
    @Deprecated
    public void overwriteIFDValue(final DataHandle<Location> raf, final int ifd,
                                  final int tag, final Object value) throws FormatException, IOException {
        if (raf == null) throw new FormatException("Output cannot be null");
//        log.debug("overwriteIFDValue (ifd=" + ifd + "; tag=" + tag + "; value=" +
//                value + ")");

        final DataHandle<Location> out = getStream();
        raf.seek(0);
        final io.scif.formats.tiff.TiffParser parser = new io.scif.formats.tiff.TiffParser(getContext(), raf);
        final Boolean valid = parser.checkHeader();
        if (valid == null) {
            throw new FormatException("Invalid TIFF header");
        }

        final boolean little = valid.booleanValue();
        final boolean bigTiff = parser.isBigTiff();

        setLittleEndian(little);
        setBigTiff(bigTiff);

        final long offset = bigTiff ? 8 : 4; // offset to the IFD

        final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY
                : TiffConstants.BYTES_PER_ENTRY;

        raf.seek(offset);

        // skip to the correct IFD
        final long[] offsets = parser.getIFDOffsets();
        if (ifd >= offsets.length) {
            throw new FormatException("No such IFD (" + ifd + " of " +
                    offsets.length + ")");
        }
        raf.seek(offsets[ifd]);

        // get the number of directory entries
        final long num = bigTiff ? raf.readLong() : raf.readUnsignedShort();

        // search directory entries for proper tag
        for (int i = 0; i < num; i++) {
            raf.seek(offsets[ifd] + (bigTiff ? 8 : 2) + bytesPerEntry * i);

            final TiffIFDEntry entry = parser.readTiffIFDEntry();
            if (entry.getTag() == tag) {
                // write new value to buffers
                final DataHandle<Location> ifdHandle = dataHandleService.create(
                        new BytesLocation(bytesPerEntry));
                final DataHandle<Location> extraHandle = dataHandleService.create(
                        new BytesLocation(0));
                extraHandle.setLittleEndian(little);
                final io.scif.formats.tiff.TiffSaver saver = new io.scif.formats.tiff.TiffSaver(ifdHandle, new BytesLocation(
                        bytesPerEntry));
                saver.setLittleEndian(isLittleEndian());
                saver.writeIFDValue(extraHandle, entry.getValueOffset(), tag, value);
                ifdHandle.seek(0);
                extraHandle.seek(0);

                // extract new directory entry parameters
                final int newTag = ifdHandle.readShort();
                final int newType = ifdHandle.readShort();
                int newCount;
                long newOffset;
                if (bigTiff) {
                    newCount = ifdHandle.readInt();
                    newOffset = ifdHandle.readLong();
                } else {
                    newCount = ifdHandle.readInt();
                    newOffset = ifdHandle.readInt();
                }
//                log.debug("overwriteIFDValue:");
//                log.debug("\told (" + entry + ");");
//                log.debug("\tnew: (tag=" + newTag + "; type=" + newType + "; count=" +
//                        newCount + "; offset=" + newOffset + ")");

                // determine the best way to overwrite the old entry
                if (extraHandle.length() == 0) {
                    // new entry is inline; if old entry wasn't, old data is
                    // orphaned
                    // do not override new offset value since data is inline
//                    log.debug("overwriteIFDValue: new entry is inline");
                } else if (entry.getValueOffset() + entry.getValueCount() * entry
                        .getType().getBytesPerElement() == raf.length()) {
                    // old entry was already at EOF; overwrite it
                    newOffset = entry.getValueOffset();
//                    log.debug("overwriteIFDValue: old entry is at EOF");
                } else if (newCount <= entry.getValueCount()) {
                    // new entry is as small or smaller than old entry;
                    // overwrite it
                    newOffset = entry.getValueOffset();
//                    log.debug("overwriteIFDValue: new entry is <= old entry");
                } else {
                    // old entry was elsewhere; append to EOF, orphaning old
                    // entry
                    newOffset = raf.length();
//                    log.debug("overwriteIFDValue: old entry will be orphaned");
                }

                // overwrite old entry
                out.seek(offsets[ifd] + (bigTiff ? 8 : 2) + bytesPerEntry * i + 2);
                out.writeShort(newType);
                writeIntValue(out, newCount);
                writeIntValue(out, newOffset);
                if (extraHandle.length() > 0) {
                    out.seek(newOffset);
                    extraHandle.seek(0l);
                    DataHandles.copy(extraHandle, out, newCount);
                }
                return;
            }
        }

        throw new FormatException("Tag not found (" + IFD.getIFDTagName(tag) + ")");
    }

    /**
     * Convenience method for overwriting a file's first ImageDescription.
     */
    @Deprecated
    public void overwriteComment(final DataHandle<Location> in,
                                 final Object value) throws FormatException, IOException {
        overwriteIFDValue(in, 0, IFD.IMAGE_DESCRIPTION, value);
    }

    @Deprecated
    public void writeImage(final byte[][] buf, final IFDList ifds,
                           final int pixelType) throws FormatException, IOException {
        if (ifds == null) {
            throw new FormatException("IFD cannot be null");
        }
        if (buf == null) {
            throw new FormatException("Image data cannot be null");
        }
        for (int i = 0; i < ifds.size(); i++) {
            if (i < buf.length) {
                writeImage(buf[i], ifds.get(i), i, pixelType, i == ifds.size() - 1);
            }
        }
    }

    @Deprecated
    public void writeImage(
            final byte[] samples, final IFD ifd, final int planeIndex,
            final int pixelType, final boolean last) throws FormatException, IOException {
        writeSamples(DetailedIFD.extend(ifd), samples, pixelType, planeIndex, last);
    }

    @Deprecated
    public void writeImage(final byte[] buf, final IFD ifd, final long planeIndex,
                           final int pixelType, final int x, final int y, final int w, final int h,
                           final boolean last) throws FormatException, IOException {
        writeImage(buf, ifd, planeIndex, pixelType, x, y, w, h, last, null, false);
    }

    @Deprecated
    public void writeImage(final byte[] buf, final IFD ifd, final long planeIndex,
                           final int pixelType, final int x, final int y, final int w, final int h,
                           final boolean last, Integer nChannels, final boolean copyDirectly)
            throws FormatException, IOException {
        //Note: modern version of writeSamples doesn't need optimization via copyDirectly
        if (nChannels == null) {
            final int bytesPerSample = FormatTools.getBytesPerPixel(pixelType);
            nChannels = buf.length / (w * h * bytesPerSample);
            // - like in original writeImage; but overflow will be checked more thoroughly inside writeSamples
        }
        writeSamples(DetailedIFD.extend(ifd), buf,
                (int) planeIndex, nChannels, pixelType, x, y, w, h, last);
    }

    @Deprecated
    private void writeIntValue(final DataHandle<Location> handle,
                               final long offset) throws IOException {
        if (isBigTiff()) {
            handle.writeLong(offset);
        } else {
            handle.writeInt((int) offset);
        }
    }
}
