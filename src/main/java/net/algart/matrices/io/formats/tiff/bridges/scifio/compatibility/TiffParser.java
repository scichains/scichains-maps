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
import io.scif.codec.CodecOptions;
import io.scif.common.Constants;
import io.scif.enumeration.EnumException;
import io.scif.formats.tiff.*;
import net.algart.matrices.io.formats.tiff.bridges.scifio.*;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTile;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTileIndex;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.util.IntRect;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Legacy version of {@link TiffReader} with some deprecated method.
 * Should be replaced with {@link TiffReader}.
 */

public class TiffParser extends TiffReader {
    private static final System.Logger LOG = System.getLogger(TiffParser.class.getName());

    /**
     * Whether 64-bit offsets are used for non-BigTIFF files.
     */
    private boolean fakeBigTiff = false;
    // - Probably support of this feature was implemented incorrectly.
    // See https://github.com/scifio/scifio/issues/514

    private IFDList ifdList;
    private IFD firstIFD;

    // This constructor is new for TiffParser: it is added for more convenience and
    // compatibility with TiffReader constructor
    public TiffParser(Context context, Path file) throws IOException {
        this(context, new FileLocation(file.toFile()));
    }


    public TiffParser(final Context context, final Location loc) {
        this(Objects.requireNonNull(context, "Null context"),
                context.getService(DataHandleService.class).create(loc));
    }

    /**
     * Constructs a new TIFF parser from the given input source.
     */
    @Deprecated
    public TiffParser(final Context context, final DataHandle<Location> in) {
        super(Objects.requireNonNull(context, "Null context"), in, null);
        // Disable new features of TiffReader for compatibility:
        this.setAutoUnpackUnusualPrecisions(false);
        this.setCropTilesToImageBoundaries(false);
        this.setExtendedCodec(false);
        this.setMissingTilesAllowed(true);
        // - This is an interesting undocumented feature: old TiffParser really supported missing tiles!
    }

    /**
     * Sets whether or not to assume that strips are of equal size.
     *
     * @param assumeEqualStrips Whether or not the strips are of equal size.
     */
    @Deprecated
    public void setAssumeEqualStrips(final boolean assumeEqualStrips) {
        super.setAssumeEqualStrips(assumeEqualStrips);
    }

    /**
     * Sets whether or not 64-bit offsets are used for non-BigTIFF files.
     */
    @Deprecated
    public void setUse64BitOffsets(final boolean use64Bit) {
        fakeBigTiff = use64Bit;
    }

    /**
     * Sets whether or not IFD entries should be cached.
     * Use {@link #setCachingIFDs(boolean)} instead.
     */
    @Deprecated
    public void setDoCaching(final boolean cachingIFDs) {
        super.setCachingIFDs(cachingIFDs);
    }

    @Deprecated
    /** Use {@link #allIFDs()} instead. */
    public IFDList getIFDs() throws IOException {
        if (ifdList != null) return ifdList;
        final boolean doCaching = isCachingIFDs();

        final long[] offsets = getIFDOffsets();
        final IFDList ifds = new IFDList();

        for (final long offset : offsets) {
            final IFD ifd = getIFD(offset);
            if (ifd == null) continue;
            if (ifd.containsKey(IFD.IMAGE_WIDTH)) ifds.add(ifd);
            long[] subOffsets = null;
            try {
                if (!doCaching && ifd.containsKey(IFD.SUB_IFD)) {
                    fillInIFD(ifd);
                }
                subOffsets = ifd.getIFDLongArray(IFD.SUB_IFD);
            }
            catch (final FormatException e) {}
            if (subOffsets != null) {
                for (final long subOffset : subOffsets) {
                    final IFD sub = getIFD(subOffset);
                    if (sub != null) {
                        ifds.add(sub);
                    }
                }
            }
        }
        if (doCaching) ifdList = ifds;

        return ifds;
    }

