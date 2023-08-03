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
import io.scif.codec.PassthroughCodec;
import io.scif.common.Constants;
import io.scif.enumeration.EnumException;
import io.scif.formats.tiff.*;
import net.algart.matrices.io.formats.tiff.bridges.scifio.codecs.ExtendedJPEGCodec;
import org.scijava.AbstractContextual;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.Location;
import org.scijava.util.Bytes;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Parses TIFF data from an input source.
 *
 * @author Curtis Rueden
 * @author Eric Kjellman
 * @author Melissa Linkert
 * @author Chris Allan
 * @author Denial Alievsky
 */
public class TiffReader extends AbstractContextual implements Closeable {

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

    private static final boolean OPTIMIZE_READING_IFD_ARRAYS = true;
    // - Note: this optimization allows to speed up reading large array of offsets in 100 and more times:
    // on my computer, 23220 int32 values were loaded in 3 ms instead of 500 ms.

    private static final System.Logger LOG = System.getLogger(TiffReader.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);
    private static final boolean LOGGABLE_TRACE = LOG.isLoggable(System.Logger.Level.TRACE);

    private byte filler = 0;
    private boolean requireValidTiff;
    private boolean autoInterleave = false;
    private boolean autoUnpackUnusualPrecisions = true;
    private boolean extendedCodec = true;
    private boolean use64BitOffsets = false;
    private boolean assumeEqualStrips = false;
    private boolean yCbCrCorrection = true;
    private boolean cachingIFDs = true;

    private final Exception openingException;
    private final DataHandle<Location> in;
    private final boolean valid;
    private final boolean bigTiff;

    /**
     * Cached list of IFDs in the current file.
     */
    private List<DetailedIFD> ifdList;

    /**
     * Cached first IFD in the current file.
     */
    private IFD firstIFD;
    private final SCIFIO scifio;

    /**
     * Codec options to be used when decoding compressed pixel data.
     */
    CodecOptions codecOptions = CodecOptions.getDefaultOptions();

    private volatile long positionOfLastOffset = -1;
    private long timeReading = 0;
    private long timeCustomizingDecoding = 0;
    private long timeDecoding = 0;
    private long timeCompleteDecoding = 0;

    public TiffReader(Path file) throws IOException {
        this(null, file, true);
    }

    public TiffReader(Path file, boolean requireValidTiff) throws IOException {
        this(null, file, requireValidTiff);
    }

    public TiffReader(Context context, Path file) throws IOException {
        this(context, file, true);
    }

    public TiffReader(Context context, Path file, boolean requireValidTiff) throws IOException {
        this(context, TiffTools.getExistingFileHandle(file), requireValidTiff);
    }

    public TiffReader(Context context, DataHandle<Location> in) throws IOException {
        this(context, in, true);
    }

    /**
     * Constructs new reader.
     *
     * <p>If <tt>requireValidTiff</tt> is <tt>true</tt> (standard variant), it will throw an exception
     * in a case of incorrect TIFF header or some other I/O errors.
     * If it is <tt>false</tt>, exceptions will be ignored, but {@link #isValid()} method will return <tt>false</tt>.
     *
     * @param context          SCIFIO context; may be <tt>null</tt>, but in this case some codecs
     *                         will possibly not work.
     * @param in               input stream.
     * @param requireValidTiff whether the input file must exist and be a readable TIFF-file with a correct header.
     * @throws IOException in a case of any problems with the input file; if the file is readable, but it is non-TIFF,
     *                     this exception will have {@link FormatException} as a cause.
     */
    public TiffReader(Context context, DataHandle<Location> in, boolean requireValidTiff) throws IOException {
        this(context, in, null);
        this.requireValidTiff = requireValidTiff;
        if (requireValidTiff && openingException != null) {
            if (openingException instanceof IOException e) {
                throw e;
            }
            throw new IOException(openingException);
        }
    }

    /**
     * Universal constructor, helping to make subclasses with constructors, which not throw exceptions.
     *
     * @param context          SCIFIO context; may be <tt>null</tt>, but in this case some codecs
     *                         will possibly not work.
     * @param in               input stream.
     * @param exceptionHandler if not <tt>null</tt>, it will be called in a case of some checked exception;
     *                         for example, it may log it. But usually it is better idea to use the main
     *                         constructor {@link #TiffReader(Context, DataHandle, boolean)} with catching exception.
     */
    protected TiffReader(
            Context context,
            DataHandle<Location> in,
            Consumer<Exception> exceptionHandler) {
        Objects.requireNonNull(in, "Null in stream");
        this.requireValidTiff = false;
        if (context != null) {
            setContext(context);
            scifio = new SCIFIO(context);
        } else {
            scifio = null;
        }
        this.in = in;
        AtomicBoolean bigTiff = new AtomicBoolean(false);
        this.openingException = readAndTestHeader(bigTiff);
        this.valid = openingException == null;
        this.bigTiff = bigTiff.get();
        if (exceptionHandler != null) {
            exceptionHandler.accept(openingException);
        }
    }

    @Deprecated
    protected void setAssumeEqualStrips(final boolean assumeEqualStrips) {
        this.assumeEqualStrips = assumeEqualStrips;
    }

    @Deprecated
    protected boolean isAssumeEqualStrips() {
        return assumeEqualStrips;
    }

    @Deprecated
    protected void setUse64BitOffsets(final boolean use64BitOffsets) {
        this.use64BitOffsets = use64BitOffsets;
    }

