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
import io.scif.formats.tiff.*;
import io.scif.util.FormatTools;

import java.lang.reflect.Array;
import java.util.*;

//!! Better analog of IFD (can be merged with the main IFD)
public class DetailedIFD extends IFD {
    public static final int ICC_PROFILE = 34675;
    public static final int MATTEING = 32995;
    public static final int DATA_TYPE = 32996;
    public static final int IMAGE_DEPTH = 32997;
    public static final int TILE_DEPTH = 32998;
    public static final int STO_NITS = 37439;

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

    public static final int PREDICTOR_NONE = 1;
    public static final int PREDICTOR_HORIZONTAL = 2;
    public static final int PREDICTOR_FLOATING_POINT = 3;
    // - value 3 is not supported by TiffParser/TiffSaver

    private static final System.Logger LOG = System.getLogger(DetailedIFD.class.getName());

    private final Long offset;
    private Map<Integer, TiffIFDEntry> entries = null;
    //!! - provides additional information like IFDType for each entry
    private Integer subIFDType = null;

    private volatile long[] cachedStripByteCounts = null;
    private volatile long[] cachedStripOffsets = null;
    private volatile Integer cachedStripSizeY = null;

    public DetailedIFD(IFD ifd) {
        super(ifd, null);
        // Note: log argument is never used in this class.
        if (ifd instanceof DetailedIFD detailedIFD) {
            offset = detailedIFD.offset;
            entries = detailedIFD.entries;
            subIFDType = detailedIFD.subIFDType;
        } else {
            offset = null;
            entries = null;
            subIFDType = null;
        }
    }

    public DetailedIFD(DetailedIFD detailedIFD) {
        super(detailedIFD, null);
        // Note: log argument is never used in this class.
        offset = detailedIFD.offset;
        entries = detailedIFD.entries;
        subIFDType = detailedIFD.subIFDType;
    }

    public DetailedIFD() {
        super(null);
        // Note: log argument is never used in this class.
        this.offset = null;
    }

    public DetailedIFD(long offset) {
        super(null);
        // Note: log argument is never used in this class.
        this.offset = offset;
    }

    public static DetailedIFD extend(IFD ifd) {
        Objects.requireNonNull(ifd, "Null IFD");
        return ifd instanceof DetailedIFD detailedIFD ? detailedIFD : new DetailedIFD(ifd);
    }

    public Long getOffset() {
        return offset;
    }

    public Map<Integer, TiffIFDEntry> getEntries() {
        return entries;
    }

    public DetailedIFD setEntries(Map<Integer, TiffIFDEntry> entries) {
        Objects.requireNonNull(entries, "Null entries");
        this.entries = Collections.unmodifiableMap(entries);
        // So, the copy constructor above really creates a good copy.
        // But we cannot provide full guarantees of the ideal behaviour,
        // because this HashMap contains Java arrays and can theoretically contain anything.
        return this;
    }

    public boolean isMainIFD() {
        return subIFDType == null;
    }

    public Integer getSubIFDType() {
        return subIFDType;
    }

    public DetailedIFD setSubIFDType(Integer subIFDType) {
        this.subIFDType = subIFDType;
        return this;
    }

    // This method is overridden to check that result is positive and to avoid exception for illegal compression.
    @Override
    public int getSamplesPerPixel() throws FormatException {
        Object compressionValue = getIFDValue(IFD.COMPRESSION);
        if (compressionValue != null && compressionValue.equals(TiffCompression.OLD_JPEG.getCode())) {
            return 3; // always RGB
        }
        final int samplesPerPixel = getIFDIntValue(SAMPLES_PER_PIXEL, 1);
        if (samplesPerPixel < 1) {
            throw new FormatException("Illegal SamplesPerPixel = " + samplesPerPixel);
        }
        return samplesPerPixel;
    }

    // This method is overridden for removing usage of log field
    @Override
    public int[] getBitsPerSample() throws FormatException {
        int[] bitsPerSample = getIFDIntArray(BITS_PER_SAMPLE);
        if (bitsPerSample == null) {
            bitsPerSample = new int[]{1};
        }

        final int samplesPerPixel = getSamplesPerPixel();
        if (bitsPerSample.length < samplesPerPixel) {
            // - Result must contain at least samplesPerPixel elements (SCIFIO agreement)
            final int bits = bitsPerSample[0];
            bitsPerSample = new int[samplesPerPixel];
            Arrays.fill(bitsPerSample, bits);
        }
        for (int i = 0; i < samplesPerPixel; i++) {
            if (bitsPerSample[i] < 1) {
                throw new FormatException("Illegal BitsPerSample[" + i + "] = " + bitsPerSample[i]);
            }
        }
        return bitsPerSample;
    }


