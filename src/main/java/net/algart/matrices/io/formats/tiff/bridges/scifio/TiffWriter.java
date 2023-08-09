
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


    private boolean bigTiff = false;
    private boolean writingSequentially = true;
    private boolean appendToExisting = false;
    private boolean autoInterleave = true;
    private Integer defaultStripHeight = 128;
    private CodecOptions codecOptions;
    private boolean extendedCodec = true;
    private boolean jpegInPhotometricRGB = false;
    private double jpegQuality = 1.0;
    private PhotoInterp predefinedPhotoInterpretation = null;

    private final DataHandle<Location> out;
    private final Location location;
    private final SCIFIO scifio;
    private final DataHandleService dataHandleService;

    private volatile long positionOfLastOffset = 0;

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

    public boolean isWritingSequentially() {
        return writingSequentially;
    }

    /**
     * Sets whether or not we know that the planes will be written sequentially.
     * If we are writing planes sequentially, this flag increases performance.
     * Default value if <tt>true</tt>.
     *
     * <p>Note: if this flag is not set (random-access mode) and the file already exist,
     * then new IFD images will be still written after the end of the file,
     * i.e. the file will grow. So, we do not recommend using <tt>false</tt> value
     * without necessity: it leads to inefficient usage of space inside the file.
     */
    public TiffWriter setWritingSequentially(final boolean sequential) {
        writingSequentially = sequential;
        return this;
    }

    public boolean isAppendToExisting() {
        return appendToExisting;
    }

    /**
     * Sets appending mode: the specified file must be an existing TIFF file, and this saver
     * will append IFD images to the end of this file.
     * Default value is <tt>false</tt>.
     *
     * @param appendToExisting whether we want to append IFD to an existing TIFF file.
     * @return a reference to this object.
     */
    public TiffWriter setAppendToExisting(boolean appendToExisting) {
        this.appendToExisting = appendToExisting;
        return this;
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

    public boolean isAutoInterleave() {
        return autoInterleave;
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
     * @param autoInterleave new auto-interleave mode. Default value is <tt>true</tt>.
     * @return a reference to this object.
     */
    public TiffWriter setAutoInterleave(boolean autoInterleave) {
        this.autoInterleave = autoInterleave;
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

    public long positionOfLastOffset() {
        return positionOfLastOffset;
    }

    public void startWriting() throws IOException {
        synchronized (this) {
            if (appendToExisting) {
                final DataHandle<Location> in = TiffTools.getDataHandle(dataHandleService, location);
                final boolean bigTiff;
                final boolean littleEndian;
                final long positionOfLastOffset;
                try (final TiffReader parser = new TiffReader(null, in, true)) {
                    parser.getIFDOffsets();
                    bigTiff = parser.isBigTiff();
                    littleEndian = parser.isLittleEndian();
                    positionOfLastOffset = parser.positionOfLastOffset();
                }
                //noinspection resource
                setBigTiff(bigTiff).setLittleEndian(littleEndian);
                out.seek(positionOfLastOffset);
                final long fileLength = out.length();
                writeOffset(out, fileLength);
                out.seek(fileLength);
                // - we are ready to write after the end of the file
            } else {
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
            // - we are ready to write after the header
        }
    }

    public void finish() {
        //TODO!! correct last offset
    }

    public void writeIFD(final IFD ifd, final long nextOffset) throws FormatException, IOException {
        final TreeSet<Integer> keys = new TreeSet<>(ifd.keySet());
        final int keyCount = keys.size() - (int) ifd.keySet().stream().filter(DetailedIFD::isPseudoTag).count();
        // - Not counting pseudo-fields (not actually saved in TIFF)

        final long fp = out.offset();
        if ((fp & 0x1) != 0) {
            throw new FormatException("Attempt to write IFD at odd offset " + fp + " is prohibited for valid TIFF");
        }
        final int keyCountLimit = bigTiff ? 10_000_000 : 65535;
        if (keyCount > keyCountLimit) {
            throw new FormatException("Too many number of IFD entries: " + keyCount + " > " + keyCountLimit);
            // - theoretically BigTIFF allows to write more, but we prefer to make some restriction and
            // guarantee 32-bit number of bytes
        }
        final int bytesPerEntry = bigTiff ?
                TiffConstants.BIG_TIFF_BYTES_PER_ENTRY :
                TiffConstants.BYTES_PER_ENTRY;
        final int ifdBytes = (bigTiff ? 16 : 6) + bytesPerEntry * keyCount;

        if (bigTiff) {
            out.writeLong(keyCount);
        } else {
            out.writeShort(keyCount);
        }

        final BytesLocation bytesLocation = new BytesLocation(0, "memory-buffer");
        try (final DataHandle<Location> extraHandle = TiffTools.getBytesHandle(bytesLocation)) {
            for (final Integer key : keys) {
                if (DetailedIFD.isPseudoTag(key)) {
                    continue;
                }

                final Object value = ifd.get(key);
                writeIFDValue(extraHandle, ifdBytes + fp, key, value);
            }

            writeOffset(out, nextOffset);
            final int ifdLen = (int) extraHandle.offset();
            extraHandle.seek(0L);
            DataHandles.copy(extraHandle, out, ifdLen);
        }
    }

    /**
     * Writes the given IFD value to the given output object.
     *
     * @param extraOut buffer to which "extra" IFD information should be written
     *                 (any data, for which IFD contains its offsets instead of data itself)
     * @param offset   global offset to use for IFD offset values
     * @param tag      IFD tag to write
     * @param value    IFD value to write
     */
    public void writeIFDValue(
            final DataHandle<Location> extraOut,
            final long offset,
            final int tag,
            Object value) throws FormatException, IOException {
        extraOut.setLittleEndian(isLittleEndian());
        if ((extraOut.offset() & 0x1) != 0) {
            extraOut.writeByte(0);
            // - Well-formed IFD requires even offsets
        }

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
                writeOffset(out, offset + extraOut.length());
                extraOut.write(q);
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
                writeOffset(out, offset + extraOut.length());
                for (short shortValue : q) {
                    extraOut.writeByte(shortValue);
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
                writeOffset(out, offset + extraOut.length());
                for (char charValue : q) {
                    extraOut.writeByte(charValue); // values
                }
                extraOut.writeByte(0); // concluding NULL byte
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
                writeOffset(out, offset + extraOut.length());
                for (int intValue : q) {
                    extraOut.writeShort(intValue); // values
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
                writeOffset(out, offset + extraOut.length());
                for (long longValue : q) {
                    writeIntOrLongValue(extraOut, longValue);
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
                writeOffset(out, offset + extraOut.length());
                for (TiffRational tiffRational : q) {
                    extraOut.writeInt((int) tiffRational.getNumerator());
                    extraOut.writeInt((int) tiffRational.getDenominator());
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
                writeOffset(out, offset + extraOut.length());
                for (float floatValue : q) {
                    extraOut.writeFloat(floatValue); // values
                }
            }
        } else if (value instanceof double[]) { // DOUBLE
            final double[] q = (double[]) value;
            out.writeShort(IFDType.DOUBLE.getCode()); // type
            writeIntOrLongValue(out, q.length);
            writeOffset(out, offset + extraOut.length());
            for (final double doubleValue : q) {
                extraOut.writeDouble(doubleValue); // values
            }
        } else {
            throw new FormatException("Unknown IFD value type (" + value.getClass()
                    .getName() + "): " + value);
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
        tile.setStoredDataFileOffset(out.length());
        TiffTileIO.write(tile, out, freeAfterWriting);
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
//        if (!map.isReadyForWriting()) {
//            throw new IllegalStateException("Cannot update tiles: IFD is not prepared for writing!");
//        }
        //TODO!!
        final boolean planarSeparated = map.isPlanarSeparated();
        final int dimX = map.dimX();
        final int dimY = map.dimY();
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        TiffTools.checkRequestedAreaInArray(sourceSamples, sizeX, sizeY, map.totalBytesPerPixel());
        map.expandSizes(fromX + sizeX, fromY + sizeY);

        final int bytesPerSample = map.bytesPerSample();
        final int numberOfSeparatedPlanes = map.numberOfSeparatedPlanes();
        final int samplesPerPixel = map.tileSamplesPerPixel();
        final int bytesPerPixel = map.tileBytesPerPixel();

        // - checks that tile sizes are non-negative and <2^31,
        // and checks that we can multiply them by bytesPerSample and ifd.getSamplesPerPixel() without overflow
        final int tileSizeX = map.tileSizeX();
        final int tileSizeY = map.tileSizeY();
        final int tileCountXInRegion = (sizeX + tileSizeX - 1) / tileSizeX;
        final int tileCountYInRegion = (sizeY + tileSizeY - 1) / tileSizeY;
        // Probably we write not full image!
        assert tileCountXInRegion <= sizeX;
        assert tileCountYInRegion <= sizeY;
        final int numberOfActualTiles = tileCountXInRegion * tileCountYInRegion;

        final boolean autoInterleave = this.autoInterleave;
        // - true means that the data are already interleaved by an external code
        final boolean needToCorrectLastRow = !map.ifd().hasTileInformation();
        // - If tiling is requested via TILE_WIDTH/TILE_LENGTH tags, we SHOULD NOT correct the height
        // of the last row, as in a case of splitting to strips; else GIMP and other libtiff-based programs
        // will report about an error (see libtiff, tif_jpeg.c, assigning segment_width/segment_height)
        for (int p = 0, tileIndex = 0; p < numberOfSeparatedPlanes; p++) {
            // - in a rare case PlanarConfiguration=2 (RRR...GGG...BBB...),
            // this order provides increasing tile offsets while simple usage of this class
            // (this is not required, but provides more efficient per-plane reading)
            for (int yIndex = 0; yIndex < tileCountYInRegion; yIndex++) {
                final int yOffset = yIndex * tileSizeY;
                final int partSizeY = Math.min(sizeY - yOffset, tileSizeY);
                assert (long) fromY + (long) yOffset < dimY : "region must  be checked before calling splitTiles";
                final int y = fromY + yOffset;
                final int validTileSizeY = !needToCorrectLastRow ? tileSizeY : Math.min(tileSizeY, dimY - y);
                // - last strip should have exact height, in other case TIFF may be read with a warning
                final int validTileChannelSize = tileSizeX * validTileSizeY * bytesPerSample;
                final int validTileSize = validTileChannelSize * map.tileSamplesPerPixel();
                for (int xIndex = 0; xIndex < tileCountXInRegion; xIndex++, tileIndex++) {
                    assert tileSizeX > 0 && tileSizeY > 0 : "loop should not be executed for zero-size tiles";
                    final int xOffset = xIndex * tileSizeX;
                    assert (long) fromX + (long) xOffset < dimX : "region must be checked before calling splitTiles";
                    final int x = fromX + xOffset;
                    final int partSizeX = Math.min(sizeX - xOffset, tileSizeX);
                    final TiffTile tile = map.getOrNewMultiplane(p, x / tileSizeX, y / tileSizeY);
                    tile.setSizeY(validTileSizeY);
                    final byte[] data = tile.getDecodedOrNew(validTileSize);
                    // - if the tile already exists, we will accurately update its content
                    if (!planarSeparated && !autoInterleave) {
                        // - Source data are already interleaved (like RGBRGB...): maybe, external code prefers
                        // to use interleaved form, for example, OpenCV library.
                        final int tileRowSizeInBytes = tileSizeX * bytesPerPixel;
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
                        final int separatedPlaneSize = sizeX * sizeY * bytesPerSample;
                        final int tileRowSizeInBytes = tileSizeX * bytesPerSample;
                        final int partSizeXInBytes = partSizeX * bytesPerSample;
                        int tileChannelOffset = 0;
                        for (int s = 0; s < samplesPerPixel; s++, tileChannelOffset += validTileChannelSize) {
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

    public void writeSamples(DetailedIFD ifd, byte[] samples, int numberOfChannels, int pixelType, boolean last)
            throws FormatException, IOException {
        if (!writingSequentially) {
            throw new IllegalStateException("Writing samples without IFD index is possible only in sequential mode");
        }
        writeSamples(ifd, samples, null, numberOfChannels, pixelType, last);
    }

    public void writeSamples(
            final DetailedIFD ifd, final byte[] samples, final Integer ifdIndex,
            final int numberOfChannels, final int pixelType, final boolean last) throws FormatException, IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        final int sizeX = ifd.getImageDimX();
        final int sizeY = ifd.getImageDimY();
        writeSamples(ifd, samples, ifdIndex, pixelType, numberOfChannels, 0, 0, sizeX, sizeY, last);
    }

    public TiffMap prepareImage(final DetailedIFD ifd, boolean resizable) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        prepareValidIFD(ifd);
        return new TiffMap(ifd, resizable);
    }

    /**
     * Prepare writing new image with known fixed sizes.
     * This method writes image header (IFD) to the end of the TIFF file,
     * so it will be placed before actually written data: it helps
     * to improve performance of future reading this file.
     *
     * <p>Note: this method does nothing if the image is {@link TiffMap#isResizable() resizable}.
     * In this case, we don't know, how much number of bytes we must reserve in the file for storing IFD.
     *
     * @param map map, describing the image.
     */
    public void prepareWritingImage(final TiffMap map) throws IOException {
        Objects.requireNonNull(map);
        if (map.isResizable()) {
            return;
        }
        //TODO!!
    }

    public void writeSamples(
            final DetailedIFD ifd,
            byte[] samples,
            final Integer ifdIndex,
            final int numberOfChannels,
            final int pixelType,
            final int fromX, final int fromY, final int sizeX, final int sizeY,
            final boolean lastIFD)
            throws FormatException, IOException {
        ifd.putBaseInformation(numberOfChannels, pixelType);
        TiffMap map = prepareImage(ifd, false);
        prepareWritingImage(map);
        writeSamples(map, samples, ifdIndex, fromX, fromY, sizeX, sizeY, lastIFD);
    }

    public void writeSamples(
            final TiffMap map,
            byte[] samples,
            final Integer ifdIndex,
            final int fromX, final int fromY, final int sizeX, final int sizeY,
            final boolean lastIFD)
            throws FormatException, IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samples, "Null samples");
        DetailedIFD ifd = map.ifd();
        clearTime();
        if (!writingSequentially) {
            if (appendToExisting) {
                throw new IllegalStateException("appendToExisting mode can be used only together with " +
                        "writingSequentially (random-access writing mode is used to rewrite some existing " +
                        "image inside the TIFF, not for appending new images to the end)");
            }
            if (ifdIndex == null || ifdIndex < 0) {
                throw new IllegalArgumentException("Null or negative ifdIndex = " + ifdIndex +
                        " is not allowed when writing mode is not sequential");
            }
        }
        long t1 = debugTime();
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY, ifd.getImageDimX(), ifd.getImageDimY());
        int sizeOf = ifd.sizeOfRegionBasedOnType(sizeX, sizeY);

        long t2 = debugTime();
        synchronized (this) {
            // Following methods only read ifd, not modify it
            updateTiles(map, samples, fromX, fromY, sizeX, sizeY);
        }
        long t3 = debugTime();

        // - Note: already created tiles will have access to newly update information,
        // because they contain references to this IFD

        // Compress tiles according to given differencing and compression schemes,
        // this operation is NOT synchronized and is the ONLY portion of the
        // methods stack that is NOT synchronized.
        for (TiffTile tile : map.tiles()) {
            encode(tile);
        }
        long t4 = debugTime();

        // This operation is synchronized
        synchronized (this) {
            writeSamplesAndIFD(
                    ifd, ifdIndex, new ArrayList<>(map.tiles()),
                    fromX, fromY, lastIFD);
        }
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t5 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s wrote %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f initialize + %.3f splitting " +
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

    public void writeSamplesArray(
            final TiffMap map,
            Object samplesArray,
            final Integer ifdIndex,
            final int fromX, final int fromY, final int sizeX, final int sizeY,
            final boolean last)
            throws FormatException, IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        long t1 = debugTime();
        final byte[] samples = TiffTools.arrayToBytes(samplesArray, isLittleEndian());
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s converted %d bytes (%.3f MB) from %s[] in %.3f ms%s",
                    getClass().getSimpleName(),
                    samples.length, samples.length / 1048576.0,
                    samplesArray.getClass().getComponentType().getSimpleName(),
                    (t2 - t1) * 1e-6,
                    samples == samplesArray ?
                            "" :
                            String.format(Locale.US, " %.3f MB/s",
                                    samples.length / 1048576.0 / ((t2 - t1) * 1e-9))));
        }
        writeSamples(map, samples, ifdIndex, fromX, fromY, sizeX, sizeY, last);
    }


    @Override
    public void close() throws IOException {
        out.close();
    }

    protected void prepareEncoding(TiffTile tile) throws FormatException {
        Objects.requireNonNull(tile, "Null tile");
        TiffTools.invertFillOrderIfRequested(tile);
        if (autoInterleave) {
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


    /**
     * Coverts a list to a primitive array.
     *
     * @param l The list of {@code Long} to convert.
     * @return A primitive array of type {@code long[]} with the values from
     * </code>l</code>.
     */
    private static long[] toPrimitiveArray(final List<Long> l) {
        final long[] toReturn = new long[l.size()];
        for (int i = 0; i < l.size(); i++) {
            toReturn[i] = l.get(i);
        }
        return toReturn;
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

    private void writeOffset(final DataHandle<Location> handle, final long offset) throws IOException {
        if (bigTiff) {
            handle.writeLong(offset);
        } else {
            if (offset != (int) offset) {
                throw new IOException("Attempt to write 64-bit offset as 32-bit: " + offset);
            }
            handle.writeInt((int) offset);
        }
    }

    private CodecOptions buildWritingOptions(TiffTile tile, Codec customCodec) throws FormatException {
        DetailedIFD ifd = tile.ifd();
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
        final PhotoInterp pi = predefinedPhotoInterpretation != null ? predefinedPhotoInterpretation
                : palette ? PhotoInterp.RGB_PALETTE
                : samplesPerPixel == 1 ? PhotoInterp.BLACK_IS_ZERO
                : jpeg && ifd.isChunked() && !compressJPEGInPhotometricRGB() ? PhotoInterp.Y_CB_CR
                : PhotoInterp.RGB;
        ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, pi.getCode());

        ifd.putIFDValue(IFD.LITTLE_ENDIAN, out.isLittleEndian());
        // - will be used, for example, in getCompressionCodecOptions
    }

    /**
     * Performs the actual work of dealing with IFD data and writing it to the
     * TIFF for a given image or sub-image.
     *
     * @param ifd      The Image File Directories. Mustn't be {@code null}.
     * @param ifdIndex The image index within the current file, starting from 0.
     * @param tiles    The strips/tiles to write to the file.
     * @param fromX    The initial X offset of the strips/tiles to write.
     * @param fromY    The initial Y offset of the strips/tiles to write.
     * @param lastIFD  Pass {@code true} if it is the last image, {@code false}
     *                 otherwise.
     * @throws FormatException
     * @throws IOException
     */
    private void writeSamplesAndIFD(
            DetailedIFD ifd, final Integer ifdIndex,
            final List<TiffTile> tiles,
            final int fromX, final int fromY,
            final boolean lastIFD)
            throws FormatException, IOException {
        //TODO!! use TiffMap
        final int tilesPerRow = (int) ifd.getTilesPerRow();
        final int tilesPerColumn = (int) ifd.getTilesPerColumn();
        final boolean interleaved = ifd.getPlanarConfiguration() == 1;
        final boolean isTiled = ifd.isTiled();
        assert out != null : "must be checked in the constructor";

        if (!writingSequentially) {
            final DataHandle<Location> in = TiffTools.getDataHandle(dataHandleService, location);
            if (ifdIndex == null || ifdIndex < 0) {
                throw new IllegalArgumentException("Null or negative ifdIndex = " + ifdIndex +
                        " is not allowed when writing mode is not sequential");
            }
            try (final TiffReader reader = new TiffReader(null, in, false)) {
                // - note: we MUST NOT require valid TIFF here, because this file is only
                // in a process of creating and the last offset is probably incorrect
                final long[] ifdOffsets = reader.getIFDOffsets();
                if (ifdIndex < ifdOffsets.length) {
                    out.seek(ifdOffsets[ifdIndex]);
                    LOG.log(System.Logger.Level.TRACE, () ->
                            "Reading IFD from " + ifdOffsets[ifdIndex] + " for non-sequential writing");
                    ifd = reader.readIFD(ifdOffsets[ifdIndex]);
                }
            }
        }

        // record strip byte counts and offsets

        final List<Long> byteCounts = new ArrayList<>();
        final List<Long> offsets = new ArrayList<>();
        long totalTiles = (long) tilesPerRow * (long) tilesPerColumn;

        if (!interleaved) {
            totalTiles *= ifd.getSamplesPerPixel();
        }

        if (ifd.containsKey(IFD.STRIP_BYTE_COUNTS) || ifd.containsKey(IFD.TILE_BYTE_COUNTS)) {
            final long[] ifdByteCounts = isTiled ?
                    ifd.getIFDLongArray(IFD.TILE_BYTE_COUNTS) :
                    ifd.getStripByteCounts();
            for (final long stripByteCount : ifdByteCounts) {
                byteCounts.add(stripByteCount);
            }
        } else {
            while (byteCounts.size() < totalTiles) {
                byteCounts.add(0L);
            }
        }
        final int tileXIndex = fromX / (int) ifd.getTileWidth();
        final int tileYIndex = fromY / (int) ifd.getTileLength();
        final int firstTileIndex = (tileYIndex * tilesPerRow) + tileXIndex;
        if (ifd.containsKey(IFD.STRIP_OFFSETS) || ifd.containsKey(IFD.TILE_OFFSETS)) {
            final long[] ifdOffsets = isTiled ?
                    ifd.getIFDLongArray(IFD.TILE_OFFSETS) :
                    ifd.getStripOffsets();
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

        for (TiffTile tile : tiles) {
            writeEncodedTile(tile, true);
        }

        for (int i = 0; i < tiles.size(); i++) {
            final TiffTile tile = tiles.get(i);
            final int thisOffset = firstTileIndex + i;
            offsets.set(thisOffset, tile.getStoredDataFileOffset());
            byteCounts.set(thisOffset, (long) tile.getStoredDataLength());
        }
        if (isTiled) {
            ifd.putIFDValue(IFD.TILE_BYTE_COUNTS, toPrimitiveArray(byteCounts));
            ifd.putIFDValue(IFD.TILE_OFFSETS, toPrimitiveArray(offsets));
        } else {
            ifd.putIFDValue(IFD.STRIP_BYTE_COUNTS, toPrimitiveArray(byteCounts));
            ifd.putIFDValue(IFD.STRIP_OFFSETS, toPrimitiveArray(offsets));
        }
        long endFP = out.offset();
        if ((endFP & 0x1) != 0) {
            out.writeByte(0);
            endFP = out.offset();
            // - Well-formed IFD requires even offsets
        }
        if (LOGGABLE_TRACE) {
            LOG.log(System.Logger.Level.TRACE, "Offset before IFD write: " + endFP + "; seeking to: " + fp);
        }
        out.seek(fp);

        writeIFD(ifd, lastIFD ? 0 : endFP);
        // - rewriting IFD with already filled offsets (not too quick, but quick enough)
        out.seek(endFP);
        // - restoring correct file pointer at the end of tile
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