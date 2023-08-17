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
import io.scif.SCIFIO;
import io.scif.codec.CodecOptions;
import io.scif.formats.tiff.TiffParser;
import io.scif.formats.tiff.*;
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
import org.scijava.log.LogService;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Legacy version of {@link TiffWriter} with some deprecated method.
 * Should be replaced with {@link TiffWriter}.
 */
public class TiffSaver extends TiffWriter {
    private final DataHandleService dataHandleService;

    private boolean sequentialWrite = false;
    private SCIFIO scifio;
    private LogService log;

    @Deprecated
    public TiffSaver(final Context ctx, final String filename) throws IOException {
        this(ctx, new FileLocation(filename));
    }

    @Deprecated
    public TiffSaver(final Context ctx, final Location loc) {
        super(Objects.requireNonNull(ctx, "Null context"),
                ctx.getService(DataHandleService.class).create(loc));
        scifio = new SCIFIO(ctx);
        log = scifio.log();
        this.dataHandleService = ctx.getService(DataHandleService.class);
        // Disable new features of TiffWriter for compatibility:
        this.setWritingSequentially(false);
        this.setAutoMarkLastImageOnClose(false);
        this.setAutoInterleaveSource(false);
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
     * Sets whether or not we know that the planes will be written sequentially.
     * If we are writing planes sequentially and set this flag, then performance
     * is slightly improved.
     */
    public void setWritingSequentially(final boolean sequential) {
        sequentialWrite = sequential;
    }

    /**
     * Writes the TIFF file header.
     *
     * <p>Use {@link #startWriting()} instead.
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
     * Use instead {@link #rewriteIFD(DetailedIFD, boolean)} together with
     * {@link DetailedIFD#setNextIFDOffset(long, boolean)} and {@link DetailedIFD#setFileOffsetForWriting(long)}.
     */
    @Deprecated
    public void writeIFD(final IFD ifd, final long nextOffset) throws FormatException, IOException {
        DetailedIFD extended = DetailedIFD.extend(ifd);
        extended.setFileOffsetForWriting(getStream().offset());
        extended.setNextIFDOffset(nextOffset, false);
        rewriteIFD(extended, false);
    }

    @Deprecated
    public void writeIFDValue(
            final DataHandle<Location> extraOut,
            final long offset, final int tag, Object value)
            throws FormatException, IOException {
        super.writeIFDValueAtCurrentPosition(extraOut, offset, tag, value);
    }

    /**
     * Please use code like inside {@link #startWriting()}.
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


    /**
     *
     */
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

    /**
     *
     */
    public void writeImage(final byte[] buf, final IFD ifd, final int no,
                           final int pixelType, final boolean last) throws FormatException, IOException {
        if (ifd == null) {
            throw new FormatException("IFD cannot be null");
        }
        final int w = (int) ifd.getImageWidth();
        final int h = (int) ifd.getImageLength();
        writeImage(buf, ifd, no, pixelType, 0, 0, w, h, last);
    }

    /**
     * Writes to any rectangle from the passed block.
     *
     * @param buf        The block that is to be written.
     * @param ifd        The Image File Directories. Mustn't be {@code null}.
     * @param planeIndex The image index within the current file, starting from 0.
     * @param pixelType  The type of pixels.
     * @param x          The X-coordinate of the top-left corner.
     * @param y          The Y-coordinate of the top-left corner.
     * @param w          The width of the rectangle.
     * @param h          The height of the rectangle.
     * @param last       Pass {@code true} if it is the last image, {@code false}
     *                   otherwise.
     * @throws FormatException
     * @throws IOException
     */
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
        {
            log.debug("Attempting to write image.");
            // b/c method is public should check parameters again
            if (buf == null) {
                throw new FormatException("Image data cannot be null");
            }

            if (ifd == null) {
                throw new FormatException("IFD cannot be null");
            }

            // These operations are synchronized
            TiffCompression compression;
            int tileWidth, tileHeight, nStrips;
            boolean interleaved;
            ByteArrayOutputStream[] stripBuf;
            synchronized (this) {
                final int bytesPerPixel = FormatTools.getBytesPerPixel(pixelType);
                final int blockSize = w * h * bytesPerPixel;
                if (nChannels == null) {
                    nChannels = buf.length / (w * h * bytesPerPixel);
                }
                interleaved = ifd.getPlanarConfiguration() == 1;

                makeValidIFD(ifd, pixelType, nChannels);

                // create pixel output buffers

                compression = ifd.getCompression();
                tileWidth = (int) ifd.getTileWidth();
                tileHeight = (int) ifd.getTileLength();
                final int tilesPerRow = (int) ifd.getTilesPerRow();
                final int rowsPerStrip = (int) ifd.getRowsPerStrip()[0];
                int stripSize = rowsPerStrip * tileWidth * bytesPerPixel;
                nStrips = ((w + tileWidth - 1) / tileWidth) * ((h + tileHeight - 1) /
                        tileHeight);

                if (interleaved) stripSize *= nChannels;
                else nStrips *= nChannels;

                stripBuf = new ByteArrayOutputStream[nStrips];
                final DataOutputStream[] stripOut = new DataOutputStream[nStrips];
                for (int strip = 0; strip < nStrips; strip++) {
                    stripBuf[strip] = new ByteArrayOutputStream(stripSize);
                    stripOut[strip] = new DataOutputStream(stripBuf[strip]);
                }
                final int[] bps = ifd.getBitsPerSample();
                int off;

                // write pixel strips to output buffers
                final int effectiveStrips = !interleaved ? nStrips / nChannels : nStrips;
                if (effectiveStrips == 1 && copyDirectly) {
                    stripOut[0].write(buf);
                } else {
                    for (int strip = 0; strip < effectiveStrips; strip++) {
                        final int xOffset = (strip % tilesPerRow) * tileWidth;
                        final int yOffset = (strip / tilesPerRow) * tileHeight;
                        for (int row = 0; row < tileHeight; row++) {
                            for (int col = 0; col < tileWidth; col++) {
                                final int ndx = ((row + yOffset) * w + col + xOffset) *
                                        bytesPerPixel;
                                for (int c = 0; c < nChannels; c++) {
                                    for (int n = 0; n < bps[c] / 8; n++) {
                                        if (interleaved) {
                                            off = ndx * nChannels + c * bytesPerPixel + n;
                                            if (row >= h || col >= w) {
                                                stripOut[strip].writeByte(0);
                                            } else {
                                                stripOut[strip].writeByte(buf[off]);
                                            }
                                        } else {
                                            off = c * blockSize + ndx + n;
                                            if (row >= h || col >= w) {
                                                stripOut[strip].writeByte(0);
                                            } else {
                                                stripOut[c * (nStrips / nChannels) + strip].writeByte(
                                                        buf[off]);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Compress strips according to given differencing and compression
            // schemes,
            // this operation is NOT synchronized and is the ONLY portion of the
            // TiffWriter.saveBytes() --> TiffSaver.writeImage() stack that is NOT
            // synchronized.
            final byte[][] strips = new byte[nStrips][];
            for (int strip = 0; strip < nStrips; strip++) {
                strips[strip] = stripBuf[strip].toByteArray();
                scifio.tiff().difference(strips[strip], ifd);
                final CodecOptions codecOptions = compression.getCompressionCodecOptions(
                        ifd, getCodecOptions());
                codecOptions.height = tileHeight;
                codecOptions.width = tileWidth;
                codecOptions.channels = interleaved ? nChannels : 1;

                strips[strip] = compression.compress(scifio.codec(), strips[strip],
                        codecOptions);
                if (log.isDebug()) {
                    log.debug(String.format("Compressed strip %d/%d length %d", strip + 1,
                            nStrips, strips[strip].length));
                }
            }

            // This operation is synchronized
            synchronized (this) {
                writeImageIFD(ifd, planeIndex, strips, nChannels, last, x, y);
            }
        }
    }

    private void writeImageIFD(IFD ifd, final long planeIndex,
                               final byte[][] strips, final int nChannels, final boolean last, final int x,
                               final int y) throws FormatException, IOException {
        DataHandle<Location> out = getStream();
        log.debug("Attempting to write image IFD.");
        final int tilesPerRow = (int) ifd.getTilesPerRow();
        final int tilesPerColumn = (int) ifd.getTilesPerColumn();
        final boolean interleaved = ifd.getPlanarConfiguration() == 1;
        final boolean isTiled = ifd.isTiled();

        if (!sequentialWrite) {
            DataHandle<Location> in = dataHandleService.create(out.get());
            try {
                final io.scif.formats.tiff.TiffParser parser = new TiffParser(getContext(), in);
                final long[] ifdOffsets = parser.getIFDOffsets();
                log.debug("IFD offsets: " + Arrays.toString(ifdOffsets));
                if (planeIndex < ifdOffsets.length) {
                    out.seek(ifdOffsets[(int) planeIndex]);
                    log.debug("Reading IFD from " + ifdOffsets[(int) planeIndex] +
                            " in non-sequential write.");
                    ifd = parser.getIFD(ifdOffsets[(int) planeIndex]);
                }
            } finally {
                in.close();
            }
        }

        // record strip byte counts and offsets

        final List<Long> byteCounts = new ArrayList<>();
        final List<Long> offsets = new ArrayList<>();
        long totalTiles = tilesPerRow * tilesPerColumn;

        if (!interleaved) {
            totalTiles *= nChannels;
        }

        if (ifd.containsKey(IFD.STRIP_BYTE_COUNTS) || ifd.containsKey(
                IFD.TILE_BYTE_COUNTS)) {
            final long[] ifdByteCounts = isTiled ? ifd.getIFDLongArray(
                    IFD.TILE_BYTE_COUNTS) : ifd.getStripByteCounts();
            for (final long stripByteCount : ifdByteCounts) {
                byteCounts.add(stripByteCount);
            }
        } else {
            while (byteCounts.size() < totalTiles) {
                byteCounts.add(0L);
            }
        }
        final int tileOrStripOffsetX = x / (int) ifd.getTileWidth();
        final int tileOrStripOffsetY = y / (int) ifd.getTileLength();
        final int firstOffset = (tileOrStripOffsetY * tilesPerRow) +
                tileOrStripOffsetX;
        if (ifd.containsKey(IFD.STRIP_OFFSETS) || ifd.containsKey(
                IFD.TILE_OFFSETS)) {
            final long[] ifdOffsets = isTiled ? ifd.getIFDLongArray(IFD.TILE_OFFSETS)
                    : ifd.getStripOffsets();
            for (final long ifdOffset : ifdOffsets) {
                offsets.add(ifdOffset);
            }
        } else {
            while (offsets.size() < totalTiles) {
                offsets.add(0L);
            }
        }

        if (isTiled) {
            ifd.putIFDValue(IFD.TILE_BYTE_COUNTS, toPrimitiveArray(byteCounts));
            ifd.putIFDValue(IFD.TILE_OFFSETS, toPrimitiveArray(offsets));
        } else {
            ifd.putIFDValue(IFD.STRIP_BYTE_COUNTS, toPrimitiveArray(byteCounts));
            ifd.putIFDValue(IFD.STRIP_OFFSETS, toPrimitiveArray(offsets));
        }

        final long fp = out.offset();
        writeIFD(ifd, 0);

        for (int i = 0; i < strips.length; i++) {
            out.seek(out.length());
            final int thisOffset = firstOffset + i;
            offsets.set(thisOffset, out.offset());
            byteCounts.set(thisOffset, (long) strips[i].length);
            if (log.isDebug()) {
                log.debug(String.format("Writing tile/strip %d/%d size: %d offset: %d",
                        thisOffset + 1, totalTiles, byteCounts.get(thisOffset), offsets.get(
                                thisOffset)));
            }
            out.write(strips[i]);
        }
        if (isTiled) {
            ifd.putIFDValue(IFD.TILE_BYTE_COUNTS, toPrimitiveArray(byteCounts));
            ifd.putIFDValue(IFD.TILE_OFFSETS, toPrimitiveArray(offsets));
        } else {
            ifd.putIFDValue(IFD.STRIP_BYTE_COUNTS, toPrimitiveArray(byteCounts));
            ifd.putIFDValue(IFD.STRIP_OFFSETS, toPrimitiveArray(offsets));
        }
        final long endFP = out.offset();
        if (log.isDebug()) {
            log.debug("Offset before IFD write: " + out.offset() + " Seeking to: " +
                    fp);
        }
        out.seek(fp);

        if (log.isDebug()) {
            log.debug("Writing tile/strip offsets: " + Arrays.toString(
                    toPrimitiveArray(offsets)));
            log.debug("Writing tile/strip byte counts: " + Arrays.toString(
                    toPrimitiveArray(byteCounts)));
        }
        writeIFD(ifd, last ? 0 : endFP);
        if (log.isDebug()) {
            log.debug("Offset after IFD write: " + out.offset());
        }
    }

    private void makeValidIFD(final IFD ifd, final int pixelType,
                              final int nChannels) {
        final int bytesPerPixel = FormatTools.getBytesPerPixel(pixelType);
        final int bps = 8 * bytesPerPixel;
        final int[] bpsArray = new int[nChannels];
        Arrays.fill(bpsArray, bps);
        ifd.putIFDValue(IFD.BITS_PER_SAMPLE, bpsArray);

        if (FormatTools.isFloatingPoint(pixelType)) {
            ifd.putIFDValue(IFD.SAMPLE_FORMAT, 3);
        }
        if (ifd.getIFDValue(IFD.COMPRESSION) == null) {
            ifd.putIFDValue(IFD.COMPRESSION, TiffCompression.UNCOMPRESSED.getCode());
        }

        final boolean indexed = nChannels == 1 && ifd.getIFDValue(
                IFD.COLOR_MAP) != null;
        final PhotoInterp pi = indexed ? PhotoInterp.RGB_PALETTE : nChannels == 1
                ? PhotoInterp.BLACK_IS_ZERO : PhotoInterp.RGB;
        ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, pi.getCode());

        ifd.putIFDValue(IFD.SAMPLES_PER_PIXEL, nChannels);

        if (ifd.get(IFD.X_RESOLUTION) == null) {
            ifd.putIFDValue(IFD.X_RESOLUTION, new TiffRational(1, 1));
        }
        if (ifd.get(IFD.Y_RESOLUTION) == null) {
            ifd.putIFDValue(IFD.Y_RESOLUTION, new TiffRational(1, 1));
        }
        if (ifd.get(IFD.SOFTWARE) == null) {
            ifd.putIFDValue(IFD.SOFTWARE, "SCIFIO");
        }
        if (ifd.get(IFD.ROWS_PER_STRIP) == null) {
            ifd.putIFDValue(IFD.ROWS_PER_STRIP, new long[]{1});
        }
        if (ifd.get(IFD.IMAGE_DESCRIPTION) == null) {
            ifd.putIFDValue(IFD.IMAGE_DESCRIPTION, "");
        }
    }

    private static long[] toPrimitiveArray(final List<Long> l) {
        final long[] toReturn = new long[l.size()];
        for (int i = 0; i < l.size(); i++) {
            toReturn[i] = l.get(i);
        }
        return toReturn;
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
