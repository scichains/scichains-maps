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

package net.algart.scifio.tiff.improvements;

import io.scif.FormatException;
import io.scif.enumeration.EnumException;
import io.scif.formats.tiff.*;
import io.scif.util.FormatTools;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.util.Bytes;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ExtendedTiffParser extends TiffParser implements Closeable {
    private static final boolean DEBUG_OPTIMIZATION = false;

    private static final System.Logger LOG = System.getLogger(ExtendedTiffParser.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);
    private static final boolean BUILT_IN_TIMING = LOGGABLE_DEBUG && getBooleanProperty(
            "net.algart.matrices.libs.scifio.tiff.builtInTiming");

    private boolean doCaching = true;
    //!! - necessary to provide access to private field of TiffParser
    private byte filler = 0;

    //!! Additional constructor, more convenient in most cases.
    public ExtendedTiffParser(final Context context, final Path file) throws IOException {
        this(context, existingFileToLocation(file));
    }

    //!! Additional "throws", for possible future needs
    public ExtendedTiffParser(final Context context, final Location location) throws IOException {
        this(context, context.getService(DataHandleService.class).create(location));
    }

    public ExtendedTiffParser(Context context, final DataHandle<Location> in) {
        super(context, in);
    }

    public boolean isDoCaching() {
        return doCaching;
    }

    @Override
    public void setDoCaching(boolean doCaching) {
        super.setDoCaching(doCaching);
        this.doCaching = doCaching;
    }

    public byte getFiller() {
        return filler;
    }

    public ExtendedTiffParser setFiller(byte filler) {
        this.filler = filler;
        return this;
    }

    @Override
    public TiffIFDEntry readTiffIFDEntry() throws IOException {
        final TiffIFDEntry result = super.readTiffIFDEntry();
        LOG.log(System.Logger.Level.TRACE, () -> String.format(
                "Reading IFD entry: %s - %s", result, ExtendedIFD.ifdTagName(result.getTag())));
        return result;
    }

    @Override
    public ExtendedIFD getIFD(long offset) throws IOException {
        long t1 = System.nanoTime();
        final DataHandle<Location> in = getStream();
        final boolean bigTiff = isBigTiff();
        if (offset < 0 || offset >= in.length()) {
            return null;
        }
        final Map<Integer, IFDType> types = new LinkedHashMap<>();
        final ExtendedIFD ifd = new ExtendedIFD(null, offset);
        //!! Could be better to pass here correct log, but it has private access in TiffParser

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
            }
            catch (final EnumException e) {
                continue;
                //!! - In the SCIFIO core, here it "break" operator, but why?
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
                types.put(tag, entry.getType());
                ifd.put(tag, value);
            }
        }
        ifd.setTypes(types);

        in.seek(offset + baseOffset + bytesPerEntry * numEntries);
        long t2 = System.nanoTime();
        LOG.log(System.Logger.Level.TRACE, () -> String.format(
                "reading IFD at offset %d: %.3f ms", offset, (t2 - t1) * 1e-6));
        return ifd;
    }

    //!! Optimized version of original getIFDValue for cases of large arrays of 32- or 64-bit integers
    @Override
    public Object getIFDValue(TiffIFDEntry entry) throws IOException {
        long t1 = BUILT_IN_TIMING ? System.nanoTime() : 0;
        final DataHandle<?> in = getStream();
        final int count = entry.getValueCount();
        Object result = null;
        if (count > 1) {
            // count == 1 is a special case in TiffParser.getIFDValue
            switch (entry.getType()) {
                case LONG -> {
                    if (!prepareReadingIFD(entry)) {
                        // - call this only when necessary (the same code is executed inside super.getIFDValue())
                        return null;
                    }
                    final long[] longs = new long[count];
                    final byte[] b = new byte[count * 4];
                    final ByteBuffer buffer = ByteBuffer.allocateDirect(b.length);
                    buffer.order(in.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    in.readFully(b);
                    buffer.put(b, 0, b.length);
                    buffer.rewind();
                    getLongs(buffer.asIntBuffer(), longs);
                    result = longs;
                    debugOptimization(longs, entry);
                }
                case LONG8 -> {
                    if (!prepareReadingIFD(entry)) {
                        // - call this only when necessary (the same code is executed inside super.getIFDValue())
                        return null;
                    }
                    final long[] longs = new long[count];
                    final byte[] b = new byte[count * 8];
                    final ByteBuffer buffer = ByteBuffer.allocateDirect(b.length);
                    buffer.order(in.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    in.readFully(b);
                    buffer.put(b, 0, b.length);
                    buffer.rewind();
                    buffer.asLongBuffer().get(longs);
                    result = longs;
                    debugOptimization(longs, entry);
                }
            }
        }
        if (result == null) {
            result = super.getIFDValue(entry);
        }
        if (BUILT_IN_TIMING && count > 500) {
            long t2 = System.nanoTime();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                    "Reading %s in %.3f ms, %.1f ns/element",
                    entry,
                    (t2 - t1) * 1e-6, (double) (t2 - t1) / (double) entry.getValueCount()));
        }
        return result;
    }

    //!! Unlike original getTile, this method allows to pass samples=null. In this case,
    //!! it allocates the buffer itself. It simplifies usage and allows to implement
    //!! more efficient inheritors, which cache the tiles and can return result from this method
    //!! very quickly (in a constant time).
    @Override
    public byte[] getTile(IFD ifd, byte[] samples, int row, int col) throws FormatException, IOException {
        if (samples == null) {
            final int size = sizeOfIFDTileBasedOnBits(ifd);
            samples = new byte[size];
        }
        return super.getTile(ifd, samples, row, col);
    }

    // Please do not change IFD in a parallel thread while calling this method!
    @Override
    public byte[] getSamples(IFD ifd, byte[] samples, int fromX, int fromY, long sizeX, long sizeY)
            throws FormatException, IOException {
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        return getSamples(ifd, samples, fromX, fromY, (int) sizeX, (int) sizeY);
    }

    // Please do not change IFD in a parallel thread while calling this method!
    public byte[] getSamples(IFD ifd, byte[] samples, int fromX, int fromY, int sizeX, int sizeY)
            throws FormatException, IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        final int size = sizeOfIFDRegion(ifd, sizeX, sizeY);
        // - also checks that sizeX/sizeY are allowed
        assert sizeX >= 0 && sizeY >= 0 : "sizeOfIFDRegion didn't check sizes accurately: " + sizeX + "fromX" + sizeY;
        if (samples == null) {
            samples = new byte[size];
        }
        if (sizeX == 0 || sizeY == 0) {
            return samples;
        }
        sizeOfIFDTileBasedOnBits(ifd);
        // - checks that results of ifd.getTileWidth(), ifd.getTileLength() are non-negative and <2^31,
        // and checks that we can multiply them by bytesPerSample and ifd.getSamplesPerPixel() without overflow
        Arrays.fill(samples, 0, size, filler);
        // - important for a case when the requested area is outside the image;
        // old SCIFIO code did not check this and could return undefined results

        readTiles(ifd, samples, fromX, fromY, sizeX, sizeY);

        adjustFillOrder(ifd, samples);
        return samples;
    }

    public byte[] readSamples(IFD ifd, int fromX, int fromY, int sizeX, int sizeY, boolean interleave)
            throws IOException, FormatException {
        long t1 = BUILT_IN_TIMING ? System.nanoTime() : 0;
        byte[] samples = getSamples(ifd, null, fromX, fromY, sizeX, sizeY);
        long t2 = BUILT_IN_TIMING ? System.nanoTime() : 0;
        correctUnusualPrecisions(ifd, samples, sizeX * sizeY);
        long t3 = BUILT_IN_TIMING ? System.nanoTime() : 0;
        byte[] result = interleave ?
                interleaveSamples(ifd, samples, sizeX * sizeY) :
                samples;
        if (BUILT_IN_TIMING) {
            long t4 = System.nanoTime();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                    "%s read %dx%d (%.3f MB) in %.3f ms = %.3f get + %.3f unusual + %.3f interleave, %.3f MB/s",
                    getClass().getSimpleName(),
                    sizeX, sizeY, result.length / 1048576.0,
                    (t4 - t1) * 1e-6,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6,
                    result.length / 1048576.0 / ((t4 - t1) * 1e-9)));
        }
        return result;
    }

    public Object readSamplesArray(IFD ifd, int fromX, int fromY, int sizeX, int sizeY, boolean interleave)
            throws IOException, FormatException {
        return readSamplesArray(
                ifd, fromX, fromY, sizeX, sizeY, interleave, null, null);
    }

    public Object readSamplesArray(
            IFD ifd,
            final int fromX,
            final int fromY,
            final int sizeX,
            final int sizeY,
            boolean interleave,
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
        byte[] resultBytes = readSamples(ifd, fromX, fromY, sizeX, sizeY, interleave);
        final int pixelType = ifd.getPixelType();
        return Bytes.makeArray(
                resultBytes,
                FormatTools.getBytesPerPixel(pixelType),
                FormatTools.isFloatingPoint(pixelType),
                ifd.isLittleEndian());
    }

    //!! This method is very deprecated and should be removed. No sense to optimize it.
    @Override
    public byte[] getSamples(IFD ifd, byte[] buf, int x, int y, long width, long height, int overlapX, int overlapY)
            throws FormatException, IOException {
        if (overlapX == 0 && overlapY == 0) {
            return getSamples(ifd, buf, x, y, width, height);
        }
        return super.getSamples(ifd, buf, x, y, width, height, overlapX, overlapY);
    }

    //TODO!! deprecated since 0.46.0 version
    @Override
    public void close() throws IOException {
        getStream().close();
    }

    public static Class<?> javaElementType(int ifdPixelType) throws FormatException {
        return switch (ifdPixelType) {
            case FormatTools.INT8, FormatTools.UINT8 -> byte.class;
            case FormatTools.INT16, FormatTools.UINT16 -> short.class;
            case FormatTools.INT32, FormatTools.UINT32 -> int.class;
            case FormatTools.FLOAT -> float.class;
            case FormatTools.DOUBLE -> double.class;
            default -> throw new FormatException("Unknown pixel type: " + ifdPixelType);
        };
    }

    public static Optional<Class<?>> optionalElementType(IFD ifd) {
        Objects.requireNonNull(ifd, "Null IFD");
        try {
            return Optional.of(javaElementType(ifd.getPixelType()));
        } catch (FormatException e) {
            return Optional.empty();
        }
    }

    public static int getBytesPerSampleBasedOnType(IFD ifd) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        final int pixelType = ifd.getPixelType();
        return FormatTools.getBytesPerPixel(pixelType);
    }

    public static int getBytesPerSampleBasedOnBits(IFD ifd) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        final int[] bytesPerSample = ifd.getBytesPerSample();
        final int result = bytesPerSample[0];
        // - for example, if we have 5 bits R + 6 bits G + 4 bits B, it will be ceil(6/8) = 1 byte;
        // usually the same for all components
        checkDifferentBytesPerSample(ifd, bytesPerSample);
        return result;
    }

    /**
     * Checks that the sizes of this IFD (ImageWidth and ImageLength) are positive integers
     * in range <tt>1..Integer.MAX_VALUE</tt>. If it is not so, throws {@link FormatException}.
     * This class does not require this condition, but it is a reasonable requirement for many applications.
     *
     * @param ifd element of Image File Directories
     * @throws FormatException if image width or height is negaitve or <tt>&gt;Integer.MAX_VALUE</tt>.
     */
    public static void checkSizesArePositive(IFD ifd) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        long dimX = ifd.getImageWidth();
        long dimY = ifd.getImageLength();
        if (dimX <= 0 || dimY <= 0) {
            throw new FormatException("Zero or negative IFD image sizes " + dimX + "x" + dimY + " are not allowed");
            // - important for some classes, processing IFD images, that cannot work with zero-size areas
            // (for example, due to usage of AlgART IRectangularArea)
        }
        assert dimX <= Integer.MAX_VALUE : "getImageWidth() did not check 31-bit result";
        assert dimY <= Integer.MAX_VALUE : "getImageLength() did not check 31-bit result";
    }

    public static int sizeOfIFDRegion(final IFD ifd, long sizeX, long sizeY) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        return checkedMul(sizeX, sizeY, ifd.getSamplesPerPixel(), getBytesPerSampleBasedOnType(ifd),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample (type-based)",
                () -> "Invalid requested area: ", () -> "");
    }

    public static int sizeOfIFDRegionBasedOnBits(final IFD ifd, long sizeX, long sizeY) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        return checkedMul(sizeX, sizeY, ifd.getSamplesPerPixel(), getBytesPerSampleBasedOnBits(ifd),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample",
                () -> "Invalid requested area: ", () -> "");
    }

    public static int sizeOfIFDTileBasedOnBits(final IFD ifd) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        final int channels = ifd.getPlanarConfiguration() == ExtendedIFD.PLANAR_CONFIG_SEPARATE ?
                1 :
                ifd.getSamplesPerPixel();
        // - if separate (RRR...GGG...BBB...),
        // we have (for 3 channels) only 1 channel instead of 3, but number of tiles is greater:
        // 3 * numTileRows effective rows of tiles instead of numTileRows
        return checkedMul(ifd.getTileWidth(), ifd.getTileLength(), channels, getBytesPerSampleBasedOnBits(ifd),
                "tile width", "tile height", "effective number of channels", "bytes per sample",
                () -> "Invalid TIFF tile sizes: ", () -> "");
    }


    public static void correctUnusualPrecisions(final IFD ifd, final byte[] samples, final int numberOfPixels) {
        Objects.requireNonNull(samples, "Null samples");
        Objects.requireNonNull(ifd, "Null IFD");
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        final boolean float16;
        final boolean float24;
        final boolean littleEndian;
        final int samplesPerPixel;
        try {
            samplesPerPixel = ifd.getSamplesPerPixel();
            final int pixelType = ifd.getPixelType();
            final int[] bitsPerSample = ifd.getBitsPerSample();
            float16 = pixelType == FormatTools.FLOAT && bitsPerSample[0] == 16;
            float24 = pixelType == FormatTools.FLOAT && bitsPerSample[0] == 24;
            littleEndian = ifd.isLittleEndian();
        } catch (FormatException e) {
            throw new IllegalArgumentException("Illegal TIFF IFD", e);
            // - usually should not occur: this function is typically called after analysing IFD
        }

        if (float16 || float24) {
            final int nBytes = float16 ? 2 : 3;
            final int mantissaBits = float16 ? 10 : 16;
            final int exponentBits = float16 ? 5 : 7;
            final int maxExponent = (int) Math.pow(2, exponentBits) - 1;
            final int bits = (nBytes * 8) - 1;

            checkedMul(new long[]{numberOfPixels, samplesPerPixel, nBytes},
                    new String[]{"number of pixels", "samples per pixel", "bytes per sample"},
                    () -> "Invalid sizes: ", () -> "");
            final int numberOfSamples = samplesPerPixel * numberOfPixels;

            final byte[] newBuf = new byte[samples.length];
            for (int i = 0; i < numberOfSamples; i++) {
                final int v = Bytes.toInt(samples, i * nBytes, nBytes, littleEndian);
                final int sign = v >> bits;
                int exponent = (v >> mantissaBits) & (int) (Math.pow(2,
                        exponentBits) - 1);
                int mantissa = v & (int) (Math.pow(2, mantissaBits) - 1);

                if (exponent == 0) {
                    if (mantissa != 0) {
                        while ((mantissa & (int) Math.pow(2, mantissaBits)) == 0) {
                            mantissa <<= 1;
                            exponent--;
                        }
                        exponent++;
                        mantissa &= (int) (Math.pow(2, mantissaBits) - 1);
                        exponent += 127 - (Math.pow(2, exponentBits - 1) - 1);
                    }
                } else if (exponent == maxExponent) {
                    exponent = 255;
                } else {
                    exponent += 127 - (Math.pow(2, exponentBits - 1) - 1);
                }

                mantissa <<= (23 - mantissaBits);

                final int value = (sign << 31) | (exponent << 23) | mantissa;
                Bytes.unpack(value, newBuf, i * 4, 4, littleEndian);
            }
            System.arraycopy(newBuf, 0, samples, 0, newBuf.length);
        }
    }

    public static byte[] interleaveSamples(IFD ifd, byte[] samples, int numberOfPixels) throws FormatException {
        return interleaveSamples(samples, ifd.getSamplesPerPixel(), getBytesPerSampleBasedOnType(ifd), numberOfPixels);
    }

    public static byte[] interleaveSamples(
            byte[] samples,
            int samplesPerPixel,
            int bytesPerSample,
            int numberOfPixels) {
        Objects.requireNonNull(samples, "Null samples");
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        if (samplesPerPixel <= 0) {
            throw new IllegalArgumentException("Zero or negative samplesPerPixel = " + samplesPerPixel);
        }
        if (bytesPerSample <= 0) {
            throw new IllegalArgumentException("Zero or negative bytesPerSample = " + bytesPerSample);
        }
        if (samplesPerPixel == 1) {
            return samples;
        }
        final int bandSize = numberOfPixels * bytesPerSample;
        final byte[] interleavedBytes = new byte[samples.length];
        if (bytesPerSample == 1) {
            // optimization
            for (int i = 0, disp = 0; i < bandSize; i++) {
                for (int bandDisp = i, j = 0; j < samplesPerPixel; j++, bandDisp += bandSize) {
                    // note: we must check j, not bandDisp, because "bandDisp += bandSize" can lead to overflow
                    interleavedBytes[disp++] = samples[bandDisp];
                }
            }
        } else {
            for (int i = 0, disp = 0; i < bandSize; i += bytesPerSample) {
                for (int bandDisp = i, j = 0; j < samplesPerPixel; j++, bandDisp += bandSize) {
                    for (int k = 0; k < bytesPerSample; k++) {
                        interleavedBytes[disp++] = samples[bandDisp + k];
                    }
                }
            }
        }
        return interleavedBytes;
    }

    private static FileLocation existingFileToLocation(Path file) throws FileNotFoundException {
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("File " + file
                    + (Files.exists(file) ? " is not a regular file" : " does not exist"));
        }
        return new FileLocation(file.toFile());
    }

    // A clone of starting code of super.getIFDValue
    private boolean prepareReadingIFD(TiffIFDEntry entry) throws IOException {
        final long offset = entry.getValueOffset();
        final var in = getStream();
        if (offset >= in.length()) {
            return false;
        }

        if (offset != in.offset()) {
            in.seek(offset);
        }
        return true;
    }

    private void debugOptimization(long[] optimized, TiffIFDEntry entry) throws IOException {
        if (DEBUG_OPTIMIZATION) {
            final Object ifdValue = super.getIFDValue(entry);
            if (!(ifdValue instanceof long[] longs)) {
                throw new AssertionError("Unexpected type of getIFDValue result");
            }
            if (longs.length != optimized.length) {
                throw new AssertionError("Invalid length: " + optimized.length
                        + " instead of " + longs.length);
            }
            for (int i = 0; i < longs.length; i++) {
                if (optimized[i] != longs[i]) {
                    throw new AssertionError("Different element #" + i + ": 0x"
                            + Long.toHexString(optimized[i]) + " instead of " + Long.toHexString(longs[i]));
                }
            }
            LOG.log(System.Logger.Level.INFO,
                    () -> String.format(Locale.US, "Testing %s: all O'k", entry));
        }
    }

    private static void getLongs(IntBuffer buffer, long[] dest) {
        for (int i = 0; i < dest.length; i++) {
            dest[i] = buffer.get();
        }
    }

    private void readTiles(IFD ifd, byte[] buf, int fromX, int fromY, int sizeX, int sizeY)
            throws FormatException, IOException {
        assert fromX >= 0 && fromY >= 0 && sizeX >= 0 && sizeY >= 0;
        Objects.requireNonNull(ifd, "Null IFD");
        // Note: we cannot process image larger than 2^31 x 2^31 pixels,
        // though TIFF supports maximal sizes 2^32 x 2^32
        // (IFD.getImageWidth/getImageLength do not allow so large results)

        ExtendedIFD eifd = ExtendedIFD.extend(ifd);
        //!! - temporary solution (while ExtendedIFD methods are not added into IFD class itself)
        final int tileSizeX = eifd.getTileSizeX();
        final int tileSizeY = eifd.getTileSizeY();
        final int numTileCols = eifd.getTilesPerRow(tileSizeX);
        final int numTileRows = eifd.getTilesPerColumn(tileSizeY);
        // - If the image is not really tiled, we will work with full image size.
        // (Old SCIFIO code used the height of requested area instead of the full image height, as getTileHeight(),
        // but it leads to the same results in the algorithm below.)
        if (tileSizeX <= 0 || tileSizeY <= 0) {
            // - strange case (probably zero-size image); in any case a tile with zero sizes cannot intersect anything
            return;
        }

        final int bytesPerSample = getBytesPerSampleBasedOnBits(ifd);
        final int channelsInSeparated, channelsUsual;
        if (eifd.isPlanarSeparated()) {
            // - it is a very rare case PlanarConfiguration=2 (RRR...GGG...BBB...);
            // we have (for 3 channels) only 1 effective channel instead of 3,
            // but we have 3 * numTileRows effective rows
            channelsInSeparated = ifd.getSamplesPerPixel();
            if ((long) channelsInSeparated * (long) numTileRows > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Too large image: number of tiles in column " + numTileCols
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
        final int maxCol = Math.min(numTileCols - 1, (fromX + sizeX - 1) / tileSizeX);
        final int maxRow = Math.min(numTileRows - 1, (fromY + sizeY - 1) / tileSizeY);
        assert minRow <= maxRow && minCol <= maxCol;
        final int tileRowSizeInBytes = tileSizeX * bytesPerSample;
        final int outputRowSizeInBytes = sizeX * bytesPerSample;

        byte[] tileBuffer = null;

        /*
        final IntRect imageBounds = new IntRect(fromX, fromY, sizeX, sizeY);
        IntRect tileBounds = new IntRect(0, 0, (int) tileSizeX, (int) tileSizeY);
        for (int cs = 0, effectiveRow = 0; cs < channelsInSeparated; cs++) {
            // - in a rare case PlanarConfiguration=2 (RRR...GGG...BBB...),
            // we have (for 3 channels) 3 * numTileRows effective rows instead of numTileRows
            effectiveRow = cs * numTileRows;
            for (int row = 0; row < numTileRows; row++, effectiveRow++) {
                for (int column = 0; column < numTileCols; column++) {
                    tileBounds.x = column * (int) tileSizeX;
                    tileBounds.y = row * (int) tileSizeY;

                    if (!imageBounds.intersects(tileBounds)) {
                        continue;
                    }
                    tileBuffer = getTile(ifd, tileBuffer, effectiveRow, column);

                    final int tileX = Math.max(tileBounds.x, fromX);
                    final int tileY = Math.max(tileBounds.y, fromY);
                    final int xRem = tileX % tileSizeX;
                    final int yRem = tileY % tileSizeY;
                    int xDiff = tileX - fromX;
                    int yDiff = tileY - fromY;

                    final int partWidth = Math.min(toX - tileX, tileSizeX - xRem);
                    assert partWidth > 0 : "partWidth=" + partWidth;
                    final int partHeight = Math.min(toY - tileY, tileSizeY - yRem);
                    assert partHeight > 0 : "partHeight=" + partHeight;

                    final int partWidthInBytes = partWidth * bytesPerSample;

                    for (int cu = 0; cu < channelsUsual; cu++) {
                        int srcOfs = (((cu * tileSizeY) + yRem) * tileSizeX + xRem) * bytesPerSample;
                        int destOfs = (((cu + cs) * sizeY + yDiff) * sizeX + xDiff) * bytesPerSample;
                        for (int tileRow = 0; tileRow < partHeight; tileRow++) {
                            if (tileBuffer != null) {
                                System.arraycopy(tileBuffer, srcOfs, buf, destOfs, partWidthInBytes);
                            }
                            // Deprecated solution (but may be useful in future):
                            // if (needToFillOpaque) Arrays.fill(opaque, opaqueOfs, opaqueOfs + partWidth, true);
                            srcOfs += tileRowSizeInBytes;
                            destOfs += outputRowSizeInBytes;
                            // opaqueOfs += sizeX;
                        }
                    }
                }
            }
        }
        */

        for (int cs = 0; cs < channelsInSeparated; cs++) {
            // - in a rare case PlanarConfiguration=2 (RRR...GGG...BBB...),
            // we have (for 3 channels) 3 * numTileRows effective rows instead of numTileRows
            int effectiveRow = cs * numTileRows + minRow;
            for (int row = minRow; row <= maxRow; row++, effectiveRow++) {
                for (int col = minCol; col <= maxCol; col++) {
                    tileBuffer = getTile(ifd, tileBuffer, effectiveRow, col);

                    final int tileStartX = Math.max(col * tileSizeX, fromX);
                    final int tileStartY = Math.max(row * tileSizeY, fromY);
                    final int xInTile = tileStartX % tileSizeX;
                    final int yInTile = tileStartY % tileSizeY;
                    final int xDiff = tileStartX - fromX;
                    final int yDiff = tileStartY - fromY;

                    final int partSizeX = Math.min(toX - tileStartX, tileSizeX - xInTile);
                    assert partSizeX > 0 : "partSizeX=" + partSizeX;
                    final int partSizeY = Math.min(toY - tileStartY, tileSizeY - yInTile);
                    assert partSizeY > 0 : "partSizeY=" + partSizeY;

                    final int partWidthInBytes = partSizeX * bytesPerSample;

                    for (int cu = 0; cu < channelsUsual; cu++) {
                        int srcOfs = (((cu * tileSizeY) + yInTile) * tileSizeX + xInTile) * bytesPerSample;
                        int destOfs = (((cu + cs) * sizeY + yDiff) * sizeX + xDiff) * bytesPerSample;
                        for (int tileRow = 0; tileRow < partSizeY; tileRow++) {
                            if (tileBuffer != null) {
                                System.arraycopy(tileBuffer, srcOfs, buf, destOfs, partWidthInBytes);
                            }
                            srcOfs += tileRowSizeInBytes;
                            destOfs += outputRowSizeInBytes;
                        }
                    }
                }
            }
        }
    }


    private static final byte[] REVERSE = {0x00, -0x80, 0x40, -0x40, 0x20, -0x60,
            0x60, -0x20, 0x10, -0x70, 0x50, -0x30, 0x30, -0x50, 0x70, -0x10, 0x08,
            -0x78, 0x48, -0x38, 0x28, -0x58, 0x68, -0x18, 0x18, -0x68, 0x58, -0x28,
            0x38, -0x48, 0x78, -0x08, 0x04, -0x7c, 0x44, -0x3c, 0x24, -0x5c, 0x64,
            -0x1c, 0x14, -0x6c, 0x54, -0x2c, 0x34, -0x4c, 0x74, -0x0c, 0x0c, -0x74,
            0x4c, -0x34, 0x2c, -0x54, 0x6c, -0x14, 0x1c, -0x64, 0x5c, -0x24, 0x3c,
            -0x44, 0x7c, -0x04, 0x02, -0x7e, 0x42, -0x3e, 0x22, -0x5e, 0x62, -0x1e,
            0x12, -0x6e, 0x52, -0x2e, 0x32, -0x4e, 0x72, -0x0e, 0x0a, -0x76, 0x4a,
            -0x36, 0x2a, -0x56, 0x6a, -0x16, 0x1a, -0x66, 0x5a, -0x26, 0x3a, -0x46,
            0x7a, -0x06, 0x06, -0x7a, 0x46, -0x3a, 0x26, -0x5a, 0x66, -0x1a, 0x16,
            -0x6a, 0x56, -0x2a, 0x36, -0x4a, 0x76, -0x0a, 0x0e, -0x72, 0x4e, -0x32,
            0x2e, -0x52, 0x6e, -0x12, 0x1e, -0x62, 0x5e, -0x22, 0x3e, -0x42, 0x7e,
            -0x02, 0x01, -0x7f, 0x41, -0x3f, 0x21, -0x5f, 0x61, -0x1f, 0x11, -0x6f,
            0x51, -0x2f, 0x31, -0x4f, 0x71, -0x0f, 0x09, -0x77, 0x49, -0x37, 0x29,
            -0x57, 0x69, -0x17, 0x19, -0x67, 0x59, -0x27, 0x39, -0x47, 0x79, -0x07,
            0x05, -0x7b, 0x45, -0x3b, 0x25, -0x5b, 0x65, -0x1b, 0x15, -0x6b, 0x55,
            -0x2b, 0x35, -0x4b, 0x75, -0x0b, 0x0d, -0x73, 0x4d, -0x33, 0x2d, -0x53,
            0x6d, -0x13, 0x1d, -0x63, 0x5d, -0x23, 0x3d, -0x43, 0x7d, -0x03, 0x03,
            -0x7d, 0x43, -0x3d, 0x23, -0x5d, 0x63, -0x1d, 0x13, -0x6d, 0x53, -0x2d,
            0x33, -0x4d, 0x73, -0x0d, 0x0b, -0x75, 0x4b, -0x35, 0x2b, -0x55, 0x6b,
            -0x15, 0x1b, -0x65, 0x5b, -0x25, 0x3b, -0x45, 0x7b, -0x05, 0x07, -0x79,
            0x47, -0x39, 0x27, -0x59, 0x67, -0x19, 0x17, -0x69, 0x57, -0x29, 0x37,
            -0x49, 0x77, -0x09, 0x0f, -0x71, 0x4f, -0x31, 0x2f, -0x51, 0x6f, -0x11,
            0x1f, -0x61, 0x5f, -0x21, 0x3f, -0x41, 0x7f, -0x01};

    //!! Exact copy of the private method in TiffParser.
    //!! Should be declared "public static" to allow implement our own getSamples
    //!! on the base of getTile.
    public static void adjustFillOrder(final IFD ifd, final byte[] buf)
            throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (ifd.getFillOrder() == FillOrder.REVERSED) {
            // swap bit order of all bytes
            for (int i = 0; i < buf.length; i++) {
                buf[i] = REVERSE[buf[i] & 0xFF];
            }
        }
    }

    private static void checkRequestedArea(long fromX, long fromY, long sizeX, long sizeY) {
        if (fromX < 0 || fromY < 0) {
            throw new IllegalArgumentException("Negative fromX = " + fromX + " or fromY = " + fromY);
        }
        if (sizeX > Integer.MAX_VALUE - fromX || sizeY > Integer.MAX_VALUE - fromY) {
            throw new IllegalArgumentException("Requested area [" + fromX + ".." + (fromX + sizeX - 1) +
                    " x " + fromY + ".." + (fromY + sizeY - 1) + " is out of 0..2^31-1 ranges");
        }
    }

    private static void checkDifferentBytesPerSample(IFD ifd, int[] bytesPerSample) throws FormatException {
        for (int k = 1; k < bytesPerSample.length; k++) {
            if (bytesPerSample[k] != bytesPerSample[0]) {
                throw new FormatException("Unsupported TIFF IFD: different number of bytes per samples: " +
                        Arrays.toString(bytesPerSample) + ", based on the following number of bits: " +
                        Arrays.toString(ifd.getBitsPerSample()));
            }
            // - note that LibTiff does not support different BitsPerSample values for different components;
            // we do not support different number of BYTES for different components
        }
    }

    private static int checkedMul(
            long v1, long v2, long v3, long v4,
            String n1, String n2, String n3, String n4,
            Supplier<String> prefix,
            Supplier<String> postfix) {
        return checkedMul(new long[]{v1, v2, v3, v4}, new String[]{n1, n2, n3, n4}, prefix, postfix);
    }

    private static int checkedMul(
            long[] values,
            String[] names,
            Supplier<String> prefix,
            Supplier<String> postfix) {
        Objects.requireNonNull(values);
        Objects.requireNonNull(prefix);
        Objects.requireNonNull(postfix);
        Objects.requireNonNull(names);
        if (values.length == 0) {
            return 1;
        }
        long result = 1L;
        double product = 1.0;
        boolean overflow = false;
        for (int i = 0; i < values.length; i++) {
            long m = values[i];
            if (m < 0) {
                throw new IllegalArgumentException(prefix.get() + "negative " + names[i] + " = " + m + postfix.get());
            }
            result *= m;
            product *= m;
            if (result > Integer.MAX_VALUE) {
                overflow = true;
                // - we just indicate this, but still calculate the floating-point product
            }
        }
        if (overflow) {
            throw new IllegalArgumentException(prefix.get() + "too large " + String.join(" * ", names) +
                    " = " + Arrays.stream(values).mapToObj(String::valueOf).collect(
                    Collectors.joining(" * ")) +
                    " = " + product + " >= 2^31" + postfix.get());
        }
        return (int) result;
    }

    private static boolean getBooleanProperty(String propertyName) {
        try {
            return Boolean.getBoolean(propertyName);
        } catch (Exception e) {
            return false;
        }
    }
}
