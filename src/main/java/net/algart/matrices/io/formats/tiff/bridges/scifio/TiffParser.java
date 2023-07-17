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
import io.scif.SCIFIO;
import io.scif.codec.BitBuffer;
import io.scif.codec.Codec;
import io.scif.codec.CodecOptions;
import io.scif.common.Constants;
import io.scif.enumeration.EnumException;
import io.scif.formats.tiff.*;
import net.algart.matrices.io.formats.tiff.bridges.scifio.codecs.ExtendedJPEG2000Codec;
import org.scijava.AbstractContextual;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.location.Location;
import org.scijava.util.Bytes;
import org.scijava.util.IntRect;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses TIFF data from an input source.
 *
 * @author Curtis Rueden
 * @author Eric Kjellman
 * @author Melissa Linkert
 * @author Chris Allan
 * @author Denial Alievsky
 */
public class TiffParser extends AbstractContextual implements Closeable {

    // Below is a copy of SCIFIO license (placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * #%L
     * SCIFIO library for reading and converting scientific file formats.
     * %%
     * Copyright (C) 2011 - 2023 SCIFIO developers.
     * %%
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
     * POSSIBILITY OF SUCH DAMAGE.
     * #L%
     */

    private static final System.Logger LOG = System.getLogger(TiffParser.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);
    private static final boolean LOGGABLE_TRACE = LOG.isLoggable(System.Logger.Level.TRACE);

    // -- Fields --

    /**
     * Input source from which to parse TIFF data.
     */
    private final DataHandle<Location> in;

    /**
     * Cached tile buffer to avoid re-allocations when reading tiles.
     */
    private byte[] cachedTileBuffer;

    /**
     * Whether or not the TIFF file contains BigTIFF data.
     */
    private boolean bigTiff;

    /**
     * Filler byte for tiles, completely outside the image.
     */
    private byte filler = 0;

    private boolean requireValidTiff;

    private boolean autoInterleave = false;

    private boolean autoUnpackUnusualPrecisions = false;

    private boolean extendedCodec = false;

    /**
     * Whether or not 64-bit offsets are used for non-BigTIFF files.
     */
    private boolean use64BitOffsets = false;

    private boolean yCbCrCorrection = true;

    private boolean assumeEqualStrips = false;

    private boolean doCaching;

    /**
     * Cached list of IFDs in the current file.
     */
    private List<DetailedIFD> ifdList;

    /**
     * Cached first IFD in the current file.
     */
    private IFD firstIFD;

    private volatile long positionOfLastOffset = -1;

    private final SCIFIO scifio;

    /**
     * Codec options to be used when decoding compressed pixel data.
     */
    private CodecOptions codecOptions = CodecOptions.getDefaultOptions();

    // -- Constructors --

    public TiffParser(final Context context, final Location location) {
        this(context, context.getService(DataHandleService.class).create(location));
    }

    /**
     * Constructs a new TIFF parser from the given file location.
     */
    public TiffParser(Context context, Location location, boolean requireValidTiff) throws IOException {
        this(context, TiffTools.getDataHandle(context, location), requireValidTiff);
    }

    public TiffParser(Context context, DataHandle<Location> in) {
        Objects.requireNonNull(in, "Null in stream");
        this.requireValidTiff = false;
        if (context != null) {
            setContext(context);
            scifio = new SCIFIO(context);
        } else {
            scifio = null;
        }
        this.in = in;
        doCaching = true;
        try {
            final long fp = in.offset();
            checkHeader();
            in.seek(fp);
        } catch (IOException ignored) {
        }
    }

    /**
     * Constructs a new TIFF parser from the given input source.
     */
    public TiffParser(Context context, DataHandle<Location> in, boolean requireValidTiff) throws IOException {
        Objects.requireNonNull(in, "Null in stream");
        this.requireValidTiff = requireValidTiff;
        if (context != null) {
            setContext(context);
            scifio = new SCIFIO(context);
        } else {
            scifio = null;
        }
        this.in = in;
        doCaching = true;
        try {
            final long fp = in.offset();
            checkHeader();
            in.seek(fp);
        } catch (IOException e) {
            if (requireValidTiff) {
                throw e;
            }
        }
    }

    public static TiffParser getInstance(Context context, DataHandle<Location> dataHandle, boolean requireValidTiff)
            throws IOException {
        return new TiffParser(context, dataHandle, requireValidTiff)
                .setAutoUnpackUnusualPrecisions(true)
                .setExtendedCodec(true);
    }

    public static TiffParser getInstance(Context context, Location location, boolean requireValidTiff)
            throws IOException {
        return getInstance(context, TiffTools.getDataHandle(context, location), requireValidTiff);
    }

    public static TiffParser getInstance(final Context context, Path file, boolean requireValidTiff)
            throws IOException {
        return getInstance(context, TiffTools.existingFileToLocation(file), requireValidTiff);
    }

    public static TiffParser getInstance(final Context context, Path file) throws IOException {
        return getInstance(context, file, true);
    }


    // -- TiffParser methods --


    public byte getFiller() {
        return filler;
    }

    /**
     * Sets the filler byte for tiles, lying completely outside the image.
     * Value 0 means black color, 0xFF usually means white color.
     *
     * <p><b>Warning!</b> If you want to work with non-8-bit TIFF, especially float precision, you should
     * preserve default 0 value, in other case results could be very strange.
     *
     * @param filler new filler.
     * @return a reference to this object.
     */
    public TiffParser setFiller(byte filler) {
        this.filler = filler;
        return this;
    }

    public boolean isRequireValidTiff() {
        return requireValidTiff;
    }

    /**
     * Sets whether the parser should always require valid TIFF format.
     * Default value is specified in the constructor or is <tt>false</tt>
     * when using a constructor without such argument.
     *
     * @param requireValidTiff whether TIFF file should be correct.
     * @return a reference to this object.
     */
    public TiffParser setRequireValidTiff(boolean requireValidTiff) {
        this.requireValidTiff = requireValidTiff;
        return this;
    }

    public boolean isAutoInterleave() {
        return autoInterleave;
    }

    /**
     * Sets the interleave mode: the loaded samples will be returned in chunked form, for example, RGBRGBRGB...
     * in a case of RGB image. If not set (default behaviour), the samples are returned in unpacked form:
     * RRR...GGG...BBB...
     *
     * @param autoInterleave new interleavcing mode.
     * @return a reference to this object.
     */
    public TiffParser setAutoInterleave(boolean autoInterleave) {
        this.autoInterleave = autoInterleave;
        return this;
    }

    public boolean isAutoUnpackUnusualPrecisions() {
        return autoUnpackUnusualPrecisions;
    }

    public TiffParser setAutoUnpackUnusualPrecisions(boolean autoUnpackUnusualPrecisions) {
        this.autoUnpackUnusualPrecisions = autoUnpackUnusualPrecisions;
        return this;
    }

    public boolean isExtendedCodec() {
        return extendedCodec;
    }

    public TiffParser setExtendedCodec(boolean extendedCodec) {
        this.extendedCodec = extendedCodec;
        return this;
    }

    /**
     * Sets whether or not to assume that strips are of equal size.
     *
     * @param assumeEqualStrips Whether or not the strips are of equal size.
     * @return a reference to this object.
     */
    public TiffParser setAssumeEqualStrips(final boolean assumeEqualStrips) {
        this.assumeEqualStrips = assumeEqualStrips;
        return this;
    }

