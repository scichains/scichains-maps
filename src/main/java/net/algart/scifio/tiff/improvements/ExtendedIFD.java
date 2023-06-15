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
import io.scif.formats.tiff.FillOrder;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDType;
import io.scif.util.FormatTools;
import org.scijava.log.LogService;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

//!! Better analog of IFD (should be merged with the main IFD)
public class ExtendedIFD extends IFD {
    public static final int ICC_PROFILE = 34675;
    public static final int MATTEING = 32995;
    public static final int DATA_TYPE = 32996;
    public static final int IMAGE_DEPTH = 32997;
    public static final int TILE_DEPTH = 32998;
    public static final int STO_NITS = 37439;

    private final Long offset;
    private Map<Integer, IFDType> types = null;
    //!! - provides additional information like IFDType for each entry

    public ExtendedIFD(IFD ifd) {
        this(ifd, null);
    }

    public ExtendedIFD(IFD ifd, LogService log) {
        super(ifd, log);
        offset = null;
        types = null;
    }

    public ExtendedIFD(ExtendedIFD ifd, LogService log) {
        super(ifd, log);
        offset = ifd.offset;
        types = ifd.types;
    }

    public ExtendedIFD(LogService log, long offset) {
        super(log);
        this.offset = offset;
    }

    public static ExtendedIFD extend(IFD ifd) {
        Objects.requireNonNull(ifd, "Null IFD");
        return ifd instanceof ExtendedIFD extendedIFD ? extendedIFD : new ExtendedIFD(ifd, null);
    }

    /**
     * Contiguous (chunked) samples format (PlanarConfiguration), for example: RGBRGBRGB....
     */
    public static final int PLANAR_CONFIG_CONTIGUOUSLY_CHUNKED = 1;

    /**
     * Planar samples format (PlanarConfiguration), for example: RRR...GGG...BBB...
     * Note: the specification adds a warning that PlanarConfiguration=2 is not in widespread use and
     * that Baseline TIFF readers are not required to support it.
     */
    public static final int PLANAR_CONFIG_SEPARATE = 2;

    public static final int SAMPLE_FORMAT_UINT = 1;
    public static final int SAMPLE_FORMAT_INT = 2;
    public static final int SAMPLE_FORMAT_IEEEFP = 3;
    public static final int SAMPLE_FORMAT_VOID = 4;
    public static final int SAMPLE_FORMAT_COMPLEX_INT = 5;
    public static final int SAMPLE_FORMAT_COMPLEX_IEEEFP = 6;

    public Long getOffset() {
        return offset;
    }

    public ExtendedIFD setTypes(Map<Integer, IFDType> types) {
        this.types = Objects.requireNonNull(types, "Null types");
        return this;
    }

    public Map<Integer, IFDType> getTypes() {
        return types;
    }

    // Usually false: PlanarConfiguration=2 is not in widespread use
    public boolean isPlanarSeparated() throws FormatException {
        return getPlanarConfiguration() == PLANAR_CONFIG_SEPARATE;
    }

    // Usually true: PlanarConfiguration=2 is not in widespread use
    public boolean isContiguouslyChunked() throws FormatException {
        return getPlanarConfiguration() == PLANAR_CONFIG_CONTIGUOUSLY_CHUNKED;
    }

    public boolean isReversedBits() throws FormatException {
        return getFillOrder() == FillOrder.REVERSED;
    }

    //!! Better analog of IFD.getTileWidth()
    public int getTileSizeX() throws FormatException {
        final long tileWidth = getIFDLongValue(IFD.TILE_WIDTH, 0);
        if (tileWidth < 0) {
            throw new IllegalArgumentException("Negative tile width = " + tileWidth);
            // - impossible in a correct TIFF
        }
        if (tileWidth > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Very large tile width " + tileWidth + " >= 2^31 is not supported");
            // - TIFF allows to use values <= 2^32-1, but in any case we cannot allocate Java array for such tile
        }
        if (tileWidth != 0) {
            return (int) tileWidth;
        }
        final long imageWidth = getImageWidth();
        if (imageWidth < 0) {
            throw new IllegalArgumentException("Negative image width = " + imageWidth);
            // - impossible in a correct TIFF
        }
        assert imageWidth <= Integer.MAX_VALUE : "getImageWidth() did not check 31-bit result";
        return (int) imageWidth;
    }

