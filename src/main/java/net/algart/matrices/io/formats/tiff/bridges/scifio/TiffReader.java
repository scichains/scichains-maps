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
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTile;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTileIO;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTileIndex;
import org.scijava.AbstractContextual;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.ReadBufferDataHandle;
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
import java.util.stream.Collectors;

/**
 * Reads TIFF format.
 *
 * <p>This object is internally synchronized and thread-safe when used in multi-threaded environment.
 * However, you should not modify objects, passed to the methods of this class from a parallel thread;
 * first of all, it concerns the {@link DetailedIFD} arguments of many methods.
 * The same is true for the result of {@link #getStream()} method.</p>
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

    /**
     * IFD with number of entries, greater than this limit, is not allowed:
     * it is mostly probable that it is corrupted file.
     */
    public static int MAX_NUMBER_OF_IFD_ENTRIES = 1_000_000;

    private static final boolean OPTIMIZE_READING_IFD_ARRAYS = true;
    // - Note: this optimization allows to speed up reading large array of offsets.
    // If we use simple FileHandle for reading file (based on RandomAccessFile),
    // acceleration is up to 100 and more times:
    // on my computer, 23220 int32 values were loaded in 0.2 ms instead of 570 ms.
    // Since scijava-common 2.95.1, we use optimized ReadBufferDataHandle for reading file;
    // now acceleration for 23220 int32 values is 0.2 ms instead of 0.4 ms.

    private static final boolean USE_OLD_UNPACK_BYTES = false;
    // - Should be false for better performance; necessary for debugging needs only.

    private static final int MINIMAL_ALLOWED_TIFF_FILE_LENGTH = 8 + 2 + 12 + 4;
    // - 8 bytes header + at least 1 IFD entry (usually at least 2 entries required: ImageWidth + ImageLength);
    // this constant should be > 16 to detect "dummy" BigTIFF file, containing header only

    private static final System.Logger LOG = System.getLogger(TiffReader.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private boolean requireValidTiff;
    private boolean interleaveResults = false;
    private boolean autoUnpackUnusualPrecisions = true;
    private boolean extendedCodec = true;
    private boolean cropTilesToImageBoundaries = true;
    private boolean assumeEqualStrips = false;
    private boolean yCbCrCorrection = true;
    private boolean cachingIFDs = true;
    private boolean missingTilesAllowed = false;
    private byte byteFiller = 0;

    private final Exception openingException;
    private final DataHandle<Location> in;
    private final boolean valid;
    private final boolean bigTiff;

    /**
     * Cached list of IFDs in the current file.
     */
    private volatile List<DetailedIFD> ifds;

    /**
     * Cached first IFD in the current file.
     */
    private volatile DetailedIFD firstIFD;
    private final SCIFIO scifio;

    private final Object fileLock = new Object();

    /**
     * Codec options to be used when decoding compressed pixel data.
     */
    CodecOptions codecOptions = CodecOptions.getDefaultOptions();

    private volatile long positionOfLastIFDOffset = -1;

    private long timeReading = 0;
    private long timeCustomizingDecoding = 0;
    private long timeDecoding = 0;
    private long timeCompleteDecoding = 0;

    public TiffReader(Path file) throws IOException, FormatException {
        this(null, file, true);
    }

    public TiffReader(Path file, boolean requireValidTiff) throws IOException, FormatException {
        this(null, file, requireValidTiff);
    }

    public TiffReader(Context context, Path file) throws IOException, FormatException {
        this(context, file, true);
    }

    public TiffReader(Context context, Path file, boolean requireValidTiff) throws IOException, FormatException {
        this(context, TiffTools.getExistingFileHandle(file), requireValidTiff);
    }

    public TiffReader(Context context, DataHandle<Location> in) throws IOException, FormatException {
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
     * @throws IOException     in a case of any problems with the input file
     * @throws FormatException if the file is not a correct TIFF file
     */
    public TiffReader(Context context, DataHandle<Location> in, boolean requireValidTiff)
            throws IOException, FormatException {
        this(context, in, null);
        this.requireValidTiff = requireValidTiff;
        if (requireValidTiff && openingException != null) {
            if (openingException instanceof IOException e) {
                throw e;
            }
            if (openingException instanceof FormatException e) {
                throw e;
            }
            if (openingException instanceof RuntimeException e) {
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
        this.in = in instanceof ReadBufferDataHandle ? in : new ReadBufferDataHandle<>(in);
        AtomicBoolean bigTiff = new AtomicBoolean(false);
        this.openingException = startReading(bigTiff);
        this.valid = openingException == null;
        this.bigTiff = bigTiff.get();
        if (exceptionHandler != null) {
            exceptionHandler.accept(openingException);
        }
    }

    @Deprecated
    protected boolean isAssumeEqualStrips() {
        return assumeEqualStrips;
    }

    @Deprecated
    protected void setAssumeEqualStrips(final boolean assumeEqualStrips) {
        this.assumeEqualStrips = assumeEqualStrips;
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

    public boolean isInterleaveResults() {
        return interleaveResults;
    }

    /**
     * Sets the interleave mode: the loaded samples will be returned in chunked form, for example, RGBRGBRGB...
     * in a case of RGB image. If not set (default behaviour), the samples are returned in unpacked form:
     * RRR...GGG...BBB...
     *
     * @param interleaveResults new interleaving mode.
     * @return a reference to this object.
     */
    public TiffReader setInterleaveResults(boolean interleaveResults) {
        this.interleaveResults = interleaveResults;
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

    public boolean isCropTilesToImageBoundaries() {
        return cropTilesToImageBoundaries;
    }

    public TiffReader setCropTilesToImageBoundaries(boolean cropTilesToImageBoundaries) {
        this.cropTilesToImageBoundaries = cropTilesToImageBoundaries;
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
     * Sets the codec options to be used when decompressing pixel data.
     *
     * @param codecOptions Codec options to use.
     * @return a reference to this object.
     */
    public TiffReader setCodecOptions(final CodecOptions codecOptions) {
        this.codecOptions = codecOptions;
        return this;
    }

    public boolean isYCbCrCorrection() {
        return yCbCrCorrection;
    }

    /**
     * Sets whether or not YCbCr color correction is allowed.
     */
    public TiffReader setYCbCrCorrection(final boolean yCbCrCorrection) {
        this.yCbCrCorrection = yCbCrCorrection;
        return this;
    }

    public boolean isCachingIFDs() {
        return cachingIFDs;
    }

    /**
     * Sets whether IFD entries, returned by {@link #allIFDs()} method, should be cached.
     *
     * <p>Default value is <tt>true</tt>. Possible reason to set is to <tt>false</tt>
     * is reading file which is dynamically modified.
     * In other cases, usually it should be <tt>true</tt>, though <tt>false</tt> value
     * also works well if you are not going to call {@link #allIFDs()} more than once.
     *
     * @param cachingIFDs whether caching IFD is enabled.
     * @return a reference to this object.
     */
    public TiffReader setCachingIFDs(final boolean cachingIFDs) {
        this.cachingIFDs = cachingIFDs;
        return this;
    }

    public boolean isMissingTilesAllowed() {
        return missingTilesAllowed;
    }

    /**
     * Sets the special mode, when TIFF file is allowed to contain "missing" tiles or strips,
     * for which the offset (<tt>TileOffsets</tt> or <tt>StripOffsets</tt> tag) and/or
     * byte count (<tt>TileByteCounts</tt> or <tt>StripByteCounts</tt> tag) contains zero value.
     * In this mode, such tiles/strips will be successfully read as empty rectangles, filled by
     * the {@link #setByteFiller(byte) default filler}.
     *
     * <p>Default value is <tt>false</tt>. In this case, such tiles/strips are not allowed,
     * as the standard TIFF format requires.
     *
     * @param missingTilesAllowed whether "missing" tiles/strips are allowed.
     * @return a reference to this object.
     */
    public TiffReader setMissingTilesAllowed(boolean missingTilesAllowed) {
        this.missingTilesAllowed = missingTilesAllowed;
        return this;
    }

    public byte getByteFiller() {
        return byteFiller;
    }

    /**
     * Sets the filler byte for tiles, lying completely outside the image.
     * Value 0 means black color, 0xFF usually means white color.
     *
     * <p><b>Warning!</b> If you want to work with non-8-bit TIFF, especially float precision, you should
     * preserve default 0 value, in other case results could be very strange.
     *
     * @param byteFiller new filler.
     * @return a reference to this object.
     */
    public TiffReader setByteFiller(byte byteFiller) {
        this.byteFiller = byteFiller;
        return this;
    }

    /**
     * Gets the stream from which TIFF data is being parsed.
     */
    public DataHandle<Location> getStream() {
        synchronized (fileLock) {
            // - we prefer not to return this stream in the middle of I/O operations
            return in;
        }
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
     * Returns position in the file of the last IFD offset, loaded by {@link #readIFDOffsets()},
     * {@link #readSingleIFDOffset(int)} or {@link #readFirstIFDOffset()} methods.
     * Usually it is just a position of the offset of the last IFD, because
     * popular {@link #allIFDs()} method calls {@link #readIFDOffsets()} inside.
     *
     * <p>Immediately after creating new object this position is <tt>-1</tt>.
     *
     * @return file position of the last IFD offset.
     */
    public long positionOfLastIFDOffset() {
        return positionOfLastIFDOffset;
    }

    public DetailedIFD ifd(int ifdIndex) throws IOException, FormatException {
        List<DetailedIFD> ifdList = allIFDs();
        if (ifdIndex < 0 || ifdIndex >= ifdList.size()) {
            throw new IndexOutOfBoundsException(
                    "IFD index " + ifdIndex + " is out of bounds 0 <= i < " + ifdList.size());
        }
        return ifdList.get(ifdIndex);
    }

    /**
     * Equivalent to {@link #ifd(int) ifd(0)}. However, if you really needs access only to 1st IFD,
     * this method may work faster.
     *
     * <p>Note: if this TIFF file is not valid ({@link #isValid()} returns <tt>false</tt>), this method
     * returns <tt>null</tt> and does not throw an exception.
     */
    public DetailedIFD firstIFD() throws IOException, FormatException {
        if (!isValid()) {
            return null;
        }
        DetailedIFD firstIFD = this.firstIFD;
        if (cachingIFDs && firstIFD != null) {
            return this.firstIFD;
        }
        final long offset = readFirstIFDOffset();
        firstIFD = readIFDAt(offset);
        if (cachingIFDs) {
            this.firstIFD = firstIFD;
        }
        return firstIFD;
    }

    /**
     * Returns all IFDs in the file.
     *
     * <p>Note: if this TIFF file is not valid ({@link #isValid()} returns <tt>false</tt>), this method
     * returns an empty list and does not throw an exception. For valid TIFF, result cannot be empty.
     */
    public List<DetailedIFD> allIFDs() throws IOException, FormatException {
        long t1 = debugTime();
        List<DetailedIFD> ifds;
        synchronized (fileLock) {
            // - this synchronization is not necessary, but helps
            // to be sure that the client will not try to read TIFF images
            // when all IFD are not fully loaded and checked
            ifds = this.ifds;
            if (cachingIFDs && ifds != null) {
                return ifds;
            }

            final long[] offsets = readIFDOffsets();
            ifds = new ArrayList<>();

            for (final long offset : offsets) {
                final DetailedIFD ifd = readIFDAt(offset);
                assert ifd != null;
                ifds.add(ifd);
                long[] subOffsets = null;
                try {
                    // Deprecated solution: "fillInIFD" technique is no longer used
                    // if (!cachingIFDs && ifd.containsKey(IFD.SUB_IFD)) {
                    //     fillInIFD(ifd);
                    // }
                    subOffsets = ifd.getIFDLongArray(IFD.SUB_IFD);
                } catch (final FormatException ignored) {
                }
                if (subOffsets != null) {
                    for (final long subOffset : subOffsets) {
                        final DetailedIFD sub = readIFDAt(subOffset, IFD.SUB_IFD, false);
                        if (sub != null) {
                            ifds.add(sub);
                        }
                    }
                }
            }
            if (cachingIFDs) {
                this.ifds = ifds;
            }
        }
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %d IFDs: %.3f ms",
                    getClass().getSimpleName(), ifds.size(),
                    (t2 - t1) * 1e-6));
        }
        return ifds;
    }

    /**
     * Returns thumbnail IFDs.
     */
    public List<DetailedIFD> allThumbnailIFDs() throws IOException, FormatException {
        final List<DetailedIFD> ifds = allIFDs();
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
    public List<DetailedIFD> allNonThumbnailIFDs() throws IOException, FormatException {
        final List<DetailedIFD> ifds = allIFDs();
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
    public List<DetailedIFD> allExifIFDs() throws FormatException, IOException {
        final List<DetailedIFD> ifds = allIFDs();
        final List<DetailedIFD> exif = new ArrayList<>();
        for (final DetailedIFD ifd : ifds) {
            final long offset = ifd.getLong(IFD.EXIF, 0);
            if (offset != 0) {
                final DetailedIFD exifIFD = readIFDAt(offset, IFD.EXIF, false);
                if (exifIFD != null) {
                    exif.add(exifIFD);
                }
            }
        }
        return exif;
    }

    /**
     * Gets offset to the first IFD, or -1 if stream is not TIFF.
     * Updates {@link #positionOfLastIFDOffset()} to the position of first offset (4, for Bit-TIFF 8).
     */
    public long readFirstIFDOffset() throws IOException, FormatException {
        if (!isValid()) {
            return -1;
        }
        synchronized (fileLock) {
            in.seek(bigTiff ? 8 : 4);
            return readFirstOffsetFromCurrentPosition(true, this.bigTiff);
        }
    }

    /**
     * Returns the file offset of IFD with given index or <tt>-1</tt> if the index is too high.
     * Updates {@link #positionOfLastIFDOffset()} to position of this offset.
     *
     * @param ifdIndex index of IFD (0, 1, ...).
     * @return offset of this IFD in the file or <tt>-1</tt> if the index is too high.
     */
    public long readSingleIFDOffset(int ifdIndex) throws IOException, FormatException {
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative ifdIndex = " + ifdIndex);
        }
        synchronized (fileLock) {
            final long fileLength = in.length();
            long offset = readFirstIFDOffset();

            while (offset > 0 && offset < fileLength) {
                if (ifdIndex-- <= 0) {
                    return offset;
                }
                in.seek(offset);
                skipIFDEntries(fileLength);
                final long newOffset = readNextOffset(true);
                if (newOffset == offset) {
                    throw new IOException("TIFF file is broken - infinite loop of IFD offsets is detected " +
                            "for offset " + offset);
                }
                offset = newOffset;
            }
            return -1;
        }
    }

    /**
     * Gets the offsets to every IFD in the file.
     */
    public long[] readIFDOffsets() throws IOException, FormatException {
        synchronized (fileLock) {
            final long fileLength = in.length();
            final LinkedHashSet<Long> ifdOffsets = new LinkedHashSet<>();
            long offset = readFirstIFDOffset();

            while (offset > 0 && offset < fileLength) {
                in.seek(offset);
                final boolean wasNotPresent = ifdOffsets.add(offset);
                if (!wasNotPresent) {
                    throw new IOException("TIFF file is broken - infinite loop of IFD offsets is detected " +
                            "for offset " + offset + " (the stored ifdOffsets sequence is " +
                            ifdOffsets.stream().map(Object::toString).collect(Collectors.joining(", ")) +
                            ", " + offset + ", ...)");
                }
                skipIFDEntries(fileLength);
                offset = readNextOffset(true);
            }
            if (requireValidTiff && ifdOffsets.isEmpty()) {
                throw new AssertionError("No IFDs, but it was not checked in readFirstIFDOffset");
            }
            return ifdOffsets.stream().mapToLong(v -> v).toArray();
        }
    }

    public DetailedIFD readSingleIFD(int ifdIndex) throws IOException, FormatException, NoSuchElementException {
        long startOffset = readSingleIFDOffset(ifdIndex);
        if (startOffset < 0) {
            throw new NoSuchElementException("No IFD #" + ifdIndex + " in TIFF" + prettyInName()
                    + ": too large index");
        }
        return readIFDAt(startOffset);
    }

    /**
     * Reads the IFD stored at the given offset.
     * Never returns <tt>null</tt>.
     */
    public DetailedIFD readIFDAt(long startOffset) throws IOException, FormatException {
        return readIFDAt(startOffset, null, true);
    }

    public DetailedIFD readIFDAt(final long startOffset, Integer subIFDType, boolean readNextOffset)
            throws IOException, FormatException {
        if (startOffset < 0) {
            throw new IllegalArgumentException("Negative file offset = " + startOffset);
        }
        if (startOffset < (bigTiff ? 16 : 8)) {
            throw new IllegalArgumentException("Attempt to read IFD from too small start offset " + startOffset);
        }
        long t1 = debugTime();
        long timeEntries = 0;
        long timeArrays = 0;
        final DetailedIFD ifd;
        synchronized (fileLock) {
            if (startOffset >= in.length()) {
                throw new FormatException("TIFF IFD offset " + startOffset + " is outside the file");
            }
            final Map<Integer, TiffIFDEntry> entries = new LinkedHashMap<>();
            ifd = new DetailedIFD().setFileOffsetOfReading(startOffset);
            ifd.setSubIFDType(subIFDType);

            // save little-endian flag to internal LITTLE_ENDIAN tag
            ifd.put(IFD.LITTLE_ENDIAN, in.isLittleEndian());
            ifd.put(IFD.BIG_TIFF, bigTiff);

            // read in directory entries for this IFD
            in.seek(startOffset);
            final long numberOfEntries = bigTiff ? in.readLong() : in.readUnsignedShort();
            if (numberOfEntries > MAX_NUMBER_OF_IFD_ENTRIES) {
                throw new FormatException("Too many number of IFD entries: " + numberOfEntries +
                        " > " + MAX_NUMBER_OF_IFD_ENTRIES);
                // - theoretically BigTIFF allows to have more entries, but we prefer to make some restriction;
                // in any case, billions if entries will probably lead to OutOfMemoryError or integer overflow
            }

            final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY : TiffConstants.BYTES_PER_ENTRY;
            final int baseOffset = bigTiff ? 8 : 2;

            for (long i = 0; i < numberOfEntries; i++) {
                long tEntry1 = debugTime();
                in.seek(startOffset + baseOffset + bytesPerEntry * i);

                final TiffIFDEntry entry = readIFDEntry();
                final int tag = entry.getTag();
                long tEntry2 = debugTime();
                timeEntries += tEntry2 - tEntry1;

                final Object value = readIFDValueAtCurrentPosition(in, entry, assumeEqualStrips);
                long tEntry3 = debugTime();
                timeArrays += tEntry3 - tEntry2;
//            System.err.printf("%d values from %d: %.6f ms%n", valueCount, valueOffset, (tEntry3 - tEntry2) * 1e-6);

                if (value != null && !ifd.containsKey(tag)) {
                    // - null value should not occur in current version, but theoretically
                    // it means that this IFDType is not supported and should not be stored;
                    // if this tag is present twice (strange mistake if TIFF file),
                    // we do not throw exception and just use the 1st entry
                    entries.put(tag, entry);
                    ifd.put(tag, value);
                }
            }
            ifd.setEntries(entries);
            final long positionOfNextOffset = startOffset + baseOffset + bytesPerEntry * numberOfEntries;
            in.seek(positionOfNextOffset);

            if (readNextOffset) {
                final long nextOffset = readNextOffset(false);
                ifd.setNextIFDOffset(nextOffset);
                in.seek(positionOfNextOffset);
                // - this "in.seek" provides maximal compatibility with old code (which did not read next IFD offset)
                // and also with behaviour of this method, when readNextOffset is not requested
            }
        }

        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.TRACE, String.format(Locale.US,
                    "%s read IFD at offset %d: %.3f ms, including %.6f entries + %.6f arrays",
                    getClass().getSimpleName(), startOffset,
                    (t2 - t1) * 1e-6, timeEntries * 1e-6, timeArrays * 1e-6));
        }
        return ifd;
    }

    /**
     * Convenience method for obtaining a stream's first ImageDescription.
     */
    public String getComment() throws IOException, FormatException {
        final IFD firstIFD = firstIFD();
        if (firstIFD == null) {
            return null;
        }
        // Deprecated solution: "fillInIFD" technique is no longer used
        //
        // fillInIFD(firstIFD);
        //
        return firstIFD.getComment();
    }

    /**
     * Reads and decodes the tile at the specified position.
     * Note: the loaded tile is always {@link TiffTile#isSeparated() separated}.
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
        // - also checks that tile index is not out of image bounds
        final int countIndex = assumeEqualStrips ? 0 : index;
        // - see getIFDValue(): if assumeEqualStrips, getStripByteCounts() will return long[1] array,
        // filled in getIFDValue(TiffIFDEntry)
        final long offset = ifd.cachedTileOrStripOffset(index);
        assert offset >= 0 : "offset " + offset + " was not checked in DetailedIFD";
        final int byteCount = ifd.cachedTileOrStripByteCount(countIndex);
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

        final TiffTile result = new TiffTile(tileIndex);
        // - No reasons to put it into the map: this class do not provide access to temporary created map.

        if (cropTilesToImageBoundaries) {
            result.cropToMap(true);
        }
        // If cropping is disabled, we should not avoid reading extra content of the last strip.
        // Note the last encoded strip can have actually full strip sizes,
        // i.e. larger than necessary; this situation is quite possible.
        if (byteCount == 0 || offset == 0) {
            if (missingTilesAllowed) {
                return result;
            } else {
                throw new FormatException("Zero tile/strip " + (byteCount == 0 ? "byte-count" : "offset")
                        + " is not allowed in a valid TIFF file (tile " + tileIndex + ")");
            }
        }

        synchronized (fileLock) {
            if (offset >= in.length()) {
                throw new FormatException("Offset of TIFF tile/strip " + offset + " is out of file length (tile " +
                        tileIndex + ")");
                // - note: old SCIFIO code allowed such offsets and returned zero-filled tile
            }
            TiffTileIO.read(result, in, offset, byteCount);
        }
        long t2 = debugTime();
        timeReading += t2 - t1;
        return result;
    }

    // Note: result is usually interleaved (RGBRGB...) or monochrome; it is always so in UNCOMPRESSED, LZW, DEFLATE
    public void decode(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        long t1 = debugTime();
        prepareEncodedTileForDecoding(tile);

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

    public void prepareEncodedTileForDecoding(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            // - unlike full decoding, here it is better not to throw exception for empty tile
            return;
        }
        TiffTools.invertFillOrderIfRequested(tile);
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
                            "Too large tile/strip at " + tile.index() + ": JPEG table length " +
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
                tile.setEncodedData(appended);
            }
        }
    }

    public void completeDecoding(TiffTile tile) throws FormatException {
        // scifio.tiff().undifference(tile.getDecodedData(), tile.ifd());
        // - this solution requires using SCIFIO context class; it is better to avoid this
        TiffTools.addPredictionIfRequested(tile);

        if (USE_OLD_UNPACK_BYTES) {
            byte[] samples = new byte[tile.map().tileSizeInBytes()];
            unpackBytesLegacy(samples, 0, tile.getDecodedData(), tile.ifd());
            tile.setDecodedData(samples);
            tile.setInterleaved(false);
        } else {
            if (!decodeYCbCr(tile)) {
                unpackBytes(tile);
            }
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
    }

    public byte[] readImage(final DetailedIFD ifd) throws FormatException, IOException {
        return readImage(new TiffMap(ifd));
    }

    public byte[] readImage(TiffMap map) throws FormatException, IOException {
        return readImage(map, false);
    }

    public byte[] readImage(TiffMap map, boolean storeTilesInMap) throws FormatException, IOException {
        return readImage(map, 0, 0, map.dimX(), map.dimY(), storeTilesInMap);
    }

    /**
     * Reads samples in <tt>byte[]</tt> array.
     *
     * <p>Note: you should not change IFD in a parallel thread while calling this method.
     *
     * @return loaded samples in a normalized form of byte sequence.
     */
    public byte[] readImage(DetailedIFD ifd, int fromX, int fromY, int sizeX, int sizeY)
            throws FormatException, IOException {
        return readImage(new TiffMap(ifd), fromX, fromY, sizeX, sizeY);
    }

    public byte[] readImage(TiffMap map, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException, FormatException {
        return readImage(map, fromX, fromY, sizeX, sizeY, false);
    }

    public byte[] readImage(TiffMap map, int fromX, int fromY, int sizeX, int sizeY, boolean storeTilesInMap)
            throws FormatException, IOException {
            Objects.requireNonNull(map, "Null TIFF map");
        long t1 = debugTime();
        clearTime();
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        // - note: we allow this area to be outside the image
        final int numberOfChannels = map.numberOfChannels();
        final DetailedIFD ifd = map.ifd();
        final int size = ifd.sizeOfRegionBasedOnType(sizeX, sizeY);
        // - note: it may differ from the size, calculated on the base of tile information (saved in the map),
        // in a case when unpackUnusualPrecisions performs unpacking
        assert sizeX >= 0 && sizeY >= 0 : "sizeOfIFDRegion didn't check sizes accurately: " + sizeX + "fromX" + sizeY;
        byte[] samples = new byte[size];
        if (size == 0) {
            return samples;
        }

        if (byteFiller != 0) {
            // - samples array is already zero-filled by Java
            Arrays.fill(samples, 0, size, byteFiller);
        }
        // - important for a case when the requested area is outside the image;
        // old SCIFIO code did not check this and could return undefined results
        long t2 = debugTime();

        readTiles(map, samples, fromX, fromY, sizeX, sizeY, storeTilesInMap);

        long t3 = debugTime();
        if (autoUnpackUnusualPrecisions) {
            samples = TiffTools.unpackUnusualPrecisions(samples, ifd, numberOfChannels, sizeX * sizeY);
        }
        long t4 = debugTime();
        if (interleaveResults) {
            samples = TiffTools.toInterleavedSamples(
                    samples, numberOfChannels, ifd.bytesPerSampleBasedOnType(), sizeX * sizeY);
        }
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t5 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f initializing + %.3f read/decode " +
                            "(%.3f read + %.3f customize/bit-order + %.3f decode + %.3f complete) + " +
                            "%.3f unusual + %.3f interleave, %.3f MB/s",
                    getClass().getSimpleName(),
                    numberOfChannels, sizeX, sizeY, size / 1048576.0,
                    (t5 - t1) * 1e-6,
                    (t2 - t1) * 1e-6,
                    (t3 - t2) * 1e-6,
                    timeReading * 1e-6,
                    timeCustomizingDecoding * 1e-6,
                    timeDecoding * 1e-6,
                    timeCompleteDecoding * 1e-6,
                    (t4 - t3) * 1e-6, (t5 - t4) * 1e-6,
                    size / 1048576.0 / ((t5 - t1) * 1e-9)));
        }
        return samples;
    }

    public Object readImageIntoArray(final DetailedIFD ifd) throws FormatException, IOException {
        return readImageIntoArray(ifd, 0, 0, ifd.getImageDimX(), ifd.getImageDimY());
    }

    public Object readImageIntoArray(DetailedIFD ifd, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException, FormatException {
        return readImageIntoArray(
                ifd, fromX, fromY, sizeX, sizeY, null, null);
    }

    public Object readImageIntoArray(
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
                    "Number of channel mismatch: expected " + requiredSamplesPerPixel
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
        final byte[] samples = readImage(ifd, fromX, fromY, sizeX, sizeY);
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
        synchronized (fileLock) {
            in.close();
        }
    }

    private void clearTime() {
        timeReading = 0;
        timeCustomizingDecoding = 0;
        timeDecoding = 0;
        timeCompleteDecoding = 0;
    }

    // We prefer make this.bigTiff a final field, so we cannot set it outside the constructor
    private Exception startReading(AtomicBoolean bigTiffReference) {
        try {
            synchronized (fileLock) {
                // - this synchronization is extra, but may become useful
                // if we will decide to make this method public and called not only from the constructor
                if (!in.exists()) {
                    return new FileNotFoundException("Input TIFF file" + prettyInName() + " does not exist");
                }
                testHeader(bigTiffReference);
                return null;
            }
        } catch (IOException | FormatException e) {
            return e;
        }
    }

    private void testHeader(AtomicBoolean bigTiffReference) throws IOException, FormatException {
        final long savedOffset = in.offset();
        try {
            in.seek(0);
            final long length = in.length();
            if (length < MINIMAL_ALLOWED_TIFF_FILE_LENGTH) {
                // - sometimes we can meet 8-byte "TIFF-files" (or 16-byte "Big-TIFF"), containing only header
                // and no actual data (for example, results of debugging writing algorithm)
                throw new FormatException("Too short TIFF file" + prettyInName() + ": only " + length +
                        " bytes (we require minimum " + MINIMAL_ALLOWED_TIFF_FILE_LENGTH + " bytes for valid TIFF)");
            }
            final int endianOne = in.read();
            final int endianTwo = in.read();
            // byte order must be II or MM
            final boolean littleEndian = endianOne == TiffConstants.LITTLE && endianTwo == TiffConstants.LITTLE; // II
            final boolean bigEndian = endianOne == TiffConstants.BIG && endianTwo == TiffConstants.BIG; // MM
            if (!littleEndian && !bigEndian) {
                throw new FormatException("The file" + prettyInName() + " is not TIFF");
            }

            // check magic number (42)
            in.setLittleEndian(littleEndian);
            final short magic = in.readShort();
            final boolean bigTiff = magic == TiffConstants.BIG_TIFF_MAGIC_NUMBER;
            bigTiffReference.set(bigTiff);
            if (magic != TiffConstants.MAGIC_NUMBER && magic != TiffConstants.BIG_TIFF_MAGIC_NUMBER) {
                throw new FormatException("The file" + prettyInName() + " is not TIFF");
            }
            if (bigTiff) {
                in.seek(8);
            }
            readFirstOffsetFromCurrentPosition(false, bigTiff);
            // - additional check, filling positionOfLastOffset
        } finally {
            in.seek(savedOffset);
            // - for maximal compatibility: in old versions, constructor of this class
            // guaranteed that file position in the input stream will not change
            // (that is illogical, because "little-endian" mode was still changed)
        }
    }

    private CodecOptions buildReadingOptions(TiffTile tile, Codec customCodec) throws FormatException {
        DetailedIFD ifd = tile.ifd();
        CodecOptions codecOptions = new CodecOptions(this.codecOptions);
        codecOptions.littleEndian = ifd.isLittleEndian();
        final int samplesLength = tile.getSizeInBytes();
        // - Note: it may be LESS than a usual number of samples in the tile/strip.
        // Current readEncodedTile() always returns full-size tile without cropping
        // (see comments inside that method), but the user CAN crop last tile/strip in an external code.
        // Old SCIFIO code did not detect this situation, in particular, did not distinguish between
        // last and usual strips in stripped image, and its behaviour could be described by the following assignment:
        //      final int samplesLength = tile.map().tileSizeInBytes();
        // For many codecs (like DEFLATE or JPEG) this is not important, but at least
        // LZWCodec creates result array on the base of codecOptions.maxBytes.
        // If it will be invalid (too large) value, returned decoded data will be too large,
        // and this class will throw an exception "data may be lost" in further
        // tile.completeNumberOfPixels() call.
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
        // - Rare case: Y_CB_CR is encoded with non-standard sub-sampling

        if (USE_OLD_UNPACK_BYTES) {
            codecOptions.interleaved = true;
            // - old-style unpackBytes does not "understand" already-separated tiles
        } else {
            codecOptions.interleaved =
                    !(customCodec instanceof ExtendedJPEGCodec || ifd.getCompression() == TiffCompression.JPEG);
        }
        // - ExtendedJPEGCodec or standard codec JPEFCodec (it may be chosen below by scifio.codec(),
        // but we are sure that JPEG compression will be served by it even in future versions):
        // they "understand" this settings well (unlike LZW or DECOMPRESSED codecs,
        // which suppose that data are interleaved according TIFF format specification).
        // Value "true" is necessary for other codecs, that work with high-level classes (like JPEG or JPEG-2000) and
        // need to be instructed to interleave results (unlike LZW or DECOMPRESSED, which work with data "as-is").
        return codecOptions;
    }

    // Note: this method does not store tile in the tile map.
    private void readTiles(
            TiffMap map,
            byte[] resultSamples,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws FormatException, IOException {
        Objects.requireNonNull(map, "Null tile map");
        Objects.requireNonNull(resultSamples, "Null result samples");
        assert fromX >= 0 && fromY >= 0 && sizeX >= 0 && sizeY >= 0;
        // Note: we cannot process image larger than 2^31 x 2^31 pixels,
        // though TIFF supports maximal sizes 2^32 x 2^32
        // (IFD.getImageWidth/getImageLength do not allow so large results)

        final int mapTileSizeX = map.tileSizeX();
        final int mapTileSizeY = map.tileSizeY();
        final int bytesPerSample = map.bytesPerSample();
        final int numberOfSeparatedPlanes = map.numberOfSeparatedPlanes();
        final int samplesPerPixel = map.tileSamplesPerPixel();

        final int toX = Math.min(fromX + sizeX, cropTilesToImageBoundaries ? map.dimX() : Integer.MAX_VALUE);
        final int toY = Math.min(fromY + sizeY, cropTilesToImageBoundaries ? map.dimY() : Integer.MAX_VALUE);
        // - crop by image sizes to avoid reading unpredictable content of the boundary tiles outside the image
        final int minXIndex = fromX / mapTileSizeX;
        final int minYIndex = fromY / mapTileSizeY;
        if (minXIndex >= map.gridTileCountX() || minYIndex >= map.gridTileCountY() || toX < fromX || toY < fromY) {
            return;
        }
        final int maxXIndex = Math.min(map.gridTileCountX() - 1, (toX - 1) / mapTileSizeX);
        final int maxYIndex = Math.min(map.gridTileCountY() - 1, (toY - 1) / mapTileSizeY);
        assert minYIndex <= maxYIndex && minXIndex <= maxXIndex;
        final int tileOneChannelRowSizeInBytes = mapTileSizeX * bytesPerSample;
        final int samplesOneChannelRowSizeInBytes = sizeX * bytesPerSample;

        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            // - for a rare case PlanarConfiguration=2 (RRR...GGG...BBB...)
            for (int yIndex = minYIndex; yIndex <= maxYIndex; yIndex++) {
                final int tileStartY = Math.max(yIndex * mapTileSizeY, fromY);
                final int fromYInTile = tileStartY % mapTileSizeY;
                final int yDiff = tileStartY - fromY;

                for (int xIndex = minXIndex; xIndex <= maxXIndex; xIndex++) {
                    final int tileStartX = Math.max(xIndex * mapTileSizeX, fromX);
                    final int fromXInTile = tileStartX % mapTileSizeX;
                    final int xDiff = tileStartX - fromX;

                    final TiffTile tile = readTile(map.multiplaneIndex(p, xIndex, yIndex));
                    if (storeTilesInMap) {
                        map.put(tile);
                    }
                    if (tile.isEmpty()) {
                        continue;
                    }
                    if (!tile.isSeparated()) {
                        throw new AssertionError("Illegal behavior of readTile: it returned interleaved tile!");
                        // - theoretically possible in subclasses
                    }
                    byte[] data = tile.getDecodedData();

                    final int tileSizeX = tile.getSizeX();
                    final int tileSizeY = tile.getSizeY();
                    final int sizeXInTile = Math.min(toX - tileStartX, tileSizeX - fromXInTile);
                    assert sizeXInTile > 0 : "sizeXInTile=" + sizeXInTile;
                    final int sizeYInTile = Math.min(toY - tileStartY, tileSizeY - fromYInTile);
                    assert sizeYInTile > 0 : "sizeYInTile=" + sizeYInTile;

                    final int partSizeXInBytes = sizeXInTile * bytesPerSample;
                    for (int s = 0; s < samplesPerPixel; s++) {
                        int tOffset = (((s * tileSizeY) + fromYInTile) * tileSizeX + fromXInTile) * bytesPerSample;
                        int samplesOffset = (((p + s) * sizeY + yDiff) * sizeX + xDiff) * bytesPerSample;
                        for (int i = 0; i < sizeYInTile; i++) {
                            System.arraycopy(data, tOffset, resultSamples, samplesOffset, partSizeXInBytes);
                            tOffset += tileOneChannelRowSizeInBytes;
                            samplesOffset += samplesOneChannelRowSizeInBytes;
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

        final int samplesLength = tile.map().tileSizeInBytes();
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

        final int bytesPerSample = ifd.equalBytesPerSample();
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
                    final long r = (long) subY * (tileIndex / nTiles) + (pixel / subX);
                    final long c = (long) subX * (tileIndex % nTiles) + (pixel % subX);

                    final int idx = (int) (r * tileSizeX + c);

                    if (idx < numberOfPixels) {
                        final int y = (bytes[lumaIndex] & 0xff) - reference[0];
                        final int cb = (bytes[chromaIndex] & 0xff) - reference[2];
                        final int cr = (bytes[chromaIndex + 1] & 0xff) - reference[4];

                        final double red = cr * (2 - 2 * lumaRed) + y;
                        final double blue = cb * (2 - 2 * lumaBlue) + y;
                        final double green = (y - lumaBlue * blue - lumaRed * red) * lumaGreenInv;

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

    // Made from unpackBytes method of old TiffParser
    // Processing Y_CB_CR is extracted to decodeYCbCr() method.
    private void unpackBytes(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile);
        final DetailedIFD ifd = tile.ifd();
        final TiffMap map = tile.map();

        if (KnownTiffCompression.isNonJpegYCbCr(ifd)) {
            throw new IllegalArgumentException("Y_CB_CR photometric interpretation should be processed separately");
        }

        final int samplesPerPixel = tile.samplesPerPixel();
        final PhotoInterp photoInterpretation = ifd.getPhotometricInterpretation();
        final int resultSamplesLength = tile.map().tileSizeInBytes();
        // - the length of the RESULT array, that should be stored in the returning tile
        // (in the old code it was just the length of the passed byte[] "samples" array)

        final int bytesPerSample = tile.bytesPerSample();
        final int numberOfPixels = resultSamplesLength / (samplesPerPixel * bytesPerSample);
        assert numberOfPixels == tile.map().tileSizeInPixels();

        final int bits = ifd.tryEqualBitsPerSample().orElse(-1);
        final boolean usualPrecision = bits == 8 || bits == 16 || bits == 32 || bits == 64;

        // Hyper optimisation that takes any 8-bit or 16-bit data, where there is
        // only one channel, the source byte buffer's size is less than or equal to
        // that of the destination buffer and for which no special unpacking is
        // required and performs a simple array copy. Over the course of reading
        // semi-large datasets this can save **billions** of method calls.
        // Wed Aug 5 19:04:59 BST 2009
        // Chris Allan <callan@glencoesoftware.com>
        if (usualPrecision &&
                tile.getStoredDataLength() <= resultSamplesLength &&
                photoInterpretation != PhotoInterp.WHITE_IS_ZERO &&
                photoInterpretation != PhotoInterp.CMYK) {
            if (tile.getStoredDataLength() > map.tileSizeInBytes()) {
                // - this check is better than IllegalArgumentException in the further adjustNumberOfPixels
                throw new FormatException("Too large decoded TIFF data: " + tile.getStoredDataLength() +
                        " bytes, its is greater than one " +
                        (map.isTiled() ? "tile" : "strip") + " (" + map.tileSizeInBytes() + " bytes); "
                        + "probably TIFF file is corrupted or format is not properly supported");
            }
            tile.adjustNumberOfPixels(cropTilesToImageBoundaries);
            // - Note: bytes.length is unpredictable, because it is the result of decompression by a codec;
            // in particular, for JPEG compression last strip in non-tiled TIFF may be shorter or even larger
            // than a full tile.
            // If cropping boundary tiles is disabled, larger data should be considered as a format error,
            // because tile sizes are the FULL sizes of tile in the grid (it is checked above independently).
            // If cropping is enabled, actual height of the last strip is reduced (see readEncodedTile method),
            // so larger data is possible (it is a minor format separately).
            // Also note: it is better to rearrange pixels before separating (if necessary),
            // because rearranging interleaved pixels is little more simple.
            tile.separateSamplesIfNecessary();
            return;
        }

        final int[] bitsPerSample = ifd.getBitsPerSample();
        final int bps0 = bitsPerSample[0];
        final boolean noDiv8 = bps0 % 8 != 0;
        byte[] bytes = tile.getDecodedData();
        long sampleCount = (long) 8 * bytes.length / bitsPerSample[0];
        if (!tile.isPlanarSeparated()) {
            sampleCount /= samplesPerPixel;
        }
        if (sampleCount > Integer.MAX_VALUE) {
            throw new FormatException("Too large tile: " + sampleCount + " >= 2^31 actual samples");
        }

        final long imageWidth = tile.getSizeX();
        final long imageHeight = tile.getSizeY();

        final boolean littleEndian = ifd.isLittleEndian();

        final BitBuffer bb = new BitBuffer(bytes);

        final byte[] unpacked = new byte[resultSamplesLength];

        long maxValue = (long) Math.pow(2, bps0) - 1;
        if (photoInterpretation == PhotoInterp.CMYK) maxValue = Integer.MAX_VALUE;

        int skipBits = (int) (8 - ((imageWidth * bps0 * samplesPerPixel) % 8));
        if (skipBits == 8 || ((long) bytes.length * 8 < bps0 * (samplesPerPixel * imageWidth + imageHeight))) {
            skipBits = 0;
        }

        // unpack pixels
        for (int i = 0; i < sampleCount; i++) {
            if (i >= numberOfPixels) break;

            for (int channel = 0; channel < samplesPerPixel; channel++) {
                final int index = bytesPerSample * (i * samplesPerPixel + channel);
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

    // Exact copy of old code
    private static void unpackBytesLegacy(
            final byte[] samples, final int startIndex,
            final byte[] bytes, final IFD ifd) throws FormatException {
        final boolean planar = ifd.getPlanarConfiguration() == 2;

        final TiffCompression compression = ifd.getCompression();
        PhotoInterp photoInterp = ifd.getPhotometricInterpretation();
        if (compression == TiffCompression.JPEG) photoInterp = PhotoInterp.RGB;

        final int[] bitsPerSample = ifd.getBitsPerSample();
        int nChannels = bitsPerSample.length;

        int sampleCount = (int) (((long) 8 * bytes.length) / bitsPerSample[0]);
        if (photoInterp == PhotoInterp.Y_CB_CR) sampleCount *= 3;
        if (planar) {
            nChannels = 1;
        } else {
            sampleCount /= nChannels;
        }

//        log.trace("unpacking " + sampleCount + " samples (startIndex=" +
//                startIndex + "; totalBits=" + (nChannels * bitsPerSample[0]) +
//                "; numBytes=" + bytes.length + ")");

        final long imageWidth = ifd.getImageWidth();
        final long imageHeight = ifd.getImageLength();

        final int bps0 = bitsPerSample[0];
        final int numBytes = ifd.getBytesPerSample()[0];
        final int nSamples = samples.length / (nChannels * numBytes);

        final boolean noDiv8 = bps0 % 8 != 0;
        final boolean bps8 = bps0 == 8;
        final boolean bps16 = bps0 == 16;

        final boolean littleEndian = ifd.isLittleEndian();

        final BitBuffer bb = new BitBuffer(bytes);

        // Hyper optimisation that takes any 8-bit or 16-bit data, where there
        // is
        // only one channel, the source byte buffer's size is less than or equal
        // to
        // that of the destination buffer and for which no special unpacking is
        // required and performs a simple array copy. Over the course of reading
        // semi-large datasets this can save **billions** of method calls.
        // Wed Aug 5 19:04:59 BST 2009
        // Chris Allan <callan@glencoesoftware.com>
        if ((bps8 || bps16) && bytes.length <= samples.length && nChannels == 1 &&
                photoInterp != PhotoInterp.WHITE_IS_ZERO &&
                photoInterp != PhotoInterp.CMYK && photoInterp != PhotoInterp.Y_CB_CR) {
            System.arraycopy(bytes, 0, samples, 0, bytes.length);
            return;
        }

        long maxValue = (long) Math.pow(2, bps0) - 1;
        if (photoInterp == PhotoInterp.CMYK) maxValue = Integer.MAX_VALUE;

        int skipBits = (int) (8 - ((imageWidth * bps0 * nChannels) % 8));
        if (skipBits == 8 || (bytes.length * 8L < bps0 * (nChannels * imageWidth +
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
            final int ndx = startIndex + sample;
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

                    if (outputIndex + numBytes <= samples.length) {
                        Bytes.unpack(value, samples, outputIndex, numBytes, littleEndian);
                    }
                } else {
                    // unpack YCbCr samples; these need special handling, as
                    // each of
                    // the RGB components depends upon two or more of the YCbCr
                    // components
                    if (channel == nChannels - 1) {
                        final int lumaIndex = sample + (2 * (sample / block));
                        final int chromaIndex = (sample / block) * (block + 2) + block;

                        if (chromaIndex + 1 >= bytes.length) break;

                        final int tile = ndx / block;
                        final int pixel = ndx % block;
                        final long r = (long) subY * (tile / nTiles) + (pixel / subX);
                        final long c = (long) subX * (tile % nTiles) + (pixel % subX);

                        final int idx = (int) (r * imageWidth + c);

                        if (idx < nSamples) {
                            final int y = (bytes[lumaIndex] & 0xff) - reference[0];
                            final int cb = (bytes[chromaIndex] & 0xff) - reference[2];
                            final int cr = (bytes[chromaIndex + 1] & 0xff) - reference[4];

                            final int red = (int) (cr * (2 - 2 * lumaRed) + y);
                            final int blue = (int) (cb * (2 - 2 * lumaBlue) + y);
                            final int green = (int) ((y - lumaBlue * blue - lumaRed * red) /
                                    lumaGreen);

                            samples[idx] = (byte) (red & 0xff);
                            samples[nSamples + idx] = (byte) (green & 0xff);
                            samples[2 * nSamples + idx] = (byte) (blue & 0xff);
                        }
                    }
                }
            }
        }
    }

    private long readFirstOffsetFromCurrentPosition(boolean updatePositionOfLastOffset, boolean bigTiff)
            throws IOException, FormatException {
        final long offset = readNextOffset(updatePositionOfLastOffset, true, bigTiff);
        if (offset == 0) {
            throw new FormatException("Invalid TIFF" + prettyInName() +
                    ": zero first offset (TIFF must contain at least one IFD!)");
        }
        return offset;
    }

    private void skipIFDEntries(long fileLength) throws IOException, FormatException {
        final long offset = in.offset();
        final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY : TiffConstants.BYTES_PER_ENTRY;
        final long numberOfEntries = bigTiff ? in.readLong() : in.readUnsignedShort();
        if (numberOfEntries > Integer.MAX_VALUE / bytesPerEntry) {
            throw new FormatException(
                    "Too many number of IFD entries in Big TIFF: " + numberOfEntries +
                            " (it is not supported, probably file is broken)");
        }
        long skippedIFDBytes = numberOfEntries * bytesPerEntry;
        if (offset + skippedIFDBytes >= fileLength) {
            throw new FormatException(
                    "Invalid TIFF" + prettyInName() + ": position of next IFD offset " +
                            (offset + skippedIFDBytes) + " after " + numberOfEntries +
                            " entries is outside the file (probably file is broken)");
        }
        in.skipBytes((int) skippedIFDBytes);
    }

    private long readNextOffset(boolean updatePositionOfLastOffset) throws IOException, FormatException {
        return readNextOffset(updatePositionOfLastOffset, this.requireValidTiff, this.bigTiff);
    }

    /**
     * Read a file offset. For bigTiff, a 64-bit number is read. For other Tiffs,
     * a 32-bit number is read and possibly adjusted for a possible carry-over
     * from the previous offset.
     */
    private long readNextOffset(boolean updatePositionOfLastOffset, boolean requireValidTiff, boolean bigTiff)
            throws IOException, FormatException {
        final long fileLength = in.length();
        final long filePosition = in.offset();
        long offset;
        if (bigTiff) {
            offset = in.readLong();
        } else {
            // Below is a deprecated solution
            // (this "trick" cannot help if a SINGLE image is very large (>2^32): for example,
            // previous = 8 (1st IFD) and the next is 0x120000000; but it is the mostly typical
            // problematic situation: for example, very large 1st IFD in SVS file).
            //
            // offset = (previous & ~0xffffffffL) | (in.readInt() & 0xffffffffL);
            // Only adjust the offset if we know that the file is too large for
            // 32-bit
            // offsets to be accurate; otherwise, we're making the incorrect
            // assumption
            // that IFDs are stored sequentially.
            // if (offset < previous && offset != 0 && in.length() > Integer.MAX_VALUE) {
            //      offset += 0x100000000L;
            // }
            // return offset;

            offset = (long) in.readInt() & 0xffffffffL;
            // - in usual TIFF format, offset if 32-bit UNSIGNED value
        }
        if (requireValidTiff) {
            if (offset < 0) {
                // - possibly in Big-TIFF only
                throw new FormatException("Invalid TIFF" + prettyInName() +
                        ": negative 64-bit offset " + offset + " at file position " + filePosition +
                        ", probably the file is corrupted");
            }
            if (offset >= fileLength) {
                throw new FormatException("Invalid TIFF" + prettyInName() + ": offset " + offset +
                        " at file position " + filePosition + " is outside the file, probably the is corrupted");
            }
        }
        if (updatePositionOfLastOffset) {
            this.positionOfLastIFDOffset = filePosition;
        }
        return offset;
    }

    private static Object readIFDValueAtCurrentPosition(
            DataHandle<?> in,
            TiffIFDEntry entry,
            boolean assumeEqualStrips)
            throws IOException, FormatException {
        final IFDType type = entry.getType();
        final int count = entry.getValueCount();
        final long offset = entry.getValueOffset();

        LOG.log(System.Logger.Level.TRACE, () ->
                "Reading entry " + entry.getTag() + " from " + offset + "; type=" + type + ", count=" + count);

        in.seek(offset);
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
                final byte[] bytes = readIFDBytes(in, 2 * (long) count);
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
                return in.readInt() & 0xFFFFFFFFL;
            }
            if (OPTIMIZE_READING_IFD_ARRAYS) {
                final byte[] bytes = readIFDBytes(in, 4 * (long) count);
                final int[] ints = TiffTools.bytesToIntArray(bytes, in.isLittleEndian());
                return Arrays.stream(ints).mapToLong(anInt -> anInt & 0xFFFFFFFFL).toArray();
                // note: IFDType.LONG is UNSIGNED long
            } else {
                final long[] longs = new long[count];
                for (int j = 0; j < count; j++) {
                    longs[j] = in.readInt() & 0xFFFFFFFFL;
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
            } else {
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readIFDBytes(in, 8 * (long) count);
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
            for (int j = 0; j < count; j++) {
                sshorts[j] = in.readShort();
            }
            return sshorts;
        } else if (type == IFDType.SLONG) {
            // A 32-bit (4-byte) signed (twos-complement) integer
            if (count == 1) {
                return in.readInt();
            }
            final int[] slongs = new int[count];
            for (int j = 0; j < count; j++) {
                slongs[j] = in.readInt();
            }
            return slongs;
        } else if (type == IFDType.FLOAT) {
            // Single precision (4-byte) IEEE format
            if (count == 1) {
                return in.readFloat();
            }
            final float[] floats = new float[count];
            for (int j = 0; j < count; j++) {
                floats[j] = in.readFloat();
            }
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

    private static byte[] readIFDBytes(DataHandle<?> in, long length) throws IOException, FormatException {
        if (length > Integer.MAX_VALUE) {
            throw new FormatException("Too large IFD value: " + length + " >= 2^31 bytes");
        }
        byte[] bytes = new byte[(int) length];
        in.readFully(bytes);
        return bytes;
    }

    private TiffIFDEntry readIFDEntry() throws IOException, FormatException {
        final int entryTag = in.readUnsignedShort();
        final int entryTypeCode = in.readUnsignedShort();

        // Parse the entry's "Type"
        final IFDType entryType;
        try {
            entryType = IFDType.get(entryTypeCode);
        } catch (EnumException e) {
            throw new FormatException("Invalid TIFF: unknown IFD entry type " + entryTypeCode);
        }

        // Parse the entry's "ValueCount"
        final int valueCount = bigTiff ? (int) in.readLong() : in.readInt();
        if (valueCount < 0) {
            throw new FormatException("Invalid TIFF: negative number of IFD values " + valueCount);
        }

        final int bytesPerElement = entryType.getBytesPerElement();
        assert bytesPerElement > 0 : "non-positive bytes per element in IFDType";
        final long valueLength = (long) valueCount * (long) bytesPerElement;
        final int threshold = bigTiff ? 8 : 4;
        final long valueOffset = valueLength > threshold ?
                readNextOffset(false) :
                in.offset();
        if (valueOffset < 0) {
            throw new FormatException("Invalid TIFF: negative offset of IFD values " + valueOffset);
        }
        if (valueOffset > in.length() - valueLength) {
            throw new FormatException("Invalid TIFF: offset of IFD values " + valueOffset +
                    " + total lengths of values " + valueLength + " = " + valueCount + "*" + bytesPerElement +
                    " is outside the file length " + in.length());
        }
        final TiffIFDEntry result = new TiffIFDEntry(entryTag, entryType, valueCount, valueOffset);
        LOG.log(System.Logger.Level.TRACE, () -> String.format(
                "Reading IFD entry: %s - %s", result, DetailedIFD.ifdTagName(result.getTag(), true)));
        return result;
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
}