    public boolean isAssumeEqualStrips() {
        return assumeEqualStrips;
    }

    /**
     * Sets the codec options to be used when decompressing pixel data.
     *
     * @param codecOptions Codec options to use.
     * @return a reference to this object.
     */
    public TiffParser setCodecOptions(final CodecOptions codecOptions) {
        this.codecOptions = codecOptions;
        return this;
    }

    /**
     * Retrieves the current set of codec options being used to decompress pixel
     * data.
     *
     * @return See above.
     */
    public CodecOptions getCodecOptions() {
        return codecOptions;
    }

    /**
     * Sets whether or not IFD entries should be cached.
     */
    public TiffParser setDoCaching(final boolean doCaching) {
        this.doCaching = doCaching;
        return this;
    }

    public boolean isDoCaching() {
        return doCaching;
    }

    /**
     * Sets whether or not 64-bit offsets are used for non-BigTIFF files.
     */
    public TiffParser setUse64BitOffsets(final boolean use64BitOffsets) {
        this.use64BitOffsets = use64BitOffsets;
        return this;
    }

    public boolean isUse64BitOffsets() {
        return use64BitOffsets;
    }

    /**
     * Sets whether or not YCbCr color correction is allowed.
     */
    public TiffParser setYCbCrCorrection(final boolean yCbCrCorrection) {
        this.yCbCrCorrection = yCbCrCorrection;
        return this;
    }

    public boolean isYCbCrCorrection() {
        return yCbCrCorrection;
    }

    /**
     * Gets the stream from which TIFF data is being parsed.
     */
    public DataHandle<Location> getStream() {
        return in;
    }

    /**
     * Returns position in the file of the last offset, loaded by {@link #getNextOffset(long)} method.
     * Usually it is just a position of the offset of the last IFD.
     *
     * @return file position of the last IFD offset.
     */
    public long getPositionOfLastOffset() {
        return positionOfLastOffset;
    }