    /** Use {@link #allThumbnailIFDs()} ()} instead. */
    @Deprecated
    public IFDList getThumbnailIFDs() throws IOException {
        final IFDList ifds = getIFDs();
        final IFDList thumbnails = new IFDList();
        for (final IFD ifd : ifds) {
            final Number subfile = (Number) ifd.getIFDValue(IFD.NEW_SUBFILE_TYPE);
            final int subfileType = subfile == null ? 0 : subfile.intValue();
            if (subfileType == 1) {
                thumbnails.add(ifd);
            }
        }
        return thumbnails;
    }

    /** Use {@link #allNonThumbnailIFDs()} ()} instead. */
    @Deprecated
    public IFDList getNonThumbnailIFDs() throws IOException {
        final IFDList ifds = getIFDs();
        final IFDList nonThumbs = new IFDList();
        for (final IFD ifd : ifds) {
            final Number subfile = (Number) ifd.getIFDValue(IFD.NEW_SUBFILE_TYPE);
            final int subfileType = subfile == null ? 0 : subfile.intValue();
            if (subfileType != 1 || ifds.size() <= 1) {
                nonThumbs.add(ifd);
            }
        }
        return nonThumbs;
    }

    /** Use {@link #allExifIFDs()} instead. */
    @Deprecated
    public IFDList getExifIFDs() throws FormatException, IOException {
        final IFDList ifds = getIFDs();
        final IFDList exif = new IFDList();
        for (final IFD ifd : ifds) {
            final long offset = ifd.getIFDLongValue(IFD.EXIF, 0);
            if (offset != 0) {
                final IFD exifIFD = getIFD(offset);
                if (exifIFD != null) {
                    exif.add(exifIFD);
                }
            }
        }
        return exif;
    }




