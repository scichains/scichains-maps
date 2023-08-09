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
    public static final int PLANAR_CONFIGURATION_CHUNKED = 1;

    /**
     * Planar samples format (PlanarConfiguration), for example: RRR...GGG...BBB...
     * Note: the specification adds a warning that PlanarConfiguration=2 is not in widespread use and
     * that Baseline TIFF readers are not required to support it.
     */
    public static final int PLANAR_CONFIGURATION_SEPARATE = 2;

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

    private long fileOffset = -1;
    private Map<Integer, TiffIFDEntry> entries = null;
    //!! - provides additional information like IFDType for each entry
    private Integer subIFDType = null;
    private volatile boolean frozenForWriting = false;

    private volatile long[] cachedStripByteCounts = null;
    private volatile long[] cachedStripOffsets = null;
    private volatile Integer cachedStripSizeY = null;

    public DetailedIFD(IFD ifd) {
        super(ifd, null);
        // Note: log argument is never used in this class.
        if (ifd instanceof DetailedIFD detailedIFD) {
            fileOffset = detailedIFD.fileOffset;
            entries = detailedIFD.entries;
            subIFDType = detailedIFD.subIFDType;
        } else {
            fileOffset = -1;
            entries = null;
            subIFDType = null;
        }
    }

    public DetailedIFD(DetailedIFD detailedIFD) {
        super(detailedIFD, null);
        // Note: log argument is never used in this class.
        fileOffset = detailedIFD.fileOffset;
        entries = detailedIFD.entries;
        subIFDType = detailedIFD.subIFDType;
    }

    public DetailedIFD() {
        super(null);
        // Note: log argument is never used in this class.
    }

    public static DetailedIFD extend(IFD ifd) {
        Objects.requireNonNull(ifd, "Null IFD");
        return ifd instanceof DetailedIFD detailedIFD ? detailedIFD : new DetailedIFD(ifd);
    }

    public boolean hasFileOffset() {
        return fileOffset >= 0;
    }

    public long getFileOffset() {
        if (fileOffset < 0) {
            throw new IllegalStateException("IFD offset of the TIFF tile is not set");
        }
        return fileOffset;
    }

    public DetailedIFD setFileOffset(long fileOffset) {
        if (fileOffset < 0) {
            throw new IllegalArgumentException("Negative IFD offset in the file: " + fileOffset);
        }
        this.fileOffset = fileOffset;
        return this;
    }

    public DetailedIFD removeIFDFileOffset() {
        this.fileOffset = -1;
        return this;
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

    public boolean isReadyForWriting() {
        return frozenForWriting;
    }

    public DetailedIFD freezeForWriting() {
        this.frozenForWriting = true;
        return this;
    }

    @Override
    public Object put(Integer key, Object value) {
        checkImmutable();
        return super.put(key, value);
    }

    @Override
    public void putAll(Map<? extends Integer, ?> m) {
        checkImmutable();
        super.putAll(m);
    }

    @Override
    public Object remove(Object key) {
        checkImmutable();
        return super.remove(key);
    }

    @Override
    public void clear() {
        checkImmutable();
        super.clear();
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
            // - In the following loop, this array will be appended to necessary length.
        }

        final int samplesPerPixel = getSamplesPerPixel();
        if (bitsPerSample.length < samplesPerPixel) {
            // - Result must contain at least samplesPerPixel elements (SCIFIO agreement)
            final int bits = bitsPerSample[0];
            bitsPerSample = new int[samplesPerPixel];
            Arrays.fill(bitsPerSample, bits);
        }
        for (int i = 0; i < samplesPerPixel; i++) {
            if (bitsPerSample[i] <= 0) {
                throw new FormatException("Zero or negative BitsPerSample[" + i + "] = " + bitsPerSample[i]);
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
        if (getPlanarConfiguration() == PLANAR_CONFIGURATION_SEPARATE) {
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
        return getPlanarConfiguration() == PLANAR_CONFIGURATION_SEPARATE;
    }

    // Usually true: PlanarConfiguration=2 is not in widespread use
    public boolean isChunked() throws FormatException {
        return getPlanarConfiguration() == PLANAR_CONFIGURATION_CHUNKED;
    }

    public boolean isReversedBits() throws FormatException {
        return getFillOrder() == FillOrder.REVERSED;
    }

    public long getImageWidth() throws FormatException {
        return getImageDimX();
    }

    public long getImageLength() throws FormatException {
        return getImageDimY();
    }

    public boolean hasImageDimensions() {
        return containsKey(IMAGE_WIDTH) && containsKey(IMAGE_LENGTH);
    }

    //!! Better analog of IFD.getImageWidth()
    public int getImageDimX() throws FormatException {
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
    public int getImageDimY() throws FormatException {
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
        final int imageDimY = getImageDimY();
        if (rowsPerStrip == null || rowsPerStrip.length == 0) {
            // - zero rowsPerStrip.length is possible only as a result of manual modification of this IFD
            return imageDimY == 0 ? 1 : imageDimY;
            // - imageDimY == 0 is checked to be on the safe side
        }

        // rowsPerStrip should never be more than the total number of rows
        for (int i = 0; i < rowsPerStrip.length; i++) {
            int rows = (int) Math.min(rowsPerStrip[i], imageDimY);
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

    /**
     * Returns the tile height in tiled image, strip height in other images. If there are no tiles or strips,
     * returns max(h,1), where h is the image height.
     *
     * <p>Note: result is always positive!
     *
     * @return tile/strip height.
     * @throws FormatException in a case of incorrect IFD.
     */
    public int getTileSizeY() throws FormatException {
        //!! Better analog of IFD.getTileLength()
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
    /**
     * Returns the tile width in tiled image. If there are no tiles,
     * returns max(w,1), where w is the image width.
     *
     * <p>Note: result is always positive!
     *
     * @return tile width.
     * @throws FormatException in a case of incorrect IFD.
     */
    public int getTileSizeX() throws FormatException {
        //!! Better analog of IFD.getTileWidth()
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
        final int imageDimX = getImageDimX();
        return imageDimX == 0 ? 1 : imageDimX;
        // - imageDimX == 0 is checked to be on the safe side
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

    /**
     * Checks that all bits per sample (BitsPerSample tag) for all channels are equal to the same positive integer,
     * and returns this integer. If it is not so, throws {@link FormatException}.
     * Note that unequal bits per sample is not supported by all software.
     *
     * <p>Note: {@link TiffReader} class does not strictly require this condition, it requires only
     * equality of number of <i>bytes</i> per sample: see {@link #equalBytesPerSample()}
     * (though the complete support of TIFF with different number of bits is not provided).
     * In comparison, {@link TiffWriter} class really <i>does</i> require this condition: it cannot
     * create TIFF files with different number of bits per channel.
     *
     * @return bits per sample (if this value is the same for all channels).
     * @throws FormatException if IFD contains different number of bits per sample for some channels.
     */
    public int equalBitsPerSample() throws FormatException {
        final OptionalInt bits = tryEqualBitsPerSample();
        if (bits.isEmpty()) {
            throw new FormatException("Unsupported TIFF IFD: different number of bits per samples (" +
                            Arrays.toString(getBitsPerSample()) + ")");
        }
        return bits.getAsInt();
    }

    /**
     * Analog of {@link #equalBitsPerSample()}, returning empty result instead of throwing exception
     * in a case of unequal number of bits per samples.
     *
     * @return bits per sample, if this value is the same for all channels, or empty value in other case.
     * @throws FormatException in a case of any problems while parsing IFD.
     */
    public OptionalInt tryEqualBitsPerSample() throws FormatException {
        final int[] bitsPerSample = getBitsPerSample();
        final int bits0 = bitsPerSample[0];
        for (int i = 1; i < bitsPerSample.length; i++) {
            if (bitsPerSample[i] != bits0) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of(bits0);
    }

    /**
     * Returns the number of bytes per each sample. It is calculated by rounding the number of
     * {@link #getBitsPerSample() bits per sample},
     * divided by 8 with rounding up to nearest integer: &#8968;BitsPerSample/8&#8969;.
     *
     * <p>This method requires that the number of bytes, calculated by this formula, must be positive and
     * equal for all channels.
     * This is also requirement for TIFF files, that can be read by {@link TiffReader} class.
     * However, equality of number of <i>bits</i> is not required.
     *
     * @return number of bytes per each sample.
     * @throws FormatException if &#8968;bitsPerSample/8&#8969; values are different for some channels.
     */
    public int equalBytesPerSample() throws FormatException {
        final int[] bytesPerSample = getBytesPerSample();
        final int bytes0 = bytesPerSample[0];
        // - for example, if we have 5 bits R + 6 bits G + 4 bits B, it will be ceil(6/8) = 1 byte;
        // usually the same for all components
        if (bytes0 < 1) {
            throw new FormatException("Invalid format: zero or negative bytes per sample = " + bytes0);
        }
        for (int k = 1; k < bytesPerSample.length; k++) {
            if (bytesPerSample[k] != bytes0) {
                throw new FormatException("Unsupported TIFF IFD: different number of bytes per samples (" +
                        Arrays.toString(bytesPerSample) + "), based on the following number of bits (" +
                        Arrays.toString(getBitsPerSample()) + ")");
            }
            // - note that LibTiff does not support different BitsPerSample values for different components;
            // we do not support different number of BYTES for different components
        }
        return bytes0;
    }

    public int bytesPerSampleBasedOnType() throws FormatException {
        final int pixelType = getPixelType();
        return FormatTools.getBytesPerPixel(pixelType);
    }

    public long getIFDLongValue(final int tag) throws FormatException {
        final Number number = (Number) getIFDValue(tag, Number.class);
        if (number == null) {
            throw new FormatException("No tag " + tag + " in IFD");
        }
        return number.longValue();
    }

    public DetailedIFD putImageDimensions(int dimX, int dimY) {
        if (dimX <= 0) {
            throw new IllegalArgumentException("Zero or negative image width (x-dimension)");
        }
        if (dimY <= 0) {
            throw new IllegalArgumentException("Zero or negative image height (y-dimension)");
        }
        putIFDValue(IFD.IMAGE_WIDTH, dimX);
        putIFDValue(IFD.IMAGE_LENGTH, dimY);
        return this;
    }

    /**
     * Puts base pixel type and channels information: BitsPerSample, SampleFormat, SamplesPerPixel
     *
     * @param numberOfChannels number of channels (in other words, number of samples per every pixel).
     * @param pixelType        pixel type.
     * @return a reference to this object.
     */
    public DetailedIFD putBaseInformation(int numberOfChannels, int pixelType) {
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        final int bytesPerSample = FormatTools.getBytesPerPixel(pixelType);
        final boolean signed = FormatTools.isSigned(pixelType);
        final boolean floatingPoint = FormatTools.isFloatingPoint(pixelType);
        final int bitsPerSample = 8 * bytesPerSample;
        final int[] bpsArray = new int[numberOfChannels];
        Arrays.fill(bpsArray, bitsPerSample);
        putIFDValue(IFD.BITS_PER_SAMPLE, bpsArray);
        if (floatingPoint) {
            putIFDValue(IFD.SAMPLE_FORMAT, DetailedIFD.SAMPLE_FORMAT_IEEEFP);
        } else if (signed) {
            putIFDValue(IFD.SAMPLE_FORMAT, DetailedIFD.SAMPLE_FORMAT_INT);
        } else {
            remove(IFD.SAMPLE_FORMAT);
        }
        putIFDValue(IFD.SAMPLES_PER_PIXEL, numberOfChannels);
        return this;
    }

    public boolean hasTileInformation() {
        return containsKey(IFD.TILE_LENGTH);
    }

    public DetailedIFD putTileInformation(int tileSizeX, int tileSizeY) {
        if (tileSizeX <= 0) {
            throw new IllegalArgumentException("Zero or negative tile x-size");
        }
        if (tileSizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative tile y-size");
        }
        if ((tileSizeX & 15) != 0 || (tileSizeY & 15) != 0) {
            throw new IllegalArgumentException("Illegal tile sizes " + tileSizeX + "x" + tileSizeY
                    + ": they must be multiples of 16");
        }
        putIFDValue(IFD.TILE_WIDTH, tileSizeX);
        putIFDValue(IFD.TILE_LENGTH, tileSizeY);
        return this;
    }

    public DetailedIFD removeTileInformation() {
        remove(IFD.TILE_WIDTH);
        remove(IFD.TILE_LENGTH);
        return this;
    }

    public boolean hasStripInformation() {
        return containsKey(IFD.ROWS_PER_STRIP);
    }

    public DetailedIFD putStripInformation(int stripSizeY) {
        if (stripSizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative strip y-size");
        }
        putIFDValue(IFD.ROWS_PER_STRIP, new long[]{stripSizeY});
        return this;
    }

    public DetailedIFD removeStripInformation(int stripSizeY) {
        remove(IFD.ROWS_PER_STRIP);
        return this;
    }

    public DetailedIFD putCompression(TiffCompression compression) {
        return putCompression(compression, false);
    }

    public DetailedIFD putCompression(TiffCompression compression, boolean keepDefaultValue) {
        if (compression == null && keepDefaultValue) {
            compression = TiffCompression.UNCOMPRESSED;
        }
        if (compression == null) {
            remove(IFD.COMPRESSION);
        } else {
            putIFDValue(IFD.COMPRESSION, compression.getCode());
        }
        return this;
    }

    public DetailedIFD putPlanarSeparated(boolean planarSeparated) {
        if (planarSeparated) {
            putIFDValue(IFD.PLANAR_CONFIGURATION, DetailedIFD.PLANAR_CONFIGURATION_SEPARATE);
        } else {
            remove(IFD.PLANAR_CONFIGURATION);
        }
        return this;
    }

    public int sizeOfRegionBasedOnType(long sizeX, long sizeY) throws FormatException {
        return TiffTools.checkedMul(sizeX, sizeY, getSamplesPerPixel(), bytesPerSampleBasedOnType(),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample (type-based)",
                () -> "Invalid requested area: ", () -> "");
    }

    public int sizeOfRegion(long sizeX, long sizeY) throws FormatException {
        return TiffTools.checkedMul(sizeX, sizeY, getSamplesPerPixel(), equalBytesPerSample(),
                "sizeX", "sizeY", "samples per pixel", "bytes per sample",
                () -> "Invalid requested area: ", () -> "");
    }

    /**
     * Checks that the sizes of this IFD (ImageWidth and ImageLength) are positive integers
     * in range <tt>1..Integer.MAX_VALUE</tt>. If it is not so, throws {@link FormatException}.
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

    @Override
    public String toString() {
        return toString(true);
    }

    public String toString(boolean detailed) {
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
            sb.append(isChunked() ? ", chunked" : ", planar");
        } catch (Exception e) {
            sb.append(" [").append(e.getMessage()).append("]");
        }
        if (hasFileOffset()) {
            sb.append(" (offset @%d=0x%X)".formatted(fileOffset, fileOffset));
        }
        if (!detailed) {
            return sb.toString();
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
                                case PLANAR_CONFIGURATION_CHUNKED -> additional = "chunky";
                                case PLANAR_CONFIGURATION_SEPARATE -> additional = "rarely-used planar";
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

    private void checkImmutable() {
        if (frozenForWriting) {
            throw new IllegalStateException("IFD is frozen for writing TIFF and cannot be modified");
        }
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