    /**
     * Tests this stream to see if it represents a TIFF file.
     */
    public boolean isValidHeader() {
        try {
            return checkHeader() != null;
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * Checks the TIFF header.
     *
     * @return true if little-endian, false if big-endian, or null if not a TIFF.
     */
    public Boolean checkHeader() throws IOException {
        if (requireValidTiff && !in.exists()) {
            throw new FileNotFoundException("Input TIFF data" + prettyInName() + " does not exist");
        }
        if (in.length() < 8) {
            if (requireValidTiff) {
                throw new IOException("Too short TIFF file" + prettyInName() + ": only " + in.length() + " bytes");
            } else {
                return null;
            }
        }

        // byte order must be II or MM
        in.seek(0);
        final int endianOne = in.read();
        final int endianTwo = in.read();
        final boolean littleEndian = endianOne == TiffConstants.LITTLE && endianTwo == TiffConstants.LITTLE; // II
        final boolean bigEndian = endianOne == TiffConstants.BIG && endianTwo == TiffConstants.BIG; // MM
        if (!littleEndian && !bigEndian) {
            if (requireValidTiff) {
                throw new IOException("The file" + prettyInName() + " is not TIFF");
            } else {
                return null;
            }
        }

        // check magic number (42)
        in.setLittleEndian(littleEndian);
        final short magic = in.readShort();
        bigTiff = magic == TiffConstants.BIG_TIFF_MAGIC_NUMBER;
        if (magic != TiffConstants.MAGIC_NUMBER && magic != TiffConstants.BIG_TIFF_MAGIC_NUMBER) {
            if (requireValidTiff) {
                throw new IOException("The file" + prettyInName() + " is not TIFF");
            } else {
                return null;
            }
        }
        return littleEndian;
    }

    /**
     * Returns whether or not the current TIFF file contains BigTIFF data.
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Returns whether or not we are reading little-endian data.
     * Determined by {@link #checkHeader()}.
     */
    public boolean isLittleEndian() {
        return in.isLittleEndian();
    }

    // -- TiffParser methods - IFD parsing --

    public DetailedIFD ifd(int ifdIndex) throws IOException {
        List<DetailedIFD> ifdList = allIFD();
        if (ifdIndex < 0 || ifdIndex >= ifdList.size()) {
            throw new IndexOutOfBoundsException("IFD index " +
                    ifdIndex + " is out of bounds 0 <= i < " + ifdList.size());
        }
        return ifdList.get(ifdIndex);
    }

    /**
     * Returns all IFDs in the file.
     */
    public List<DetailedIFD> allIFD() throws IOException {
        if (ifdList != null) {
            return ifdList;
        }

        final long[] offsets = getIFDOffsets();
        final List<DetailedIFD> ifds = new ArrayList<>();

        for (final long offset : offsets) {
            final DetailedIFD ifd = getIFD(offset);
            if (ifd == null) {
                continue;
            }
            if (ifd.containsKey(IFD.IMAGE_WIDTH)) {
                ifds.add(ifd);
            }
            long[] subOffsets = null;
            try {
                if (!doCaching && ifd.containsKey(IFD.SUB_IFD)) {
                    fillInIFD(ifd);
                }
                subOffsets = ifd.getIFDLongArray(IFD.SUB_IFD);
            } catch (final FormatException ignored) {
            }
            if (subOffsets != null) {
                for (final long subOffset : subOffsets) {
                    final DetailedIFD sub = getIFD(subOffset, IFD.SUB_IFD);
                    if (sub != null) {
                        ifds.add(sub);
                    }
                }
            }
        }
        if (doCaching) {
            ifdList = ifds;
        }
        return ifds;
    }

    /**
     * Returns thumbnail IFDs.
     */
    public List<DetailedIFD> allThumbnailIFD() throws IOException {
        final List<DetailedIFD> ifds = allIFD();
        final List<DetailedIFD> thumbnails = new ArrayList<>();
        for (final DetailedIFD ifd : ifds) {
            final Number subfile = (Number) ifd.getIFDValue(IFD.NEW_SUBFILE_TYPE);
            final int subfileType = subfile == null ? 0 : subfile.intValue();
            if (subfileType == 1) {
                thumbnails.add(ifd);
            }
        }
        return thumbnails;
    }

    /**
     * Returns non-thumbnail IFDs.
     */
    public List<DetailedIFD> allNonThumbnailIFD() throws IOException {
        final List<DetailedIFD> ifds = allIFD();
        final List<DetailedIFD> nonThumbs = new ArrayList<>();
        for (final DetailedIFD ifd : ifds) {
            final Number subFile = (Number) ifd.getIFDValue(IFD.NEW_SUBFILE_TYPE);
            final int subfileType = subFile == null ? 0 : subFile.intValue();
            if (subfileType != 1 || ifds.size() <= 1) {
                nonThumbs.add(ifd);
            }
        }
        return nonThumbs;
    }

    /**
     * Returns EXIF IFDs.
     */
    public List<DetailedIFD> allExifIFD() throws FormatException, IOException {
        final List<DetailedIFD> ifds = allIFD();
        final List<DetailedIFD> exif = new ArrayList<>();
        for (final DetailedIFD ifd : ifds) {
            final long offset = ifd.getIFDLongValue(IFD.EXIF, 0);
            if (offset != 0) {
                final DetailedIFD exifIFD = getIFD(offset, IFD.EXIF);
                if (exifIFD != null) {
                    exif.add(exifIFD);
                }
            }
        }
        return exif;
    }

    /**
     * Gets the offsets to every IFD in the file.
     * Note: after calling this function, the file pointer in the input stream refers
     * to the last IFD offset in the file.
     */
    public long[] getIFDOffsets() throws IOException {
        // check TIFF header

        final long fileLength = in.length();
        final List<Long> offsets = new ArrayList<>();
        long offset = getFirstOffset();

        final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY : TiffConstants.BYTES_PER_ENTRY;
        while (offset > 0 && offset < fileLength) {
            in.seek(offset);
            offsets.add(offset);
            final long numberOfEntries = bigTiff ? in.readLong() : in.readUnsignedShort();
            if (numberOfEntries > Integer.MAX_VALUE / bytesPerEntry) {
                throw new IOException("Too many number of IFD entries in Big TIFF: " + numberOfEntries +
                        " (it is not supported, probably file is broken)");
            }
            long skippedIFDBytes = numberOfEntries * bytesPerEntry;
            if (offset + skippedIFDBytes >= in.length()) {
                throw new IOException("Invalid TIFF" + prettyInName() + ": position of next IFD offset " +
                        (offset + skippedIFDBytes) + " after " + numberOfEntries + " entries is outside the file" +
                        " (probably file is broken)");
            }
            in.skipBytes((int) skippedIFDBytes);
            offset = getNextOffset(offset);
        }

        final long[] f = new long[offsets.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = offsets.get(i);
        }

        return f;
    }

    /**
     * Gets the first IFD within the TIFF file, or null if the input source is not
     * a valid TIFF file.
     */
    public IFD getFirstIFD() throws IOException {
        if (firstIFD != null) {
            return firstIFD;
        }
        final long offset = getFirstOffset();
        final IFD ifd = getIFD(offset);
        if (doCaching) {
            firstIFD = ifd;
        }
        return ifd;
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
    public TiffIFDEntry getFirstIFDEntry(final int tag) throws IOException {
        // Get the offset of the first IFD
        final long offset = getFirstOffset();
        if (offset < 0) {
            return null;
        }

        // The following loosely resembles the logic of getIFD()...
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

    /**
     * Gets offset to the first IFD, or -1 if stream is not TIFF.
     */
    public long getFirstOffset() throws IOException {
        final Boolean header = checkHeader();
        if (header == null) {
            return -1;
        }
        if (bigTiff) {
            in.skipBytes(4);
        }
        return getNextOffset(0);
    }

    /**
     * Gets the IFD stored at the given offset.
     */
    public DetailedIFD getIFD(long offset) throws IOException {
        return getIFD(offset, null);
    }

    public DetailedIFD getIFD(long offset, Integer subIFDType) throws IOException {
        long t1 = System.nanoTime();
        if (offset < 0 || offset >= in.length()) {
            return null;
        }
        final Map<Integer, TiffIFDEntry> entries = new LinkedHashMap<>();
        final DetailedIFD ifd = new DetailedIFD(offset);
        ifd.setSubIFDType(subIFDType);

        // save little-endian flag to internal LITTLE_ENDIAN tag
        ifd.put(IFD.LITTLE_ENDIAN, in.isLittleEndian());
        ifd.put(IFD.BIG_TIFF, bigTiff);

        // read in directory entries for this IFD
        in.seek(offset);
        final long numEntries = bigTiff ? in.readLong() : in.readUnsignedShort();
        if (numEntries == 0) {
            //!! - In the SCIFIO core, this is performed also if numEntries == 1, but why?
            return ifd;
        }

        final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY : TiffConstants.BYTES_PER_ENTRY;
        final int baseOffset = bigTiff ? 8 : 2;

        for (int i = 0; i < numEntries; i++) {
            in.seek(offset + baseOffset + bytesPerEntry * (long) i);

            TiffIFDEntry entry;
            try {
                entry = readTiffIFDEntry();
            } catch (final EnumException e) {
                continue;
                //!! - In the previous SCIFIO code, here is "break" operator, but why?
                // Maybe this is a type, added in future versions of TIFF format.
            }
            int count = entry.getValueCount();
            final int tag = entry.getTag();
            final long pointer = entry.getValueOffset();
            final int bpe = entry.getType().getBytesPerElement();

            if (count < 0 || bpe <= 0) {
                // invalid data
                in.skipBytes(bytesPerEntry - 4 - (bigTiff ? 8 : 4));
                continue;
            }
            Object value;

            final long inputLen = in.length();
            if ((long) count * (long) bpe + pointer > inputLen) {
                final int oldCount = count;
                count = (int) ((inputLen - pointer) / bpe);
//                log.trace("getIFDs: truncated " + (oldCount - count) +
//                        " array elements for tag " + tag);
                if (count < 0) {
                    count = oldCount;
                }
                entry = new TiffIFDEntry(entry.getTag(), entry.getType(), count, entry.getValueOffset());
            }
            if (count > in.length()) {
                break;
            }

            if (pointer != in.offset() && !doCaching) {
                value = entry;
            } else {
                value = getIFDValue(entry);
            }
            if (value != null && !ifd.containsKey(tag)) {
                entries.put(tag, entry);
                ifd.put(tag, value);
            }
        }
        ifd.setEntries(entries);

        in.seek(offset + baseOffset + bytesPerEntry * numEntries);
        long t2 = System.nanoTime();
        LOG.log(System.Logger.Level.TRACE, () -> String.format(
                "reading IFD at offset %d: %.3f ms", offset, (t2 - t1) * 1e-6));
        return ifd;
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

    /**
     * Fill in IFD entries that are stored at an arbitrary offset.
     */
    public void fillInIFD(final IFD ifd) throws IOException {
        final HashSet<TiffIFDEntry> entries = new HashSet<>();
        for (final Integer key : ifd.keySet()) {
            if (ifd.get(key) instanceof TiffIFDEntry) {
                entries.add((TiffIFDEntry) ifd.get(key));
            }
        }

        for (final TiffIFDEntry entry : entries) {
            if (entry.getValueCount() < 10 * 1024 * 1024 || entry.getTag() < 32768) {
                ifd.put(entry.getTag(), getIFDValue(entry));
            }
        }
    }

    /**
     * Retrieve the value corresponding to the given TiffIFDEntry.
     */
    public Object getIFDValue(final TiffIFDEntry entry) throws IOException {
        final IFDType type = entry.getType();
        final int count = entry.getValueCount();
        final long offset = entry.getValueOffset();

        LOG.log(System.Logger.Level.TRACE, () -> "Reading entry " + entry.getTag() + " from " + offset +
                "; type=" + type + ", count=" + count);

        if (offset >= in.length()) {
            return null;
        }

        if (offset != in.offset()) {
            in.seek(offset);
        }

        if (type == IFDType.BYTE) {
            // 8-bit unsigned integer
            if (count == 1) return (short) in.readByte();
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
            if (count == 1) return in.readUnsignedShort();
            final int[] shorts = new int[count];
            for (int j = 0; j < count; j++) {
                shorts[j] = in.readUnsignedShort();
            }
            return shorts;
        } else if (type == IFDType.LONG || type == IFDType.IFD) {
            // 32-bit (4-byte) unsigned integer
            if (count == 1) return (long) in.readInt();
            final long[] longs = new long[count];
            for (int j = 0; j < count; j++) {
                if (in.offset() + 4 <= in.length()) {
                    longs[j] = in.readInt();
                }
            }
            return longs;
        } else if (type == IFDType.LONG8 || type == IFDType.SLONG8 ||
                type == IFDType.IFD8) {
            if (count == 1) return in.readLong();
            long[] longs = null;

            if (assumeEqualStrips && (entry.getTag() == IFD.STRIP_BYTE_COUNTS || entry
                    .getTag() == IFD.TILE_BYTE_COUNTS)) {
                longs = new long[1];
                longs[0] = in.readLong();
            } else if (assumeEqualStrips && (entry.getTag() == IFD.STRIP_OFFSETS || entry
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
            if (count == 1) return in.readByte();
            final byte[] sbytes = new byte[count];
            in.read(sbytes);
            return sbytes;
        } else if (type == IFDType.SSHORT) {
            // A 16-bit (2-byte) signed (twos-complement) integer
            if (count == 1) return in.readShort();
            final short[] sshorts = new short[count];
            for (int j = 0; j < count; j++)
                sshorts[j] = in.readShort();
            return sshorts;
        } else if (type == IFDType.SLONG) {
            // A 32-bit (4-byte) signed (twos-complement) integer
            if (count == 1) return in.readInt();
            final int[] slongs = new int[count];
            for (int j = 0; j < count; j++)
                slongs[j] = in.readInt();
            return slongs;
        } else if (type == IFDType.FLOAT) {
            // Single precision (4-byte) IEEE format
            if (count == 1) return in.readFloat();
            final float[] floats = new float[count];
            for (int j = 0; j < count; j++)
                floats[j] = in.readFloat();
            return floats;
        } else if (type == IFDType.DOUBLE) {
            // Double precision (8-byte) IEEE format
            if (count == 1) return in.readDouble();
            final double[] doubles = new double[count];
            for (int j = 0; j < count; j++) {
                doubles[j] = in.readDouble();
            }
            return doubles;
        }

        return null;
    }

    /**
     * Convenience method for obtaining a stream's first ImageDescription.
     */
    public String getComment() throws IOException {
        final IFD firstIFD = getFirstIFD();
        if (firstIFD == null) {
            return null;
        }
        fillInIFD(firstIFD);
        return firstIFD.getComment();
    }

    // -- TiffParser methods - image reading --

    public TiffTile readTile(TiffTileIndex tileIndex) throws FormatException, IOException {
        TiffTile tile = readEncodedTile(tileIndex);
        if (tile.isEmpty()) {
            return tile;
        }

        decode(tile);
        // scifio.tiff().undifference(tile.getDecodedData(), tile.ifd());
        // - this solution requires using SCIFIO context class; it is better to avoid this
        TiffTools.undifference(tile.ifd(), tile.getDecodedData());

        postProcessTile(tile);

        return tile;
    }

    public TiffTile readEncodedTile(TiffTileIndex tileIndex) throws FormatException, IOException {
        Objects.requireNonNull(tileIndex, "Null tileIndex");
        final TiffTile result = new TiffTile(tileIndex);
        byte[] tile = readTileRawBytes(tileIndex.ifd(), tileIndex.x(), tileIndex.y());
        if (tile == null) {
            // - empty result
            return result;
        }
        final byte[] jpegTable = (byte[]) tileIndex.ifd().getIFDValue(IFD.JPEG_TABLES);
        if (jpegTable != null) {
            if ((long) jpegTable.length + (long) tile.length - 4 >= Integer.MAX_VALUE) {
                throw new FormatException("Too large tile/strip at " + tileIndex + ": "
                        + "JPEG table length " + jpegTable.length + " + number of bytes " + tile.length + " > 2^31-1");

            }
            final byte[] appended = new byte[jpegTable.length + tile.length - 4];
            System.arraycopy(jpegTable, 0, appended, 0, jpegTable.length - 2);
            System.arraycopy(tile, 2, appended, jpegTable.length - 2, tile.length - 2);
            tile = appended;
        }
        return result.setEncodedData(tile);
    }

    public byte[] readTileRawBytes(TiffTileIndex tileIndex) throws FormatException, IOException {
        Objects.requireNonNull(tileIndex, "Null tileIndex");
        return readTileRawBytes(tileIndex.ifd(), tileIndex.x(), tileIndex.y());
    }

    public byte[] readTileRawBytes(final DetailedIFD ifd, int xIndex, int yIndex) throws FormatException, IOException {
        Objects.requireNonNull(ifd, "Null IFD");

        final int tileSizeX = ifd.getTileSizeX();
        final int tileSizeY = ifd.getTileSizeY();
        final int numTileCols = ifd.getTilesPerRow(tileSizeX);
        int numTileRows = ifd.getTilesPerColumn(tileSizeY);
        if (ifd.isPlanarSeparated()) {
            int channelsInSeparated = ifd.getSamplesPerPixel();
            if ((long) channelsInSeparated * (long) numTileRows > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Too large image: number of tiles in column " + numTileCols
                        + " * samples per pixel " + channelsInSeparated + " >= 2^31");
            }
            numTileRows *= channelsInSeparated;
        }
        if (xIndex < 0 || xIndex >= numTileCols || yIndex < 0 || yIndex >= numTileRows) {
            throw new IndexOutOfBoundsException("Tile/strip position (xIndex, yIndex) = (" + xIndex + ", " + yIndex
                    + ") is out of bounds 0 <= xIndex < " + numTileCols + ", 0 <= yIndex < " + numTileRows);
        }
        final long numberOfTiles = (long) numTileRows * (long) numTileCols;
        if (numberOfTiles > Integer.MAX_VALUE) {
            // - this check allows to be sure that tileIndex below is 31-bit
            throw new FormatException("Too large number of tiles/strips: "
                    + numTileRows + " * " + numTileCols + " > 2^31-1");
        }
        final long[] byteCounts = ifd.getStripByteCounts();
        final long[] rowsPerStrip = ifd.getRowsPerStrip();
        // - newly allocated arrays (not too quick solution)

        final int tileIndex = yIndex * numTileCols + xIndex;
        int countIndex = tileIndex;
        if (assumeEqualStrips) {
            countIndex = 0;
        }
        if (countIndex >= byteCounts.length) {
            throw new FormatException("Too short " + (ifd.isTiled() ? "TileByteCounts" : "StripByteCounts")
                    + " array: " + byteCounts.length
                    + " elements, it is not enough for the tile byte-count index " + countIndex
                    + " (this image contains " + numTileCols + "x" + numTileRows + " tiles)");
        }
        long byteCount = byteCounts[countIndex];
        final int bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
        if (byteCount == (rowsPerStrip[0] * tileSizeX) && bytesPerSample > 1) {
            // - what situation is checked here??
            byteCount *= bytesPerSample;
        }
        if (byteCount >= Integer.MAX_VALUE) {
            throw new FormatException("Too large tile/strip #" + tileIndex + ": " + byteCount + " bytes > 2^31-1");
        }

        final OnDemandLongArray onDemandOffsets = ifd.getOnDemandStripOffsets();
        final long[] offsets = onDemandOffsets != null ? null : ifd.getStripOffsets();
        final long numberOfOffsets = onDemandOffsets != null ? onDemandOffsets.size() : offsets.length;
        if (tileIndex >= numberOfOffsets) {
            throw new FormatException("Too short " + (ifd.isTiled() ? "TileOffsets" : "StripOffsets")
                    + " array: " + numberOfOffsets
                    + " elements, it is not enough for the tile offset index " + tileIndex
                    + " (this image contain " + numTileCols + "x" + numTileRows + " tiles)");
        }
        final long stripOffset = onDemandOffsets != null ? onDemandOffsets.get(tileIndex) : offsets[tileIndex];

        if (byteCount == 0 || stripOffset < 0 || stripOffset >= in.length()) {
            return null;
        }
        byte[] tile = new byte[(int) byteCount];
        in.seek(stripOffset);
        in.read(tile);
        return tile;
    }

    // Note: result is always interleaved (RGBRGB...) or monochrome.
//    public byte[] decode(ExtendedIFD ifd, byte[] stripSamples, int samplesLength) throws FormatException {
    public void decode(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        DetailedIFD ifd = tile.ifd();
        final byte[] stripSamples = tile.getEncodedData();
        final int samplesLength = tile.tileIndex().sizeOfBasedOnBits();
        final TiffCompression compression = ifd.getCompression();

        codecOptions.interleaved = true;
        codecOptions.littleEndian = ifd.isLittleEndian();
        codecOptions.maxBytes = Math.max(samplesLength, stripSamples.length);
        final int subSampleHorizontal = ifd.getIFDIntValue(IFD.Y_CB_CR_SUB_SAMPLING);
        // - Usually it is an array [1,1], [2,2], [2,1] or something like this:
        // see documentation on YCbCrSubSampling TIFF tag.
        // Default is [2,2].
        // getIFDIntValue method returns the element #0, i.e. horizontal sub-sampling
        // Value 1 means "ImageWidth of this chroma image is equal to the ImageWidth of the associated luma image".
        codecOptions.ycbcr = ifd.getPhotometricInterpretation() == PhotoInterp.Y_CB_CR
                && subSampleHorizontal == 1
                && yCbCrCorrection;
        // Rare case: Y_CB_CR is encoded with non-standard sub-sampling

        Codec codec = null;
        if (extendedCodec) {
            switch (compression) {
                case JPEG_2000, JPEG_2000_LOSSY, ALT_JPEG2000 -> {
                    codec = new ExtendedJPEG2000Codec();
                }
            }
        }
        if (codec != null) {
            tile.setDecodedData(codec.decompress(stripSamples, codecOptions));
        } else {
            if (scifio == null) {
                throw new IllegalStateException("Compression type " + compression +
                        " requires specifying non-null SCIFIO context");
            }
            tile.setDecodedData(compression.decompress(scifio.codec(), stripSamples, codecOptions));
        }
    }


    public byte[] getSamples(final IFD ifd, final byte[] samples) throws FormatException, IOException {
        final long width = ifd.getImageWidth();
        final long length = ifd.getImageLength();
        TiffTools.checkRequestedArea(0, 0, width, length);
        //!! In future ExtendedIFD.extend should be removed, when it will become the single class
        return getSamples(ifd, samples, 0, 0, (int) width, (int) length);
    }

    /**
     * Reads samples in <tt>byte[]</tt> array.
     *
     * <p>Note: you should not change IFD in a parallel thread while calling this method.
     *
     * @param samples work array for reading data; may be <tt>null</tt>.
     * @return loaded samples; will be a reference to passed samples, if it is not <tt>null</tt>.
     */
    public byte[] getSamples(IFD simpleIFD, byte[] samples, int fromX, int fromY, int sizeX, int sizeY)
            throws FormatException, IOException {
        Objects.requireNonNull(simpleIFD, "Null IFD");
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        DetailedIFD ifd = DetailedIFD.extend(simpleIFD);
        //!! - temporary solution, until ExtendedIFD methods will be moved into IFD
        final int size = ifd.sizeOfRegion(sizeX, sizeY);
        // - also checks that sizeX/sizeY are allowed
        assert sizeX >= 0 && sizeY >= 0 : "sizeOfIFDRegion didn't check sizes accurately: " + sizeX + "fromX" + sizeY;
        if (samples == null) {
            samples = new byte[size];
        } else if (size > samples.length) {
            throw new IllegalArgumentException("Insufficient length of the result samples array: " + samples.length
                    + " < necessary " + size + " bytes");
        }
        if (sizeX == 0 || sizeY == 0) {
            return samples;
        }

        ifd.sizeOfTileBasedOnBits();
        // - checks that results of ifd.getTileWidth(), ifd.getTileLength() are non-negative and <2^31,
        // and checks that we can multiply them by bytesPerSample and ifd.getSamplesPerPixel() without overflow
        long t1 = debugTime();
        Arrays.fill(samples, 0, size, filler);
        // - important for a case when the requested area is outside the image;
        // old SCIFIO code did not check this and could return undefined results

        readTiles(ifd, samples, fromX, fromY, sizeX, sizeY);

        long t2 = debugTime();
        TiffTools.invertFillOrderIfNecessary(ifd, samples);
        if (autoUnpackUnusualPrecisions) {
            TiffTools.unpackUnusualPrecisions(ifd, samples, sizeX * sizeY);
        }
        long t3 = debugTime();
        if (autoInterleave) {
            TiffTools.interleaveSamples(ifd, samples, sizeX * sizeY);
        }
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t4 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f get + %.3f corrections + %.3f interleave, %.3f MB/s",
                    getClass().getSimpleName(),
                    ifd.getSamplesPerPixel(), sizeX, sizeY, size / 1048576.0,
                    (t4 - t1) * 1e-6,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6,
                    size / 1048576.0 / ((t4 - t1) * 1e-9)));
        }
        return samples;
    }

    public Object getSamplesArray(DetailedIFD ifd, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException, FormatException {
        return getSamplesArray(
                ifd, fromX, fromY, sizeX, sizeY, null, null);
    }

    public Object getSamplesArray(
            DetailedIFD ifd,
            final int fromX,
            final int fromY,
            final int sizeX,
            final int sizeY,
            Integer requiredSamplesPerPixel,
            Class<?> requiredElementType)
            throws FormatException, IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (requiredSamplesPerPixel != null && ifd.getSamplesPerPixel() != requiredSamplesPerPixel) {
            throw new FormatException("Number of bands mismatch: expected " + requiredSamplesPerPixel
                    + " samples per pixel, but IFD image contains " + ifd.getSamplesPerPixel()
                    + " samples per pixel");
        }
        if (requiredElementType != null && optionalElementType(ifd).orElse(null) != requiredElementType) {
            throw new FormatException("Element type mismatch: expected " + requiredElementType
                    + "[] elements, but some IFD image contains "
                    + optionalElementType(ifd).map(Class::getName).orElse("unknown")
                    + "[] elements");
        }
        final byte[] samples = getSamples(ifd, null, fromX, fromY, sizeX, sizeY);
        long t1 = debugTime();
        final Object samplesArray = TiffTools.planeBytesToJavaArray(samples, ifd.getPixelType(), ifd.isLittleEndian());
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s converted %d bytes (%.3f MB) to %s[] in %.3f ms%s",
                    getClass().getSimpleName(),
                    samples.length, samples.length / 1048576.0,
                    samplesArray.getClass().getComponentType().getSimpleName(),
                    (t2 - t1) * 1e-6,
                    samples == samplesArray ?
                            "" :
                            String.format(Locale.US, " %.3f MB/s",
                                    samples.length / 1048576.0 / ((t2 - t1) * 1e-9))));
        }
        return samplesArray;
    }