    @Deprecated
    protected boolean isUse64BitOffsets() {
        return use64BitOffsets;
    }


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
    public TiffReader setFiller(byte filler) {
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
    public TiffReader setRequireValidTiff(boolean requireValidTiff) {
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
    public TiffReader setAutoInterleave(boolean autoInterleave) {
        this.autoInterleave = autoInterleave;
        return this;
    }

    public boolean isAutoUnpackUnusualPrecisions() {
        return autoUnpackUnusualPrecisions;
    }

    public TiffReader setAutoUnpackUnusualPrecisions(boolean autoUnpackUnusualPrecisions) {
        this.autoUnpackUnusualPrecisions = autoUnpackUnusualPrecisions;
        return this;
    }

    public boolean isExtendedCodec() {
        return extendedCodec;
    }

    public TiffReader setExtendedCodec(boolean extendedCodec) {
        this.extendedCodec = extendedCodec;
        return this;
    }

    /**
     * Sets the codec options to be used when decompressing pixel data.
     *
     * @param codecOptions Codec options to use.
     * @return a reference to this object.
     */
    public TiffReader setCodecOptions(final CodecOptions codecOptions) {
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
     * Sets whether or not YCbCr color correction is allowed.
     */
    public TiffReader setYCbCrCorrection(final boolean yCbCrCorrection) {
        this.yCbCrCorrection = yCbCrCorrection;
        return this;
    }

    public boolean isYCbCrCorrection() {
        return yCbCrCorrection;
    }

    /**
     * Sets whether or not IFD entries should be cached.
     */
    public TiffReader setCachingIFDs(final boolean cachingIFDs) {
        this.cachingIFDs = cachingIFDs;
        return this;
    }

    public boolean isCachingIFDs() {
        return cachingIFDs;
    }

    /**
     * Gets the stream from which TIFF data is being parsed.
     */
    public DataHandle<Location> getStream() {
        return in;
    }

    public boolean isValid() {
        return valid;
    }

    /**
     * Returns whether or not the current TIFF file contains BigTIFF data.
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Returns whether or not we are reading little-endian data.
     * Determined in the constructors.
     */
    public boolean isLittleEndian() {
        return in.isLittleEndian();
    }

    /**
     * Returns position in the file of the last offset, loaded by {@link #getIFDOffsets()} or
     * {@link #getFirstOffset()} methods.
     * Usually it is just a position of the offset of the last IFD.
     *
     * @return file position of the last IFD offset.
     */
    public long getPositionOfLastOffset() {
        return positionOfLastOffset;
    }

    public DetailedIFD ifd(int ifdIndex) throws IOException {
        List<DetailedIFD> ifdList = allIFD();
        if (ifdIndex < 0 || ifdIndex >= ifdList.size()) {
            throw new IndexOutOfBoundsException(
                    "IFD index " + ifdIndex + " is out of bounds 0 <= i < " + ifdList.size());
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
            final DetailedIFD ifd = readIFD(offset);
            if (ifd == null) {
                continue;
            }
            if (ifd.containsKey(IFD.IMAGE_WIDTH)) {
                ifds.add(ifd);
            }
            long[] subOffsets = null;
            try {
                if (!cachingIFDs && ifd.containsKey(IFD.SUB_IFD)) {
                    fillInIFD(ifd);
                }
                subOffsets = ifd.getIFDLongArray(IFD.SUB_IFD);
            } catch (final FormatException ignored) {
            }
            if (subOffsets != null) {
                for (final long subOffset : subOffsets) {
                    final DetailedIFD sub = readIFD(subOffset, IFD.SUB_IFD);
                    if (sub != null) {
                        ifds.add(sub);
                    }
                }
            }
        }
        if (cachingIFDs) {
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
                final DetailedIFD exifIFD = readIFD(offset, IFD.EXIF);
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
        final long fileLength = in.length();
        final List<Long> offsets = new ArrayList<>();
        long offset = getFirstOffset();

        final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY : TiffConstants.BYTES_PER_ENTRY;
        while (offset > 0 && offset < fileLength) {
            in.seek(offset);
            offsets.add(offset);
            final long numberOfEntries = bigTiff ? in.readLong() : in.readUnsignedShort();
            if (numberOfEntries > Integer.MAX_VALUE / bytesPerEntry) {
                throw new IOException(
                        "Too many number of IFD entries in Big TIFF: " + numberOfEntries +
                                " (it is not supported, probably file is broken)");
            }
            long skippedIFDBytes = numberOfEntries * bytesPerEntry;
            if (offset + skippedIFDBytes >= in.length()) {
                throw new IOException(
                        "Invalid TIFF" + prettyInName() + ": position of next IFD offset " +
                                (offset + skippedIFDBytes) + " after " + numberOfEntries +
                                " entries is outside the file (probably file is broken)");
            }
            in.skipBytes((int) skippedIFDBytes);
            offset = readNextOffset(offset);
        }

        final long[] result = new long[offsets.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = offsets.get(i);
        }
        if (requireValidTiff && result.length <= 0) {
            throw new AssertionError("No IFDs, but it was not checked in getFirstOffset");
        }
        return result;
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
        final IFD ifd = readIFD(offset);
        if (cachingIFDs) {
            firstIFD = ifd;
        }
        return ifd;
    }

    /**
     * Gets offset to the first IFD, or -1 if stream is not TIFF.
     */
    public long getFirstOffset() throws IOException {
        if (!isValid()) {
            return -1;
        }
        in.seek(bigTiff ? 8 : 4);
        final long offset = readNextOffset(0);
        if (offset == 0) {
            throw new IOException("Invalid TIFF" + prettyInName() + ": zero first offset");
        }
        return offset;
    }

    /**
     * Reads the IFD stored at the given offset.
     * Never returns <tt>null</tt>.
     */
    public DetailedIFD readIFD(long offset) throws IOException {
        return readIFD(offset, null);
    }

    public DetailedIFD readIFD(long offset, Integer subIFDType) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("Negative file offset = " + offset);
        }
        if (offset >= in.length()) {
            throw new IOException("File offset " + offset + " is outside the file");
        }
        long t1 = traceTime();
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

        long timeEntries = 0;
        long timeArrays = 0;
        for (int i = 0; i < numEntries; i++) {
            long tEntry1 = traceTime();
            in.seek(offset + baseOffset + bytesPerEntry * (long) i);

            TiffIFDEntry entry;
            try {
                entry = readTiffIFDEntry();
            } catch (EnumException e) {
                continue;
                //!! - In the previous SCIFIO code, here is "break" operator, but why?
                // Maybe this is a type, added in future versions of TIFF format.
            }
            int count = entry.getValueCount();
            final int tag = entry.getTag();
            final long valueOffset = entry.getValueOffset();
            final int bpe = entry.getType().getBytesPerElement();
            long tEntry2 = traceTime();
            timeEntries += tEntry2 - tEntry1;

            if (count < 0 || bpe <= 0) {
                // invalid data
                in.skipBytes(bytesPerEntry - 4 - (bigTiff ? 8 : 4));
                continue;
            }

            final long inputLen = in.length();
            if ((long) count * (long) bpe + valueOffset > inputLen) {
                final int oldCount = count;
                count = (int) ((inputLen - valueOffset) / bpe);
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

            Object value;
            if (valueOffset != in.offset() && !cachingIFDs) {
                value = entry;
            } else {
                value = readIFDValue(entry);
            }
            long tEntry3 = traceTime();
            timeArrays += tEntry3 - tEntry2;
//            System.out.printf("%d values from %d: %.6f ms%n", count, valueOffset, (tEntry3 - tEntry2) * 1e-6);

            if (value != null && !ifd.containsKey(tag)) {
                entries.put(tag, entry);
                ifd.put(tag, value);
            }
        }
        ifd.setEntries(entries);

        in.seek(offset + baseOffset + bytesPerEntry * numEntries);
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_TRACE) {
            long t2 = traceTime();
            LOG.log(System.Logger.Level.TRACE, String.format(Locale.US,
                    "%s read IFD at offset %d: %.3f ms, including %.6f entries + %.6f arrays",
                    getClass().getSimpleName(), offset,
                    (t2 - t1) * 1e-6, timeEntries * 1e-6, timeArrays * 1e-6));
        }
        return ifd;
    }

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
                ifd.put(entry.getTag(), readIFDValue(entry));
            }
        }
    }

    /**
     * Retrieve the value corresponding to the given TiffIFDEntry.
     * Sometimes used to postpone actual reading data until actual necessity.
     */
    public Object readIFDValue(final TiffIFDEntry entry) throws IOException {
        final IFDType type = entry.getType();
        final int count = entry.getValueCount();
        final long offset = entry.getValueOffset();

        LOG.log(System.Logger.Level.TRACE, () ->
                "Reading entry " + entry.getTag() + " from " + offset + "; type=" + type + ", count=" + count);

        if (offset >= in.length()) {
            return null;
        }

        if (offset != in.offset()) {
            in.seek(offset);
        }

        if (type == IFDType.BYTE) {
            // 8-bit unsigned integer
            if (count == 1) {
                return (short) in.readByte();
            }
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
                if (strings != null && s != null) {
                    strings[c++] = s;
                }
            }
            return strings == null ? s : strings;
        } else if (type == IFDType.SHORT) {
            // 16-bit (2-byte) unsigned integer
            if (count == 1) {
                return in.readUnsignedShort();
            }
            if (OPTIMIZE_READING_IFD_ARRAYS) {
                final byte[] bytes = readIFDBytes(2 * (long) count);
                final short[] shorts = TiffTools.bytesToShortArray(bytes, in.isLittleEndian());
                final int[] result = new int[count];
                for (int j = 0; j < count; j++) {
                    result[j] = shorts[j] & 0xFFFF;
                }
                return result;
            } else {
                final int[] shorts = new int[count];
                for (int j = 0; j < count; j++) {
                    shorts[j] = in.readUnsignedShort();
                }
                return shorts;
            }
        } else if (type == IFDType.LONG || type == IFDType.IFD) {
            // 32-bit (4-byte) unsigned integer
            if (count == 1) {
                return (long) in.readInt();
            }
            if (OPTIMIZE_READING_IFD_ARRAYS) {
                final byte[] bytes = readIFDBytes(4 * (long) count);
                final int[] ints = TiffTools.bytesToIntArray(bytes, in.isLittleEndian());
                return Arrays.stream(ints).asLongStream().toArray();
            } else {
                final long[] longs = new long[count];
                for (int j = 0; j < count; j++) {
                    if (in.offset() + 4 <= in.length()) {
                        longs[j] = in.readInt();
                    }
                }
                return longs;
            }
        } else if (type == IFDType.LONG8 || type == IFDType.SLONG8 || type == IFDType.IFD8) {
            if (count == 1) {
                return in.readLong();
            }
            if (assumeEqualStrips && (entry.getTag() == IFD.STRIP_BYTE_COUNTS ||
                    entry.getTag() == IFD.TILE_BYTE_COUNTS)) {
                long[] longs = new long[1];
                longs[0] = in.readLong();
                return longs;
            } else if (assumeEqualStrips && (entry.getTag() == IFD.STRIP_OFFSETS ||
                    entry.getTag() == IFD.TILE_OFFSETS)) {
                final OnDemandLongArray offsets = new OnDemandLongArray(in);
                offsets.setSize(count);
                return offsets;
            } else {
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readIFDBytes(8 * (long) count);
                    return TiffTools.bytesToLongArray(bytes, in.isLittleEndian());
                } else {
                    long[] longs = new long[count];
                    for (int j = 0; j < count; j++) {
                        longs[j] = in.readLong();
                    }
                    return longs;
                }
            }
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
            if (count == 1) {
                return in.readByte();
            }
            final byte[] sbytes = new byte[count];
            in.read(sbytes);
            return sbytes;
        } else if (type == IFDType.SSHORT) {
            // A 16-bit (2-byte) signed (twos-complement) integer
            if (count == 1) {
                return in.readShort();
            }
            final short[] sshorts = new short[count];
            for (int j = 0; j < count; j++)
                sshorts[j] = in.readShort();
            return sshorts;
        } else if (type == IFDType.SLONG) {
            // A 32-bit (4-byte) signed (twos-complement) integer
            if (count == 1) {
                return in.readInt();
            }
            final int[] slongs = new int[count];
            for (int j = 0; j < count; j++)
                slongs[j] = in.readInt();
            return slongs;
        } else if (type == IFDType.FLOAT) {
            // Single precision (4-byte) IEEE format
            if (count == 1) {
                return in.readFloat();
            }
            final float[] floats = new float[count];
            for (int j = 0; j < count; j++)
                floats[j] = in.readFloat();
            return floats;
        } else if (type == IFDType.DOUBLE) {
            // Double precision (8-byte) IEEE format
            if (count == 1) {
                return in.readDouble();
            }
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
        final long offset = nValueBytes > threshhold ? readNextOffset(0) : in.offset();

        final TiffIFDEntry result = new TiffIFDEntry(entryTag, entryType, valueCount, offset);
        LOG.log(System.Logger.Level.TRACE, () -> String.format(
                "Reading IFD entry: %s - %s", result, DetailedIFD.ifdTagName(result.getTag(), true)));
        return result;
    }

    /**
     * Reads and decodes the tile at the specified position.
     * Note: the loaded tils is always {@link TiffTile#isSeparated() separated}.
     *
     * @param tileIndex position of the file
     * @return loaded tile.
     */
    public TiffTile readTile(TiffTileIndex tileIndex) throws FormatException, IOException {
        TiffTile tile = readEncodedTile(tileIndex);
        if (tile.isEmpty()) {
            return tile;
        }
        decode(tile);
        return tile;
    }

    public TiffTile readEncodedTile(TiffTileIndex tileIndex) throws FormatException, IOException {
        Objects.requireNonNull(tileIndex, "Null tileIndex");
        long t1 = debugTime();
        final DetailedIFD ifd = tileIndex.ifd();
        final int index = tileIndex.linearIndex();
        final int countIndex = assumeEqualStrips ? 0 : index;
        // - see getIFDValue(): if assumeEqualStrips, getStripByteCounts() will return long[1] array,
        // filled in getIFDValue(TiffIFDEntry)
        final long offset = ifd.cachedStripOffset(index);
        final int byteCount = ifd.cachedStripByteCount(countIndex);
        /*
        // Some strange old code, seems to be useless
        final int rowsPerStrip = ifd.cachedStripSizeY();
        final int bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
        if (byteCount == ((long) rowsPerStrip * tileSizeX) && bytesPerSample > 1) {
            byteCount *= bytesPerSample;
        }
        if (byteCount >= Integer.MAX_VALUE) {
            throw new FormatException("Too large tile/strip #" + index + ": " + byteCount + " bytes > 2^31-1");
        }
        */

        final TiffTile result = tileIndex.newTile();
        if (byteCount == 0 || offset < 0 || offset >= in.length()) {
            // - We support a special case of empty result
            return result;
        }

        TiffTileIO.read(result, in, offset, byteCount);
        long t2 = debugTime();
        timeReading += t2 - t1;
        return result;
    }

    // Note: result is usually interleaved (RGBRGB...) or monochrome; it is always so in UNCOMPRESSED, LZW, DEFLATE
    public void decode(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        long t1 = debugTime();
        correctEncodedJpegTile(tile);

        DetailedIFD ifd = tile.ifd();
        byte[] encodedData = tile.getEncodedData();
        final TiffCompression compression = ifd.getCompression();

        final KnownTiffCompression known = KnownTiffCompression.valueOfOrNull(compression);
        Codec codec = null;
        if (extendedCodec && known != null) {
            codec = known.extendedCodec(scifio == null ? null : scifio.getContext());
        }
        if (codec == null && scifio == null && known != null) {
            codec = known.noContextCodec();
            // - if there is no SCIFIO context, let's create codec directly: it's better than do nothing
        }
        final CodecOptions codecOptions = buildReadingOptions(tile, codec);

        long t2 = debugTime();
        if (codec != null) {
            tile.setDecodedData(codecDecompress(encodedData, codec, codecOptions));
        } else {
            if (scifio == null) {
                throw new IllegalStateException(
                        "Compression type " + compression + " requires specifying non-null SCIFIO context");
            }
            tile.setDecodedData(compression.decompress(scifio.codec(), encodedData, codecOptions));
        }
        tile.setInterleaved(codecOptions.interleaved);
        long t3 = debugTime();

        completeDecoding(tile);
        long t4 = debugTime();

        timeCustomizingDecoding += t2 - t1;
        timeDecoding += t3 - t2;
        timeCompleteDecoding += t4 - t3;
    }

    public void correctEncodedJpegTile(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        DetailedIFD ifd = tile.ifd();
        final TiffCompression compression = ifd.getCompression();
        if (KnownTiffCompression.isJpeg(compression)) {
            final byte[] data = tile.getEncodedData();
            final byte[] jpegTable = (byte[]) ifd.getIFDValue(IFD.JPEG_TABLES);
            // Structure of data:
            //      FF D8 (SOI, start of image)
            //      FF C0 (SOF0, start of frame, or some other marker)
            //      ...
            //      FF D9 (EOI, end of image)
            // Structure of jpegTable:
            //      FF D8 (SOI, start of image)
            //      FF DB (DQT, define quantization table(s)
            //      ...
            //      FF D9 (EOI, end of image)
            // From libtiff specification:
            //      When the JPEGTables field is present, it shall contain a valid JPEG
            //      "abbreviated table specification" datastream.  This datastream shall begin
            //      with SOI and end with EOI.
            if (data.length < 2 || data[0] != (byte) 0xFF || data[1] != (byte) 0xD8) {
                // - the same check is performed inside Java API ImageIO (JPEGImageReaderSpi),
                // and we prefer to repeat it here for better diagnostics
                throw new FormatException(
                        (compression == TiffCompression.JPEG ? "Invalid" : "Unsupported format of") +
                                " TIFF content: it is declared as \"" + compression.getCodecName() +
                                "\", but the data are not actually JPEG");
            }
            if (jpegTable != null) {
                if (jpegTable.length <= 4) {
                    throw new FormatException("Too short JPEGTables tag: only " + jpegTable.length + " bytes");
                }
                if ((long) jpegTable.length + (long) data.length - 4 >= Integer.MAX_VALUE) {
                    // - very improbable
                    throw new FormatException(
                            "Too large tile/strip at " + tile.tileIndex() + ": JPEG table length " +
                                    (jpegTable.length - 2) + " + number of bytes " +
                                    (data.length - 2) + " > 2^31-1");

                }
                final byte[] appended = new byte[jpegTable.length + data.length - 4];
                appended[0] = (byte) 0xFF;
                appended[1] = (byte) 0xD8;
                // - writing SOI
                System.arraycopy(jpegTable, 2, appended, 2, jpegTable.length - 4);
                // - skipping both SOI and EOI (2 first and 2 last bytes) from jpegTable
                System.arraycopy(data, 2, appended, jpegTable.length - 2, data.length - 2);
                // - skipping SOI (2 first bytes) from main data
                tile.setData(appended);
            }
        }
    }

    public void completeDecoding(TiffTile tile) throws FormatException {
        // scifio.tiff().undifference(tile.getDecodedData(), tile.ifd());
        // - this solution requires using SCIFIO context class; it is better to avoid this
        TiffTools.undifferenceIfRequested(tile);

        if (!decodeYCbCr(tile)) {
            unpackBytes(tile);
        }

        /*
        // The following code is equivalent to the analogous code from SCIFIO 0.46.0 and earlier version,
        // which performed correction of channels with different precision in a rare case PLANAR_CONFIG_SEPARATE.
        // But it is deprecated: we do not support different number of BYTES in different samples,
        // and it is checked inside getBytesPerSampleBasedOnBits() method.

        byte[] samples = tile.getDecodedData();
        final TiffTileIndex tileIndex = tile.tileIndex();
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
        */

        TiffTools.invertFillOrderIfRequested(tile);
    }

    public byte[] readSamples(final DetailedIFD ifd) throws FormatException, IOException {
        return readSamples(ifd, 0, 0, ifd.getImageSizeX(), ifd.getImageSizeY());
    }

    /**
     * Reads samples in <tt>byte[]</tt> array.
     *
     * <p>Note: you should not change IFD in a parallel thread while calling this method.
     *
     * @return loaded samples in a normalized form of byte sequence.
     */
    public byte[] readSamples(DetailedIFD ifd, int fromX, int fromY, int sizeX, int sizeY)
            throws FormatException, IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        clearTime();
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        // - note: we allow this area to be outside the image
        final TiffTileSet tileSet = new TiffTileSet(ifd, false);
        final int numberOfChannels = tileSet.numberOfChannels();
        final int size = ifd.sizeOfRegionBasedOnType(sizeX, sizeY);
        // - also checks that sizeX/sizeY are allowed and that
        //      sizeX * sizeY * ifd.getSamplesPerPixel() * ifd.getBytesPerSampleBasedOnType()
        // is calculated without overflow
        assert sizeX >= 0 && sizeY >= 0 : "sizeOfIFDRegion didn't check sizes accurately: " + sizeX + "fromX" + sizeY;
        byte[] samples = new byte[size];
        if (size == 0) {
            return samples;
        }

        ifd.sizeOfTileBasedOnBits();
        // - checks that we can multiply tile sizes by bytesPerSample and ifd.getSamplesPerPixel() without overflow
        long t1 = debugTime();
        Arrays.fill(samples, 0, size, filler);
        // - important for a case when the requested area is outside the image;
        // old SCIFIO code did not check this and could return undefined results

        readTiles(tileSet, samples, fromX, fromY, sizeX, sizeY);

        long t2 = debugTime();
        if (autoUnpackUnusualPrecisions) {
            samples = TiffTools.unpackUnusualPrecisions(samples, ifd, numberOfChannels, sizeX * sizeY);
        }
        long t3 = debugTime();
        if (autoInterleave) {
            samples = TiffTools.toInterleavedSamples(
                    samples, numberOfChannels, ifd.getBytesPerSampleBasedOnType(), sizeX * sizeY);
        }
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t4 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f read/decode " +
                            "(%.3f read + %.3f customize + %.3f decode + %.3f complete) + " +
                            "%.3f unusual precisions + %.3f interleave, %.3f MB/s",
                    getClass().getSimpleName(),
                    numberOfChannels, sizeX, sizeY, size / 1048576.0,
                    (t4 - t1) * 1e-6,
                    (t2 - t1) * 1e-6,
                    timeReading * 1e-6,
                    timeCustomizingDecoding * 1e-6,
                    timeDecoding * 1e-6,
                    timeCompleteDecoding * 1e-6,
                    (t3 - t2) * 1e-6, (t4 - t3) * 1e-6,
                    size / 1048576.0 / ((t4 - t1) * 1e-9)));
        }
        return samples;
    }

    public Object readSamplesArray(DetailedIFD ifd, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException, FormatException {
        return readSamplesArray(
                ifd, fromX, fromY, sizeX, sizeY, null, null);
    }

    public Object readSamplesArray(
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
            throw new FormatException(
                    "Number of bands mismatch: expected " + requiredSamplesPerPixel
                            + " samples per pixel, but IFD image contains " + ifd.getSamplesPerPixel()
                            + " samples per pixel");
        }
        if (requiredElementType != null && optionalElementType(ifd).orElse(null) != requiredElementType) {
            throw new FormatException(
                    "Element type mismatch: expected " + requiredElementType
                            + "[] elements, but some IFD image contains "
                            + optionalElementType(ifd).map(Class::getName).orElse("unknown")
                            + "[] elements");
        }
        final byte[] samples = readSamples(ifd, fromX, fromY, sizeX, sizeY);
        long t1 = debugTime();
        final Object samplesArray = TiffTools.bytesToArray(samples, ifd.getPixelType(), ifd.isLittleEndian());
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

    @Override
    public void close() throws IOException {
        in.close();
    }

    private void clearTime() {
        timeReading = 0;
        timeCustomizingDecoding = 0;
        timeDecoding = 0;
        timeCompleteDecoding = 0;
    }

    private Exception readAndTestHeader(AtomicBoolean bigTiff) {
        try {
            if (!in.exists()) {
                return new FileNotFoundException("Input TIFF data" + prettyInName() + " does not exist");
            }
            final long length = in.length();
            if (length < 10) {
                // - sometimes we can meet 8-byte "TIFF-files", containing only header and no actual data
                // (for example, results of debugging writing algorithm)
                return new FormatException("Too short TIFF file" + prettyInName() + ": only " + length + " bytes");
            }
            return testHeader(bigTiff);
        } catch (IOException e) {
            return e;
        }
    }

    private FormatException testHeader(AtomicBoolean bigTiff) throws IOException {
        final long savedOffset = in.offset();
        in.seek(0);
        try {
            final int endianOne = in.read();
            final int endianTwo = in.read();
            // byte order must be II or MM
            final boolean littleEndian = endianOne == TiffConstants.LITTLE && endianTwo == TiffConstants.LITTLE; // II
            final boolean bigEndian = endianOne == TiffConstants.BIG && endianTwo == TiffConstants.BIG; // MM
            if (!littleEndian && !bigEndian) {
                return new FormatException("The file" + prettyInName() + " is not TIFF");
            }

            // check magic number (42)
            in.setLittleEndian(littleEndian);
            final short magic = in.readShort();
            bigTiff.set(magic == TiffConstants.BIG_TIFF_MAGIC_NUMBER);
            if (magic != TiffConstants.MAGIC_NUMBER && magic != TiffConstants.BIG_TIFF_MAGIC_NUMBER) {
                return new FormatException("The file" + prettyInName() + " is not TIFF");
            }
        } finally {
            in.seek(savedOffset);
            // - for maximal compatibility: in old versions, constructor of this class
            // guaranteed that file position in the input stream will not change
            // (that is illogical, because "little-endian" mode was still changed)
        }
        return null;
    }

    private CodecOptions buildReadingOptions(TiffTile tile, Codec customCodec) throws FormatException {
        DetailedIFD ifd = tile.ifd();
        final int samplesLength = tile.tileSet().tileSizeInBytes();
        CodecOptions codecOptions = new CodecOptions(this.codecOptions);
        codecOptions.littleEndian = ifd.isLittleEndian();
        codecOptions.maxBytes = Math.max(samplesLength, tile.getStoredDataLength());
        final int subSampleHorizontal = ifd.getIFDIntValue(IFD.Y_CB_CR_SUB_SAMPLING);
        // - Usually it is an array [1,1], [2,2], [2,1] or something like this:
        // see documentation on YCbCrSubSampling TIFF tag.
        // Default is [2,2].
        // getIFDIntValue method returns the element #0, i.e. horizontal sub-sampling
        // Value 1 means "ImageWidth of this chroma image is equal to the ImageWidth of the associated luma image".
        codecOptions.ycbcr =
                ifd.getPhotometricInterpretation() == PhotoInterp.Y_CB_CR
                        && subSampleHorizontal == 1
                        && yCbCrCorrection;
        // Rare case: Y_CB_CR is encoded with non-standard sub-sampling
        codecOptions.interleaved =
                !(customCodec instanceof ExtendedJPEGCodec || ifd.getCompression() == TiffCompression.JPEG);
        // - ExtendedJPEGCodec and standard codec JPEFCodec (it may be chosen below by scifio.codec(),
        // but we are sure that JPEG compression will be served by it even in future versions)
        // "understand" this settings well (unlike LZW or DECOMPRESSED codecs,
        // which suppose that data are interleaved according TIFF format specification).
        // Value "true" is necessary for other codecs, that work with high-level classes (like JPEG or JPEG-2000) and
        // need to be instructed to interleave results (unlike LZW or DECOMPRESSED, which work with data "as-is").
        return codecOptions;
    }

    // Note: this method does not store tile in the tile set.
    private void readTiles(TiffTileSet tileSet, byte[] resultSamples, int fromX, int fromY, int sizeX, int sizeY)
            throws FormatException, IOException {
        Objects.requireNonNull(tileSet, "Null tileSet");
        Objects.requireNonNull(resultSamples, "Null resultSamples");
        assert fromX >= 0 && fromY >= 0 && sizeX >= 0 && sizeY >= 0;
        // Note: we cannot process image larger than 2^31 x 2^31 pixels,
        // though TIFF supports maximal sizes 2^32 x 2^32
        // (IFD.getImageWidth/getImageLength do not allow so large results)

        final int tileSizeX = tileSet.tileSizeX();
        final int tileSizeY = tileSet.tileSizeY();
        final int bytesPerSample = tileSet.bytesPerSample();
        final int numberOfSeparatedPlanes = tileSet.numberOfSeparatedPlanes();
        final int channelsPerPixel = tileSet.channelsPerPixel();

        final int toX = Math.min(fromX + sizeX, tileSet.getSizeX());
        final int toY = Math.min(fromY + sizeY, tileSet.getSizeY());
        // - crop by image sizes to avoid reading unpredictable content of the boundary tiles outside the image
        final int minXIndex = fromX / tileSizeX;
        final int minYIndex = fromY / tileSizeY;
        if (minXIndex >= tileSet.tileCountX() || minYIndex >= tileSet.tileCountY() || toX < fromX || toY < fromY) {
            return;
        }
        final int maxXIndex = Math.min(tileSet.tileCountX() - 1, (toX - 1) / tileSizeX);
        final int maxYIndex = Math.min(tileSet.tileCountY() - 1, (toY - 1) / tileSizeY);
        assert minYIndex <= maxYIndex && minXIndex <= maxXIndex;
        final int tileRowSizeInBytes = tileSizeX * bytesPerSample;
        final int outputRowSizeInBytes = sizeX * bytesPerSample;

        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            // - for a rare case PlanarConfiguration=2 (RRR...GGG...BBB...)
            for (int yIndex = minYIndex; yIndex <= maxYIndex; yIndex++) {
                for (int xIndex = minXIndex; xIndex <= maxXIndex; xIndex++) {
                    final TiffTile tile = readTile(tileSet.tileIndex(p, xIndex, yIndex));
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
                    for (int c = 0; c < channelsPerPixel; c++) {
                        int srcOffset = (((c * tileSizeY) + yInTile) * tileSizeX + xInTile) * bytesPerSample;
                        int destOffset = (((c + p) * sizeY + yDiff) * sizeX + xDiff) * bytesPerSample;
                        for (int tileRow = 0; tileRow < partSizeY; tileRow++) {
                            System.arraycopy(data, srcOffset, resultSamples, destOffset, partSizeXInBytes);
                            srcOffset += tileRowSizeInBytes;
                            destOffset += outputRowSizeInBytes;
                        }
                    }
                }
            }
        }
    }


    // Unlike AbstractCodec.decompress, this method does not require using "handles" field, annotated as @Parameter
    // This function is not universal, it cannot be applied to any codec!
    private static byte[] codecDecompress(byte[] data, Codec codec, CodecOptions options) throws FormatException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(codec, "Null codec");
        if (codec instanceof PassthroughCodec) {
            // - PassthroughCodec does not support decompress(DataHandle) method, called below
            return data;
        }
        try (DataHandle<Location> handle = TiffTools.getBytesHandle(new BytesLocation(data))) {
            return codec.decompress(handle, options);
        } catch (final IOException e) {
            throw new FormatException(e);
        }
    }

    private String prettyInName() {
        return prettyFileName(" %s", in);
    }

    // Made from unpackBytes method of old TiffParser
    // Processing Y_CB_CR is extracted to decodeYCbCr() method.
    private static void unpackBytes(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile);
        final DetailedIFD ifd = tile.ifd();
        byte[] bytes = tile.getDecodedData();

        if (KnownTiffCompression.isNonJpegYCbCr(ifd)) {
            throw new IllegalArgumentException("Y_CB_CR photometric interpretation should be processed separately");
        }

        final PhotoInterp photoInterpretation = ifd.getPhotometricInterpretation();
        final int samplesLength = tile.tileSet().tileSizeInBytes();
        final int[] bitsPerSample = ifd.getBitsPerSample();
        final int bps0 = bitsPerSample[0];
        final boolean equalBitsPerSample = Arrays.stream(bitsPerSample).allMatch(t -> t == bps0);
        final boolean usualPrecision = equalBitsPerSample && (bps0 == 8 || bps0 == 16 || bps0 == 32 || bps0 == 64);

        // Hyper optimisation that takes any 8-bit or 16-bit data, where there is
        // only one channel, the source byte buffer's size is less than or equal to
        // that of the destination buffer and for which no special unpacking is
        // required and performs a simple array copy. Over the course of reading
        // semi-large datasets this can save **billions** of method calls.
        // Wed Aug 5 19:04:59 BST 2009
        // Chris Allan <callan@glencoesoftware.com>
        if (usualPrecision &&
                bytes.length <= samplesLength &&
                photoInterpretation != PhotoInterp.WHITE_IS_ZERO &&
                photoInterpretation != PhotoInterp.CMYK) {
            if (bytes.length < samplesLength) {
                // Note: bytes.length is unpredictable, because it is the result of decompression by a codec;
                // we need to check it before quick returning
                // Daniel Alievsky
                bytes = Arrays.copyOf(bytes, samplesLength);
            }
            tile.setDecodedData(bytes);
            tile.separateSamplesIfNecessary();
            return;
        }

        final int numberOfChannels = tile.channelsPerPixel();
        final boolean noDiv8 = bps0 % 8 != 0;
        long sampleCount = (long) 8 * bytes.length / bitsPerSample[0];
        if (!tile.isPlanarSeparated()) {
            sampleCount /= numberOfChannels;
        }
        if (sampleCount > Integer.MAX_VALUE) {
            throw new FormatException("Too large tile: " + sampleCount + " >= 2^31 actual samples");
        }

        final long imageWidth = tile.getSizeX();
        final long imageHeight = tile.getSizeY();

        final int bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
        final int numberOfPixels = samplesLength / (numberOfChannels * bytesPerSample);

        final boolean littleEndian = ifd.isLittleEndian();

        final BitBuffer bb = new BitBuffer(bytes);

        final byte[] unpacked = new byte[samplesLength];

        long maxValue = (long) Math.pow(2, bps0) - 1;
        if (photoInterpretation == PhotoInterp.CMYK) maxValue = Integer.MAX_VALUE;

        int skipBits = (int) (8 - ((imageWidth * bps0 * numberOfChannels) % 8));
        if (skipBits == 8 || ((long) bytes.length * 8 < bps0 * (numberOfChannels * imageWidth + imageHeight))) {
            skipBits = 0;
        }

        // unpack pixels
        for (int i = 0; i < sampleCount; i++) {
            if (i >= numberOfPixels) break;

            for (int channel = 0; channel < numberOfChannels; channel++) {
                final int index = bytesPerSample * (i * numberOfChannels + channel);
                final int outputIndex = (channel * numberOfPixels + i) * bytesPerSample;

                // unpack non-YCbCr samples
                long value = 0;

                if (noDiv8) {
                    // bits per sample is not a multiple of 8

                    if ((channel == 0 && photoInterpretation == PhotoInterp.RGB_PALETTE) ||
                            (photoInterpretation != PhotoInterp.CFA_ARRAY &&
                                    photoInterpretation != PhotoInterp.RGB_PALETTE)) {
                        value = bb.getBits(bps0) & 0xffff;
                        if ((i % imageWidth) == imageWidth - 1) {
                            bb.skipBits(skipBits);
                        }
                    }
                } else {
                    value = Bytes.toLong(bytes, index, bytesPerSample, littleEndian);
                }

                if (photoInterpretation == PhotoInterp.WHITE_IS_ZERO ||
                        photoInterpretation == PhotoInterp.CMYK) {
                    value = maxValue - value;
                }

                if (outputIndex + bytesPerSample <= unpacked.length) {
                    Bytes.unpack(value, unpacked, outputIndex, bytesPerSample, littleEndian);
                }
            }
        }
        tile.setDecodedData(unpacked);
        tile.setInterleaved(false);
    }

    private static boolean decodeYCbCr(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile);
        final DetailedIFD ifd = tile.ifd();
        byte[] bytes = tile.getDecodedData();

        if (!KnownTiffCompression.isNonJpegYCbCr(ifd)) {
            return false;
        }
        // - JPEG codec, based on Java API BufferedImage, always returns RGB data

        if (ifd.isPlanarSeparated()) {
            // - there is no simple way to support this exotic situation: Cb/Cr values are saved in other tiles
            throw new FormatException("TIFF YCbCr photometric interpretation is not supported in planar format " +
                    "(separated component plances)");
        }

        final int samplesLength = tile.tileSet().tileSizeInBytes();
        final int[] bitsPerSample = ifd.getBitsPerSample();

        final int channels = ifd.isPlanarSeparated() ? 1 : ifd.getSamplesPerPixel();
        final long sampleCount = (long) 8 * bytes.length / bitsPerSample[0];
        if (channels != 3) {
            throw new FormatException("TIFF YCbCr photometric interpretation requires 3 channels, but " +
                    "there are " + channels + " channels in SamplesPerPixel TIFF tag");
        }
        // - Note: we do not divide sampleCount by number of channels!
        // It is necessary, for example, for unpacking dscf0013.tif from libtiffpic examples set
        if (sampleCount > Integer.MAX_VALUE) {
            throw new FormatException("Too large tile: " + sampleCount + " >= 2^31 actual samples");
        }

        final long tileSizeX = tile.getSizeX();

        final int bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
        final int numberOfPixels = samplesLength / (channels * bytesPerSample);

        final byte[] unpacked = new byte[samplesLength];

        // unpack pixels
        // set up YCbCr-specific values
        float lumaRed = PhotoInterp.LUMA_RED;
        float lumaGreen = PhotoInterp.LUMA_GREEN;
        float lumaBlue = PhotoInterp.LUMA_BLUE;
        int[] reference = ifd.getIFDIntArray(IFD.REFERENCE_BLACK_WHITE);
        if (reference == null) {
            reference = new int[]{0, 0, 0, 0, 0, 0};
        }
        final int[] subsampling = ifd.getIFDIntArray(IFD.Y_CB_CR_SUB_SAMPLING);
        final TiffRational[] coefficients = (TiffRational[]) ifd.getIFDValue(IFD.Y_CB_CR_COEFFICIENTS);
        if (coefficients != null) {
            lumaRed = coefficients[0].floatValue();
            lumaGreen = coefficients[1].floatValue();
            lumaBlue = coefficients[2].floatValue();
        }
        final double lumaGreenInv = 1.0 / lumaGreen;
        final int subX = subsampling == null ? 2 : subsampling[0];
        final int subY = subsampling == null ? 2 : subsampling[1];
        final int block = subX * subY;
        final int nTiles = (int) (tileSizeX / subX);
        for (int i = 0; i < sampleCount; i++) {
            if (i >= numberOfPixels) break;

            for (int channel = 0; channel < channels; channel++) {
                // unpack non-YCbCr samples
                // unpack YCbCr unpacked; these need special handling, as
                // each of the RGB components depends upon two or more of the YCbCr
                // components
                if (channel == channels - 1) {
                    final int lumaIndex = i + (2 * (i / block));
                    final int chromaIndex = (i / block) * (block + 2) + block;

                    if (chromaIndex + 1 >= bytes.length) break;

                    final int tileIndex = i / block;
                    final int pixel = i % block;
                    final long r = subY * (tileIndex / nTiles) + (pixel / subX);
                    final long c = subX * (tileIndex % nTiles) + (pixel % subX);

                    final int idx = (int) (r * tileSizeX + c);

                    if (idx < numberOfPixels) {
                        final int y = (bytes[lumaIndex] & 0xff) - reference[0];
                        final int cb = (bytes[chromaIndex] & 0xff) - reference[2];
                        final int cr = (bytes[chromaIndex + 1] & 0xff) - reference[4];

                        final double red = cr * (2 - 2 * lumaRed) + y;
                        final double blue = cb * (2 - 2 * lumaBlue) + y;
                        final double green = (y - lumaBlue * blue - lumaRed * red) * lumaGreenInv;

//                            unpacked[idx] = (byte) (red & 0xff);
//                            unpacked[nSamples + idx] = (byte) (green & 0xff);
//                            unpacked[2 * nSamples + idx] = (byte) (blue & 0xff);
                        unpacked[idx] = (byte) toUnsignedByte(red);
                        unpacked[numberOfPixels + idx] = (byte) toUnsignedByte(green);
                        unpacked[2 * numberOfPixels + idx] = (byte) toUnsignedByte(blue);
                    }
                }
            }
        }

        tile.setDecodedData(unpacked);
        tile.setInterleaved(false);
        return true;
    }

    /**
     * Read a file offset. For bigTiff, a 64-bit number is read. For other Tiffs,
     * a 32-bit number is read and possibly adjusted for a possible carry-over
     * from the previous offset.
     */
    private long readNextOffset(final long previous) throws IOException {
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
                throw new IOException(
                        "Invalid TIFF" + prettyInName() + ": negative offset " + offset + " at file position " + fp);
            }
            if (offset >= in.length()) {
                throw new IOException(
                        "Invalid TIFF" + prettyInName() + ": offset " + offset + " at file position " + fp +
                                " is outside the file");
            }
        }
        this.positionOfLastOffset = fp;
        return offset;
    }

    private byte[] readIFDBytes(long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IOException("Too large IFD value: " + length + " >= 2^31 bytes");
        }
        byte[] bytes = new byte[(int) length];
        in.readFully(bytes);
        return bytes;
    }

    private static int toUnsignedByte(double v) {
        return v < 0.0 ? 0 : v > 255.0 ? 255 : (int) Math.round(v);
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

    private static long traceTime() {
        return TiffTools.BUILT_IN_TIMING && LOGGABLE_TRACE ? System.nanoTime() : 0;
    }
}