    // This method is overridden to remove extra support of absence of StripByteCounts
    // and to remove extra doubling result for LZW (may lead to a bug)
    @Override
    public long[] getStripByteCounts() throws FormatException {
        final boolean tiled = isTiled();
        final int tag = tiled ? TILE_BYTE_COUNTS : STRIP_BYTE_COUNTS;
        long[] counts = getIFDLongArray(tag);
        if (tiled && counts == null) {
            counts = getIFDLongArray(STRIP_BYTE_COUNTS);
            // - rare situation, when tile byte counts is actually stored in StripByteCounts
        }
        if (counts == null) {
            throw new FormatException("Invalid IFD: no required StripByteCounts/TileByteCounts tag");
        }
        if (tiled) {
            return counts;
        }

        final long rowsPerStrip = getRowsPerStrip()[0];
        assert rowsPerStrip <= Integer.MAX_VALUE :
                "getImageLength() inside getRowsPerStrip() did not check that result is 31-bit";
        long numberOfStrips = (getImageLength() + rowsPerStrip - 1) / rowsPerStrip;
        if (getPlanarConfiguration() == PLANAR_CONFIG_SEPARATE) {
            numberOfStrips *= getSamplesPerPixel();
        }

        if (counts.length < numberOfStrips) {
            throw new FormatException("StripByteCounts/TileByteCounts length (" + counts.length +
                    ") does not match expected number of strips/tiles (" + numberOfStrips + ")");
        }
        return counts;
    }

    public long[] cachedStripByteCounts() throws FormatException {
        long[] result = this.cachedStripByteCounts;
        if (result == null) {
            this.cachedStripByteCounts = result = getStripByteCounts();
        }
        return result;
    }

    public int cachedStripByteCount(int index) throws FormatException {
        long[] byteCounts = cachedStripByteCounts();
        if (index < 0) {
            throw new IllegalArgumentException("Negative index = " + index);
        }
        if (index >= byteCounts.length) {
            throw new FormatException((isTiled() ?
                    "Tile index is too big for TileByteCounts" :
                    "Strip index is too big for StripByteCounts") +
                    "array: it contains only " + byteCounts.length + " elements");
        }
        long result = byteCounts[index];
        if (result < 0) {
            throw new FormatException(
                    "Negative value " + result + " in " +
                            (isTiled() ? "TileByteCounts" : "StripByteCounts") + " array");
        }
        if (result > Integer.MAX_VALUE) {
            throw new FormatException("Too large tile/strip #" + index + ": " + result + " bytes > 2^31-1");
        }
        return (int) result;
    }

    @Override
    public long[] getStripOffsets() throws FormatException {
        // Maybe will be re-written in future
        return super.getStripOffsets();
    }

    public long[] cachedStripOffsets() throws FormatException {
        long[] result = this.cachedStripOffsets;
        if (result == null) {
            this.cachedStripOffsets = result = getStripOffsets();
        }
        return result;
    }

    public long cachedStripOffset(int index) throws FormatException {
        long[] offsets = cachedStripOffsets();
        if (index < 0) {
            throw new IllegalArgumentException("Negative index = " + index);
        }
        if (index >= offsets.length) {
            throw new FormatException((isTiled() ?
                    "Tile index is too big for TileOffsets" :
                    "Strip index is too big for StripOffsets") +
                    "array: it contains only " + offsets.length + " elements");
        }
        final long result = offsets[index];
        if (result < 0) {
            throw new FormatException(
                    "Negative value " + result + " in " + (isTiled() ? "TileOffsets" : "StripOffsets") + " array");
        }
        return result;
    }