    public TiffIFDEntry readTiffIFDEntry() throws IOException {
        final int entryTag = in.readUnsignedShort();

        // Parse the entry's "Type"
        IFDType entryType;
        try {
            entryType = IFDType.get(in.readUnsignedShort());
        } catch (final EnumException e) {
            throw new IOException("Error reading TIFF IFD type at position " + in.offset() + ": " + e.getMessage(), e);
        }

        // Parse the entry's "ValueCount"
        final int valueCount = bigTiff ? (int) in.readLong() : in.readInt();
        if (valueCount < 0) {
            throw new IOException("Invalid TIFF: negative number of IFD values " + valueCount);
        }

        final int nValueBytes = valueCount * entryType.getBytesPerElement();
        final int threshhold = bigTiff ? 8 : 4;
        final long offset = nValueBytes > threshhold ? getNextOffset(0) : in.offset();

        final TiffIFDEntry result = new TiffIFDEntry(entryTag, entryType, valueCount, offset);
        LOG.log(System.Logger.Level.TRACE, () -> String.format(
                "Reading IFD entry: %s - %s", result, DetailedIFD.ifdTagName(result.getTag(), true)));
        return result;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    protected static void postProcessTile(TiffTile tile) throws FormatException {
        final TiffTileIndex tileIndex = tile.tileIndex();
        byte[] samples = new byte[tileIndex.sizeOfBasedOnBits()];
        unpackBytes(tile.ifd(), samples, tile.getDecodedData());

        final int tileSizeX = tileIndex.tileSizeX();
        final int tileSizeY = tileIndex.tileSizeY();
        DetailedIFD ifd = tile.ifd();
        final int samplesPerPixel = ifd.getSamplesPerPixel();
        final int planarConfig = ifd.getPlanarConfiguration();
        final int bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
        final int effectiveChannels = planarConfig == DetailedIFD.PLANAR_CONFIG_SEPARATE ? 1 : samplesPerPixel;
        assert samples.length == ((long) tileSizeX * (long) tileSizeY * bytesPerSample * effectiveChannels);
        if (planarConfig == DetailedIFD.PLANAR_CONFIG_SEPARATE && !ifd.isTiled() && ifd.getSamplesPerPixel() > 1) {
            final OnDemandLongArray onDemandOffsets = ifd.getOnDemandStripOffsets();
            final long[] offsets = onDemandOffsets != null ? null : ifd.getStripOffsets();
            final long numberOfStrips = onDemandOffsets != null ? onDemandOffsets.size() : offsets.length;
            final int channel = (int) (tileIndex.y() % numberOfStrips);
            int[] differentBytesPerSample = ifd.getBytesPerSample();
            if (channel < differentBytesPerSample.length) {
                final int realBytes = differentBytesPerSample[channel];
                if (realBytes != bytesPerSample) {
                    //TODO!! - it seems that this branch is impossible! See ExtendedIFD.getBytesPerSampleBasedOnBits

                    // re-pack pixels to account for differing bits per sample
                    final boolean littleEndian = ifd.isLittleEndian();
                    final int[] newSamples = new int[samples.length / bytesPerSample];
                    for (int i = 0; i < newSamples.length; i++) {
                        newSamples[i] = Bytes.toInt(samples, i * realBytes, realBytes,
                                littleEndian);
                    }

                    for (int i = 0; i < newSamples.length; i++) {
                        Bytes.unpack(newSamples[i], samples, i * bytesPerSample, bytesPerSample, littleEndian);
                    }
                }
            }
        }
        tile.setDecodedData(samples);
    }

    // -- Helper methods --
    private void readTiles(DetailedIFD ifd, byte[] samples, int fromX, int fromY, int sizeX, int sizeY)
            throws FormatException, IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        Objects.requireNonNull(samples, "Null samples");
        assert fromX >= 0 && fromY >= 0 && sizeX >= 0 && sizeY >= 0;
        // Note: we cannot process image larger than 2^31 x 2^31 pixels,
        // though TIFF supports maximal sizes 2^32 x 2^32
        // (IFD.getImageWidth/getImageLength do not allow so large results)

        final int tileSizeX = ifd.getTileSizeX();
        final int tileSizeY = ifd.getTileSizeY();
        final int numTileCols = ifd.getTilesPerRow(tileSizeX);
        final int numTileRows = ifd.getTilesPerColumn(tileSizeY);
        // - If the image is not really tiled, we will work with full image size.
        // (Old SCIFIO code used the height of requested area instead of the full image height, as getTileHeight(),
        // but it leads to the same results in the algorithm below.)
        if (tileSizeX <= 0 || tileSizeY <= 0) {
            // - strange case (probably zero-size image); in any case a tile with zero sizes cannot intersect anything
            return;
        }

        final int bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
        final int channelsInSeparated, channelsUsual;
        if (ifd.isPlanarSeparated()) {
            // - it is a very rare case PlanarConfiguration=2 (RRR...GGG...BBB...);
            // we have (for 3 channels) only 1 effective channel instead of 3,
            // but we have 3 * numTileRows effective rows
            channelsInSeparated = ifd.getSamplesPerPixel();
            if ((long) channelsInSeparated * (long) numTileRows > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Too large image: number of tiles in column " + numTileRows
                        + " * samples per pixel " + channelsInSeparated + " >= 2^31");
            }
            channelsUsual = 1;
        } else {
            channelsInSeparated = 1;
            channelsUsual = ifd.getSamplesPerPixel();
        }

