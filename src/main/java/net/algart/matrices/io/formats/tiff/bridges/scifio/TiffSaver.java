
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
import io.scif.codec.CodecOptions;
import io.scif.formats.tiff.*;
import io.scif.gui.AWTImageTools;
import io.scif.util.FormatTools;
import org.scijava.AbstractContextual;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.handle.DataHandles;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
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
public class TiffSaver extends AbstractContextual implements Closeable {

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

    // -- Fields --

    /**
     * Output stream to use when saving TIFF data.
     */
    private final DataHandle<Location> out;

    /**
     * Output Location.
     */
    private Location loc;

    /**
     * Output bytes.
     */
    private BytesLocation bytes;

    /**
     * Whether or not to write BigTIFF data.
     */
    private boolean bigTiff = false;

    private boolean writingSequentially = false;

    private boolean autoInterleave = false;

    private Integer defaultStripHeight = 128;

    /**
     * The codec options if set.
     */
    private CodecOptions codecOptions;

    private boolean customJpeg = false;

    private boolean jpegInPhotometricRGB = false;

    private double jpegQuality = 1.0;

    private SCIFIO scifio;

    @Parameter
    private LogService log;

    @Parameter
    private DataHandleService dataHandleService;

    // -- Constructors --

    public TiffSaver(Context context, Location location) throws IOException {
        Objects.requireNonNull(context, "Null context");
        Objects.requireNonNull(location, "Null location");

        setContext(context);
        this.loc = location;
        this.out = dataHandleService.create(location);
        Objects.requireNonNull(out, "Data handle service created null output stream");
        // - just in case: maybe this implementation of DataHandleService is incorrect
        scifio = new SCIFIO(context);
        log = scifio.log();
    }

    /**
     * Constructs a new TIFF saver from the given output source.
     *
     * @param out Output stream to save TIFF data to.
     */
    public TiffSaver(Context context, DataHandle<Location> out) {
        Objects.requireNonNull(context, "Null context");
        Objects.requireNonNull(out, "Null output stream");
        this.out = out;
        this.loc = out.get();
        setContext(context);
        scifio = new SCIFIO(context);
        log = scifio.log();
    }

    public static TiffSaver getInstance(Context context, Location location) throws IOException {
        return new TiffSaver(context, location)
                .setWritingSequentially(true)
                .setAutoInterleave(true)
                .setCustomJpeg(true);
    }

    public static TiffSaver getInstance(Context context, Path file) throws IOException {
        return getInstance(context, file, true);
    }

    public static TiffSaver getInstance(Context context, Path file, boolean deleteExistingFile) throws IOException {
        Objects.requireNonNull(file, "Null file");
        if (deleteExistingFile) {
            Files.deleteIfExists(file);
        }
        return getInstance(context, new FileLocation(file.toFile()));
    }

    /**
     * Constructs a new TIFF saver from the given output source.
     *
     * @param out   Output stream to save TIFF data to.
     * @param bytes In memory byte array handle that we may use to create extra
     *              input or output streams as required.
     */
    public TiffSaver(final DataHandle<Location> out, final BytesLocation bytes) {
        setContext(new Context());

        if (out == null) {
            throw new IllegalArgumentException(
                    "Output stream expected to be not-null");
        }
        if (bytes == null) {
            throw new IllegalArgumentException("Bytes expected to be not null");
        }
        this.out = out;
        this.bytes = bytes;
    }

