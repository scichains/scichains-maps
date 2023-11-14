
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

package net.algart.matrices.tiff;

import io.scif.FormatException;
import io.scif.SCIFIO;
import io.scif.UnsupportedCompressionException;
import io.scif.codec.Codec;
import io.scif.codec.CodecOptions;
import io.scif.formats.tiff.*;
import net.algart.matrices.tiff.codecs.ExtendedJPEGCodec;
import net.algart.matrices.tiff.codecs.ExtendedJPEGCodecOptions;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIO;
import net.algart.matrices.tiff.tiles.TiffTileIndex;
import org.scijava.AbstractContextual;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandles;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.Location;

import javax.imageio.ImageWriteParam;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Writes TIFF format.
 *
 * <p>This object is internally synchronized and thread-safe when used in multi-threaded environment.
 * However, you should not modify objects, passed to the methods of this class, from a parallel thread;
 * in particular, it concerns the {@link TiffIFD} arguments and Java-arrays with samples.
 * The same is true for the result of {@link #getStream()} method.</p>
 *
 * @author Curtis Rueden
 * @author Eric Kjellman
 * @author Melissa Linkert
 * @author Chris Allan
 * @author Gabriel Einsdorf
 * @author Daniel Alievsky
 */
public class TiffWriter extends AbstractContextual implements Closeable {

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
     * If the file grows to about this limit and {@link #setBigTiff(boolean) big-TIFF} mode is not set,
     * attempt to write new IFD at the file end by methods of this class throw IO exception.
     * While writing tiles, an exception will be thrown only while exceeding the limit <tt>2^32-1</tt>
     * (~280 MB greater than this value {@value}).
     */
    public static final long MAXIMAL_ALLOWED_32BIT_IFD_OFFSET = 4_000_000_000L;

    private static final boolean AVOID_LONG8_FOR_ACTUAL_32_BITS = true;
    // - If was necessary for some old programs (like Aperio Image Viewer), which
    // did not understand LONG8 values for some popular tags like image sizes.
    // In any case, real BigTIFF files usually store most tags in standard LONG type (32 bits), not in LONG8.

    private static final System.Logger LOG = System.getLogger(TiffWriter.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private boolean writingForwardAllowed = true;
    private boolean bigTiff = false;
    private boolean autoInterleaveSource = true;
    private CodecOptions codecOptions;
    private boolean extendedCodec = true;
    private boolean jpegInPhotometricRGB = false;
    private double jpegQuality = 1.0;
    private boolean missingTilesAllowed = false;
    private byte byteFiller = 0;
    private Consumer<TiffTile> tileInitializer = this::fillEmptyTile;

    private final DataHandle<Location> out;
    private final SCIFIO scifio;

    private final Object fileLock = new Object();

    private final LinkedHashSet<Long> ifdOffsets = new LinkedHashSet<>();
    private volatile long positionOfLastIFDOffset = -1;

    private long timeWriting = 0;
    private long timePreparingDecoding = 0;
    private long timeCustomizingEncoding = 0;
    private long timeEncoding = 0;

    public TiffWriter(Path file) throws IOException {
        this(null, file, false);
    }

    public TiffWriter(Path file, boolean deleteExistingFile) throws IOException {
        this(null, file, deleteExistingFile);
    }

    public TiffWriter(DataHandle<Location> out) {
        this(null, out);
    }

    public TiffWriter(Context context, Path file) throws IOException {
        this(context, file, false);
    }

    public TiffWriter(Context context, Path file, boolean deleteExistingFile) throws IOException {
        this(context, deleteFileIfRequested(file, deleteExistingFile));
    }

    public TiffWriter(Context context, DataHandle<Location> out) {
        Objects.requireNonNull(out, "Null \"out\" data handle (output stream)");
        if (context != null) {
            setContext(context);
            scifio = new SCIFIO(context);
        } else {
            scifio = null;
        }
        this.out = out;
    }


    /**
     * Gets the stream from which TIFF data is being saved.
     */
    public DataHandle<Location> getStream() {
        synchronized (fileLock) {
            // - we prefer not to return this stream in the middle of I/O operations
            return out;
        }
    }

    /**
     * Returns whether we are writing little-endian data.
     */
    public boolean isLittleEndian() {
        synchronized (fileLock) {
            return out.isLittleEndian();
        }
    }

    /**
     * Sets whether little-endian data should be written.
     */
    public TiffWriter setLittleEndian(final boolean littleEndian) {
        synchronized (fileLock) {
            out.setLittleEndian(littleEndian);
        }
        return this;
    }

    public boolean isWritingForwardAllowed() {
        return writingForwardAllowed;
    }

    public TiffWriter setWritingForwardAllowed(boolean writingForwardAllowed) {
        this.writingForwardAllowed = writingForwardAllowed;
        return this;
    }

    /**
     * Returns whether we are writing BigTIFF data.
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Sets whether BigTIFF data should be written.
     */
    public TiffWriter setBigTiff(final boolean bigTiff) {
        this.bigTiff = bigTiff;
        return this;
    }


    public boolean isAutoInterleaveSource() {
        return autoInterleaveSource;
    }

    /**
     * Sets auto-interleave mode.
     *
     * <p>If set, then the samples array in <tt>writeImage</tt> methods is always supposed to be unpacked.
     * For multichannel images it means the samples order like RRR..GGG..BBB...: standard form, supposed by
     * {@link io.scif.Plane} class and returned by {@link TiffReader}. If the desired IFD format is
     * chunked, i.e. {@link IFD#PLANAR_CONFIGURATION} is {@link TiffIFD#PLANAR_CONFIGURATION_CHUNKED}
     * (that is the typical usage), then the passes samples are automatically re-packed into chunked (interleaved)
     * form RGBRGBRGB...
     *
     * <p>If this mode is not set, as well as if {@link IFD#PLANAR_CONFIGURATION} is
     * {@link TiffIFD#PLANAR_CONFIGURATION_SEPARATE}, the passed data are encoded as-as, i.e. as unpacked
     * RRR...GGG..BBB...  for {@link TiffIFD#PLANAR_CONFIGURATION_SEPARATE} or as interleaved RGBRGBRGB...
     * for {@link TiffIFD#PLANAR_CONFIGURATION_CHUNKED}.
     *
     * <p>Note that this flag is ignored if the result data in the file should not be interleaved,
     * i.e. for 1-channel images and if {@link IFD#PLANAR_CONFIGURATION} is
     * {@link TiffIFD#PLANAR_CONFIGURATION_SEPARATE}.
     *
     * @param autoInterleaveSource new auto-interleave mode. Default value is <tt>true</tt>.
     * @return a reference to this object.
     */
    public TiffWriter setAutoInterleaveSource(boolean autoInterleaveSource) {
        this.autoInterleaveSource = autoInterleaveSource;
        return this;
    }

    public CodecOptions getCodecOptions() {
        return codecOptions;
    }

    /**
     * Sets the codec options.
     *
     * @param options The value to set.
     * @return a reference to this object.
     */
    public TiffWriter setCodecOptions(final CodecOptions options) {
        this.codecOptions = options;
        return this;
    }

    public boolean isExtendedCodec() {
        return extendedCodec;
    }

    /**
     * Sets whether you need special optimized codecs for some cases, including JPEG compression type.
     * Must be <tt>true</tt> if you want to use any of the further parameters.
     * Default value is <tt>false</tt>.
     *
     * @param extendedCodec whether you want to use special optimized codecs.
     * @return a reference to this object.
     */
    public TiffWriter setExtendedCodec(boolean extendedCodec) {
        this.extendedCodec = extendedCodec;
        return this;
    }

    public boolean isJpegInPhotometricRGB() {
        return jpegInPhotometricRGB;
    }

    /**
     * Sets whether you need to compress JPEG tiles/stripes with photometric interpretation RGB.
     * Default value is <tt>false</tt>, that means using YCbCr photometric interpretation &mdash;
     * standard encoding for JPEG, but not so popular in TIFF.
     * If photometric interpretation in already specified IFD and if it is RGB or YCbCr,
     * it will override recommendation of this flag.
     *
     * <p>This parameter is ignored (as if it is <tt>false</tt>), unless {@link #isExtendedCodec()}.
     *
     * @param jpegInPhotometricRGB whether you want to compress JPEG in RGB encoding.
     * @return a reference to this object.
     */
    public TiffWriter setJpegInPhotometricRGB(boolean jpegInPhotometricRGB) {
        this.jpegInPhotometricRGB = jpegInPhotometricRGB;
        return this;
    }

    public boolean compressJPEGInPhotometricRGB() {
        return extendedCodec && jpegInPhotometricRGB;
    }

    public double getJpegQuality() {
        return jpegQuality;
    }

    /**
     * Sets the compression quality for JPEG tiles/strips to a value between {@code 0} and {@code 1}.
     * Default value is <tt>1.0</tt>, like in {@link ImageWriteParam#setCompressionQuality(float)} method.
     *
     * <p>Note that {@link CodecOptions#quality} is ignored by default, unless you call {@link #setJpegCodecQuality()}.
     * In any case, the quality, specified by this method, overrides the settings in {@link CodecOptions}.
     *
     * @param jpegQuality quality a {@code float} between {@code 0} and {@code 1} indicating the desired quality level.
     * @return a reference to this object.
     */
    public TiffWriter setJpegQuality(double jpegQuality) {
        this.jpegQuality = jpegQuality;
        return this;
    }

    public TiffWriter setJpegCodecQuality() {
        if (codecOptions == null) {
            throw new IllegalStateException("Codec options was not set yet");
        }
        return setJpegQuality(codecOptions.quality);
    }

    public boolean isMissingTilesAllowed() {
        return missingTilesAllowed;
    }

    /**
     * Sets the special mode, when TIFF file is allowed to contain "missing" tiles or strips,
     * for which the offset (<tt>TileOffsets</tt> or <tt>StripOffsets</tt> tag) and/or
     * byte count (<tt>TileByteCounts</tt> or <tt>StripByteCounts</tt> tag) contains zero value.
     * In this mode, this writer will use zero offset and byte count, if
     * the written tile is actually empty &mdash; no pixels were written in it via
     * {@link #updateSamples(TiffMap, byte[], int, int, int, int)} or other methods.
     * In other case, this writer will create a normal tile, filled by
     * the {@link #setByteFiller(byte) default filler}.
     *
     * <p>Default value is <tt>false</tt>. Note that <tt>true</tt> value violates requirement of
     * the standard TIFF format (but may be used by some non-standard software, which needs to
     * detect areas, not containing any actual data).
     *
     * @param missingTilesAllowed whether "missing" tiles/strips are allowed.
     * @return a reference to this object.
     */
    public TiffWriter setMissingTilesAllowed(boolean missingTilesAllowed) {
        this.missingTilesAllowed = missingTilesAllowed;
        return this;
    }

    public byte getByteFiller() {
        return byteFiller;
    }

    public TiffWriter setByteFiller(byte byteFiller) {
        this.byteFiller = byteFiller;
        return this;
    }

    public Consumer<TiffTile> getTileInitializer() {
        return tileInitializer;
    }

    public TiffWriter setTileInitializer(Consumer<TiffTile> tileInitializer) {
        this.tileInitializer = Objects.requireNonNull(tileInitializer, "Null tileInitializer");
        return this;
    }

    /**
     * Returns position in the file of the last IFD offset, written by methods of this object.
     * It is updated by {@link #rewriteIFD(TiffIFD, boolean)}.
     *
     * <p>Immediately after creating new object this position is <tt>-1</tt>.
     *
     * @return file position of the last IFD offset.
     */
    public long positionOfLastIFDOffset() {
        return positionOfLastIFDOffset;
    }

    public void startExistingFile() throws IOException, FormatException {
        synchronized (fileLock) {
            ifdOffsets.clear();
            final TiffReader reader = new TiffReader(null, out, true);
            // - note: we should NOT call reader.close() method
            final long[] offsets = reader.readIFDOffsets();
            final long readerPositionOfLastOffset = reader.positionOfLastIFDOffset();
            this.setBigTiff(reader.isBigTiff()).setLittleEndian(reader.isLittleEndian());
            ifdOffsets.addAll(Arrays.stream(offsets).boxed().toList());
            positionOfLastIFDOffset = readerPositionOfLastOffset;
            seekToEnd();
            // - ready to write after the end of the file
            // (not necessary, but can help to avoid accidental bugs)
        }
    }

    public void startNewFile() throws IOException {
        synchronized (fileLock) {
            ifdOffsets.clear();
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
            } else {
                out.writeShort(TiffConstants.MAGIC_NUMBER);
            }

            // write the offset to the first IFD

            // for vanilla TIFFs, 8 is the offset to the first IFD
            // for BigTIFFs, 8 is the number of bytes in an offset
            if (bigTiff) {
                out.writeShort(8);
                out.writeShort(0);

                // write the offset to the first IFD for BigTIFF files
                positionOfLastIFDOffset = out.offset();
                writeOffset(16);
            } else {
                positionOfLastIFDOffset = out.offset();
                writeOffset(8);
            }
            // - we are ready to write after the header
            out.setLength(out.offset());
            // - truncate the file if it already existed:
            // it is necessary, because all new information is always written
            // to the file end (to avoid damaging existing content)
        }
    }

    public void rewriteIFD(final TiffIFD ifd, boolean updateIFDLinkages) throws IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (!ifd.hasFileOffsetForWriting()) {
            throw new IllegalArgumentException("Offset for writing IFD is not specified");
        }
        final long offset = ifd.getFileOffsetForWriting();
        assert (offset & 0x1) == 0 : "DetailedIFD.setFileOffsetForWriting() has not check offset parity: " + offset;

        writeIFDAt(ifd, offset, updateIFDLinkages);
    }

    public void writeIFDAtFileEnd(TiffIFD ifd, boolean updateIFDLinkages) throws IOException {
        writeIFDAt(ifd, null, updateIFDLinkages);
    }

    /**
     * Writes IFD at the position, specified by <tt>startOffset</tt> argument, or at the file end
     * (aligned to nearest even length) if it is <tt>null</tt>.
     *
     * <p>Note: this IFD is automatically marked as last IFD in the file (next IFD offset is 0),
     * unless you explicitly specified other next offset via {@link TiffIFD#setNextIFDOffset(long)}.
     * You also may call {@link #rewritePreviousLastIFDOffset(long)} to correct
     * this mark inside the file in the previously written IFD, but usually there is no necessity to do this.</p>
     *
     * <p>If <tt>updateIFDLinkages</tt> is <tt>true</tt>, this method also performs the following 2 actions.</p>
     *
     * <ol>
     *     <li>It updates the offset, stored in the file at {@link #positionOfLastIFDOffset()}, with start offset of
     *     this IFD (i.e. <tt>startOffset</tt> or position of the file end). This action is performed <b>only</b>
     *     if this start offset is really new for this file, i.e. if it did not present in an existing file
     *     while opening it by {@link #startExistingFile()} method and if some IFD was not already written
     *     at this position by methods of this object.</li>
     *     <li>It replaces the internal field, returned by {@link #positionOfLastIFDOffset()}, with
     *     the position of the next IFD offset, written as a part of this IFD.
     *     This action is performed <b>only</b> when this IFD is marked as the last one (see the previos note).</li>
     * </ol>
     *
     * <p>Also note: this method changes position in the output stream.
     * (Actually it will be a position after the IFD information, including all additional data
     * like arrays of offsets; but you should not use this fact.)</p>
     *
     * @param ifd               IFD to write in the output stream.
     * @param updateIFDLinkages see comments above.
     * @throws IOException in a case of any I/O errors.
     */
    public void writeIFDAt(TiffIFD ifd, Long startOffset, boolean updateIFDLinkages) throws IOException {
        synchronized (fileLock) {
            checkVirginFile();
            if (startOffset == null) {
                appendFileUntilEvenLength();
                startOffset = out.length();
            }
            if (!bigTiff && startOffset > MAXIMAL_ALLOWED_32BIT_IFD_OFFSET) {
                throw new IOException("Attempt to write too large TIFF file without big-TIFF mode: " +
                        "offset of new IFD will be " + startOffset + " > " + MAXIMAL_ALLOWED_32BIT_IFD_OFFSET);
            }
            ifd.setFileOffsetForWriting(startOffset);
            // - checks that startOffset is even and >= 0

            out.seek(startOffset);
            final Map<Integer, Object> sortedIFD = ifd.removePseudoTags(TreeMap::new);
            final int numberOfEntries = sortedIFD.size();
            final int mainIFDLength = mainIFDLength(numberOfEntries);
            writeIFDNumberOfEntries(numberOfEntries);

            final long positionOfNextOffset = writeIFDEntries(sortedIFD, startOffset, mainIFDLength);

            final long previousPositionOfLastIFDOffset = positionOfLastIFDOffset;
            // - save it, because it will be updated in writeIFDNextOffsetAt
            writeIFDNextOffsetAt(ifd, positionOfNextOffset, updateIFDLinkages);
            if (updateIFDLinkages && !ifdOffsets.contains(startOffset)) {
                // - Only if it is really newly added IFD!
                // If this offset is already contained in the list, attempt to link to it
                // will probably lead to infinite loop of IFDs.
                writeIFDOffsetAt(startOffset, previousPositionOfLastIFDOffset, false);
                ifdOffsets.add(startOffset);
            }
        }
    }

    /**
     * Rewrites the offset, stored in the file at the {@link #positionOfLastIFDOffset()},
     * with the specified value.
     * This method is useful if you want to organize the sequence of IFD inside the file manually,
     * without automatic updating IFD linkage.
     *
     * @param nextLastIFDOffset new last IFD offset.
     * @throws IOException in a case of any I/O errors.
     */
    public void rewritePreviousLastIFDOffset(long nextLastIFDOffset) throws IOException {
        synchronized (fileLock) {
            if (nextLastIFDOffset < 0) {
                throw new IllegalArgumentException("Negative next last IFD offset " + nextLastIFDOffset);
            }
            if (positionOfLastIFDOffset < 0) {
                throw new IllegalStateException("Writing to this TIFF file is not started yet");
            }
            writeIFDOffsetAt(nextLastIFDOffset, positionOfLastIFDOffset, false);
            // - last argument is not important: positionOfLastIFDOffset will not change in any case
        }
    }


    public void writeTile(TiffTile tile) throws FormatException, IOException {
        encode(tile);
        writeEncodedTile(tile, true);
    }

    public void writeEncodedTile(TiffTile tile, boolean freeAfterWriting) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            return;
        }
        long t1 = debugTime();
        synchronized (fileLock) {
            checkVirginFile();
            TiffTileIO.writeToEnd(tile, out, freeAfterWriting, !bigTiff);
        }
        long t2 = debugTime();
        timeWriting += t2 - t1;
    }

    public void updateSamples(TiffMap map, byte[] samples) {
        Objects.requireNonNull(map, "Null TIFF map");
        updateImage(map, samples, 0, 0, map.dimX(), map.dimY());
    }

    public void updateSamples(
            final TiffMap map,
            final byte[] samples,
            final int fromX,
            final int fromY,
            final int sizeX,
            final int sizeY) {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samples, "Null samples");
        final boolean planarSeparated = map.isPlanarSeparated();
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        TiffTools.checkRequestedAreaInArray(samples, sizeX, sizeY, map.totalBytesPerPixel());
        if (sizeX == 0 || sizeY == 0) {
            // - if no pixels are updated, no need to expand the map and to check correct expansion
            return;
        }
        map.expandDimensions(fromX + sizeX, fromY + sizeY);

        final int mapTileSizeX = map.tileSizeX();
        final int mapTileSizeY = map.tileSizeY();
        final int bytesPerSample = map.bytesPerSample();
        final int numberOfSeparatedPlanes = map.numberOfSeparatedPlanes();
        final int samplesPerPixel = map.tileSamplesPerPixel();
        final int bytesPerPixel = map.tileBytesPerPixel();

        final int toX = fromX + sizeX;
        final int toY = fromY + sizeY;
        final int minXIndex = fromX / mapTileSizeX;
        final int minYIndex = fromY / mapTileSizeY;
        if (minXIndex >= map.gridTileCountX() || minYIndex >= map.gridTileCountY()) {
            throw new AssertionError("Map was not expanded/checked properly: minimal tile index (" +
                    minXIndex + "," + minYIndex + ") is out of tile grid 0<=x<" +
                    map.gridTileCountX() + ", 0<=y<" + map.gridTileCountY() + "; map: " + map);
        }
        final int maxXIndex = Math.min(map.gridTileCountX() - 1, (toX - 1) / mapTileSizeX);
        final int maxYIndex = Math.min(map.gridTileCountY() - 1, (toY - 1) / mapTileSizeY);

        final int tileChunkedRowSizeInBytes = mapTileSizeX * bytesPerPixel;
        final int samplesChunkedRowSizeInBytes = sizeX * bytesPerPixel;
        final int tileOneChannelRowSizeInBytes = mapTileSizeX * bytesPerSample;
        final int samplesOneChannelRowSizeInBytes = sizeX * bytesPerSample;

        final boolean autoInterleave = this.autoInterleaveSource;
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

                    final TiffTile tile = map.getOrNewMultiplane(p, xIndex, yIndex);
                    tile.checkReadyForNewDecodedData(false);
                    tile.cropToMap(true);
                    // - In stripped image, we should correct the height of the last row.
                    // It is important for writing: without this correction, GIMP and other libtiff-based programs
                    // will report about an error (see libtiff, tif_jpeg.c, assigning segment_width/segment_height)
                    // However, if tiling is requested via TILE_WIDTH/TILE_LENGTH tags, we SHOULD NOT do this.
                    tile.fillEmpty(tileInitializer);
                    final byte[] data = tile.getDecodedData();

                    final int tileSizeX = tile.getSizeX();
                    final int tileSizeY = tile.getSizeY();
                    final int sizeXInTile = Math.min(toX - tileStartX, tileSizeX - fromXInTile);
                    assert sizeXInTile > 0 : "sizeXInTile=" + sizeXInTile;
                    final int sizeYInTile = Math.min(toY - tileStartY, tileSizeY - fromYInTile);
                    assert sizeYInTile > 0 : "sizeYInTile=" + sizeYInTile;
                    tile.reduceUnsetInTile(fromXInTile, fromYInTile, sizeXInTile, sizeYInTile);

                    // Tile must be interleaved always (RGBRGB...).
                    // A) planarSeparated=false, autoInterleave=false:
                    //      source pixels should be RGBRGB..., tile also will be RGBRGB...
                    // B) planarSeparated=false, autoInterleave=true:
                    //      source pixels are RRR...GGG..BBB..., every tile will also be RRR...GGG..BBB...
                    //      (will be interleaved later by tile.interleaveSamples() call)
                    // C) planarSeparated=true, autoInterleave is ignored:
                    //      source pixels are RRR...GGG..BBB..., we will have separate RRR tiles, GGG tiles, BBB tiles
                    //      (actually each tile is monochrome).
                    if (!planarSeparated && !autoInterleave) {
//                        System.out.printf("!!!Chunked: %d%n", samplesPerPixel);
                        // - Case A: source data are already interleaved (like RGBRGB...): maybe, external code
                        // prefers to use interleaved form, for example, OpenCV library.
                        // Here tile will be actually interleaved directly after this method;
                        // we'll need to inform it about this fact (by setInterleaved(true)) later in encode().
                        final int partSizeXInBytes = sizeXInTile * bytesPerPixel;
                        int tOffset = (fromYInTile * tileSizeX + fromXInTile) * bytesPerPixel;
                        int samplesOffset = (yDiff * sizeX + xDiff) * bytesPerPixel;
                        for (int i = 0; i < sizeYInTile; i++) {
                            System.arraycopy(samples, samplesOffset, data, tOffset, partSizeXInBytes);
                            tOffset += tileChunkedRowSizeInBytes;
                            samplesOffset += samplesChunkedRowSizeInBytes;
                        }
                    } else {
//                        System.out.printf("!!!Separate: %d%n", samplesPerPixel);
                        // - Source data are separated to channel planes: standard form, more convenient for image
                        // processing; this form is used for results of TiffReader by default (unless
                        // you specify another behaviour by setInterleaveResults method).
                        // Here are 2 possible cases:
                        //      B) planarSeparated=false (most typical): results in the file should be interleaved;
                        // we must prepare a single tile, but with SEPARATED data (they will be interleaved later);
                        //      C) planarSeparated=true (rare): for 3 channels (RGB) we must prepare 3 separate tiles;
                        // in this case samplesPerPixel=1.
                        final int partSizeXInBytes = sizeXInTile * bytesPerSample;
                        for (int s = 0; s < samplesPerPixel; s++) {
                            int tOffset = (((s * tileSizeY) + fromYInTile) * tileSizeX + fromXInTile) * bytesPerSample;
                            int samplesOffset = (((p + s) * sizeY + yDiff) * sizeX + xDiff) * bytesPerSample;
                            for (int i = 0; i < sizeYInTile; i++) {
                                System.arraycopy(samples, samplesOffset, data, tOffset, partSizeXInBytes);
                                tOffset += tileOneChannelRowSizeInBytes;
                                samplesOffset += samplesOneChannelRowSizeInBytes;
                            }
                        }
                    }
                }
            }
        }

        /* // The following code did properly work only starting from tile boundaries.

        final int dimX = map.dimX();
        final int dimY = map.dimY();
        final int tileCountXInRegion = (sizeX + mapTileSizeX - 1) / mapTileSizeX;
        final int tileCountYInRegion = (sizeY + mapTileSizeY - 1) / mapTileSizeY;
        assert tileCountXInRegion <= sizeX;
        assert tileCountYInRegion <= sizeY;

        for (int p = 0, tileIndex = 0; p < numberOfSeparatedPlanes; p++) {
            // - in a rare case PlanarConfiguration=2 (RRR...GGG...BBB...),
            // this order provides increasing tile offsets while simple usage of this class
            // (this is not required, but provides more efficient per-plane reading)
            for (int yIndex = 0; yIndex < tileCountYInRegion; yIndex++) {
                final int yOffset = yIndex * mapTileSizeY;
                assert (long) fromY + (long) yOffset < dimY : "region must  be checked before calling splitTiles";
                final int y = fromY + yOffset;
                for (int xIndex = 0; xIndex < tileCountXInRegion; xIndex++, tileIndex++) {
                    final int xOffset = xIndex * mapTileSizeX;
                    assert (long) fromX + (long) xOffset < dimX : "region must be checked before calling splitTiles";
                    final int x = fromX + xOffset;
                    final TiffTile tile = map.getOrNewMultiplane(p, x / mapTileSizeX, y / mapTileSizeY);
                    tile.cropToMap(true);
                    // - In stripped image, we should correct the height of the last row.
                    // It is important for writing: without this correction, GIMP and other libtiff-based programs
                    // will report about an error (see libtiff, tif_jpeg.c, assigning segment_width/segment_height)
                    // However, if tiling is requested via TILE_WIDTH/TILE_LENGTH tags, we SHOULD NOT do this.
                    tile.fillEmpty(tileInitializer);
                    final int partSizeY = Math.min(sizeY - yOffset, tile.getSizeY());
                    final int partSizeX = Math.min(sizeX - xOffset, tile.getSizeX());
                    final byte[] data = tile.getDecoded();
                    // - if the tile already exists, we will accurately update its content
                    if (!planarSeparated && !autoInterleave) {
                        // - Source data are already interleaved (like RGBRGB...): maybe, external code prefers
                        // to use interleaved form, for example, OpenCV library.
                        final int tileRowSizeInBytes = mapTileSizeX * bytesPerPixel;
                        final int partSizeXInBytes = partSizeX * bytesPerPixel;
                        for (int yInTile = 0; yInTile < partSizeY; yInTile++) {
                            final int i = yInTile + yOffset;
                            final int tileOffset = yInTile * tileRowSizeInBytes;
                            final int samplesOffset = (i * sizeX + xOffset) * bytesPerPixel;
                            System.arraycopy(samples, samplesOffset, data, tileOffset, partSizeXInBytes);
                        }
                    } else {
                        // - Source data are separated to channel planes: standard form, more convenient for image
                        // processing; this form is used for results of TiffReader by default (unless
                        // you specify another behaviour by setInterleaveResults method).
                        // Here are 2 possible cases:
                        //      planarSeparated=false (most typical): results in the file should be interleaved;
                        // we must prepare a single tile, but with SEPARATED data (they will be interleaved later);
                        //      planarSeparated=true (rare): for 3 channels (RGB) we must prepare 3 separate tiles;
                        // in this case samplesPerPixel=1.
                        final int channelSize = tile.getSizeInPixels() * bytesPerSample;
                        final int separatedPlaneSize = sizeX * sizeY * bytesPerSample;
                        final int tileRowSizeInBytes = mapTileSizeX * bytesPerSample;
                        final int partSizeXInBytes = partSizeX * bytesPerSample;
                        int tileChannelOffset = 0;
                        for (int s = 0; s < samplesPerPixel; s++, tileChannelOffset += channelSize) {
                            final int channelOffset = (p + s) * separatedPlaneSize;
                            for (int yInTile = 0; yInTile < partSizeY; yInTile++) {
                                final int i = yInTile + yOffset;
                                final int tileOffset = tileChannelOffset + yInTile * tileRowSizeInBytes;
                                final int samplesOffset = channelOffset + (i * sizeX + xOffset) * bytesPerSample;
                                System.arraycopy(samples, samplesOffset, data, tileOffset, partSizeXInBytes);
                            }
                        }
                    }
                }
            }
        }
         */
    }

    public void updateImage(TiffMap map, Object samplesArray) {
        Objects.requireNonNull(map, "Null TIFF map");
        updateImage(map, samplesArray, 0, 0, map.dimX(), map.dimY());
    }

    public void updateImage(
            final TiffMap map,
            final Object samplesArray,
            final int fromX,
            final int fromY,
            final int sizeX,
            final int sizeY) {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        final Class<?> elementType = samplesArray.getClass().getComponentType();
        if (elementType == null) {
            throw new IllegalArgumentException("The specified samplesArray is not actual an array: " +
                    "it is " + samplesArray.getClass());
        }
        if (elementType != map.elementType()) {
            throw new IllegalArgumentException("Invalid element type of samples array: " + elementType +
                    ", but the specified TIFF map stores " + map.elementType());
        }
        final byte[] samples = TiffTools.arrayToBytes(samplesArray, isLittleEndian());
        updateSamples(map, samples, fromX, fromY, sizeX, sizeY);
    }

    public void encode(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            return;
        }
        long t1 = debugTime();
        prepareDecodedTileForEncoding(tile);
        long t2 = debugTime();

        tile.checkStoredNumberOfPixels();

        TiffCompression compression = tile.ifd().getCompression();
        final KnownCompression known = KnownCompression.valueOfOrNull(compression);
        if (known == null) {
            throw new UnsupportedCompressionException("Compression \"" + compression.getCodecName() +
                    "\" (TIFF code " + compression.getCode() + ") is not supported");
        }
        // - don't try to write unknown compressions: they may require additional processing

        Codec codec = null;
        if (extendedCodec) {
            codec = known.extendedCodec(scifio == null ? null : scifio.getContext());
        }
        if (codec == null && scifio == null) {
            codec = known.noContextCodec();
            // - if there is no SCIFIO context, let's create codec directly: it's better than do nothing
        }
        CodecOptions codecOptions = buildWritingOptions(known, tile, codec);
        long t3 = debugTime();
        byte[] data = tile.getDecodedData();
        if (codec != null) {
            tile.setEncodedData(codec.compress(data, codecOptions));
        } else {
            if (scifio == null) {
                throw new IllegalStateException(
                        "Compression type " + compression + " requires specifying non-null SCIFIO context");
            }
            tile.setEncodedData(compression.compress(scifio.codec(), data, codecOptions));
        }
        TiffTools.invertFillOrderIfRequested(tile);
        long t4 = debugTime();

        timePreparingDecoding += t2 - t1;
        timeCustomizingEncoding += t3 - t2;
        timeEncoding += t4 - t3;
    }

    public void encode(TiffMap map) throws FormatException {
        Objects.requireNonNull(map, "Null TIFF map");
        for (TiffTile tile : map.tiles()) {
            if (!tile.isEncoded()) {
                encode(tile);
            }
        }
    }

    public void correctIFDForWriting(TiffIFD ifd) throws FormatException {
        correctIFDForWriting(ifd, true);
    }

    public void correctIFDForWriting(TiffIFD ifd, boolean strictCheck) throws FormatException {
        final int samplesPerPixel = ifd.getSamplesPerPixel();
        if (!ifd.containsKey(IFD.BITS_PER_SAMPLE)) {
            ifd.put(IFD.BITS_PER_SAMPLE, new int[]{8});
            // - Default value of BitsPerSample is 1 bit/pixel, but it is a rare case,
            // not supported at all by SCIFIO library FormatTools; so, we set another default 8 bits/pixel
            // Note: we do not change SAMPLE_FORMAT tag here!
        }
        final TiffSampleType sampleType;
        try {
            sampleType = ifd.sampleType();
        } catch (FormatException e) {
            throw new UnsupportedTiffFormatException("Cannot write TIFF, because " +
                    "requested combination of number of bits per sample and sample format is not supported: " +
                    e.getMessage());
        }
        if (strictCheck) {
            final OptionalInt optionalBits = ifd.tryEqualBitDepth();
            if (optionalBits.isEmpty()) {
                throw new UnsupportedTiffFormatException("Cannot write TIFF, because requested number of " +
                        "bits per samples is unequal for different channels: " +
                        Arrays.toString(ifd.getBitsPerSample()) + " (this variant is not supported)");
            }
            final int bits = optionalBits.getAsInt();
            final boolean usualPrecision = bits == 8 || bits == 16 || bits == 32 || bits == 64;
            if (!usualPrecision) {
                throw new UnsupportedTiffFormatException("Cannot write TIFF, because " +
                        "requested number of bits per sample is not supported: " + bits + " bits");
            }
            if (sampleType == TiffSampleType.FLOAT && bits != 32) {
                throw new UnsupportedTiffFormatException("Cannot write TIFF, because " +
                        "requested number of bits per sample is not supported: " +
                        bits + " bits for floating-point precision");
            }
        } else {
            ifd.putSampleType(sampleType);
        }

        if (!ifd.containsKey(IFD.COMPRESSION)) {
            ifd.put(IFD.COMPRESSION, TiffCompression.UNCOMPRESSED.getCode());
            // - We prefer explicitly specify this case
        }
        final TiffCompression compression = ifd.getCompression();
        // - UnsupportedTiffFormatException for unknown compression

        final boolean jpeg = compression == TiffCompression.JPEG;
        PhotoInterp photometric = ifd.containsKey(IFD.PHOTOMETRIC_INTERPRETATION) ?
                ifd.getPhotometricInterpretation() :
                null;
        if (jpeg) {
            if (samplesPerPixel != 1 && samplesPerPixel != 3) {
                throw new FormatException("JPEG compression for " + samplesPerPixel + " channels is not supported");
            }
            if (sampleType != TiffSampleType.UINT8) {
                throw new FormatException("JPEG compression is supported for 8-bit unsigned samples only, but " +
                        (sampleType == TiffSampleType.INT8 ? "signed 8-bit samples requested" :
                                "requested number of bits/samples is " + Arrays.toString(ifd.getBitsPerSample())));
            }
            if (photometric == null) {
                photometric = samplesPerPixel == 1 ? PhotoInterp.BLACK_IS_ZERO :
                        (jpegInPhotometricRGB || !ifd.isChunked()) ? PhotoInterp.RGB : PhotoInterp.Y_CB_CR;
            } else {
                checkPhotometricInterpretation(photometric,
                        samplesPerPixel == 1 ? EnumSet.of(PhotoInterp.BLACK_IS_ZERO) :
                                extendedCodec ?
                                        EnumSet.of(PhotoInterp.Y_CB_CR, PhotoInterp.RGB) :
                                        EnumSet.of(PhotoInterp.Y_CB_CR),
                        "JPEG " + samplesPerPixel + "-channel image");
            }
        } else if (samplesPerPixel == 1) {
            final boolean hasColorMap = ifd.containsKey(IFD.COLOR_MAP);
            if (photometric == null) {
                photometric = hasColorMap ? PhotoInterp.RGB_PALETTE : PhotoInterp.BLACK_IS_ZERO;
            } else {
                if (photometric == PhotoInterp.RGB_PALETTE && !hasColorMap) {
                    throw new FormatException("Cannot write TIFF image: photometric interpretation \"" +
                            photometric.getName() + "\" requires also \"ColorMap\" tag");
                }
                checkPhotometricInterpretation(photometric, EnumSet.of(
                                PhotoInterp.WHITE_IS_ZERO, PhotoInterp.BLACK_IS_ZERO,
                                PhotoInterp.RGB_PALETTE, PhotoInterp.CFA_ARRAY,
                                PhotoInterp.TRANSPARENCY_MASK),
                        "single-channel image (1 sample/pixel)");
            }
        } else if (samplesPerPixel == 3) {
            if (photometric == null) {
                photometric = PhotoInterp.RGB;
            }
        } else {
            if (photometric == null) {
                throw new IllegalArgumentException("Cannot write TIFF image: photometric interpretation " +
                        "is not specified in IFD and cannot be determined automatically");
            }
        }
        ifd.putPhotometricInterpretation(photometric);

        ifd.put(IFD.LITTLE_ENDIAN, out.isLittleEndian());
        // - will be used, for example, in getCompressionCodecOptions
        ifd.put(IFD.BIG_TIFF, bigTiff);
        // - not used, but helps to provide good DetailedIFD.toString
    }

    public TiffMap newMap(TiffIFD ifd, int numberOfChannels, Class<?> elementType, boolean signedIntegers)
            throws FormatException {
        return newMap(ifd, numberOfChannels, elementType, signedIntegers, false);
    }

    public TiffMap newMap(
            TiffIFD ifd,
            int numberOfChannels,
            Class<?> elementType,
            boolean signedIntegers,
            boolean resizable)
            throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        ifd.putPixelInformation(numberOfChannels, elementType, signedIntegers);
        return newMap(ifd, resizable);
    }

    /**
     * Starts writing new IFD image.
     *
     * @param ifd       newly created and probably customized IFD.
     * @param resizable if <tt>true</tt>, IFD dimensions may not be specified yet.
     * @return map for writing further data.
     */
    public TiffMap newMap(TiffIFD ifd, boolean resizable) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (ifd.isFrozen()) {
            throw new IllegalStateException("IFD is already frozen for usage while writing TIFF; " +
                    "probably you called this method twice");
        }
        correctIFDForWriting(ifd);
        final TiffMap map = new TiffMap(ifd, resizable);
        map.buildGrid();
        // - useful to perform loops on all tiles, especially in non-resizable case
        ifd.removeNextIFDOffset();
        ifd.removeDataPositioning();
        if (resizable) {
            ifd.removeImageDimensions();
        }
        ifd.freeze();
        // - actually not necessary, but helps to avoid possible bugs
        return map;
    }

    public TiffMap newMap(TiffIFD ifd) throws FormatException {
        return newMap(ifd, false);
    }

    /**
     * Starts overwriting existing IFD image.
     *
     * <p>Usually you should avoid usage this method without necessity: though it allows modify some existing tiles,
     * but all newly updated tiles will written at the file end, and the previously occupied space in the file
     * will be lost. This method may be suitable if you need to perform little correction in 1-2 tiles of
     * very large TIFF without full recompression of all its tiles.</p>
     *
     * <p>Note: this method does not remove information about tile/strip offsets and byte counts. So, you can
     * read some tiles from this IFD via {@link TiffReader} class (it is important for tiles, that you neeed to
     * partially fill, but partially load from the old file).</p>
     *
     * @param ifd IFD of some existing image, probably loaded from the current TIFF file.
     * @return map for writing further data.
     */
    public TiffMap existingMap(TiffIFD ifd) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        correctIFDForWriting(ifd);
        final TiffMap map = new TiffMap(ifd);
        final long[] offsets = ifd.cachedTileOrStripOffsets();
        final long[] byteCounts = ifd.cachedTileOrStripByteCounts();
        assert offsets != null;
        assert byteCounts != null;
        map.buildGrid();
        if (offsets.length < map.size() || byteCounts.length < map.size()) {
            throw new ConcurrentModificationException("Strange length of tile offsets " + offsets.length +
                    " or byte counts " + byteCounts.length);
            // - should not occur: it is checked in getTileOrStripOffsets/getTileOrStripByteCounts methods
            // (only possible way is modification from parallel thread)
        }
        ifd.freeze();
        // - actually not necessary, but helps to avoid possible bugs
        int k = 0;
        for (TiffTile tile : map.tiles()) {
            tile.setStoredDataFileRange(offsets[k], (int) byteCounts[k]);
            // - we "tell" that all tiles already exist in the file;
            // note we can use index k, because buildGrid() method, called above for an empty map,
            // provided correct tiles order
            tile.removeUnset();
            k++;
        }
        return map;
    }

    /**
     * Prepare writing new image with known fixed sizes.
     * This method writes image header (IFD) to the end of the TIFF file,
     * so it will be placed before actually written data: it helps
     * to improve performance of future reading this file.
     *
     * <p>Note: this method does nothing if the image is {@link TiffMap#isResizable() resizable}
     * or if this action is disabled by {@link #setWritingForwardAllowed(boolean) setWritingForwardAllowed(false)}
     * call.
     * In this case, IFD will be written at the final stage ({@link #complete(TiffMap)} method).
     *
     * @param map map, describing the image.
     */
    public void writeForward(TiffMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        if (!writingForwardAllowed || map.isResizable()) {
            return;
        }
        final long[] offsets = new long[map.numberOfGridTiles()];
        final long[] byteCounts = new long[map.numberOfGridTiles()];
        // - zero-filled by Java
        map.ifd().updateDataPositioning(offsets, byteCounts);
        final TiffIFD ifd = map.ifd();
        if (!ifd.hasFileOffsetForWriting()) {
            writeIFDAtFileEnd(ifd, false);
        }
    }

    public void complete(final TiffMap map) throws IOException, FormatException {
        Objects.requireNonNull(map, "Null TIFF map");
        final boolean resizable = map.isResizable();
        map.checkDimensions();

        encode(map);
        // - encode tiles, which are not encoded yet

        final TiffIFD ifd = map.ifd();
        if (resizable) {
            ifd.updateImageDimensions(map.dimX(), map.dimY());
        }

        completeWritingMap(map);
        map.cropAllUnset();
        appendFileUntilEvenLength();
        // - not absolutely necessary, but good idea

        if (ifd.hasFileOffsetForWriting()) {
            // - usually it means that we did call writeForward
            rewriteIFD(ifd, true);
        } else {
            writeIFDAtFileEnd(ifd, true);
        }

        seekToEnd();
        // - This seeking to file end is not necessary, but can help to avoid accidental bugs
        // (this is much better than keeping file offset in the middle of the last image
        // between IFD and newly written TIFF tiles).
    }

    public void writeSamples(final TiffMap map, byte[] samples) throws FormatException, IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        writeSamples(map, samples, 0, 0, map.dimX(), map.dimY());
    }

    public void writeSamples(TiffMap map, byte[] samples, int fromX, int fromY, int sizeX, int sizeY)
            throws FormatException, IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samples, "Null samples");

        clearTime();
        long t1 = debugTime();
        updateSamples(map, samples, fromX, fromY, sizeX, sizeY);
        long t2 = debugTime();
        writeForward(map);
        long t3 = debugTime();
        encode(map);
        long t4 = debugTime();
        complete(map);
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t5 = debugTime();
            long sizeOf = map.totalSizeInBytes();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s wrote %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f copying data + %.3f writing IFD " +
                            "+ %.3f/%.3f encoding/writing " +
                            "(%.3f prepare + %.3f customize + %.3f encode + %.3f write), %.3f MB/s",
                    getClass().getSimpleName(),
                    map.numberOfChannels(), sizeX, sizeY, sizeOf / 1048576.0,
                    (t5 - t1) * 1e-6,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6,
                    (t4 - t3) * 1e-6, (t5 - t4) * 1e-6,
                    timePreparingDecoding * 1e-6,
                    timeCustomizingEncoding * 1e-6,
                    timeEncoding * 1e-6,
                    timeWriting * 1e-6,
                    sizeOf / 1048576.0 / ((t5 - t1) * 1e-9)));
        }
    }

    public void writeImage(TiffMap map, Object samplesArray) throws FormatException, IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        writeImage(map, samplesArray, 0, 0, map.dimX(), map.dimY());
    }

    public void writeImage(TiffMap map, Object samplesArray, int fromX, int fromY, int sizeX, int sizeY)
            throws FormatException, IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        clearTime();
        long t1 = debugTime();
        updateImage(map, samplesArray, fromX, fromY, sizeX, sizeY);
        long t2 = debugTime();
        writeForward(map);
        long t3 = debugTime();
        encode(map);
        long t4 = debugTime();
        complete(map);
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t5 = debugTime();
            long sizeOf = map.totalSizeInBytes();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s wrote %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f conversion/copying data + %.3f writing IFD " +
                            "+ %.3f/%.3f encoding/writing " +
                            "(%.3f prepare + %.3f customize + %.3f encode + %.3f write), %.3f MB/s",
                    getClass().getSimpleName(),
                    map.numberOfChannels(), sizeX, sizeY, sizeOf / 1048576.0,
                    (t5 - t1) * 1e-6,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6,
                    (t4 - t3) * 1e-6, (t5 - t4) * 1e-6,
                    timePreparingDecoding * 1e-6,
                    timeCustomizingEncoding * 1e-6,
                    timeEncoding * 1e-6,
                    timeWriting * 1e-6,
                    sizeOf / 1048576.0 / ((t5 - t1) * 1e-9)));
        }
    }

    public void fillEmptyTile(TiffTile tiffTile) {
        if (byteFiller != 0) {
            // - Java-arrays are automatically filled by zero
            Arrays.fill(tiffTile.getDecodedData(), byteFiller);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (fileLock) {
            out.close();
        }
    }

    protected void prepareDecodedTileForEncoding(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        if (autoInterleaveSource) {
            tile.interleaveSamples();
        } else {
            tile.setInterleaved(true);
            // - if not autoInterleave, we should suppose that the samples were already interleaved
        }

        // scifio.tiff().difference(tile.getDecodedData(), ifd);
        // - this solution requires using SCIFIO context class; it is better to avoid this
        TiffTools.subtractPredictionIfRequested(tile);
    }

    private void clearTime() {
        timeWriting = 0;
        timeCustomizingEncoding = 0;
        timePreparingDecoding = 0;
        timeEncoding = 0;
    }

    private void checkVirginFile() throws IOException {
        if (positionOfLastIFDOffset < 0) {
            throw new IllegalStateException("Writing to this TIFF file is not started yet");
        }
        final boolean exists = out.exists();
        if (!exists || out.length() < (bigTiff ? 16 : 8)) {
            // - very improbable, but can occur as a result of direct operations with output stream
            throw new IllegalStateException(
                    (exists ?
                            "Existing TIFF file is too short (" + out.length() + " bytes)" :
                            "TIFF file does not exists yet") +
                            ": probably file header was not written correctly by startWriting() method");
        }
    }

    private int mainIFDLength(int numberOfEntries) {
        final int numberOfEntriesLimit = bigTiff ? TiffReader.MAX_NUMBER_OF_IFD_ENTRIES : 65535;
        if (numberOfEntries > numberOfEntriesLimit) {
            throw new IllegalStateException("Too many IFD entries: " + numberOfEntries + " > " + numberOfEntriesLimit);
            // - theoretically BigTIFF allows to write more, but we prefer to make some restriction and
            // guarantee 32-bit number of bytes
        }
        final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY : TiffConstants.BYTES_PER_ENTRY;
        return (bigTiff ? 8 + 8 : 2 + 4) + bytesPerEntry * numberOfEntries;
        // - includes starting number of entries (2 or 8) and ending next offset (4 or 8)
    }

    private void writeIFDNumberOfEntries(int numberOfEntries) throws IOException {
        if (bigTiff) {
            out.writeLong(numberOfEntries);
        } else {
            writeUnsignedShort(out, numberOfEntries);
        }
    }

    private long writeIFDEntries(Map<Integer, Object> ifd, long startOffset, int mainIFDLength) throws IOException {
        final long afterMain = startOffset + mainIFDLength;
        final BytesLocation bytesLocation = new BytesLocation(0, "memory-buffer");
        final long positionOfNextOffset;
        try (final DataHandle<Location> extraHandle = TiffTools.getBytesHandle(bytesLocation)) {
            for (final Map.Entry<Integer, Object> e : ifd.entrySet()) {
                writeIFDValueAtCurrentPosition(extraHandle, afterMain, e.getKey(), e.getValue());
            }

            positionOfNextOffset = out.offset();
            writeOffset(TiffIFD.LAST_IFD_OFFSET);
            // - not too important: will be rewritten in writeIFDNextOffset
            final int extraLength = (int) extraHandle.offset();
            extraHandle.seek(0L);
            DataHandles.copy(extraHandle, out, extraLength);
            appendUntilEvenPosition(out);
        }
        return positionOfNextOffset;
    }

    /**
     * Writes the given IFD value to the {@link #getStream() main output stream}, excepting "extra" data,
     * which are written into the specified <tt>extraBuffer</tt>. After calling this method, you
     * should copy full content of <tt>extraBuffer</tt> into the main stream at the position,
     * specified by the 2nd argument; {@link #rewriteIFD(TiffIFD, boolean)} method does it automatically.
     *
     * <p>Here "extra" data means all data, for which IFD contains their offsets instead of data itself,
     * like arrays or text strings. The "main" data is 12-byte IFD record (20-byte for Big-TIFF),
     * which is written by this method into the main output stream from its current position.
     *
     * @param extraBuffer              buffer to which "extra" IFD information should be written.
     * @param bufferOffsetInResultFile position of "extra" data in the result TIFF file =
     *                                 bufferOffsetInResultFile +
     *                                 offset of the written "extra" data inside <tt>extraBuffer</tt>>;
     *                                 for example, this argument may be a position directly after
     *                                 the "main" content (sequence of 12/20-byte records).
     * @param tag                      IFD tag to write.
     * @param value                    IFD value to write.
     */
    private void writeIFDValueAtCurrentPosition(
            final DataHandle<Location> extraBuffer,
            final long bufferOffsetInResultFile,
            final int tag,
            Object value) throws IOException {
        extraBuffer.setLittleEndian(isLittleEndian());
        appendUntilEvenPosition(extraBuffer);

        // convert singleton objects into arrays, for simplicity
        if (value instanceof Short) {
            value = new short[]{(Short) value};
        } else if (value instanceof Integer) {
            value = new int[]{(Integer) value};
        } else if (value instanceof Long) {
            value = new long[]{(Long) value};
        } else if (value instanceof TiffRational) {
            value = new TiffRational[]{(TiffRational) value};
        } else if (value instanceof Float) {
            value = new float[]{(Float) value};
        } else if (value instanceof Double) {
            value = new double[]{(Double) value};
        }

        final boolean bigTiff = this.bigTiff;
        final int dataLength = bigTiff ? 8 : 4;

        // write directory entry to output buffers
        writeUnsignedShort(out, tag); // tag
        if (value instanceof byte[] q) {
            out.writeShort(IFDType.UNDEFINED.getCode());
            // - Most probable type. Maybe in future we will support here some algorithm,
            // determining necessary type on the base of the tag value.
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength) {
                for (byte byteValue : q) {
                    out.writeByte(byteValue);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                extraBuffer.write(q);
            }
        } else if (value instanceof short[]) { // suppose BYTE (unsigned 8-bit)
            final short[] q = (short[]) value;
            out.writeShort(IFDType.BYTE.getCode());
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength) {
                for (short s : q) {
                    out.writeByte(s);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (short shortValue : q) {
                    extraBuffer.writeByte(shortValue);
                }
            }
        } else if (value instanceof String) { // suppose ASCII
            final char[] q = ((String) value).toCharArray();
            out.writeShort(IFDType.ASCII.getCode()); // type
            writeIntOrLong(out, q.length + 1);
            // - with concluding zero byte
            if (q.length < dataLength) {
                for (char c : q) {
                    writeUnsignedByte(out, c); // value(s)
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0); // padding
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (char charValue : q) {
                    writeUnsignedByte(extraBuffer, charValue); // values
                }
                extraBuffer.writeByte(0); // concluding zero byte
            }
        } else if (value instanceof int[]) { // suppose SHORT (unsigned 16-bit)
            final int[] q = (int[]) value;
            if (q.length == 1) {
                // - we should allow to use usual int values for 32-bit tags, to avoid a lot of obvious bugs
                final int v = q[0];
                if (v >= 0xFFFF) {
                    out.writeShort(IFDType.LONG.getCode());
                    writeIntOrLong(out, q.length);
                    writeIntOrLong(out, v);
                    return;
                }
            }
            out.writeShort(IFDType.SHORT.getCode()); // type
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength / 2) {
                for (int intValue : q) {
                    writeUnsignedShort(out, intValue); // value(s)
                }
                for (int i = q.length; i < dataLength / 2; i++) {
                    out.writeShort(0); // padding
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (int intValue : q) {
                    extraBuffer.writeShort(intValue); // values
                }
            }
        } else if (value instanceof long[]) { // suppose LONG (unsigned 32-bit) or LONG8 for BitTIFF
            final long[] q = (long[]) value;
            if (AVOID_LONG8_FOR_ACTUAL_32_BITS && q.length == 1 && bigTiff) {
                // - note: inside TIFF, long[1] is saved in the same way as Long; we have a difference in Java only
                final long v = q[0];
                if (v == (int) v) {
                    // - it is probable for the following tags, if they are added
                    // manually via DetailedIFD.put with "long" argument
                    switch (tag) {
                        case TiffIFD.IMAGE_WIDTH,
                                TiffIFD.IMAGE_LENGTH,
                                TiffIFD.TILE_WIDTH,
                                TiffIFD.TILE_LENGTH,
                                TiffIFD.IMAGE_DEPTH,
                                TiffIFD.ROWS_PER_STRIP,
                                TiffIFD.NEW_SUBFILE_TYPE -> {
                            out.writeShort(IFDType.LONG.getCode());
                            writeIntOrLong(out, q.length);
                            out.writeInt((int) v);
                            out.writeInt(0);
                            // - 4 bytes of padding until full length 20 bytes
                            return;
                        }
                    }
                }
            }
            final int type = bigTiff ? IFDType.LONG8.getCode() : IFDType.LONG.getCode();
            out.writeShort(type);
            writeIntOrLong(out, q.length);

            if (q.length <= 1) {
                for (int i = 0; i < q.length; i++) {
                    writeIntOrLong(out, q[0]);
                    // - q[0]: it is actually performed 0 or 1 times
                }
                for (int i = q.length; i < 1; i++) {
                    writeIntOrLong(out, 0);
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (long longValue : q) {
                    writeIntOrLong(extraBuffer, longValue);
                }
            }
        } else if (value instanceof TiffRational[]) { // RATIONAL
            final TiffRational[] q = (TiffRational[]) value;
            out.writeShort(IFDType.RATIONAL.getCode()); // type
            writeIntOrLong(out, q.length);
            if (bigTiff && q.length == 1) {
                out.writeInt((int) q[0].getNumerator());
                out.writeInt((int) q[0].getDenominator());
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (TiffRational tiffRational : q) {
                    extraBuffer.writeInt((int) tiffRational.getNumerator());
                    extraBuffer.writeInt((int) tiffRational.getDenominator());
                }
            }
        } else if (value instanceof float[]) { // FLOAT
            final float[] q = (float[]) value;
            out.writeShort(IFDType.FLOAT.getCode()); // type
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength / 4) {
                for (float floatValue : q) {
                    out.writeFloat(floatValue); // value
                    // - in old SCIFIO code, here was a bug (for a case bigTiff): q[0] was always written
                }
                for (int i = q.length; i < dataLength / 4; i++) {
                    out.writeInt(0); // padding
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (float floatValue : q) {
                    extraBuffer.writeFloat(floatValue); // values
                }
            }
        } else if (value instanceof double[]) { // DOUBLE
            final double[] q = (double[]) value;
            out.writeShort(IFDType.DOUBLE.getCode()); // type
            writeIntOrLong(out, q.length);
            writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
            for (final double doubleValue : q) {
                extraBuffer.writeDouble(doubleValue); // values
            }
        } else {
            throw new UnsupportedOperationException("Unknown IFD value type (" + value.getClass()
                    .getName() + "): " + value);
        }
    }


    private void writeIFDNextOffsetAt(TiffIFD ifd, long positionToWrite, boolean updatePositionOfLastIFDOffset)
            throws IOException {
        writeIFDOffsetAt(
                ifd.hasNextIFDOffset() ? ifd.getNextIFDOffset() : TiffIFD.LAST_IFD_OFFSET,
                positionToWrite,
                updatePositionOfLastIFDOffset);
    }

    private void completeWritingMap(TiffMap map) throws IOException, FormatException {
        Objects.requireNonNull(map, "Null TIFF map");
        final long[] offsets = new long[map.numberOfGridTiles()];
        final long[] byteCounts = new long[map.numberOfGridTiles()];
        // - zero-filled by Java
        TiffTile filler = null;
        final int numberOfSeparatedPlanes = map.numberOfSeparatedPlanes();
        final int gridTileCountY = map.gridTileCountY();
        final int gridTileCountX = map.gridTileCountX();
        for (int p = 0, k = 0; p < numberOfSeparatedPlanes; p++) {
            for (int yIndex = 0; yIndex < gridTileCountY; yIndex++) {
                for (int xIndex = 0; xIndex < gridTileCountX; xIndex++, k++) {
                    TiffTileIndex tileIndex = map.multiplaneIndex(p, xIndex, yIndex);
                    TiffTile tile = map.getOrNew(tileIndex);
                    // - non-existing is created (empty) and saved in the map;
                    // this is necessary to inform the map about new data file range for this tile
                    // and to avoid twice writing it while twice calling "complete()" method
                    tile.cropToMap(true);
                    // - like in updateSamples
                    if (!tile.isEmpty()) {
                        writeEncodedTile(tile, true);
                    }
                    if (tile.hasStoredDataFileOffset()) {
                        offsets[k] = tile.getStoredDataFileOffset();
                        byteCounts[k] = tile.getStoredDataLength();
                    } else {
                        assert tile.isEmpty() : "writeEncodedTile() call above did not store data file offset!";
                        if (!missingTilesAllowed) {
                            if (!tile.equalSizes(filler)) {
                                // - usually performed once, maybe twice for stripped image (where last strip has smaller height)
                                // or even 2 * numberOfSeparatedPlanes times for plane-separated tiles
                                filler = new TiffTile(tileIndex).setEqualSizes(tile);
                                filler.fillEmpty(tileInitializer);
                                encode(filler);
                                writeEncodedTile(filler, false);
                                // - note: unlike usual tiles, the filler tile is written once,
                                // but its offset/byte-count are used many times!
                            }
                            offsets[k] = filler.getStoredDataFileOffset();
                            byteCounts[k] = filler.getStoredDataLength();
                            tile.copyStoredDataFileRange(filler);
                        }
                        // else (if missingTilesAllowed) offsets[k]/byteCounts[k] stay to be zero
                    }
                }
            }
        }
        map.ifd().updateDataPositioning(offsets, byteCounts);
    }

    /**
     * Write the given value to the given RandomAccessOutputStream. If the
     * 'bigTiff' flag is set, then the value will be written as an 8 byte long;
     * otherwise, it will be written as a 4 byte integer.
     */
    private void writeIntOrLong(DataHandle<Location> handle, long value) throws IOException {
        if (bigTiff) {
            handle.writeLong(value);
        } else {
            if (value < Integer.MIN_VALUE || value > 0xFFFFFFFFL) {
                // - note: positive values in range 0x80000000..0xFFFFFFFF are mostly probably unsigned integers,
                // not signed values with overflow
                throw new IOException("Attempt to write 64-bit value as 32-bit: " + value);
            }
            handle.writeInt((int) value);
        }
    }

    private void writeIntOrLong(DataHandle<Location> handle, int value) throws IOException {
        if (bigTiff) {
            handle.writeLong(value);
        } else {
            handle.writeInt(value);
        }
    }

    private void writeUnsignedShort(DataHandle<Location> handle, int value) throws IOException {
        if (value < 0 || value > 0xFFFF) {
            throw new IOException("Attempt to write 32-bit value as 16-bit: " + value);
        }
        handle.writeShort(value);
    }

    private void writeUnsignedByte(DataHandle<Location> handle, int value) throws IOException {
        if (value < 0 || value > 0xFF) {
            throw new IOException("Attempt to write 16/32-bit value as 8-bit: " + value);
        }
        handle.writeByte(value);
    }

    private void writeOffset(long offset) throws IOException {
        if (offset < 0) {
            throw new AssertionError("Illegal usage of writeOffset: negative offset " + offset);
        }
        if (bigTiff) {
            out.writeLong(offset);
        } else {
            if (offset > 0xFFFFFFF0L) {
                throw new IOException("Attempt to write too large 64-bit offset as unsigned 32-bit: " + offset
                        + " > 2^32-16; such large files should be written in Big-TIFF mode");
            }
            out.writeInt((int) offset);
            // - masking by 0xFFFFFFFF is not needed: cast to (int) works properly also for 32-bit unsigned values
        }
    }

    private void writeIFDOffsetAt(long offset, long positionToWrite, boolean updatePositionOfLastIFDOffset)
            throws IOException {
        synchronized (fileLock) {
            // - to be on the safe side (this synchronization is not necessary)
            final long savedPosition = out.offset();
            try {
                out.seek(positionToWrite);
                writeOffset(offset);
                if (updatePositionOfLastIFDOffset && offset == TiffIFD.LAST_IFD_OFFSET) {
                    positionOfLastIFDOffset = positionToWrite;
                }
            } finally {
                out.seek(savedPosition);
            }
        }
    }

    private void seekToEnd() throws IOException {
        synchronized (fileLock) {
            out.seek(out.length());
        }
    }

    private void appendFileUntilEvenLength() throws IOException {
        synchronized (fileLock) {
            seekToEnd();
            appendUntilEvenPosition(out);
        }
    }

    private static void appendUntilEvenPosition(DataHandle<Location> handle) throws IOException {
        if ((handle.offset() & 0x1) != 0) {
            handle.writeByte(0);
            // - Well-formed IFD requires even offsets
        }
    }

    private CodecOptions buildWritingOptions(KnownCompression known, TiffTile tile, Codec customCodec) {
        CodecOptions result = known.writeOptions(tile, this.codecOptions);
        if (customCodec instanceof ExtendedJPEGCodec) {
            ExtendedJPEGCodecOptions jpegOptions = new ExtendedJPEGCodecOptions(result);
            jpegOptions.setQuality(jpegQuality);
            if (tile.ifd().optInt(IFD.PHOTOMETRIC_INTERPRETATION, -1) == PhotoInterp.RGB.getCode()) {
                jpegOptions.setPhotometricInterpretation(PhotoInterp.RGB);
            }
            result = jpegOptions;
        }
        return result;
    }

    // Old deprecated solution
    private CodecOptions buildWritingOptions(TiffTile tile, Codec customCodec) throws FormatException {
        TiffIFD ifd = tile.ifd();
        if (!ifd.hasImageDimensions()) {
            ifd = new TiffIFD(ifd);
            // - do not change original IFD
            ifd.putImageDimensions(157, 157);
            // - Some "fake" dimensions: necessary for normal work of getCompressionCodecOptions;
            // this will work even if the original IFD is frozen for writing
            // See https://github.com/scifio/scifio/issues/516
        }

        IFD result = new IFD(null);
        result.putAll(ifd.map());
        CodecOptions codecOptions = ifd.getCompression().getCompressionCodecOptions(
                result, this.codecOptions);
        // - logger should not be necessary for correct TiffIFD
        if (customCodec instanceof ExtendedJPEGCodec) {
            codecOptions = new ExtendedJPEGCodecOptions(codecOptions)
                    .setQuality(jpegQuality);
            if (ifd.getInt(IFD.PHOTOMETRIC_INTERPRETATION, -1) == PhotoInterp.RGB.getCode()) {
                ((ExtendedJPEGCodecOptions) codecOptions).setPhotometricInterpretation(PhotoInterp.RGB);
            }
        }
        codecOptions.width = tile.getSizeX();
        codecOptions.height = tile.getSizeY();
        codecOptions.channels = tile.samplesPerPixel();
        return codecOptions;
    }

    private static void checkPhotometricInterpretation(
            PhotoInterp photometricInterpretation,
            EnumSet<PhotoInterp> allowed,
            String whatToWrite)
            throws FormatException {
        if (photometricInterpretation != null) {
            if (!allowed.contains(photometricInterpretation)) {
                throw new FormatException("Writing " + whatToWrite + " with photometric interpretation \"" +
                        photometricInterpretation + "\" is not supported (only " +
                        allowed.stream().map(photometric -> "\"" + photometric.getName() + "\"")
                                .collect(Collectors.joining(", ")) +
                        " allowed)");
            }
        }
    }

    private static long debugTime() {
        return TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }

    private static DataHandle<Location> deleteFileIfRequested(Path file, boolean deleteExisting) throws IOException {
        Objects.requireNonNull(file, "Null file");
        if (deleteExisting) {
            Files.deleteIfExists(file);
        }
        return TiffTools.getFileHandle(file);
    }
}