        final int toX = fromX + sizeX;
        final int toY = fromY + sizeY;
        final int minCol = fromX / tileSizeX;
        final int minRow = fromY / tileSizeY;
        if (minRow >= numTileRows || minCol >= numTileCols) {
            return;
        }
        final int maxCol = Math.min(numTileCols - 1, (toX - 1) / tileSizeX);
        final int maxRow = Math.min(numTileRows - 1, (toY - 1) / tileSizeY);
        assert minRow <= maxRow && minCol <= maxCol;
        final int tileRowSizeInBytes = tileSizeX * bytesPerSample;
        final int outputRowSizeInBytes = sizeX * bytesPerSample;

        for (int cs = 0; cs < channelsInSeparated; cs++) {
            // - in a rare case PlanarConfiguration=2 (RRR...GGG...BBB...),
            // we have (for 3 channels) 3 * numTileRows effective rows instead of numTileRows
            int effectiveYIndex = cs * numTileRows + minRow;
            for (int yIndex = minRow; yIndex <= maxRow; yIndex++, effectiveYIndex++) {
                for (int xIndex = minCol; xIndex <= maxCol; xIndex++) {
                    final TiffTile tile = readTile(new TiffTileIndex(ifd, xIndex, effectiveYIndex));
                    if (tile.isEmpty()) {
                        continue;
                    }
                    byte[] data = tile.getData();

                    final int tileStartX = Math.max(xIndex * tileSizeX, fromX);
                    final int tileStartY = Math.max(yIndex * tileSizeY, fromY);
                    final int xInTile = tileStartX % tileSizeX;
                    final int yInTile = tileStartY % tileSizeY;
                    final int xDiff = tileStartX - fromX;
                    final int yDiff = tileStartY - fromY;

                    final int partSizeX = Math.min(toX - tileStartX, tileSizeX - xInTile);
                    assert partSizeX > 0 : "partSizeX=" + partSizeX;
                    final int partSizeY = Math.min(toY - tileStartY, tileSizeY - yInTile);
                    assert partSizeY > 0 : "partSizeY=" + partSizeY;

                    final int partSizeXInBytes = partSizeX * bytesPerSample;
                    for (int cu = 0; cu < channelsUsual; cu++) {
                        int srcOffset = (((cu * tileSizeY) + yInTile) * tileSizeX + xInTile) * bytesPerSample;
                        int destOffset = (((cu + cs) * sizeY + yDiff) * sizeX + xDiff) * bytesPerSample;
                        for (int tileRow = 0; tileRow < partSizeY; tileRow++) {
                            System.arraycopy(data, srcOffset, samples, destOffset, partSizeXInBytes);
                            srcOffset += tileRowSizeInBytes;
                            destOffset += outputRowSizeInBytes;
                        }
                    }
                }
            }
        }
    }

    private String prettyInName() {
        return prettyFileName(" %s", in);
    }

    /**
     * Extracts pixel information from the given byte array according to the bits
     * per sample, photometric interpretation and color map IFD directory entry
     * values, and the specified byte ordering. No error checking is performed.
     */
    public static void unpackBytes(
            final DetailedIFD ifd, final byte[] resultSamples,
            final byte[] bytes) throws FormatException {
        final boolean planar = ifd.getPlanarConfiguration() == 2;
//        final int samplesLength = ifd.sizeOfTileBasedOnBits();

        final TiffCompression compression = ifd.getCompression();
        PhotoInterp photoInterp = ifd.getPhotometricInterpretation();
        if (compression == TiffCompression.JPEG) {
            photoInterp = PhotoInterp.RGB;
        }

        final int[] bitsPerSample = ifd.getBitsPerSample();
        int nChannels = bitsPerSample.length;

        int sampleCount = (int) (((long) 8 * bytes.length) / bitsPerSample[0]);
        if (photoInterp == PhotoInterp.Y_CB_CR) sampleCount *= 3;
        if (planar) {
            nChannels = 1;
        } else {
            sampleCount /= nChannels;
        }

        if (LOGGABLE_TRACE) {
            LOG.log(System.Logger.Level.TRACE, "unpacking " + sampleCount + " resultSamples" +
                    "; totalBits=" + (nChannels * bitsPerSample[0]) +
                    "; numBytes=" + bytes.length + ")");
        }

        final long imageWidth = ifd.getImageWidth();
        final long imageHeight = ifd.getImageLength();

        final int bps0 = bitsPerSample[0];
        final int numBytes = ifd.getBytesPerSample()[0];
        final int nSamples = resultSamples.length / (nChannels * numBytes);

        final boolean noDiv8 = bps0 % 8 != 0;
        final boolean bps8 = bps0 == 8;
        final boolean bps16 = bps0 == 16;

        final boolean littleEndian = ifd.isLittleEndian();

        final BitBuffer bb = new BitBuffer(bytes);

        // Hyper optimisation that takes any 8-bit or 16-bit data, where there is
        // only one channel, the source byte buffer's size is less than or equal to
        // that of the destination buffer and for which no special unpacking is
        // required and performs a simple array copy. Over the course of reading
        // semi-large datasets this can save **billions** of method calls.
        // Wed Aug 5 19:04:59 BST 2009
        // Chris Allan <callan@glencoesoftware.com>
        //TODO!! bytes.length should be always == resultSamples.length??
        if ((bps8 || bps16) && bytes.length <= resultSamples.length && nChannels == 1 &&
                photoInterp != PhotoInterp.WHITE_IS_ZERO &&
                photoInterp != PhotoInterp.CMYK && photoInterp != PhotoInterp.Y_CB_CR) {
            System.arraycopy(bytes, 0, resultSamples, 0, bytes.length);
            return;
        }

        long maxValue = (long) Math.pow(2, bps0) - 1;
        if (photoInterp == PhotoInterp.CMYK) maxValue = Integer.MAX_VALUE;

        int skipBits = (int) (8 - ((imageWidth * bps0 * nChannels) % 8));
        if (skipBits == 8 || (bytes.length * 8 < bps0 * (nChannels * imageWidth +
                imageHeight))) {
            skipBits = 0;
        }

        // set up YCbCr-specific values
        float lumaRed = PhotoInterp.LUMA_RED;
        float lumaGreen = PhotoInterp.LUMA_GREEN;
        float lumaBlue = PhotoInterp.LUMA_BLUE;
        int[] reference = ifd.getIFDIntArray(IFD.REFERENCE_BLACK_WHITE);
        if (reference == null) {
            reference = new int[]{0, 0, 0, 0, 0, 0};
        }
        final int[] subsampling = ifd.getIFDIntArray(IFD.Y_CB_CR_SUB_SAMPLING);
        final TiffRational[] coefficients = (TiffRational[]) ifd.getIFDValue(
                IFD.Y_CB_CR_COEFFICIENTS);
        if (coefficients != null) {
            lumaRed = coefficients[0].floatValue();
            lumaGreen = coefficients[1].floatValue();
            lumaBlue = coefficients[2].floatValue();
        }
        final int subX = subsampling == null ? 2 : subsampling[0];
        final int subY = subsampling == null ? 2 : subsampling[1];
        final int block = subX * subY;
        final int nTiles = (int) (imageWidth / subX);

        // unpack pixels
        for (int sample = 0; sample < sampleCount; sample++) {
            final int ndx = sample;
            if (ndx >= nSamples) break;

            for (int channel = 0; channel < nChannels; channel++) {
                final int index = numBytes * (sample * nChannels + channel);
                final int outputIndex = (channel * nSamples + ndx) * numBytes;

                // unpack non-YCbCr samples
                if (photoInterp != PhotoInterp.Y_CB_CR) {
                    long value = 0;

                    if (noDiv8) {
                        // bits per sample is not a multiple of 8

                        if ((channel == 0 && photoInterp == PhotoInterp.RGB_PALETTE) ||
                                (photoInterp != PhotoInterp.CFA_ARRAY &&
                                        photoInterp != PhotoInterp.RGB_PALETTE)) {
                            value = bb.getBits(bps0) & 0xffff;
                            if ((ndx % imageWidth) == imageWidth - 1) {
                                bb.skipBits(skipBits);
                            }
                        }
                    } else {
                        value = Bytes.toLong(bytes, index, numBytes, littleEndian);
                    }

                    if (photoInterp == PhotoInterp.WHITE_IS_ZERO ||
                            photoInterp == PhotoInterp.CMYK) {
                        value = maxValue - value;
                    }

                    if (outputIndex + numBytes <= resultSamples.length) {
                        Bytes.unpack(value, resultSamples, outputIndex, numBytes, littleEndian);
                    }
                } else {
                    // unpack YCbCr resultSamples; these need special handling, as
                    // each of the RGB components depends upon two or more of the YCbCr
                    // components
                    if (channel == nChannels - 1) {
                        final int lumaIndex = sample + (2 * (sample / block));
                        final int chromaIndex = (sample / block) * (block + 2) + block;

                        if (chromaIndex + 1 >= bytes.length) break;

                        final int tile = ndx / block;
                        final int pixel = ndx % block;
                        final long r = subY * (tile / nTiles) + (pixel / subX);
                        final long c = subX * (tile % nTiles) + (pixel % subX);

                        final int idx = (int) (r * imageWidth + c);

                        if (idx < nSamples) {
                            final int y = (bytes[lumaIndex] & 0xff) - reference[0];
                            final int cb = (bytes[chromaIndex] & 0xff) - reference[2];
                            final int cr = (bytes[chromaIndex + 1] & 0xff) - reference[4];

                            final int red = (int) (cr * (2 - 2 * lumaRed) + y);
                            final int blue = (int) (cb * (2 - 2 * lumaBlue) + y);
                            final int green = (int) ((y - lumaBlue * blue - lumaRed * red) /
                                    lumaGreen);

                            resultSamples[idx] = (byte) (red & 0xff);
                            resultSamples[nSamples + idx] = (byte) (green & 0xff);
                            resultSamples[2 * nSamples + idx] = (byte) (blue & 0xff);
                        }
                    }
                }
            }
        }
    }

    /**
     * Read a file offset. For bigTiff, a 64-bit number is read. For other Tiffs,
     * a 32-bit number is read and possibly adjusted for a possible carry-over
     * from the previous offset.
     */
    private long getNextOffset(final long previous) throws IOException {
        long fp = in.offset();
        long offset;
        if (bigTiff || use64BitOffsets) {
            offset = in.readLong();
        } else {
            offset = (previous & ~0xffffffffL) | (in.readInt() & 0xffffffffL);

            // Only adjust the offset if we know that the file is too large for 32-bit
            // offsets to be accurate; otherwise, we're making the incorrect assumption
            // that IFDs are stored sequentially.
            if (offset < previous && offset != 0 && in.length() > Integer.MAX_VALUE) {
                offset += 0x100000000L;
            }
        }
        if (requireValidTiff) {
            if (offset < 0) {
                throw new IOException("Invalid TIFF" + prettyInName() + ": negative offset " + offset +
                        " at file position " + fp);
            }
            if (offset >= in.length()) {
                throw new IOException("Invalid TIFF" + prettyInName() + ": offset " + offset +
                        " at file position " + fp + " is outside the file");
            }
        }
        this.positionOfLastOffset = fp;
        return offset;
    }

    private static Optional<Class<?>> optionalElementType(IFD ifd) {
        Objects.requireNonNull(ifd, "Null IFD");
        try {
            return Optional.of(TiffTools.pixelTypeToElementType(ifd.getPixelType()));
        } catch (FormatException e) {
            return Optional.empty();
        }
    }

    private static String prettyFileName(String format, DataHandle<Location> handle) {
        if (handle == null) {
            return "";
        }
        Location location = handle.get();
        if (location == null) {
            return "";
        }
        URI uri = location.getURI();
        if (uri == null) {
            return "";
        }
        return format.formatted(uri);
    }

    private static long debugTime() {
        return TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }


    @Deprecated
    public IFDList getIFDs() throws IOException {
        return DetailedIFD.toIFDList(allIFD());
    }

    /**
     * Returns thumbnail IFDs.
     */
    @Deprecated
    public IFDList getThumbnailIFDs() throws IOException {
        return DetailedIFD.toIFDList(allThumbnailIFD());
    }

    /**
     * Returns non-thumbnail IFDs.
     */
    @Deprecated
    public IFDList getNonThumbnailIFDs() throws IOException {
        return DetailedIFD.toIFDList(allNonThumbnailIFD());
    }

    /**
     * Returns EXIF IFDs.
     */
    @Deprecated
    public IFDList getExifIFDs() throws FormatException, IOException {
        return DetailedIFD.toIFDList(allExifIFD());
    }

    @Deprecated
    public byte[] getTile(final IFD ifd, byte[] buf, final int row, final int col)
            throws FormatException, IOException {
        TiffTileIndex tileIndex = new TiffTileIndex(DetailedIFD.extend(ifd), col, row);
        if (buf == null) {
            buf = new byte[tileIndex.sizeOfBasedOnBits()];
        }
        TiffTile tile = readTile(tileIndex);
        if (!tile.isEmpty()) {
            byte[] data = tile.getDecodedData();
            System.arraycopy(data, 0, buf, 0, data.length);
        }
        return buf;
    }

    /**
     * This function is deprecated, because it has identical behaviour with
     * {@link #getSamples(IFD, byte[], int, int, int, int)} and actually is always called
     * from other classes with <tt>int</tt> parameters, even in OME BioFormats.
     */
    @Deprecated
    public byte[] getSamples(IFD ifd, byte[] samples, int fromX, int fromY, long sizeX, long sizeY)
            throws FormatException, IOException {
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        return getSamples(DetailedIFD.extend(ifd), samples, fromX, fromY, (int) sizeX, (int) sizeY);
    }

    /**
     * This function is deprecated, because it is almost not used - the only exception is
     * TrestleReader from OME BioFormats.
     */
    @Deprecated
    public byte[] getSamples(final IFD ifd, final byte[] buf, final int x,
                             final int y, final long width, final long height, final int overlapX,
                             final int overlapY) throws FormatException, IOException {
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
            throw new FormatException("Sorry, ImageWidth x ImageLength > " +
                    Integer.MAX_VALUE + " is not supported (" + width + " x " + height +
                    ")");
        }
        if (width * height * effectiveChannels * pixel > Integer.MAX_VALUE) {
            throw new FormatException("Sorry, ImageWidth x ImageLength x " +
                    "SamplesPerPixel x BitsPerSample > " + Integer.MAX_VALUE +
                    " is not supported (" + width + " x " + height + " x " +
                    samplesPerPixel + " x " + (pixel * 8) + ")");
        }

        // casting to int is safe because we have already determined that
        // width * height is less than Integer.MAX_VALUE
        final int numSamples = (int) (width * height);

        // read in image strips
        final TiffCompression compression = ifd.getCompression();

        if (compression == TiffCompression.JPEG_2000 ||
                compression == TiffCompression.JPEG_2000_LOSSY) {
            codecOptions = compression.getCompressionCodecOptions(ifd, codecOptions);
        } else codecOptions = compression.getCompressionCodecOptions(ifd);
        codecOptions.interleaved = true;
        codecOptions.littleEndian = ifd.isLittleEndian();
        final long imageLength = ifd.getImageLength();

        // special case: if we only need one tile, and that tile doesn't need
        // any special handling, then we can just read it directly and return
        if ((x % tileWidth) == 0 && (y % tileLength) == 0 && width == tileWidth &&
                height == imageLength && samplesPerPixel == 1 && (ifd
                .getBitsPerSample()[0] % 8) == 0 &&
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
                    long byteCount = assumeEqualStrips ? stripByteCounts[0]
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

        cachedTileBuffer = new byte[bufferSize];

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
                    int dest = q * planeSize + pixel * (tileX - x) + outputRowLen *
                            (tileY - y);
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

    @Deprecated
    private byte[] adjustFillOrder(final IFD ifd, final byte[] buf)
            throws FormatException {
        TiffTools.invertFillOrderIfNecessary(ifd, buf);
        return buf;
    }
}