    /**
     * Tests this stream to see if it represents a TIFF file.
     *
     * <p>Deprecated. Use {@link #isValid()} instead.
     */
    @Deprecated
    public boolean isValidHeader() {
        try {
            return checkHeader() != null;
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * Deprecated. Use {@link #isValid()} and {@link #isBigTiff()} instead.
     */
    @Deprecated
    public Boolean checkHeader() throws IOException {
        final DataHandle<Location> in = getStream();
        if (in.length() < 4) return null;

        // byte order must be II or MM
        in.seek(0);
        final int endianOne = in.read();
        final int endianTwo = in.read();
        final boolean littleEndian = endianOne == TiffConstants.LITTLE &&
                endianTwo == TiffConstants.LITTLE; // II
        final boolean bigEndian = endianOne == TiffConstants.BIG &&
                endianTwo == TiffConstants.BIG; // MM
        if (!littleEndian && !bigEndian) return null;

        // check magic number (42)
        in.setLittleEndian(littleEndian);
        final short magic = in.readShort();
        // bigTiff = magic == TiffConstants.BIG_TIFF_MAGIC_NUMBER;
        // - already set by the constructor
        if (magic != TiffConstants.MAGIC_NUMBER &&
                magic != TiffConstants.BIG_TIFF_MAGIC_NUMBER) {
            return null;
        }

        return littleEndian;
    }


    @Deprecated
    public long[] getIFDOffsets() throws IOException {
        return super.readIFDOffsets();
    }

    @Deprecated
    public long getFirstOffset() throws IOException {
        return super.readFirstIFDOffset();
    }

    /** Use {@link #firstIFD()} instead. */
    @Deprecated
    public IFD getFirstIFD() throws IOException {
        final boolean doCaching = isCachingIFDs();

        if (firstIFD != null) return firstIFD;
        final long offset = getFirstOffset();
        final IFD ifd = getIFD(offset);
        if (doCaching) firstIFD = ifd;
        return ifd;
    }

    /**
     * Use {@link #readIFDAt(long)} instead.
     */
    @Deprecated
    public IFD getIFD(long offset) throws IOException {
        final DataHandle<Location> in = getStream();
        final boolean bigTiff = isBigTiff();
        final boolean doCaching = isCachingIFDs();

        if (offset < 0 || offset >= in.length()) return null;
        final IFD ifd = new DetailedIFD();

        // save little-endian flag to internal LITTLE_ENDIAN tag
        ifd.put(IFD.LITTLE_ENDIAN, Boolean.valueOf(in
                .isLittleEndian()));
        ifd.put(IFD.BIG_TIFF, Boolean.valueOf(bigTiff));

        // read in directory entries for this IFD
//        log.trace("getIFDs: seeking IFD at " + offset);
        in.seek(offset);
        final long numEntries = bigTiff ? in.readLong() : in.readUnsignedShort();
//        log.trace("getIFDs: " + numEntries + " directory entries to read");
        if (numEntries == 0 || numEntries == 1) return ifd;

        final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY
                : TiffConstants.BYTES_PER_ENTRY;
        final int baseOffset = bigTiff ? 8 : 2;

        for (int i = 0; i < numEntries; i++) {
            in.seek(offset + baseOffset + bytesPerEntry * i);

            TiffIFDEntry entry = null;
            try {
                entry = readTiffIFDEntry();
            }
            catch (final EnumException e) {
//                log.debug("", e);
            }
            if (entry == null) break;
            int count = entry.getValueCount();
            final int tag = entry.getTag();
            final long pointer = entry.getValueOffset();
            final int bpe = entry.getType().getBytesPerElement();

            if (count < 0 || bpe <= 0) {
                // invalid data
                in.skipBytes(bytesPerEntry - 4 - (bigTiff ? 8 : 4));
                continue;
            }
            Object value = null;

            final long inputLen = in.length();
            if (count * bpe + pointer > inputLen) {
                final int oldCount = count;
                count = (int) ((inputLen - pointer) / bpe);
//                log.trace("getIFDs: truncated " + (oldCount - count) +
//                        " array elements for tag " + tag);
                if (count < 0) count = oldCount;
                entry = new TiffIFDEntry(entry.getTag(), entry.getType(), count, entry
                        .getValueOffset());
            }
            if (count < 0 || count > in.length()) break;

            if (pointer != in.offset() && !doCaching) {
                value = entry;
            }
            else value = getIFDValue(entry);

            if (value != null && !ifd.containsKey(tag)) {
                ifd.put(tag, value);
            }
        }

        in.seek(offset + baseOffset + bytesPerEntry * numEntries);

        return ifd;
    }

    /** Fill in IFD entries that are stored at an arbitrary offset. */
    @Deprecated
    public void fillInIFD(final IFD ifd) throws IOException {
        final HashSet<TiffIFDEntry> entries = new HashSet<>();
        for (final Object key : ifd.keySet()) {
            if (ifd.get(key) instanceof TiffIFDEntry) {
                entries.add((TiffIFDEntry) ifd.get(key));
            }
        }

        for (final TiffIFDEntry entry : entries) {
            if (entry.getValueCount() < 10 * 1024 * 1024 || entry.getTag() < 32768) {
                ifd.put(new Integer(entry.getTag()), getIFDValue(entry));
            }
        }
    }


    @Deprecated
    @SuppressWarnings("removal")
    public Object getIFDValue(final TiffIFDEntry entry) throws IOException {
        DataHandle<Location> in = getStream();
        final IFDType type = entry.getType();
        final int count = entry.getValueCount();
        final long offset = entry.getValueOffset();

//            log.trace("Reading entry " + entry.getTag() + " from " + offset +
//                    "; type=" + type + ", count=" + count);

        if (offset >= in.length()) {
            return null;
        }

        if (offset != in.offset()) {
            in.seek(offset);
        }

        if (type == IFDType.BYTE) {
            // 8-bit unsigned integer
            if (count == 1) return new Short(in.readByte());
            final byte[] bytes = new byte[count];
            in.readFully(bytes);
            // bytes are unsigned, so use shorts
            final short[] shorts = new short[count];
            for (int j = 0; j < count; j++)
                shorts[j] = (short) (bytes[j] & 0xff);
            return shorts;
        } else if (type == IFDType.ASCII) {
            // 8-bit byte that contain a 7-bit ASCII code;
            // the last byte must be NUL (binary zero)
            final byte[] ascii = new byte[count];
            in.read(ascii);

            // count number of null terminators
            int nullCount = 0;
            for (int j = 0; j < count; j++) {
                if (ascii[j] == 0 || j == count - 1) nullCount++;
            }

            // convert character array to array of strings
            final String[] strings = nullCount == 1 ? null : new String[nullCount];
            String s = null;
            int c = 0, ndx = -1;
            for (int j = 0; j < count; j++) {
                if (ascii[j] == 0) {
                    s = new String(ascii, ndx + 1, j - ndx - 1, Constants.ENCODING);
                    ndx = j;
                } else if (j == count - 1) {
                    // handle non-null-terminated strings
                    s = new String(ascii, ndx + 1, j - ndx, Constants.ENCODING);
                } else s = null;
                if (strings != null && s != null) strings[c++] = s;
            }
            return strings == null ? (Object) s : strings;
        } else if (type == IFDType.SHORT) {
            // 16-bit (2-byte) unsigned integer
            if (count == 1) return new Integer(in.readUnsignedShort());
            final int[] shorts = new int[count];
            for (int j = 0; j < count; j++) {
                shorts[j] = in.readUnsignedShort();
            }
            return shorts;
        } else if (type == IFDType.LONG || type == IFDType.IFD) {
            // 32-bit (4-byte) unsigned integer
            if (count == 1) return new Long(in.readInt());
            final long[] longs = new long[count];
            for (int j = 0; j < count; j++) {
                if (in.offset() + 4 <= in.length()) {
                    longs[j] = in.readInt();
                }
            }
            return longs;
        } else if (type == IFDType.LONG8 || type == IFDType.SLONG8 ||
                type == IFDType.IFD8) {
            if (count == 1) return new Long(in.readLong());
            long[] longs = null;
            boolean equalStrips = isAssumeEqualStrips();
            if (equalStrips && (entry.getTag() == IFD.STRIP_BYTE_COUNTS || entry
                    .getTag() == IFD.TILE_BYTE_COUNTS)) {
                longs = new long[1];
                longs[0] = in.readLong();
            } else if (equalStrips && (entry.getTag() == IFD.STRIP_OFFSETS || entry
                    .getTag() == IFD.TILE_OFFSETS)) {
                final OnDemandLongArray offsets = new OnDemandLongArray(in);
                offsets.setSize(count);
                return offsets;
            } else {
                longs = new long[count];
                for (int j = 0; j < count; j++)
                    longs[j] = in.readLong();
            }
            return longs;
        } else if (type == IFDType.RATIONAL || type == IFDType.SRATIONAL) {
            // Two LONGs or SLONGs: the first represents the numerator
            // of a fraction; the second, the denominator
            if (count == 1) return new TiffRational(in.readInt(), in.readInt());
            final TiffRational[] rationals = new TiffRational[count];
            for (int j = 0; j < count; j++) {
                rationals[j] = new TiffRational(in.readInt(), in.readInt());
            }
            return rationals;
        } else if (type == IFDType.SBYTE || type == IFDType.UNDEFINED) {
            // SBYTE: An 8-bit signed (twos-complement) integer
            // UNDEFINED: An 8-bit byte that may contain anything,
            // depending on the definition of the field
            if (count == 1) return new Byte(in.readByte());
            final byte[] sbytes = new byte[count];
            in.read(sbytes);
            return sbytes;
        } else if (type == IFDType.SSHORT) {
            // A 16-bit (2-byte) signed (twos-complement) integer
            if (count == 1) return new Short(in.readShort());
            final short[] sshorts = new short[count];
            for (int j = 0; j < count; j++)
                sshorts[j] = in.readShort();
            return sshorts;
        } else if (type == IFDType.SLONG) {
            // A 32-bit (4-byte) signed (twos-complement) integer
            if (count == 1) return new Integer(in.readInt());
            final int[] slongs = new int[count];
            for (int j = 0; j < count; j++)
                slongs[j] = in.readInt();
            return slongs;
        } else if (type == IFDType.FLOAT) {
            // Single precision (4-byte) IEEE format
            if (count == 1) return new Float(in.readFloat());
            final float[] floats = new float[count];
            for (int j = 0; j < count; j++)
                floats[j] = in.readFloat();
            return floats;
        } else if (type == IFDType.DOUBLE) {
            // Double precision (8-byte) IEEE format
            if (count == 1) return new Double(in.readDouble());
            final double[] doubles = new double[count];
            for (int j = 0; j < count; j++) {
                doubles[j] = in.readDouble();
            }
            return doubles;
        }
        return null;
    }



    /**
     * Retrieve a given entry from the first IFD in the stream.
     *
     * @param tag the tag of the entry to be retrieved.
     * @return an object representing the entry's fields.
     * @throws IOException              when there is an error accessing the stream.
     * @throws IllegalArgumentException when the tag number is unknown.
     */
    // TODO : Try to remove this method. It is only being used by
    // loci.formats.in.MetamorphReader.
    @Deprecated
    public TiffIFDEntry getFirstIFDEntry(final int tag) throws IOException {
        // Get the offset of the first IFD
        final long offset = readFirstIFDOffset();
        if (offset < 0) {
            return null;
        }

        final DataHandle<Location> in = getStream();
        final boolean bigTiff = isBigTiff();

        // The following loosely resembles the logic of readIFD()...
        in.seek(offset);
        final long numEntries = bigTiff ? in.readLong() : in.readUnsignedShort();

        for (int i = 0; i < numEntries; i++) {
            in.seek(offset + // The beginning of the IFD
                    (bigTiff ? 8 : 2) + // The width of the initial numEntries field
                    (bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY
                            : TiffConstants.BYTES_PER_ENTRY) * (long) i);

            final TiffIFDEntry entry = readTiffIFDEntry();
            if (entry.getTag() == tag) {
                return entry;
            }
        }
        throw new IllegalArgumentException("Unknown tag: " + tag);
    }


    @Deprecated
    public byte[] getTile(final IFD ifd, byte[] buf, int row, final int col)
            throws FormatException, IOException {
        TiffMap map = new TiffMap(DetailedIFD.extend(ifd));
        int planeIndex = 0;
        if (map.isPlanarSeparated()) {
            planeIndex = row / map.gridTileCountY();
            row = row % map.gridTileCountY();
            // - in terms of the old TiffParser, "row" index already contains index of the plane
        }
        TiffTileIndex tileIndex = map.multiplaneIndex(planeIndex, col, row);
        if (buf == null) {
            buf = new byte[map.tileSizeInBytes()];
        }
        TiffTile tile = readTile(tileIndex);
        if (!tile.isEmpty()) {
            byte[] data = tile.getDecoded();
            System.arraycopy(data, 0, buf, 0, data.length);
        }
        return buf;
    }

    @Deprecated
    public byte[] getSamples(final IFD ifd, final byte[] buf)
            throws FormatException, IOException {
        final long width = ifd.getImageWidth();
        final long length = ifd.getImageLength();
        return getSamples(ifd, buf, 0, 0, width, length);
    }


    /**
     * This function is deprecated, because almost identical behaviour is implemented by
     * {@link #readImage(DetailedIFD, int, int, int, int)}.
     */
    @Deprecated
    public byte[] getSamples(final IFD ifd, final byte[] buf, final int x,
                             final int y, final long width, final long height) throws FormatException,
            IOException {
        TiffTools.checkRequestedArea(x, y, width, height);
        byte[] result = readImage(DetailedIFD.extend(ifd), x, y, (int) width, (int) height);
        if (result.length > buf.length) {
            throw new IllegalArgumentException(
                    "Insufficient length of the result buf array: " +
                            buf.length + " < necessary " + result.length + " bytes");
        }
        System.arraycopy(result, 0, buf, 0, result.length);
        return buf;
    }

    /**
     * This function is deprecated, because it is almost not used - the only exception is
     * TrestleReader from OME BioFormats.
     */
    @Deprecated
    public byte[] getSamples(final IFD ifd, final byte[] buf, final int x,
                             final int y, final long width, final long height, final int overlapX,
                             final int overlapY) throws FormatException, IOException {
        final DataHandle<Location> in = getStream();
        // get internal non-IFD entries
        in.setLittleEndian(ifd.isLittleEndian());

        // get relevant IFD entries
        final int samplesPerPixel = ifd.getSamplesPerPixel();
        final long tileWidth = ifd.getTileWidth();
        long tileLength = ifd.getTileLength();
        if (tileLength <= 0) {
//            log.trace("Tile length is " + tileLength + "; setting it to " + height);
            tileLength = height;
        }

        long numTileRows = ifd.getTilesPerColumn();
        final long numTileCols = ifd.getTilesPerRow();

        final PhotoInterp photoInterp = ifd.getPhotometricInterpretation();
        final int planarConfig = ifd.getPlanarConfiguration();
        final int pixel = ifd.getBytesPerSample()[0];
        final int effectiveChannels = planarConfig == 2 ? 1 : samplesPerPixel;

//        if (log.isTrace()) {
//            ifd.printIFD();
//        }

        if (width * height > Integer.MAX_VALUE) {
            throw new FormatException(
                    "Sorry, ImageWidth x ImageLength > " +
                            Integer.MAX_VALUE + " is not supported (" + width + " x " + height +
                            ")");
        }
        if (width * height * effectiveChannels * pixel > Integer.MAX_VALUE) {
            throw new FormatException(
                    "Sorry, ImageWidth x ImageLength x " +
                            "SamplesPerPixel x BitsPerSample > " + Integer.MAX_VALUE +
                            " is not supported (" + width + " x " + height + " x " +
                            samplesPerPixel + " x " + (pixel * 8) + ")");
        }

        // casting to int is safe because we have already determined that
        // width * height is less than Integer.MAX_VALUE
        final int numSamples = (int) (width * height);

        // read in image strips
        final TiffCompression compression = ifd.getCompression();

        CodecOptions codecOptions = this.getCodecOptions();
        if (compression == TiffCompression.JPEG_2000 ||
                compression == TiffCompression.JPEG_2000_LOSSY) {
            codecOptions = compression.getCompressionCodecOptions(ifd, codecOptions);
        } else codecOptions = compression.getCompressionCodecOptions(ifd);
        codecOptions.interleaved = true;
        codecOptions.littleEndian = ifd.isLittleEndian();
        this.setCodecOptions(codecOptions);
        final long imageLength = ifd.getImageLength();

        // special case: if we only need one tile, and that tile doesn't need
        // any special handling, then we can just read it directly and return
        if ((x % tileWidth) == 0 && (y % tileLength) == 0 && width == tileWidth &&
                height == imageLength && samplesPerPixel == 1 &&
                (ifd.getBitsPerSample()[0] % 8) == 0 &&
                photoInterp != PhotoInterp.WHITE_IS_ZERO &&
                photoInterp != PhotoInterp.CMYK && photoInterp != PhotoInterp.Y_CB_CR &&
                compression == TiffCompression.UNCOMPRESSED) {
            final long[] stripOffsets = ifd.getStripOffsets();
            final long[] stripByteCounts = ifd.getStripByteCounts();

            if (stripOffsets != null && stripByteCounts != null) {
                final long column = x / tileWidth;
                final int firstTile = (int) ((y / tileLength) * numTileCols + column);
                int lastTile = (int) (((y + height) / tileLength) * numTileCols +
                        column);
                lastTile = Math.min(lastTile, stripOffsets.length - 1);

                int offset = 0;
                for (int tile = firstTile; tile <= lastTile; tile++) {
                    long byteCount = isAssumeEqualStrips() ? stripByteCounts[0]
                            : stripByteCounts[tile];
                    if (byteCount == numSamples && pixel > 1) {
                        byteCount *= pixel;
                    }

                    in.seek(stripOffsets[tile]);
                    final int len = (int) Math.min(buf.length - offset, byteCount);
                    in.read(buf, offset, len);
                    offset += len;
                }
            }
            return adjustFillOrder(ifd, buf);
        }

        final long nrows = numTileRows;
        if (planarConfig == 2) numTileRows *= samplesPerPixel;

        final IntRect imageBounds = new IntRect(x, y, (int) width, (int) height);

        final int endX = (int) width + x;
        final int endY = (int) height + y;

        final long w = tileWidth;
        final long h = tileLength;
        final int rowLen = pixel * (int) w;// tileWidth;
        final int tileSize = (int) (rowLen * h);// tileLength);

        final int planeSize = (int) (width * height * pixel);
        final int outputRowLen = (int) (pixel * width);

        int bufferSizeSamplesPerPixel = samplesPerPixel;
        if (ifd.getPlanarConfiguration() == 2) bufferSizeSamplesPerPixel = 1;
        final int bpp = ifd.getBytesPerSample()[0];
        final int bufferSize = (int) tileWidth * (int) tileLength *
                bufferSizeSamplesPerPixel * bpp;

        byte[] cachedTileBuffer = new byte[bufferSize];

        final IntRect tileBounds = new IntRect(0, 0, (int) tileWidth,
                (int) tileLength);

        for (int row = 0; row < numTileRows; row++) {
            // make the first row shorter to account for row overlap
            if (row == 0) {
                tileBounds.height = (int) (tileLength - overlapY);
            }

            for (int col = 0; col < numTileCols; col++) {
                // make the first column narrower to account for column overlap
                if (col == 0) {
                    tileBounds.width = (int) (tileWidth - overlapX);
                }

                tileBounds.x = col * (int) (tileWidth - overlapX);
                tileBounds.y = row * (int) (tileLength - overlapY);

                if (planarConfig == 2) {
                    tileBounds.y = (int) ((row % nrows) * (tileLength - overlapY));
                }

                if (!imageBounds.intersects(tileBounds)) continue;

                getTile(ifd, cachedTileBuffer, row, col);

                // adjust tile bounds, if necessary

                final int tileX = Math.max(tileBounds.x, x);
                final int tileY = Math.max(tileBounds.y, y);
                int realX = tileX % (int) (tileWidth - overlapX);
                int realY = tileY % (int) (tileLength - overlapY);

                int twidth = (int) Math.min(endX - tileX, tileWidth - realX);
                if (twidth <= 0) {
                    twidth = (int) Math.max(endX - tileX, tileWidth - realX);
                }
                int theight = (int) Math.min(endY - tileY, tileLength - realY);
                if (theight <= 0) {
                    theight = (int) Math.max(endY - tileY, tileLength - realY);
                }

                // copy appropriate portion of the tile to the output buffer

                final int copy = pixel * twidth;

                realX *= pixel;
                realY *= rowLen;

                for (int q = 0; q < effectiveChannels; q++) {
                    int src = q * tileSize + realX + realY;
                    int dest = q * planeSize + pixel * (tileX - x) + outputRowLen * (tileY - y);
                    if (planarConfig == 2) dest += (planeSize * (row / nrows));

                    // copying the tile directly will only work if there is no
                    // overlap;
                    // otherwise, we may be overwriting a previous tile
                    // (or the current tile may be overwritten by a subsequent
                    // tile)
                    if (rowLen == outputRowLen && overlapX == 0 && overlapY == 0) {
                        //!! Note: here is a bug! It is possible that x != 0!
                        System.arraycopy(cachedTileBuffer, src, buf, dest, copy * theight);
                    } else {
                        for (int tileRow = 0; tileRow < theight; tileRow++) {
                            System.arraycopy(cachedTileBuffer, src, buf, dest, copy);
                            src += rowLen;
                            dest += outputRowLen;
                        }
                    }
                }
            }
        }

        return adjustFillOrder(ifd, buf);
    }

    // Equivalent method in TiffReader became private: no reasons to declare it public
    @Deprecated
    public TiffIFDEntry readTiffIFDEntry() throws IOException {
        DataHandle<Location> in = getStream();
        final int entryTag = in.readUnsignedShort();

        // Parse the entry's "Type"
        IFDType entryType;
        try {
            entryType = IFDType.get(in.readUnsignedShort());
        } catch (final EnumException e) {
//            log.error("Error reading IFD type at: " + in.offset());
            throw e;
        }

        // Parse the entry's "ValueCount"
        final int valueCount = isBigTiff() ? (int) in.readLong() : in.readInt();
        if (valueCount < 0) {
            throw new RuntimeException("Count of '" + valueCount + "' unexpected.");
        }

        final int nValueBytes = valueCount * entryType.getBytesPerElement();
        final int threshhold = isBigTiff() ? 8 : 4;
        final long offset = nValueBytes > threshhold ? getNextOffset(0) : in
                .offset();

        return new TiffIFDEntry(entryTag, entryType, valueCount, offset);
    }


    @Deprecated
    private byte[] adjustFillOrder(final IFD ifd, final byte[] buf)
            throws FormatException {
        TiffTools.invertFillOrderIfRequested(ifd, buf);
        return buf;
    }

    // This trick is deprecated
    @Deprecated
    private long getNextOffset(final long previous) throws IOException {
        DataHandle<Location> in = getStream();
        if (isBigTiff() || fakeBigTiff) {
            // ?? getFirstOffset did not check fakeBigTiff, so it cannot work! - Daniel Alievsky
            return in.readLong();
        }
        long offset = (previous & ~0xffffffffL) | (in.readInt() & 0xffffffffL);

        // Only adjust the offset if we know that the file is too large for
        // 32-bit
        // offsets to be accurate; otherwise, we're making the incorrect
        // assumption
        // that IFDs are stored sequentially.
        if (offset < previous && offset != 0 && in.length() > Integer.MAX_VALUE) {
            offset += 0x100000000L;
        }
        return offset;
    }

    /* Deprecated code:
    public IFD getIFD(final long offset) throws IOException {
        if (offset < 0 || offset >= in.length()) return null;
        final IFD ifd = new IFD(log);

        // save little-endian flag to internal LITTLE_ENDIAN tag
        ifd.put(IFD.LITTLE_ENDIAN, in.isLittleEndian());
        ifd.put(IFD.BIG_TIFF, bigTiff);

        // read in directory entries for this IFD
        log.trace("getIFDs: seeking IFD at " + offset);
        in.seek(offset);
        final long numEntries = bigTiff ? in.readLong() : in.readUnsignedShort();
        log.trace("getIFDs: " + numEntries + " directory entries to read");
        if (numEntries == 0 || numEntries == 1) return ifd;

        final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY
                : TiffConstants.BYTES_PER_ENTRY;
        final int baseOffset = bigTiff ? 8 : 2;

        for (int i = 0; i < numEntries; i++) {
            in.seek(offset + baseOffset + bytesPerEntry * i);

            TiffIFDEntry entry = null;
            try {
                entry = readTiffIFDEntry();
            } catch (final EnumException e) {
                log.debug("", e);
            }
            if (entry == null) break;
            int count = entry.getValueCount();
            final int tag = entry.getTag();
            final long pointer = entry.getValueOffset();
            final int bpe = entry.getType().getBytesPerElement();

            if (count < 0 || bpe <= 0) {
                // invalid data
                in.skipBytes(bytesPerEntry - 4 - (bigTiff ? 8 : 4));
                continue;
            }
            Object value = null;

            final long inputLen = in.length();
            if (count * (long) bpe + pointer > inputLen) {
                final int oldCount = count;
                count = (int) ((inputLen - pointer) / bpe);
                log.trace("getIFDs: truncated " + (oldCount - count) +
                        " array elements for tag " + tag);
                if (count < 0) count = oldCount;
                entry = new TiffIFDEntry(entry.getTag(), entry.getType(), count, entry
                        .getValueOffset());
            }
            if (count < 0 || count > in.length()) break;

            if (pointer != in.offset() && !doCaching) {
                value = entry;
            } else value = getIFDValue(entry);

            if (value != null && !ifd.containsKey(tag)) {
                ifd.put(tag, value);
            }
        }

        in.seek(offset + baseOffset + bytesPerEntry * numEntries);

        return ifd;
    }
    */

    static IFDList toIFDList(List<? extends IFD> ifds) {
        final IFDList result = new IFDList();
        result.addAll(ifds);
        return result;
    }
}