    //!! Better analog of IFD.getTileLength()
    public int getTileSizeY() throws FormatException {
        final long tileLength = getIFDLongValue(IFD.TILE_LENGTH, 0);
        if (tileLength < 0) {
            throw new IllegalArgumentException("Negative tile height = " + tileLength);
            // - impossible in a correct TIFF
        }
        if (tileLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Very large tile height " + tileLength + " >= 2^31 is not supported");
            // - TIFF allows to use values <= 2^32-1, but in any case we cannot allocate Java array for such tile
        }
        if (tileLength != 0) {
            return (int) tileLength;
        }
        final long imageLength = getImageLength();
        if (imageLength < 0) {
            // - we should check this before calling getRowsPerStrip()
            // (because current version of getRowsPerStrip() does not check negative imageLength)
            throw new IllegalArgumentException("Negative image height = " + imageLength);
            // - impossible in a correct TIFF
        }
        assert imageLength <= Integer.MAX_VALUE : "getImageLength() did not check 31-bit result";
        final long rowsPerStrip = getRowsPerStrip()[0];
        if (rowsPerStrip < 0) {
            throw new IllegalArgumentException("Negative rows per strip = " + rowsPerStrip);
            // - impossible in a correct TIFF
        }
        if (rowsPerStrip > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Very large number of rows per strip " +
                    rowsPerStrip + " >= 2^31 is not supported");
            // - TIFF allows to use values <= 2^32-1, but in any case we cannot allocate Java array for such tile
        }
        if (rowsPerStrip != 0) {
            return (int) rowsPerStrip;
        }
        return (int) imageLength;
        // - unlike old SCIFIO getTileLength, we do not return 0 if rowsPerStrip==0:
        // it allows to avoid additional checks in a calling code
    }

    //!! Better analog of IFD.getTilesPerRow() (but it makes sense to change result type to "int")
    @Override
    public long getTilesPerRow() throws FormatException {
        return getTilesPerRow(getTileSizeX());
    }

    public int getTilesPerRow(int tileSizeX) throws FormatException {
        if (tileSizeX < 0) {
            throw new IllegalArgumentException("Negative tile width = " + tileSizeX);
        }
        final long imageWidth = getImageWidth();
        if (imageWidth < 0) {
            throw new IllegalArgumentException("Negative image width = " + imageWidth);
        }
        assert imageWidth <= Integer.MAX_VALUE : "getImageWidth() did not check 31-bit result";
        if (tileSizeX == 0) {
            return 0;
            // - it is better than throwing exception in this case (probably it is result
            // of getTileWidth() logic in a strange case getImageWidth()==0)
        }
        final long n = (imageWidth + (long) tileSizeX - 1) / tileSizeX;
        assert n <= Integer.MAX_VALUE : "ceil(" + imageWidth + "/" + tileSizeX + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    //!! Better analog of IFD.getTilesPerColumn() (but it makes sense to change result type to "int")
    @Override
    public long getTilesPerColumn() throws FormatException {
        return getTilesPerColumn(getTileSizeY());
    }

    public int getTilesPerColumn(int tileSizeY) throws FormatException {
        if (tileSizeY < 0) {
            throw new IllegalArgumentException("Negative tile height = " + tileSizeY);
        }
        final long imageLength = getImageLength();
        if (imageLength < 0) {
            throw new IllegalArgumentException("Negative image height = " + imageLength);
        }
        assert imageLength <= Integer.MAX_VALUE : "getImageLength() did not check 31-bit result";
        if (tileSizeY == 0) {
            return 0;
            // - it is better than throwing exception in this case (probably it is result
            // of getTileLength() logic in a strange case getImageLength()==0 or getRowsPerStrip()=={0,0,...})
        }
        final long n = (imageLength + (long) tileSizeY - 1) / tileSizeY;
        assert n <= Integer.MAX_VALUE : "ceil(" + imageLength + "/" + tileSizeY + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    public int getBytesPerSampleBasedOnType() throws FormatException {
        final int pixelType = getPixelType();
        return FormatTools.getBytesPerPixel(pixelType);
    }

    public int getBytesPerSampleBasedOnBits() throws FormatException {
        final int[] bytesPerSample = getBytesPerSample();
        final int result = bytesPerSample[0];
        // - for example, if we have 5 bits R + 6 bits G + 4 bits B, it will be ceil(6/8) = 1 byte;
        // usually the same for all components
        checkDifferentBytesPerSample(bytesPerSample);
        return result;
    }

    /**
     * Checks that the sizes of this IFD (ImageWidth and ImageLength) are positive integers
     * in range <tt>1..Integer.MAX_VALUE</tt>. If it is not so, throws {@link FormatException}.
     * This class does not require this condition, but it is a reasonable requirement for many applications.
     *
     * @throws FormatException if image width or height is negaitve or <tt>&gt;Integer.MAX_VALUE</tt>.
     */
    public void checkSizesArePositive() throws FormatException {
        long dimX = getImageWidth();
        long dimY = getImageLength();
        if (dimX <= 0 || dimY <= 0) {
            throw new FormatException("Zero or negative IFD image sizes " + dimX + "x" + dimY + " are not allowed");
            // - important for some classes, processing IFD images, that cannot work with zero-size areas
            // (for example, due to usage of AlgART IRectangularArea)
        }
        assert dimX <= Integer.MAX_VALUE : "getImageWidth() did not check 31-bit result";
        assert dimY <= Integer.MAX_VALUE : "getImageLength() did not check 31-bit result";
    }

    public int sizeOfIFDRegion(long sizeX, long sizeY) throws FormatException {
        return checkedMul(sizeX, sizeY, getSamplesPerPixel(), getBytesPerSampleBasedOnType(),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample (type-based)",
                () -> "Invalid requested area: ", () -> "");
    }

    public int sizeOfIFDRegionBasedOnBits(long sizeX, long sizeY) throws FormatException {
        return checkedMul(sizeX, sizeY, getSamplesPerPixel(), getBytesPerSampleBasedOnBits(),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample",
                () -> "Invalid requested area: ", () -> "");
    }

    public int sizeOfIFDTileBasedOnBits() throws FormatException {
        final int channels = isPlanarSeparated() ? 1 : getSamplesPerPixel();
        // - if separate (RRR...GGG...BBB...),
        // we have (for 3 channels) only 1 channel instead of 3, but number of tiles is greater:
        // 3 * numTileRows effective rows of tiles instead of numTileRows
        return checkedMul(getTileWidth(), getTileLength(), channels, getBytesPerSampleBasedOnBits(),
                "tile width", "tile height", "effective number of channels", "bytes per sample",
                () -> "Invalid TIFF tile sizes: ", () -> "");
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

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("IFD");
        try {
            final long imageWidth = getImageWidth();
            final long imageLength = getImageLength();
            final int tileSizeX = getTileSizeX();
            final int tileSizeY = getTileSizeY();
            final long tilesPerRow = (imageWidth + (long) tileSizeX - 1) / tileSizeX;
            final long tilesPerColumn = (imageLength + (long) tileSizeY - 1) / tileSizeY;
            sb.append(" %s[%dx%d], ".formatted(
                    javaElementType(getPixelType()).getSimpleName(),
                    imageWidth, imageLength));
            if (isTiled()) {
                sb.append("%dx%d=%d tiles %dx%d (last tile %sx%s)".formatted(
                        tilesPerRow,
                        tilesPerColumn,
                        tilesPerRow * tilesPerColumn,
                        tileSizeX,
                        tileSizeY,
                        remainderToString(imageWidth, tileSizeX),
                        remainderToString(imageLength, tileSizeY)));
            } else {
                sb.append("%d strips per %d lines (last strip %s, virtual \"tiles\" %dx%d)".formatted(
                        tilesPerColumn,
                        tileSizeY,
                        imageLength == tilesPerColumn * tileSizeY ?
                                "full" :
                                remainderToString(imageLength, tileSizeY) + " lines",
                        tileSizeX,
                        tileSizeY));
            }
        } catch (Exception e) {
            sb.append(" [cannot detect sizes information: ").append(e.getMessage()).append("]");
        }
        try {
            sb.append(isContiguouslyChunked() ? ", chunked" : ", planar");
        } catch (Exception e) {
            sb.append(" [").append(e.getMessage()).append("]");
        }
        if (offset != null) {
            sb.append(" (offset %d=0x%X)".formatted(offset, offset));
        }
        final Map<Integer, IFDType> types = this.types;
        final Map<Integer, Object> sortedIFD = new TreeMap<>(this);
        for (Map.Entry<Integer, Object> entry : sortedIFD.entrySet()) {
            sb.append(String.format("%n"));
            final Integer tag = entry.getKey();
            final Object v = entry.getValue();
            Object additional = null;
            try {
                switch (tag) {
                    case IFD.PHOTOMETRIC_INTERPRETATION -> additional = getPhotometricInterpretation();
                    case IFD.COMPRESSION -> additional = getCompression();
                    case IFD.PLANAR_CONFIGURATION -> {
                        if (v instanceof Number number) {
                            switch (number.intValue()) {
                                case PLANAR_CONFIG_CONTIGUOUSLY_CHUNKED -> additional = "chunky";
                                case PLANAR_CONFIG_SEPARATE -> additional = "planar";
                            }
                        }
                    }
                    case IFD.SAMPLE_FORMAT -> {
                        if (v instanceof Number number) {
                            switch (number.intValue()) {
                                case SAMPLE_FORMAT_UINT -> additional = "unsigned integer";
                                case SAMPLE_FORMAT_INT -> additional = "signed integer";
                                case SAMPLE_FORMAT_IEEEFP -> additional = "IEEE float";
                                case SAMPLE_FORMAT_VOID -> additional = "undefined";
                                case SAMPLE_FORMAT_COMPLEX_INT -> additional = "complex integer";
                                case SAMPLE_FORMAT_COMPLEX_IEEEFP -> additional = "complex float";
                            }
                        }
                    }
                }
            } catch (Exception e) {
                additional = e;
            }
            sb.append("    ").append(ifdTagName(tag)).append(" = ");
            if (v != null && v.getClass().isArray()) {
                final int len = Array.getLength(v);
                sb.append(v.getClass().getComponentType().getSimpleName()).append("[").append(len).append("]");
                sb.append(" {");
                final int limit = v instanceof byte[] || v instanceof short[] || v instanceof char[] ? 30 : 10;
                final int mask = v instanceof byte[] ? 0xFF : v instanceof short[] ? 0xFFFF : 0;
                for (int k = 0; k < len; k++) {
                    if (k >= limit) {
                        sb.append("...");
                        break;
                    }
                    if (k > 0) {
                        sb.append(", ");
                    }
                    Object o = Array.get(v, k);
                    if (mask != 0) {
                        o = ((Number) o).intValue() & mask;
                    }
                    sb.append(o);
                }
                sb.append("}");
            } else {
                sb.append(v);
            }
            if (types != null) {
                final IFDType ifdType = types.get(tag);
                if (ifdType != null) {
                    sb.append(" : ").append(ifdType);
                }
            }
            if (additional != null) {
                sb.append(" [").append(additional).append("]");
            }
        }
        return sb.toString();
    }

    /**
     * Returns user-friendly name of the given TIFF tag.
     * It is used, in particular, in {@link #toString()} function.
     *
     * @param tag entry Tag value.
     * @return user-friendly name in a style of Java constant ("BIG_TIFF" etc.)
     */
    public static String ifdTagName(int tag) {
        final String name = IFDFriendlyNames.IFD_TAG_NAMES.get(tag);
        return "%s (%d or 0x%X)".formatted(name == null ? "Unknown tag" : name, tag, tag);
    }

    private void checkDifferentBytesPerSample(int[] bytesPerSample) throws FormatException {
        for (int k = 1; k < bytesPerSample.length; k++) {
            if (bytesPerSample[k] != bytesPerSample[0]) {
                throw new FormatException("Unsupported TIFF IFD: different number of bytes per samples: " +
                        Arrays.toString(bytesPerSample) + ", based on the following number of bits: " +
                        Arrays.toString(getBitsPerSample()));
            }
            // - note that LibTiff does not support different BitsPerSample values for different components;
            // we do not support different number of BYTES for different components
        }
    }

    static int checkedMul(
            long v1, long v2, long v3, long v4,
            String n1, String n2, String n3, String n4,
            Supplier<String> prefix,
            Supplier<String> postfix) {
        return checkedMul(new long[]{v1, v2, v3, v4}, new String[]{n1, n2, n3, n4}, prefix, postfix);
    }

    static int checkedMul(
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

    private static String remainderToString(long a, long b) {
        long r = a / b;
        if (r * b == a) {
            return String.valueOf(b);
        } else {
            return String.valueOf(a - r * b);
        }
    }
}
