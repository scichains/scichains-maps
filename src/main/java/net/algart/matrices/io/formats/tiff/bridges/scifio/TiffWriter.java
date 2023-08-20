
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
import io.scif.UnsupportedCompressionException;
import io.scif.codec.Codec;
import io.scif.codec.CodecOptions;
import io.scif.formats.tiff.*;
import net.algart.matrices.io.formats.tiff.bridges.scifio.codecs.ExtendedJPEGCodec;
import net.algart.matrices.io.formats.tiff.bridges.scifio.codecs.ExtendedJPEGCodecOptions;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTile;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTileIO;
import org.scijava.AbstractContextual;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleService;
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

/**
 * Writes TIFF data to an output location.
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

    private static final boolean AVOID_LONG8_FOR_ACTUAL_32_BITS = true;
    // - If was necessary for some old programs (like Aperio Image Viewer), which
    // did not understand LONG8 values for some popular tags like image sizes.
    // In any case, real BigTIFF files usually store most tags in standard LONG type (32 bits), not in LONG8.

    private static final System.Logger LOG = System.getLogger(TiffWriter.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);
    private static final boolean LOGGABLE_TRACE = LOG.isLoggable(System.Logger.Level.TRACE);

    private boolean writingForwardAllowed = true;
    private boolean bigTiff = false;
    private boolean autoInterleaveSource = true;
    private CodecOptions codecOptions;
    private boolean extendedCodec = true;
    private boolean jpegInPhotometricRGB = false;
    private double jpegQuality = 1.0;
    private PhotoInterp predefinedPhotoInterpretation = null;
    private boolean missingTilesAllowed = false;
    private byte byteFiller = 0;
    private Consumer<TiffTile> tileInitializer = this::fillEmptyTile;

    private final DataHandle<Location> out;
    private final Location location;
    private final SCIFIO scifio;
    private final DataHandleService dataHandleService;

    private volatile long positionOfLastIFDOffset = -1;

    private long timeWriting = 0;
    private long timePreparingDecoding = 0;
    private long timeCustomizingEncoding = 0;
    private long timeEncoding = 0;

    public TiffWriter(Context context, Path file) throws IOException {
        this(context, file, true);
    }

    public TiffWriter(Context context, Path file, boolean deleteExistingFile) throws IOException {
        this(context, deleteFileIfRequested(file, deleteExistingFile));
    }

    public TiffWriter(Context context, DataHandle<Location> out) {
        Objects.requireNonNull(out, "Null \"out\" data handle (output stream)");
        if (context != null) {
            setContext(context);
            scifio = new SCIFIO(context);
            dataHandleService = context.getService(DataHandleService.class);
        } else {
            scifio = null;
            dataHandleService = null;
        }
        this.out = out;
        this.location = out.get();
        if (this.location == null) {
            throw new UnsupportedOperationException("Illegal DataHandle, returning null location: " + out.getClass());
        }
    }


    /**
     * Gets the stream from which TIFF data is being saved.
     */
    public DataHandle<Location> getStream() {
        return out;
    }

    /**
     * Returns whether or not we are writing little-endian data.
     */
    public boolean isLittleEndian() {
        return out.isLittleEndian();
    }

    /**
     * Sets whether or not little-endian data should be written.
     */
    public TiffWriter setLittleEndian(final boolean littleEndian) {
        out.setLittleEndian(littleEndian);
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
     * Returns whether or not we are writing BigTIFF data.
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Sets whether or not BigTIFF data should be written.
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
     * chunked, i.e. {@link IFD#PLANAR_CONFIGURATION} is {@link DetailedIFD#PLANAR_CONFIGURATION_CHUNKED}
     * (that is the typical usage), then the passes samples are automatically re-packed into chunked (interleaved)
     * form RGBRGBRGB...
     *
     * <p>If this mode is not set, as well as if {@link IFD#PLANAR_CONFIGURATION} is
     * {@link DetailedIFD#PLANAR_CONFIGURATION_SEPARATE}, the passed data are encoded as-as, i.e. as unpacked
     * RRR...GGG..BBB...  for {@link DetailedIFD#PLANAR_CONFIGURATION_SEPARATE} or as interleaved RGBRGBRGB...
     * for {@link DetailedIFD#PLANAR_CONFIGURATION_CHUNKED}.
     *
     * <p>Note that this mode has no effect for 1-channel images.
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

    public PhotoInterp getPredefinedPhotoInterpretation() {
        return predefinedPhotoInterpretation;
    }

    /**
     * Specifies custom photo interpretation, which will be saved in IFD instead of the automatically chosen
     * value. If the argument is <tt>null</tt>, this feature is disabled.
     *
     * <p>Such custom predefined photo interpretation allows to save unusual TIFF, for example, YCvCr LZW format.
     * However, this class does not perform any data processing in this case: you should prepare correct
     * pixel data yourself.
     *
     * @param predefinedPhotoInterpretation custom photo inrerpretation, overriding the value, chosen by default.
     * @return a reference to this object.
     */
    public TiffWriter setPredefinedPhotoInterpretation(PhotoInterp predefinedPhotoInterpretation) {
        this.predefinedPhotoInterpretation = predefinedPhotoInterpretation;
        return this;
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
     * {@link #updateTiles(TiffMap, byte[], int, int, int, int)} or other methods.
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
     * It is updated by {@link #rewriteIFD(DetailedIFD, boolean)}.
     *
     * <p>Immediately after creating new object this position is <tt>-1</tt>.
     *
     * @return file position of the last IFD offset.
     */
    public long positionOfLastIFDOffset() {
        return positionOfLastIFDOffset;
    }

    public void startAppending() throws IOException {
        synchronized (this) {
            final DataHandle<Location> in = TiffTools.getDataHandle(dataHandleService, location);
            final boolean bigTiff;
            final boolean littleEndian;
            final long readerPositionOfLastOffset;
            try (final TiffReader reader = new TiffReader(null, in, true)) {
                reader.readIFDOffsets();
                bigTiff = reader.isBigTiff();
                littleEndian = reader.isLittleEndian();
                readerPositionOfLastOffset = reader.positionOfLastIFDOffset();
            }
            this.setBigTiff(bigTiff).setLittleEndian(littleEndian);
            positionOfLastIFDOffset = readerPositionOfLastOffset;
            out.seek(out.length());
            // - ready to write after the end of the file
            // (not necessary, but can help to avoid accidental bugs)
        }
    }

    public void startNewFile() throws IOException {
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
                positionOfLastIFDOffset = out.offset();
                writeOffset(16);
            } else {
                positionOfLastIFDOffset = out.offset();
                writeOffset(8);
            }
            // - we are ready to write after the header
        }
    }

    /**
     * Writes IFD at the position, specified by {@link DetailedIFD#setFileOffsetForWriting(long)}.
     *
     * <p>Note: this IFD is automatically marked as last IFD in the file (next IFD offset is 0).
     * In addition to this method, you need to call {@link #rewritePreviousLastIFDOffset(long)} to correct
     * this mark inside the file in the previously written IFD.
     *
     * <p>Note: this method changes position in the output stream.
     * (Actually it will be a position after the IFD information, including all additional data
     * like arrays of offsets; but you should not use this fact.)
     *
     * @param ifd                        IFD to write in the output stream.
     * @param updatePositionOfLastOffset whether this IFD will be the new last IFD in the file. If yes,
     *                                   this method will automatically update internal field, returned by
     *                                   {@link #positionOfLastIFDOffset()}, so that the next call of
     *                                   {@link #rewritePreviousLastIFDOffset(long)}
     *                                   will rewrite this offset inside the file by the position of the next IFD.
     * @throws IOException in a case of any I/O errors.
     */
    public void rewriteIFD(final DetailedIFD ifd, boolean updatePositionOfLastOffset) throws IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (!ifd.hasFileOffsetForWriting()) {
            throw new IllegalArgumentException("Offset for writing IFD is not specified");
        }
        final long offset = ifd.getFileOffsetForWriting();
        assert (offset & 0x1) == 0 : "DetailedIFD.setFileOffsetForWriting() has not check offset parity: " + offset;

        writeIFDAt(ifd, offset, updatePositionOfLastOffset);
    }

    public void writeIFDAt(DetailedIFD ifd, long startOffset, boolean updatePositionOfLastIFDOffset) throws IOException {
        checkVirginFile();
        ifd.setFileOffsetForWriting(startOffset);
        // - checks that startOffset is even and >= 0

        out.seek(startOffset);
        final TreeMap<Integer, Object> sortedIFD = ifd.removePseudoTags(TreeMap::new);
        final int numberOfEntries = sortedIFD.size();
        final int mainIFDLength = mainIFDLength(numberOfEntries);
        writeIFDNumberOfEntries(numberOfEntries);

        final long positionOfNextOffset = writeIFDEntries(sortedIFD, startOffset, mainIFDLength);
        writeIFDNextOffset(ifd, positionOfNextOffset, updatePositionOfLastIFDOffset);
    }

    public void rewritePreviousLastIFDOffset(long nextIFDOffset) throws IOException {
        if (positionOfLastIFDOffset < 0) {
            throw new IllegalStateException("Writing to this TIFF file is not started yet");
        }
        writeOffsetAt(nextIFDOffset, positionOfLastIFDOffset, true);
        // - last argument is not important: positionOfLastIFDOffset will not change in any case
    }


    /**
     * Writes the given IFD value to the {@link #getStream() main output stream}, excepting "extra" data,
     * which are written into the specified <tt>extraBuffer</tt>. After calling this method, you
     * should copy full content of <tt>extraBuffer</tt> into the main stream at the position,
     * specified by the 2nd argument; {@link #rewriteIFD(DetailedIFD, boolean)} method does it automatically.
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
    public void writeIFDValueAtCurrentPosition(
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
        out.writeShort(tag); // tag
        if (value instanceof byte[] q) {
            out.writeShort(IFDType.UNDEFINED.getCode());
            // - Most probable type. Maybe in future we will support here some algorithm,
            // determining necessary type on the base of the tag value.
            writeIntOrLongValue(out, q.length);
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
        } else if (value instanceof short[]) {
            final short[] q = (short[]) value;
            out.writeShort(IFDType.BYTE.getCode());
            writeIntOrLongValue(out, q.length);
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
        } else if (value instanceof String) { // ASCII
            final char[] q = ((String) value).toCharArray();
            out.writeShort(IFDType.ASCII.getCode()); // type
            writeIntOrLongValue(out, q.length + 1);
            if (q.length < dataLength) {
                for (char c : q) {
                    out.writeByte(c); // value(s)
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0); // padding
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (char charValue : q) {
                    extraBuffer.writeByte(charValue); // values
                }
                extraBuffer.writeByte(0); // concluding NULL byte
            }
        } else if (value instanceof int[]) { // SHORT
            final int[] q = (int[]) value;
            out.writeShort(IFDType.SHORT.getCode()); // type
            writeIntOrLongValue(out, q.length);
            if (q.length <= dataLength / 2) {
                for (int intValue : q) {
                    out.writeShort(intValue); // value(s)
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
        } else if (value instanceof long[]) { // LONG
            final long[] q = (long[]) value;
            if (AVOID_LONG8_FOR_ACTUAL_32_BITS && q.length == 1 && bigTiff) {
                // - note: inside TIFF, long[1] is saved in the same way as Long; we have a difference in Java only
                final long v = q[0];
                if (v == (int) v) {
                    // - it is very probable for the following tags
                    switch (tag) {
                        case IFD.IMAGE_WIDTH,
                                IFD.IMAGE_LENGTH,
                                IFD.TILE_WIDTH,
                                IFD.TILE_LENGTH,
                                DetailedIFD.IMAGE_DEPTH,
                                IFD.ROWS_PER_STRIP,
                                IFD.NEW_SUBFILE_TYPE -> {
                            out.writeShort(IFDType.LONG.getCode());
                            out.writeLong(1L);
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
            writeIntOrLongValue(out, q.length);

            if (q.length <= 1) {
                for (int i = 0; i < q.length; i++) {
                    writeIntOrLongValue(out, q[0]);
                    // - q[0]: it is actually performed 0 or 1 times
                }
                for (int i = q.length; i < 1; i++) {
                    writeIntOrLongValue(out, 0);
                }
            } else {
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (long longValue : q) {
                    writeIntOrLongValue(extraBuffer, longValue);
                }
            }
        } else if (value instanceof TiffRational[]) { // RATIONAL
            final TiffRational[] q = (TiffRational[]) value;
            out.writeShort(IFDType.RATIONAL.getCode()); // type
            writeIntOrLongValue(out, q.length);
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
            writeIntOrLongValue(out, q.length);
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
            writeIntOrLongValue(out, q.length);
            writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
            for (final double doubleValue : q) {
                extraBuffer.writeDouble(doubleValue); // values
            }
        } else {
            throw new UnsupportedOperationException("Unknown IFD value type (" + value.getClass()
                    .getName() + "): " + value);
        }
    }

    public void writeTile(TiffTile tile) throws FormatException, IOException {
        encode(tile);
        writeEncodedTile(tile, true);
    }

    public void writeEncodedTile(TiffTile tile, boolean freeAfterWriting) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        checkVirginFile();
        if (tile.isEmpty()) {
            return;
        }
        long t1 = debugTime();
        TiffTileIO.writeToEnd(tile, out, freeAfterWriting);
        long t2 = debugTime();
        timeWriting += t2 - t1;
    }

    public void updateTiles(
            final TiffMap map,
            final byte[] sourceSamples,
            final int fromX,
            final int fromY,
            final int sizeX,
            final int sizeY) throws FormatException {
        Objects.requireNonNull(map, "Null tile map");
        Objects.requireNonNull(sourceSamples, "Null source samples");
        final boolean planarSeparated = map.isPlanarSeparated();
        final int dimX = map.dimX();
        final int dimY = map.dimY();
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        TiffTools.checkRequestedAreaInArray(sourceSamples, sizeX, sizeY, map.totalBytesPerPixel());
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
            throw new AssertionError("Map was not expanded/checked properly: too large " +
                    minXIndex + ", " + minYIndex + ", map: " + map);
        }
        final int maxXIndex = Math.min(map.gridTileCountX() - 1, (toX - 1) / mapTileSizeX);
        final int maxYIndex = Math.min(map.gridTileCountY() - 1, (toY - 1) / mapTileSizeY);
        assert minYIndex <= maxYIndex && minXIndex <= maxXIndex;
        //TODO!! use these indexes

        final int tileCountXInRegion = (sizeX + mapTileSizeX - 1) / mapTileSizeX;
        final int tileCountYInRegion = (sizeY + mapTileSizeY - 1) / mapTileSizeY;
        assert tileCountXInRegion <= sizeX;
        assert tileCountYInRegion <= sizeY;

        final boolean autoInterleave = this.autoInterleaveSource;
        // - true means that the data are already interleaved by an external code
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
                    //TODO!! reduceUnset
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
                            System.arraycopy(sourceSamples, samplesOffset, data, tileOffset, partSizeXInBytes);
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
                                System.arraycopy(sourceSamples, samplesOffset, data, tileOffset, partSizeXInBytes);
                            }
                        }
                    }
                }
            }
        }
    }

    public void encode(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            return;
        }
        long t1 = debugTime();
        prepareEncoding(tile);
        long t2 = debugTime();

        tile.checkStoredNumberOfPixels();

        TiffCompression compression = tile.ifd().getCompression();
        final KnownTiffCompression known = KnownTiffCompression.valueOfOrNull(compression);
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
        final CodecOptions codecOptions = buildWritingOptions(tile, codec);
        long t3 = debugTime();
        byte[] data = tile.getDecoded();
        if (codec != null) {
            tile.setEncoded(codec.compress(data, codecOptions));
        } else {
            if (scifio == null) {
                throw new IllegalStateException(
                        "Compression type " + compression + " requires specifying non-null SCIFIO context");
            }
            tile.setEncoded(compression.compress(scifio.codec(), data, codecOptions));
        }
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

    public TiffMap startNewImage(
            DetailedIFD ifd,
            int numberOfChannels,
            Class<?> elementType,
            boolean signedIntegers,
            boolean resizable)
            throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        ifd.putPixelInformation(numberOfChannels, elementType, signedIntegers);
        return startNewImage(ifd, resizable);
    }

    public TiffMap startNewImage(DetailedIFD ifd, boolean resizable) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        prepareValidIFD(ifd);
        ifd.removeNextIFDOffset();
        ifd.removeDataPositioning();
        final TiffMap map = new TiffMap(ifd, resizable);
        if (resizable) {
            ifd.removeImageDimensions();
        }
        ifd.freezeForWriting();
        // - actually not necessary, but helps to avoid possible bugs
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
     * In this case, IFD will be written at the final stage ({@link #completeImage(TiffMap)} method).
     *
     * @param map map, describing the image.
     */
    public void writeForward(TiffMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        if (!writingForwardAllowed || map.isResizable()) {
            return;
        }
        map.rearrangeImageGrid();
        map.cropAll(true);
        // - necessary for tiles, that will not be filled by any pixels (empty tiles)
        final long[] offsets = new long[map.numberOfGridTiles()];
        final long[] byteCounts = new long[map.numberOfGridTiles()];
        // - zero-filled by Java
        map.ifd().updateDataPositioning(offsets, byteCounts);
        final DetailedIFD ifd = map.ifd();
        if (!ifd.hasFileOffsetForWriting()) {
            writeIFDAt(ifd, out.length(), false);
        }
    }

    public void completeImage(final TiffMap map) throws IOException, FormatException {
        Objects.requireNonNull(map, "Null TIFF map");
        final boolean resizable = map.isResizable();
        map.checkDimensions();
        if (resizable) {
            map.rearrangeImageGrid();
            map.cropAll(true);
        }

        encode(map);
        // - encode tiles, which are not encoded yet

        final DetailedIFD ifd = map.ifd();
        if (resizable) {
            ifd.updateImageDimensions(map.dimX(), map.dimY());
        }

        completeWritingMap(map);
        appendUntilEvenPosition(out);

        final long previousPositionOfLastIFDOffset = positionOfLastIFDOffset;
        if (!ifd.hasFileOffsetForWriting()) {
            // - usually it means that we did not call writeForward
            ifd.setFileOffsetForWriting(out.length());
        }
        rewriteIFD(ifd, true);
        writeOffsetAt(ifd.getFileOffsetForWriting(), previousPositionOfLastIFDOffset, false);

        out.seek(out.length());
        // - This seeking to file end is not necessary, but can help to avoid accidental bugs
        // (this is much better than keeping file offset in the middle of the last image
        // between IFD and newly written TIFF tiles).
    }

    public void completeWritingMap(TiffMap map) throws IOException, FormatException {
        final long[] offsets = new long[map.numberOfGridTiles()];
        final long[] byteCounts = new long[map.numberOfGridTiles()];
        // - zero-filled by Java
        Collection<TiffTile> tiles = map.all();
        if (tiles.size() != offsets.length) {
            throw new AssertionError("Invalid map: number of tiles " + tiles.size() +
                    " does not match number of grid cells " + map.numberOfGridTiles());
        }
        TiffTile empty = null;
        int k = 0;
        for (TiffTile tile : tiles) {
            if (!tile.isEmpty()) {
                writeEncodedTile(tile, true);
            }
            if (tile.hasStoredDataFileOffset()) {
                offsets[k] = tile.getStoredDataFileOffset();
                byteCounts[k] = tile.getStoredDataLength();
            } else if (!missingTilesAllowed) {
                if (!tile.equalSizes(empty)) {
                    // - usually performed once, maybe twice for stripped image (where last strip has smaller height)
                    // or even 2 * numberOfSeparatedPlanes times for plane-separated tiles
                    empty = new TiffTile(tile.index()).setEqualSizes(tile);
                    empty.fillEmpty(tileInitializer);
                    encode(empty);
                    writeEncodedTile(empty, false);
                    // - note: unlike usual tiles, the empty tile is written once,
                    // but its offset/byte-count are used many times!
                }
                offsets[k] = empty.getStoredDataFileOffset();
                byteCounts[k] = empty.getStoredDataLength();
            } // else offsets[k]/byteCounts[k] stay to be zero
            k++;
        }
        map.ifd().updateDataPositioning(offsets, byteCounts);
    }

    public void writeImage(
            final DetailedIFD ifd, final byte[] samples) throws FormatException, IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        writeImage(ifd, samples, 0, 0, ifd.getImageDimX(), ifd.getImageDimY());
    }

    public void writeImage(
            final DetailedIFD ifd,
            byte[] samples,
            final int fromX, final int fromY, final int sizeX, final int sizeY)
            throws FormatException, IOException {
        TiffMap map = startNewImage(ifd, false);
        writeImage(map, samples, fromX, fromY, sizeX, sizeY);
    }

    public void writeImage(
            final TiffMap map,
            byte[] samples,
            final int fromX, final int fromY, final int sizeX, final int sizeY)
            throws FormatException, IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samples, "Null samples");
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY, map.dimX(), map.dimY());

        clearTime();
        long t1 = debugTime();
        writeForward(map);

        long t2 = debugTime();
        updateTiles(map, samples, fromX, fromY, sizeX, sizeY);
        long t3 = debugTime();

        // - Note: already created tiles will have access to newly update information,
        // because they contain references to this IFD
        encode(map);
        long t4 = debugTime();

        completeImage(map);
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t5 = debugTime();
            long sizeOf = map.totalSizeInBytes();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s wrote %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f initializing + %.3f splitting " +
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

    public void writeImageFromArray(
            final TiffMap map,
            Object samplesArray,
            final int fromX, final int fromY, final int sizeX, final int sizeY)
            throws FormatException, IOException {
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
        long t1 = debugTime();
        final byte[] samples = TiffTools.arrayToBytes(samplesArray, isLittleEndian());
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s converted %d bytes (%.3f MB) from %s[] in %.3f ms%s",
                    getClass().getSimpleName(),
                    samples.length, samples.length / 1048576.0,
                    elementType.getSimpleName(),
                    (t2 - t1) * 1e-6,
                    samples == samplesArray ?
                            "" :
                            String.format(Locale.US, " %.3f MB/s",
                                    samples.length / 1048576.0 / ((t2 - t1) * 1e-9))));
        }
        writeImage(map, samples, fromX, fromY, sizeX, sizeY);
    }

    public void fillEmptyTile(TiffTile tiffTile) {
        if (byteFiller != 0) {
            // - Java-arrays are automatically filled by zero
            Arrays.fill(tiffTile.getDecoded(), byteFiller);
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    protected void prepareEncoding(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        TiffTools.invertFillOrderIfRequested(tile);
        if (autoInterleaveSource) {
            tile.interleaveSamples();
        } else {
            tile.setInterleaved(true);
            // - if not autoInterleave, we should suppose that the samples were already interleaved
        }

        // scifio.tiff().difference(tile.getDecodedData(), ifd);
        // - this solution requires using SCIFIO context class; it is better to avoid this
        TiffTools.differenceIfRequested(tile);
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
            out.writeShort(numberOfEntries);
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
            writeOffset(DetailedIFD.LAST_IFD_OFFSET);
            // - not too important: will be rewritten in writeIFDNextOffset
            final int extraLength = (int) extraHandle.offset();
            extraHandle.seek(0L);
            DataHandles.copy(extraHandle, out, extraLength);
            appendUntilEvenPosition(out);
        }
        return positionOfNextOffset;
    }

    private void writeIFDNextOffset(DetailedIFD ifd, long positionOfNextOffset, boolean updatePositionOfLastIFDOffset)
            throws IOException {
        writeOffsetAt(
                ifd.hasNextIFDOffset() ? ifd.getNextIFDOffset() : DetailedIFD.LAST_IFD_OFFSET,
                positionOfNextOffset,
                updatePositionOfLastIFDOffset);
    }

    private void writeIntOrLongValue(final DataHandle<Location> handle, final int value) throws IOException {
        if (bigTiff) {
            handle.writeLong(value);
        } else {
            handle.writeInt(value);
        }
    }

    /**
     * Write the given value to the given RandomAccessOutputStream. If the
     * 'bigTiff' flag is set, then the value will be written as an 8 byte long;
     * otherwise, it will be written as a 4 byte integer.
     */
    private void writeIntOrLongValue(final DataHandle<Location> handle, final long value) throws IOException {
        if (bigTiff) {
            handle.writeLong(value);
        } else {
            if (value != (int) value) {
                throw new IOException("Attempt to write 64-bit value as 32-bit: " + value);
            }
            handle.writeInt((int) value);
        }
    }

    private void writeOffset(long offset) throws IOException {
        if (bigTiff) {
            out.writeLong(offset);
        } else {
            if (offset != (int) offset) {
                throw new IOException("Attempt to write 64-bit offset as 32-bit: " + offset);
            }
            out.writeInt((int) offset);
        }
    }

    private void writeOffsetAt(long offset, long positionToWrite, boolean updatePositionOfLastIFDOffset)
            throws IOException {
        final long savedPosition = out.offset();
        try {
            out.seek(positionToWrite);
            writeOffset(offset);
            if (updatePositionOfLastIFDOffset) {
                positionOfLastIFDOffset = positionToWrite;
            }
        } finally {
            out.seek(savedPosition);
        }
    }

    private static void appendUntilEvenPosition(DataHandle<Location> handle) throws IOException {
        if ((handle.offset() & 0x1) != 0) {
            handle.writeByte(0);
            // - Well-formed IFD requires even offsets
        }
    }

    private CodecOptions buildWritingOptions(TiffTile tile, Codec customCodec) throws FormatException {
        DetailedIFD ifd = tile.ifd();
        if (!ifd.hasImageDimensions()) {
            ifd = new DetailedIFD(ifd);
            // - do not change original IFD
            ifd.putImageDimensions(157, 157);
            // - Some "fake" dimensions: necessary for normal work of getCompressionCodecOptions;
            // this will work even if the original IFD is frozen for writing
            // See https://github.com/scifio/scifio/issues/516
        }
        CodecOptions codecOptions = ifd.getCompression().getCompressionCodecOptions(ifd, this.codecOptions);
        if (customCodec instanceof ExtendedJPEGCodec) {
            codecOptions = new ExtendedJPEGCodecOptions(codecOptions)
                    .setPhotometricRGB(jpegInPhotometricRGB)
                    .setQuality(jpegQuality);
        }
        codecOptions.width = tile.getSizeX();
        codecOptions.height = tile.getSizeY();
        codecOptions.channels = tile.samplesPerPixel();
        return codecOptions;
    }

    private void prepareValidIFD(final DetailedIFD ifd) throws FormatException {
        if (!ifd.containsKey(IFD.BITS_PER_SAMPLE)) {
            throw new IllegalArgumentException("BitsPerSample tag must be specified for writing TIFF image " +
                    "(standard TIFF default  value, 1 bit/pixel, is not supported)");
        }
        final OptionalInt optionalBits = ifd.tryEqualBitsPerSample();
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
        // The following solution seems to be unnecessary: we do not specify default tile size,
        // why to do this with rarely used strips? Single strip is also well!
        //
        // if (!ifd.hasTileInformation() && !ifd.hasStripInformation()) {
        //     if (!isDefaultSingleStrip()) {
        //         ifd.putStripInformation(getDefaultStripHeight());
        //     }
        // }

        if (!ifd.containsKey(IFD.COMPRESSION)) {
            ifd.putIFDValue(IFD.COMPRESSION, TiffCompression.UNCOMPRESSED.getCode());
            // - We prefer explicitly specify this case
        }
        final TiffCompression compression = ifd.getCompression();
        // - UnsupportedTiffFormatException for unknown compression

        final int samplesPerPixel = ifd.getSamplesPerPixel();
        final boolean palette = samplesPerPixel == 1 && ifd.getIFDValue(IFD.COLOR_MAP) != null;
        final boolean signed = ifd.getIFDIntValue(IFD.SAMPLE_FORMAT) == 2;
        final boolean jpeg = compression == TiffCompression.JPEG;
        if (jpeg && (signed || bits != 8)) {
            throw new FormatException("JPEG compression is not supported for %d-bit%s samples%s".formatted(
                    bits,
                    signed ? " signed" : "",
                    bits == 8 ? " (samples must be unsigned)" : ""));
        }
        ifd.putPhotometricInterpretation(predefinedPhotoInterpretation != null ? predefinedPhotoInterpretation
                : palette ? PhotoInterp.RGB_PALETTE
                : samplesPerPixel == 1 ? PhotoInterp.BLACK_IS_ZERO
                : jpeg && ifd.isChunked() && !compressJPEGInPhotometricRGB() ? PhotoInterp.Y_CB_CR
                : PhotoInterp.RGB);

        ifd.putIFDValue(IFD.LITTLE_ENDIAN, out.isLittleEndian());
        // - will be used, for example, in getCompressionCodecOptions
        ifd.putIFDValue(IFD.BIG_TIFF, bigTiff);
        // - not used, but helps to provide good DetailedIFD.toString
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