    public int cachedStripSizeY() throws FormatException {
        Integer result = this.cachedStripSizeY;
        if (result == null) {
            this.cachedStripSizeY = result = getStripSizeY();
        }
        return result;
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

    public long getImageWidth() throws FormatException {
        return getImageSizeX();
    }

    public long getImageLength() throws FormatException {
        return getImageSizeY();
    }

    //!! Better analog of IFD.getImageWidth()
    public int getImageSizeX() throws FormatException {
        final long imageWidth = getIFDLongValue(IMAGE_WIDTH);
        if (imageWidth <= 0) {
            throw new FormatException("Zero or negative image width = " + imageWidth);
            // - impossible in a correct TIFF
        }
        if (imageWidth > Integer.MAX_VALUE) {
            throw new FormatException("Very large image width " + imageWidth + " >= 2^31 is not supported");
        }
        return (int) imageWidth;
    }

    //!! Better analog of IFD.getImageLength()
    public int getImageSizeY() throws FormatException {
        final long imageLength = getIFDLongValue(IMAGE_LENGTH);
        if (imageLength <= 0) {
            throw new FormatException("Zero or negative image height = " + imageLength);
            // - impossible in a correct TIFF
        }
        if (imageLength > Integer.MAX_VALUE) {
            throw new FormatException("Very large image height " + imageLength + " >= 2^31 is not supported");
        }
        return (int) imageLength;
    }

    //!! Better analog of getRowsPerStrip()
    public int getStripSizeY() throws FormatException {
        final long[] rowsPerStrip = getIFDLongArray(ROWS_PER_STRIP);
        final int imageSizeY = getImageSizeY();
        if (rowsPerStrip == null || rowsPerStrip.length == 0) {
            // - zero rowsPerStrip.length is possible only as a result of manual modification of this IFD
            return imageSizeY;
        }

        // rowsPerStrip should never be more than the total number of rows
        for (int i = 0; i < rowsPerStrip.length; i++) {
            int rows = (int) Math.min(rowsPerStrip[i], imageSizeY);
            if (rows <= 0) {
                throw new FormatException("Zero or negative RowsPerStrip[" + i + "] = " + rows);
            }
            rowsPerStrip[i] = rows;
        }
        final int result = (int) rowsPerStrip[0];
        assert result > 0 : result + " was not checked in the loop above?";
        for (int i = 1; i < rowsPerStrip.length; i++) {
            if (result != rowsPerStrip[i]) {
                throw new FormatException("Non-uniform RowsPerStrip is not supported");
            }
        }
        return result;
    }

    //!! Better analog of IFD.getTileWidth()
    public int getTileSizeX() throws FormatException {
        final long tileWidth = getIFDLongValue(IFD.TILE_WIDTH, 0);
        if (tileWidth < 0) {
            throw new FormatException("Negative tile width = " + tileWidth);
            // - impossible in a correct TIFF
        }
        if (tileWidth > Integer.MAX_VALUE) {
            throw new FormatException("Very large tile width " + tileWidth + " >= 2^31 is not supported");
            // - TIFF allows to use values <= 2^32-1, but in any case we cannot allocate Java array for such tile
        }
        if (tileWidth != 0) {
            return (int) tileWidth;
        }
        final long imageWidth = getImageWidth();
        assert imageWidth <= Integer.MAX_VALUE : "getImageWidth() did not check 31-bit result";
        return (int) imageWidth;
    }

    //!! Better analog of IFD.getTileLength()
    public int getTileSizeY() throws FormatException {
        final long tileLength = getIFDLongValue(IFD.TILE_LENGTH, 0);
        if (tileLength < 0) {
            throw new FormatException("Negative tile height = " + tileLength);
            // - impossible in a correct TIFF
        }
        if (tileLength > Integer.MAX_VALUE) {
            throw new FormatException("Very large tile height " + tileLength + " >= 2^31 is not supported");
            // - TIFF allows to use values <= 2^32-1, but in any case we cannot allocate Java array for such tile
        }
        if (tileLength != 0) {
            return (int) tileLength;
        }
        final int stripSizeY = getStripSizeY();
        assert stripSizeY > 0 : "getStripSizeY() did not check non-positive result";
        return stripSizeY;
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
            throw new FormatException("Negative tile width = " + tileSizeX);
        }
        final long imageWidth = getImageWidth();
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
            throw new FormatException("Negative tile height = " + tileSizeY);
        }
        final long imageLength = getImageLength();
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
        if (result < 1) {
            throw new FormatException("Invalid format: zero or negative bytes per sample = " + result);
        }
        checkDifferentBytesPerSample(bytesPerSample);
        return result;
    }

    public long getIFDLongValue(final int tag) throws FormatException {
        final Number number = (Number) getIFDValue(tag, Number.class);
        if (number == null) {
            throw new FormatException("No tag " + tag + " in IFD");
        }
        return number.longValue();
    }

    public DetailedIFD putImageSizes(int width, int height) {
        if (width <= 0) {
            throw new IllegalArgumentException("Zero or negative image width");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("Zero or negative image height");
        }
        putIFDValue(IFD.IMAGE_WIDTH, width);
        putIFDValue(IFD.IMAGE_LENGTH, height);
        return this;
    }

    public DetailedIFD putTileSizes(int tileSizeX, int tileSizeY) {
        if (tileSizeX <= 0) {
            throw new IllegalArgumentException("Zero or negative tile X-size");
        }
        if (tileSizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative tile Y-size");
        }
        if ((tileSizeX & 15) != 0 || (tileSizeY & 15) != 0) {
            throw new IllegalArgumentException("Illegal tile sizes " + tileSizeX + "x" + tileSizeY
                    + ": they must be multiples of 16");
        }
        putIFDValue(IFD.TILE_WIDTH, tileSizeX);
        putIFDValue(IFD.TILE_LENGTH, tileSizeY);
        return this;
    }

    public DetailedIFD putCompression(TiffCompression compression) {
        if (compression == null) {
            remove(IFD.COMPRESSION);
        } else {
            putIFDValue(IFD.COMPRESSION, compression.getCode());
        }
        return this;
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

    public int sizeOfRegion(long sizeX, long sizeY) throws FormatException {
        return TiffTools.checkedMul(sizeX, sizeY, getSamplesPerPixel(), getBytesPerSampleBasedOnType(),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample (type-based)",
                () -> "Invalid requested area: ", () -> "");
    }

    public int sizeOfRegionBasedOnBits(long sizeX, long sizeY) throws FormatException {
        return TiffTools.checkedMul(sizeX, sizeY, getSamplesPerPixel(), getBytesPerSampleBasedOnBits(),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample",
                () -> "Invalid requested area: ", () -> "");
    }

    public int sizeOfTileBasedOnBits() throws FormatException {
        return sizeOfTile(getBytesPerSampleBasedOnBits());
    }

    public int sizeOfTile(int bytesPerSample) throws FormatException {
        final int channels = isPlanarSeparated() ? 1 : getSamplesPerPixel();
        // - if separate (RRR...GGG...BBB...),
        // we have (for 3 channels) only 1 channel instead of 3, but number of tiles is greater:
        // 3 * numTileRows effective rows of tiles instead of numTileRows
        return TiffTools.checkedMul(getTileSizeX(), getTileSizeY(), channels, bytesPerSample,
                "tile width", "tile height", "effective number of channels", "bytes per sample",
                () -> "Invalid TIFF tile sizes: ", () -> "");
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("IFD");
        sb.append(" (%s)".formatted(subIFDType == null ? "main" : ifdTagName(subIFDType, false)));
        try {
            final long imageWidth = getImageWidth();
            final long imageLength = getImageLength();
            final int tileSizeX = getTileSizeX();
            final int tileSizeY = getTileSizeY();
            final long tilesPerRow = (imageWidth + (long) tileSizeX - 1) / tileSizeX;
            final long tilesPerColumn = (imageLength + (long) tileSizeY - 1) / tileSizeY;
            final int pixelType = getPixelType();
            sb.append(" %s[%dx%d], %s, precision %s%s, ".formatted(
                    TiffTools.pixelTypeToElementType(getPixelType()).getSimpleName(),
                    imageWidth, imageLength,
                    isLittleEndian() ? "little-endian" : "big-endian",
                    FormatTools.getPixelTypeString(pixelType),
                    isBigTiff() ? " [BigTIFF]" : ""));
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
            sb.append(" (offset @%d=0x%X)".formatted(offset, offset));
        }
        final Map<Integer, TiffIFDEntry> entries = this.entries;
        final Map<Integer, Object> sortedIFD = new TreeMap<>(this);
        for (Map.Entry<Integer, Object> entry : sortedIFD.entrySet()) {
            final Integer tag = entry.getKey();
            final Object v = entry.getValue();
            if (tag == IFD.LITTLE_ENDIAN || tag == IFD.BIG_TIFF) {
                // - not actual tags (but we still show REUSE: it should not occur in normal IFDs)
                continue;
            }
            sb.append(String.format("%n"));
            Object additional = null;
            try {
                switch (tag) {
                    case IFD.PHOTOMETRIC_INTERPRETATION -> additional = getPhotometricInterpretation().getName();
                    case IFD.COMPRESSION -> additional = prettyCompression(getCompression());
                    case IFD.PLANAR_CONFIGURATION -> {
                        if (v instanceof Number number) {
                            switch (number.intValue()) {
                                case PLANAR_CONFIG_CONTIGUOUSLY_CHUNKED -> additional = "chunky";
                                case PLANAR_CONFIG_SEPARATE -> additional = "rarely-used planar";
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
            sb.append("    ").append(ifdTagName(tag, true)).append(" = ");
            boolean manyValues = v != null && v.getClass().isArray();
            if (manyValues) {
                sb.append(v.getClass().getComponentType().getSimpleName());
                sb.append("[").append(Array.getLength(v)).append("]");
                sb.append(" {");
                appendIFDArray(sb, v);
                sb.append("}");
            } else {
                sb.append(v);
            }
            if (entries != null) {
                final TiffIFDEntry ifdEntry = entries.get(tag);
                if (ifdEntry != null) {
                    sb.append(" : ").append(ifdEntry.getType());
                    int valueCount = ifdEntry.getValueCount();
                    if (valueCount != 1) {
                        sb.append("[").append(valueCount).append("]");
                    }
                    if (manyValues) {
                        sb.append(" at @").append(ifdEntry.getValueOffset());
                    }
                }
            }
            if (additional != null) {
                sb.append("   [it means: ").append(additional).append("]");
            }
        }
        return sb.toString();
    }

    // This method is overridden for removing usage of log field.
    @Override
    public void printIFD() {
        LOG.log(System.Logger.Level.TRACE, this);
    }


    /**
     * Returns user-friendly name of the given TIFF tag.
     * It is used, in particular, in {@link #toString()} function.
     *
     * @param tag            entry Tag value.
     * @param includeNumeric include numeric value into the result.
     * @return user-friendly name in a style of Java constant
     */
    public static String ifdTagName(int tag, boolean includeNumeric) {
        String name = Objects.requireNonNullElse(IFDFriendlyNames.IFD_TAG_NAMES.get(tag), "Unknown tag");
        if (!includeNumeric) {
            return name;
        }
        return "%s (%d or 0x%X)".formatted(name, tag, tag);
    }

    public static boolean isPseudoTag(int tag) {
        return tag == LITTLE_ENDIAN || tag == BIG_TIFF || tag == REUSE;
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

    private static String prettyCompression(TiffCompression compression) {
        if (compression == null) {
            return "No compression?";
        }
        final String s = compression.toString();
        final String codecName = compression.getCodecName();
        return s.equals(codecName) ? s : s + " (" + codecName + ")";
    }

    private static String remainderToString(long a, long b) {
        long r = a / b;
        if (r * b == a) {
            return String.valueOf(b);
        } else {
            return String.valueOf(a - r * b);
        }
    }

    private static void appendIFDArray(StringBuilder sb, Object v) {
        final int len = Array.getLength(v);
        if (v instanceof byte[] bytes) {
            appendIFDBytesArray(sb, bytes);
            return;
        }
        final int left = v instanceof short[] || v instanceof char[] ? 25 : 10;
        final int right = v instanceof short[] || v instanceof char[] ? 10 : 5;
        final int mask = v instanceof short[] ? 0xFFFF : 0;
        for (int k = 0; k < len; k++) {
            if (k == left && len >= left + 5 + right) {
                sb.append(", ...");
                k = len - right - 1;
                continue;
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
    }

    private static void appendIFDBytesArray(StringBuilder sb, byte[] v) {
        final int len = v.length;
        for (int k = 0; k < len; k++) {
            if (k == 20 && len >= 35) {
                sb.append(", ...");
                k = len - 11;
                continue;
            }
            if (k > 0) {
                sb.append(", ");
            }
            appendHexByte(sb, v[k] & 0xFF);
        }
    }

    private static void appendHexByte(StringBuilder sb, int v) {
        if (v < 16) {
            sb.append('0');
        }
        sb.append(Integer.toHexString(v));
    }
}