    // -- TiffSaver methods --


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
     * If we are writing planes sequentially and set this flag, then performance
     * is slightly improved.
     */
    public TiffSaver setWritingSequentially(final boolean sequential) {
        writingSequentially = sequential;
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
    public TiffSaver setLittleEndian(final boolean littleEndian) {
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
    public TiffSaver setBigTiff(final boolean bigTiff) {
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
     * {@link io.scif.Plane} class and returned by {@link TiffParser}. If the desired IFD format is
     * chunked, i.e. {@link IFD#PLANAR_CONFIGURATION} is {@link ExtendedIFD#PLANAR_CONFIG_CONTIGUOUSLY_CHUNKED}
     * (that is the typical usage), then the passes samples are automatically re-packed into chunked (interleaved)
     * form RGBRGBRGB...
     *
     * <p>If this mode is not set, as well as if {@link IFD#PLANAR_CONFIGURATION} is
     * {@link ExtendedIFD#PLANAR_CONFIG_SEPARATE}, the passed data are encoded as-as, i.e. as unpacked
     * RRR...GGG..BBB...  for {@link ExtendedIFD#PLANAR_CONFIG_SEPARATE} or as interleaved RGBRGBRGB...
     * for {@link ExtendedIFD#PLANAR_CONFIG_CONTIGUOUSLY_CHUNKED}.
     *
     * <p>Note that this mode has no effect for 1-channel images.
     *
     * @param autoInterleave new auto-interleave mode. Default value is <tt>true</tt>.
     * @return a reference to this object.
     */
    public TiffSaver setAutoInterleave(boolean autoInterleave) {
        this.autoInterleave = autoInterleave;
        return this;
    }

    public Integer getDefaultStripHeight() {
        return defaultStripHeight;
    }

    public TiffSaver setDefaultStripHeight(Integer defaultStripHeight) {
        if (defaultStripHeight != null && defaultStripHeight <= 0) {
            throw new IllegalArgumentException("Zero or negative default strip height " + defaultStripHeight);
        }
        this.defaultStripHeight = defaultStripHeight;
        return this;
    }

    public boolean isDefaultSingleStrip() {
        return this.defaultStripHeight == null;
    }

    public TiffSaver setDefaultSingleStrip() {
        this.defaultStripHeight = null;
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
    public TiffSaver setCodecOptions(final CodecOptions options) {
        this.codecOptions = options;
        return this;
    }

    public boolean isCustomJpeg() {
        return customJpeg;
    }

    /**
     * Sets whether you need special optimized codec for a case of JPEG compression type.
     * Must be <tt>true</tt> if you want to use any of the further parameters.
     * Default value is <tt>false</tt>.
     *
     * @param customJpeg whether you want to use special optimized codec for JPEG compression.
     * @return a reference to this object.
     */
    public TiffSaver setCustomJpeg(boolean customJpeg) {
        this.customJpeg = customJpeg;
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
     * <p>This parameter is ignored (as if it is <tt>false</tt>), unless {@link #isCustomJpeg()}.
     *
     * @param jpegInPhotometricRGB whether you want to compress JPEG in RGB encoding.
     * @return a reference to this object.
     */
    public TiffSaver setJpegInPhotometricRGB(boolean jpegInPhotometricRGB) {
        this.jpegInPhotometricRGB = jpegInPhotometricRGB;
        return this;
    }

    public boolean compressJPEGInPhotometricRGB() {
        return customJpeg && jpegInPhotometricRGB;
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
    public TiffSaver setJpegQuality(double jpegQuality) {
        this.jpegQuality = jpegQuality;
        return this;
    }

    public TiffSaver setJpegCodecQuality() {
        if (codecOptions == null) {
            throw new IllegalStateException("Codec options was not set yet");
        }
        return setJpegQuality(codecOptions.quality);
    }

    /**
     * Writes the TIFF file header.
     */
    public void writeHeader() throws IOException {
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

    public void writeIFD(final IFD ifd, final long nextOffset) throws FormatException, IOException {
        final TreeSet<Integer> keys = new TreeSet<>(ifd.keySet());
        int keyCount = keys.size();

        if (ifd.containsKey(IFD.LITTLE_ENDIAN)) {
            keyCount--;
        }
        if (ifd.containsKey(IFD.BIG_TIFF)) {
            keyCount--;
        }
        if (ifd.containsKey(IFD.REUSE)) {
            keyCount--;
        }
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

        final BytesLocation extra = new BytesLocation(0); // NB: autoresizes
        try (final DataHandle<Location> extraHandle = dataHandleService.create(extra)) {
            for (final Integer key : keys) {
                if (key.equals(IFD.LITTLE_ENDIAN) || key.equals(IFD.BIG_TIFF) || key.equals(IFD.REUSE)) {
                    continue;
                }

                final Object value = ifd.get(key);
                writeIFDValue(extraHandle, ifdBytes + fp, key, value);
            }

            writeIntValue(out, nextOffset);
            final int ifdLen = (int) extraHandle.offset();
            extraHandle.seek(0l);
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
            writeIntValue(out, q.length);
            if (q.length <= dataLength) {
                for (byte byteValue : q) {
                    out.writeByte(byteValue);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                writeIntValue(out, offset + extraOut.length());
                extraOut.write(q);
            }
        } else if (value instanceof short[]) {
            final short[] q = (short[]) value;
            out.writeShort(IFDType.BYTE.getCode());
            writeIntValue(out, q.length);
            if (q.length <= dataLength) {
                for (short s : q) {
                    out.writeByte(s);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                writeIntValue(out, offset + extraOut.length());
                for (short shortValue : q) {
                    extraOut.writeByte(shortValue);
                }
            }
        } else if (value instanceof String) { // ASCII
            final char[] q = ((String) value).toCharArray();
            out.writeShort(IFDType.ASCII.getCode()); // type
            writeIntValue(out, q.length + 1);
            if (q.length < dataLength) {
                for (char c : q) {
                    out.writeByte(c); // value(s)
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0); // padding
                }
            } else {
                writeIntValue(out, offset + extraOut.length());
                for (char charValue : q) {
                    extraOut.writeByte(charValue); // values
                }
                extraOut.writeByte(0); // concluding NULL byte
            }
        } else if (value instanceof int[]) { // SHORT
            final int[] q = (int[]) value;
            out.writeShort(IFDType.SHORT.getCode()); // type
            writeIntValue(out, q.length);
            if (q.length <= dataLength / 2) {
                for (int intValue : q) {
                    out.writeShort(intValue); // value(s)
                }
                for (int i = q.length; i < dataLength / 2; i++) {
                    out.writeShort(0); // padding
                }
            } else {
                writeIntValue(out, offset + extraOut.length());
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
                                ExtendedIFD.IMAGE_DEPTH,
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
            writeIntValue(out, q.length);

            if (q.length <= 1) {
                for (int i = 0; i < q.length; i++) {
                    writeIntValue(out, q[0]);
                    // - q[0]: it is actually performed 0 or 1 times
                }
                for (int i = q.length; i < 1; i++) {
                    writeIntValue(out, 0);
                }
            } else {
                writeIntValue(out, offset + extraOut.length());
                for (long longValue : q) {
                    writeIntValue(extraOut, longValue);
                }
            }
        } else if (value instanceof TiffRational[]) { // RATIONAL
            final TiffRational[] q = (TiffRational[]) value;
            out.writeShort(IFDType.RATIONAL.getCode()); // type
            writeIntValue(out, q.length);
            if (bigTiff && q.length == 1) {
                out.writeInt((int) q[0].getNumerator());
                out.writeInt((int) q[0].getDenominator());
            } else {
                writeIntValue(out, offset + extraOut.length());
                for (TiffRational tiffRational : q) {
                    extraOut.writeInt((int) tiffRational.getNumerator());
                    extraOut.writeInt((int) tiffRational.getDenominator());
                }
            }
        } else if (value instanceof float[]) { // FLOAT
            final float[] q = (float[]) value;
            out.writeShort(IFDType.FLOAT.getCode()); // type
            writeIntValue(out, q.length);
            if (q.length <= dataLength / 4) {
                for (float floatValue : q) {
                    out.writeFloat(floatValue); // value
                    // - in old SCIFIO code, here was a bug (for a case bigTiff): q[0] was always written
                }
                for (int i = q.length; i < dataLength / 4; i++) {
                    out.writeInt(0); // padding
                }
            } else {
                writeIntValue(out, offset + extraOut.length());
                for (float floatValue : q) {
                    extraOut.writeFloat(floatValue); // values
                }
            }
        } else if (value instanceof double[]) { // DOUBLE
            final double[] q = (double[]) value;
            out.writeShort(IFDType.DOUBLE.getCode()); // type
            writeIntValue(out, q.length);
            writeIntValue(out, offset + extraOut.length());
            for (final double doubleValue : q) {
                extraOut.writeDouble(doubleValue); // values
            }
        } else {
            throw new FormatException("Unknown IFD value type (" + value.getClass()
                    .getName() + "): " + value);
        }
    }

    /**
     *
     */
    public void writeImage(
            final byte[][] samples, final IFDList ifds,
            final int pixelType) throws FormatException, IOException {
        if (ifds == null) {
            throw new FormatException("IFD cannot be null");
        }
        if (samples == null) {
            throw new FormatException("Image data cannot be null");
        }
        for (int i = 0; i < ifds.size(); i++) {
            if (i < samples.length) {
                writeImage(samples[i], ifds.get(i), i, pixelType, i == ifds.size() - 1);
            }
        }
    }

    public void writeImage(
            final byte[] samples, final IFD ifd, final int planeIndex,
            final int pixelType, final boolean last) throws FormatException, IOException {
        if (ifd == null) {
            throw new FormatException("IFD cannot be null");
        }
        final int w = (int) ifd.getImageWidth();
        final int h = (int) ifd.getImageLength();
        writeImage(samples, ifd, planeIndex, pixelType, 0, 0, w, h, last);
    }

    /**
     * Writes to any rectangle from the passed block.
     *
     * @param samples    The block that is to be written.
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
    public void writeImage(
            final byte[] samples, final IFD ifd, final long planeIndex,
            final int pixelType, final int x, final int y, final int w, final int h,
            final boolean last) throws FormatException, IOException {
        writeImage(samples, ifd, planeIndex, null, pixelType, x, y, w, h, last);
    }

    public void writeImage(
            byte[] samples, final IFD ifd, final long planeIndex,
            Integer numberOfChannels,
            final int pixelType,
            final int fromX, final int fromY, final int sizeX, final int sizeY,
            final boolean last)
            throws FormatException, IOException {
        Objects.requireNonNull(samples, "Null samples data");
        Objects.requireNonNull(ifd, "Null IFD");
        TiffParser.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        final int bytesPerSample = FormatTools.getBytesPerPixel(pixelType);
        assert bytesPerSample >= 1;
        final long size = (long) sizeX * (long) sizeY;
        if (size > samples.length || size * bytesPerSample > samples.length) {
            throw new IllegalArgumentException("Too short samples array, even for 1 channel: " + samples.length
                    + " < " + size * bytesPerSample);
        }
        final int channelSize = (int) size * bytesPerSample;
        if (numberOfChannels == null) {
            numberOfChannels = samples.length / channelSize;
        } else if (channelSize * numberOfChannels > samples.length) {
            throw new IllegalArgumentException("Too short samples array for " + numberOfChannels +
                    " channels: " + samples.length + " < " + channelSize * numberOfChannels);
        }

        ifd.putIFDValue(IFD.LITTLE_ENDIAN, out.isLittleEndian());
        // - will be used in getCompressionCodecOptions

        // These operations are synchronized
        final byte[][] tiles;
        synchronized (this) {
            prepareValidIFD(ifd, pixelType, numberOfChannels);
            tiles = prepareTiles(samples, ifd, numberOfChannels, bytesPerSample, sizeX, sizeY);
        }

        // Compress tiles according to given differencing and compression
        // schemes,
        // this operation is NOT synchronized and is the ONLY portion of the
        // TiffWriter.saveBytes() --> TiffSaver.writeImage() stack that is NOT
        // synchronized.
        writeTiles(ifd, numberOfChannels, tiles);

        // This operation is synchronized
        synchronized (this) {
            writeImageIFD(ifd, planeIndex, tiles, numberOfChannels, last, fromX, fromY);
        }
    }

    // Note: stripSamples is always interleaved (RGBRGB...) or monochrome.
    public byte[] encode(IFD ifd, byte[] stripSamples, int sizeX, int sizeY) throws FormatException {
        Objects.requireNonNull(stripSamples, "Null stripSamples data");
        Objects.requireNonNull(ifd, "Null IFD");
        if (sizeX < 0 || sizeY < 0) {
            throw new IllegalArgumentException("Negative matrix sizes " + sizeX + "x" + sizeY);
        }
        final int effectiveChannels = ifd.getPlanarConfiguration() == 1 ? ifd.getSamplesPerPixel() : 1;
        final int bytesPerSample = ifd.getBytesPerSample()[0];
        if (effectiveChannels < 1) {
            throw new FormatException("Invalid format: zero or negative samples per pixel = " + effectiveChannels);
        }
        if (bytesPerSample < 1) {
            throw new FormatException("Invalid format: zero or negative bytes per sample = " + bytesPerSample);
        }
        final long size = (long) sizeX * (long) sizeY;
        if (size > stripSamples.length || size * effectiveChannels > stripSamples.length / bytesPerSample) {
            throw new IllegalArgumentException("Too short stripSamples array: " + stripSamples.length + " < " +
                    size * effectiveChannels * bytesPerSample);
        }

        TiffCompression compression = ifd.getCompression();
        final CodecOptions codecOptions = compression.getCompressionCodecOptions(ifd, this.codecOptions);
        codecOptions.width = sizeX;
        codecOptions.height = sizeY;
        codecOptions.channels = effectiveChannels;
        if (customJpeg && compression == TiffCompression.JPEG) {
            return compressJPEG(stripSamples, codecOptions);
        } else {
            return compression.compress(scifio.codec(), stripSamples, codecOptions);
        }
    }

    // Optimized version of io.scif.codec.JPEGCodec.compress() method
    private byte[] compressJPEG(final byte[] data, CodecOptions options) throws FormatException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        if (data.length == 0) {
            return data;
        }

        if (options.bitsPerSample > 8) {
            throw new FormatException("Cannot compress " + options.bitsPerSample + "-bit data in JPEG format " +
                    "(only 8-bit samples allowed)");
        }

        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        final BufferedImage image = AWTImageTools.makeImage(data, options.width,
                options.height, options.channels, options.interleaved,
                options.bitsPerSample / 8, false, options.littleEndian, options.signed);

        try {
            final ImageOutputStream ios = ImageIO.createImageOutputStream(result);
            final ImageWriter jpegWriter = getJPEGWriter();
            jpegWriter.setOutput(ios);

            final ImageWriteParam writeParam = jpegWriter.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionType("JPEG");
            writeParam.setCompressionQuality((float) jpegQuality);
            final ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(image);
            if (jpegInPhotometricRGB) {
                writeParam.setDestinationType(imageTypeSpecifier);
                // - Important! It informs getDefaultImageMetadata to add Adove and SOF markers,
                // that is detected by JPEGImageWriter and leads to correct outCsType = JPEG.JCS_RGB
            }
            final IIOMetadata metadata = jpegWriter.getDefaultImageMetadata(
                    jpegInPhotometricRGB ? null : imageTypeSpecifier,
                    writeParam);
            // - Important! imageType = null necessary for RGB, in other case setDestinationType will be ignored!

            final IIOImage iioImage = new IIOImage(image, null, metadata);
            // - metadata necessary (with necessary markers)
            jpegWriter.write(null, iioImage, writeParam);
        } catch (final IOException e) {
            throw new FormatException("Cannot compress JPEG data", e);
        }
        return result.toByteArray();
    }

    /* These methods are never used and should be removed. No sense to optimize it.
	public void overwriteLastIFDOffset(final DataHandle<Location> handle)
		throws FormatException, IOException
	{
		if (handle == null) throw new FormatException("Output cannot be null");
		final TiffParser parser = new TiffParser(getContext(), handle);
		parser.getIFDOffsets();
		out.seek(handle.offset() - (bigTiff ? 8 : 4));
		writeIntValue(out, 0);
	}

	/ **
	 * Surgically overwrites an existing IFD value with the given one. This method
	 * requires that the IFD directory entry already exist. It intelligently
	 * updates the count field of the entry to match the new length. If the new
	 * length is longer than the old length, it appends the new data to the end of
	 * the file and updates the offset field; if not, or if the old data is
	 * already at the end of the file, it overwrites the old data in place.
	 * /
	public void overwriteIFDValue(final DataHandle<Location> raf, final int ifd,
		final int tag, final Object value) throws FormatException, IOException
	{
		if (raf == null) throw new FormatException("Output cannot be null");
		log.debug("overwriteIFDValue (ifd=" + ifd + "; tag=" + tag + "; value=" +
			value + ")");

		raf.seek(0);
		final TiffParser parser = new TiffParser(getContext(), raf);
		final Boolean valid = parser.checkHeader();
		if (valid == null) {
			throw new FormatException("Invalid TIFF header");
		}

		final boolean little = valid;
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
				final TiffSaver saver = new TiffSaver(ifdHandle, new BytesLocation(
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
				}
				else {
					newCount = ifdHandle.readInt();
					newOffset = ifdHandle.readInt();
				}
				log.debug("overwriteIFDValue:");
				log.debug("\told (" + entry + ");");
				log.debug("\tnew: (tag=" + newTag + "; type=" + newType + "; count=" +
					newCount + "; offset=" + newOffset + ")");

				// determine the best way to overwrite the old entry
				if (extraHandle.length() == 0) {
					// new entry is inline; if old entry wasn't, old data is
					// orphaned
					// do not override new offset value since data is inline
					log.debug("overwriteIFDValue: new entry is inline");
				}
				else if (entry.getValueOffset() + entry.getValueCount() * entry
					.getType().getBytesPerElement() == raf.length())
				{
					// old entry was already at EOF; overwrite it
					newOffset = entry.getValueOffset();
					log.debug("overwriteIFDValue: old entry is at EOF");
				}
				else if (newCount <= entry.getValueCount()) {
					// new entry is as small or smaller than old entry;
					// overwrite it
					newOffset = entry.getValueOffset();
					log.debug("overwriteIFDValue: new entry is <= old entry");
				}
				else {
					// old entry was elsewhere; append to EOF, orphaning old
					// entry
					newOffset = raf.length();
					log.debug("overwriteIFDValue: old entry will be orphaned");
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

	/ ** Convenience method for overwriting a file's first ImageDescription. * /
	public void overwriteComment(final DataHandle<Location> in,
		final Object value) throws FormatException, IOException
	{
		overwriteIFDValue(in, 0, IFD.IMAGE_DESCRIPTION, value);
	}

 */

    @Override
    public void close() throws IOException {
        out.close();
    }

    // -- Helper methods --

    /**
     * Coverts a list to a primitive array.
     *
     * @param l The list of {@code Long} to convert.
     * @return A primitive array of type {@code long[]} with the values from
     * </code>l</code>.
     */
    private long[] toPrimitiveArray(final List<Long> l) {
        final long[] toReturn = new long[l.size()];
        for (int i = 0; i < l.size(); i++) {
            toReturn[i] = l.get(i);
        }
        return toReturn;
    }

    /**
     * Write the given value to the given RandomAccessOutputStream. If the
     * 'bigTiff' flag is set, then the value will be written as an 8 byte long;
     * otherwise, it will be written as a 4 byte integer.
     */
    private void writeIntValue(final DataHandle<Location> handle,
                               final long offset) throws IOException {
        if (bigTiff) {
            handle.writeLong(offset);
        } else {
            handle.writeInt((int) offset);
        }
    }


    private void prepareValidIFD(final IFD ifd, int pixelType, int numberOfChannels) throws FormatException {
        final int bytesPerPixel = FormatTools.getBytesPerPixel(pixelType);
        final int bps = 8 * bytesPerPixel;
        final int[] bpsArray = new int[numberOfChannels];
        Arrays.fill(bpsArray, bps);
        ifd.putIFDValue(IFD.BITS_PER_SAMPLE, bpsArray);

        if (FormatTools.isFloatingPoint(pixelType)) {
            ifd.putIFDValue(IFD.SAMPLE_FORMAT, 3);
        }
        Object compressionValue = ifd.getIFDValue(IFD.COMPRESSION);
        if (compressionValue == null) {
            compressionValue = TiffCompression.UNCOMPRESSED.getCode();
            ifd.putIFDValue(IFD.COMPRESSION, compressionValue);
        }

        final boolean indexed = numberOfChannels == 1 && ifd.getIFDValue(IFD.COLOR_MAP) != null;
        final PhotoInterp pi = indexed ? PhotoInterp.RGB_PALETTE :
                numberOfChannels == 1 ? PhotoInterp.BLACK_IS_ZERO :
                        compressionValue.equals(TiffCompression.JPEG.getCode())
                                && ifd.getPlanarConfiguration() == 1
                                && !compressJPEGInPhotometricRGB() ?
                                PhotoInterp.Y_CB_CR :
                                PhotoInterp.RGB;
        ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, pi.getCode());

        ifd.putIFDValue(IFD.SAMPLES_PER_PIXEL, numberOfChannels);

//        if (ifd.get(IFD.X_RESOLUTION) == null) {
//            ifd.putIFDValue(IFD.X_RESOLUTION, new TiffRational(1, 1));
//        }
//        if (ifd.get(IFD.Y_RESOLUTION) == null) {
//            ifd.putIFDValue(IFD.Y_RESOLUTION, new TiffRational(1, 1));
//        }
//        if (ifd.get(IFD.SOFTWARE) == null) {
//            ifd.putIFDValue(IFD.SOFTWARE, "SCIFIO");
//        }
        if (ifd.get(IFD.ROWS_PER_STRIP) == null && ifd.get(IFD.TILE_LENGTH) == null) {
            if (!isDefaultSingleStrip()) {
                ifd.putIFDValue(IFD.ROWS_PER_STRIP, new long[]{getDefaultStripHeight()});
            }
        }
//        if (ifd.get(IFD.IMAGE_DESCRIPTION) == null) {
//            ifd.putIFDValue(IFD.IMAGE_DESCRIPTION, "");
//        }
    }

    private byte[][] prepareTiles(
            byte[] samples,
            IFD ifd,
            int numberOfChannels,
            int bytesPerSample,
            int sizeX,
            int sizeY) throws FormatException, IOException {
        final byte[][] tiles;
        final boolean chunked = ifd.getPlanarConfiguration() == 1;
        final int channelSize = sizeX * sizeY * bytesPerSample;

        // create pixel output buffers

        final int tileWidth = (int) ifd.getTileWidth();
        final int tileHeight = (int) ifd.getTileLength();
        final int tilesPerRow = (int) ifd.getTilesPerRow();
        final int rowsPerStrip = (int) ifd.getRowsPerStrip()[0];
        //??
        int stripSize = rowsPerStrip * tileWidth * bytesPerSample;
        final int numberOfActualStrips =
                ((sizeX + tileWidth - 1) / tileWidth) * ((sizeY + tileHeight - 1) / tileHeight);
        int numberOfEncodedStrips = numberOfActualStrips;

        if (chunked) {
            stripSize *= numberOfChannels;
        } else {
            numberOfEncodedStrips *= numberOfChannels;
        }

        final ByteArrayOutputStream[] stripBuf = new ByteArrayOutputStream[numberOfEncodedStrips];
        final DataOutputStream[] stripOut = new DataOutputStream[numberOfEncodedStrips];
        for (int strip = 0; strip < numberOfEncodedStrips; strip++) {
            stripBuf[strip] = new ByteArrayOutputStream(stripSize);
            stripOut[strip] = new DataOutputStream(stripBuf[strip]);
        }
        // final int[] bps = ifd.getBitsPerSample();
        // - not necessary: correct BITS_PER_SAMPLE, based on pixelType, was written into IFD by prepareValidIFD()

        // write pixel tiles to output buffers
        if (chunked && autoInterleave) {
            samples = TiffParser.interleaveSamples(samples, numberOfChannels, bytesPerSample, sizeX * sizeY);
        }
        for (int tileIndex = 0; tileIndex < numberOfActualStrips; tileIndex++) {
            final int xOffset = (tileIndex % tilesPerRow) * tileWidth;
            final int yOffset = (tileIndex / tilesPerRow) * tileHeight;
            for (int row = 0; row < tileHeight; row++) {
                int i = row + yOffset;
                for (int col = 0; col < tileWidth; col++) {
                    int j = col + xOffset;
                    final int ndx = (i * sizeX + j) * bytesPerSample;
                    for (int c = 0; c < numberOfChannels; c++) {
                        for (int n = 0; n < bytesPerSample; n++) {
                            if (chunked) {
                                int off = ndx * numberOfChannels + c * bytesPerSample + n;
                                if (i >= sizeY || j >= sizeX) {
                                    stripOut[tileIndex].writeByte(0);
                                } else {
                                    stripOut[tileIndex].writeByte(samples[off]);
                                }

                            } else {
                                int off = c * channelSize + ndx + n;
                                if (i >= sizeY || j >= sizeX) {
                                    stripOut[c * (numberOfEncodedStrips / numberOfChannels) + tileIndex].writeByte(0);
                                } else {
                                    stripOut[c * (numberOfEncodedStrips / numberOfChannels) + tileIndex].writeByte(
                                            samples[off]);
                                }
                            }
                        }
                    }
                }
            }
        }
        tiles = new byte[numberOfEncodedStrips][];
        for (int tileIndex = 0; tileIndex < numberOfEncodedStrips; tileIndex++) {
            tiles[tileIndex] = stripBuf[tileIndex].toByteArray();
        }
        return tiles;
    }

    private void writeTiles(IFD ifd, Integer numberOfChannels, byte[][] strips) throws FormatException {
        final boolean tiled = ifd.containsKey(IFD.TILE_WIDTH);
        // - unlike ifd.isTiled, it will be true even if IFD contains STRIP_OFFSETS instead of TILE_OFFSETS
        // (IFD is not well-formed yet)
        final boolean chunked = ifd.getPlanarConfiguration() == 1;
        final int numberOfActualStrips = chunked ? strips.length : strips.length / numberOfChannels;
        final int imageHeight = (int) ifd.getImageLength();
        int tileWidth = (int) ifd.getTileWidth();
        int tileHeight = (int) ifd.getTileLength();
        int tilesPerRow = (int) ifd.getTilesPerRow();
        for (int strip = 0; strip < strips.length; strip++) {
            scifio.tiff().difference(strips[strip], ifd);
            int tileSizeX = tileWidth;
            int tileSizeY = tileHeight;
            if (!tiled) {
                final int yOffset = ((strip % numberOfActualStrips) / tilesPerRow) * tileHeight;
                if (yOffset + tileHeight > imageHeight) {
                    tileSizeY = Math.max(0, imageHeight - yOffset);
                    // - last strip should have exact height, in other case TIFF may be read with a warning
                }
            }
            strips[strip] = encode(ifd, strips[strip], tileSizeX, tileSizeY);
        }
    }

    /**
     * Performs the actual work of dealing with IFD data and writing it to the
     * TIFF for a given image or sub-image.
     *
     * @param ifd        The Image File Directories. Mustn't be {@code null}.
     * @param planeIndex The image index within the current file, starting from 0.
     * @param strips     The strips to write to the file.
     * @param last       Pass {@code true} if it is the last image, {@code false}
     *                   otherwise.
     * @param x          The initial X offset of the strips/tiles to write.
     * @param y          The initial Y offset of the strips/tiles to write.
     * @throws FormatException
     * @throws IOException
     */
    private void writeImageIFD(IFD ifd, final long planeIndex,
                               final byte[][] strips, final int nChannels, final boolean last, final int x,
                               final int y) throws FormatException, IOException {
        final int tilesPerRow = (int) ifd.getTilesPerRow();
        final int tilesPerColumn = (int) ifd.getTilesPerColumn();
        final boolean interleaved = ifd.getPlanarConfiguration() == 1;
        final boolean isTiled = ifd.isTiled();
        assert out != null : "must be checked in the constructor";

        if (!writingSequentially) {
            if (planeIndex < 0) {
                throw new IllegalArgumentException("Negative planeIndex = " + planeIndex);
            }
            DataHandle<Location> in;
            if (loc != null) {
                in = dataHandleService.create(loc);
            } else {
                in = dataHandleService.create(out.get());
            }
            try {
                final TiffParser parser = new TiffParser(getContext(), in);
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
        long totalTiles = (long) tilesPerRow * (long) tilesPerColumn;

        if (!interleaved) {
            totalTiles *= nChannels;
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
        final int tileOrStripOffsetX = x / (int) ifd.getTileWidth();
        final int tileOrStripOffsetY = y / (int) ifd.getTileLength();
        final int firstOffset = (tileOrStripOffsetY * tilesPerRow) +
                tileOrStripOffsetX;
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
        long endFP = out.offset();
        if ((endFP & 0x1) != 0) {
            out.writeByte(0);
            endFP = out.offset();
            // - Well-formed IFD requires even offsets
        }
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
        // - rewriting IFD with already filled offsets (not too quick, but quick enough)
        out.seek(endFP);
        // - restoring correct file pointer at the end of tile
    }

    private static ImageWriter getJPEGWriter() throws IIOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IIOException("Cannot write JPEG");
        }
        return writers.next();
    }
}
