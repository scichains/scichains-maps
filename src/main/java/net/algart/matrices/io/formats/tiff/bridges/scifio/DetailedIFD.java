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
import io.scif.enumeration.EnumException;
import io.scif.formats.tiff.*;
import io.scif.util.FormatTools;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Supplier;

//!! Better analog of IFD (can be merged with the main IFD)
public class DetailedIFD extends IFD {

    public static final int LAST_IFD_OFFSET = 0;

    public enum StringFormat {
        BRIEF(true, false),
        NORMAL(true, false),
        NORMAL_SORTED(true, true),
        DETAILED(false, false);
        //TODO!! JSON
        private final boolean compactArrays;
        private final boolean sorted;

        StringFormat(boolean compactArrays, boolean sorted) {
            this.compactArrays = compactArrays;
            this.sorted = sorted;
        }
    }

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

    private long fileOffsetOfReading = -1;
    private long fileOffsetForWriting = -1;
    private long nextIFDOffset = -1;
    private Map<Integer, TiffIFDEntry> entries = null;
    //!! - provides additional information like IFDType for each entry
    private Integer subIFDType = null;
    private volatile boolean frozen = false;

    private volatile long[] cachedTileOrStripByteCounts = null;
    private volatile long[] cachedTileOrStripOffsets = null;

    public DetailedIFD(IFD ifd) {
        super(ifd, null);
        // Note: log argument is never used in this class.
        if (ifd instanceof DetailedIFD detailedIFD) {
            fileOffsetOfReading = detailedIFD.fileOffsetOfReading;
            fileOffsetForWriting = detailedIFD.fileOffsetForWriting;
            nextIFDOffset = detailedIFD.nextIFDOffset;
            entries = detailedIFD.entries == null ? null : new LinkedHashMap<>(detailedIFD.entries);
            subIFDType = detailedIFD.subIFDType;
            frozen = false;
            // - Important: a copy is not frozen!
            // And it is the only way to clear this flag.
        }
    }

    @SuppressWarnings("CopyConstructorMissesField")
    public DetailedIFD(DetailedIFD detailedIFD) {
        this((IFD) detailedIFD);
    }

    public DetailedIFD() {
        super(null);
        // Note: log argument is never used in this class.
    }

    public static DetailedIFD extend(IFD ifd) {
        Objects.requireNonNull(ifd, "Null IFD");
        return ifd instanceof DetailedIFD detailedIFD ? detailedIFD : new DetailedIFD(ifd);
    }

    public boolean hasFileOffsetOfReading() {
        return fileOffsetOfReading >= 0;
    }

    public long getFileOffsetOfReading() {
        if (fileOffsetOfReading < 0) {
            throw new IllegalStateException("IFD offset of the TIFF tile is not set while reading");
        }
        return fileOffsetOfReading;
    }

    public DetailedIFD setFileOffsetOfReading(long fileOffsetOfReading) {
        if (fileOffsetOfReading < 0) {
            throw new IllegalArgumentException("Negative IFD offset in the file: " + fileOffsetOfReading);
        }
        this.fileOffsetOfReading = fileOffsetOfReading;
        return this;
    }

    public DetailedIFD removeFileOffsetOfReading() {
        this.fileOffsetOfReading = -1;
        return this;
    }

    public boolean hasFileOffsetForWriting() {
        return fileOffsetForWriting >= 0;
    }

    public long getFileOffsetForWriting() {
        if (fileOffsetForWriting < 0) {
            throw new IllegalStateException("IFD offset of the TIFF tile for writing is not set");
        }
        return fileOffsetForWriting;
    }

    public DetailedIFD setFileOffsetForWriting(long fileOffsetForWriting) {
        if (fileOffsetForWriting < 0) {
            throw new IllegalArgumentException("Negative IFD file offset: " + fileOffsetForWriting);
        }
        if ((fileOffsetForWriting & 0x1) != 0) {
            throw new IllegalArgumentException("Odd IFD file offset " + fileOffsetForWriting +
                    " is prohibited for writing valid TIFF");
            // - But we allow such offsets for reading!
            // Such minor inconsistency in the file is not a reason to decline ability to read it.
        }
        this.fileOffsetForWriting = fileOffsetForWriting;
        return this;
    }

    public DetailedIFD removeFileOffsetForWriting() {
        this.fileOffsetForWriting = -1;
        return this;
    }

    public boolean hasNextIFDOffset() {
        return nextIFDOffset >= 0;
    }

    /**
     * Returns <tt>true</tt> if this IFD is marked as the last ({@link #getNextIFDOffset()} returns 0).
     *
     * @return whether this IFD is the last one in the TIFF file.
     */
    public boolean isLastIFD() {
        return nextIFDOffset == LAST_IFD_OFFSET;
    }

    public long getNextIFDOffset() {
        if (nextIFDOffset < 0) {
            throw new IllegalStateException("Next IFD offset is not set");
        }
        return nextIFDOffset;
    }

    public DetailedIFD setNextIFDOffset(long nextIFDOffset) {
        if (nextIFDOffset < 0) {
            throw new IllegalArgumentException("Negative next IFD offset: " + nextIFDOffset);
        }
        this.nextIFDOffset = nextIFDOffset;
        return this;
    }

    public DetailedIFD setLastIFD() {
        return setNextIFDOffset(LAST_IFD_OFFSET);
    }

    public DetailedIFD removeNextIFDOffset() {
        this.nextIFDOffset = -1;
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
        return frozen;
    }

    /**
     * Disables most possible changes in this map,
     * excepting the only ones, which are absolutely necessary for making final IFD inside the file.
     * Such "necessary" changes are performed by special "updateXxx" method.
     *
     * <p>This method is usually called before writing process.
     * This helps to avoid bugs, connected with changing IFD properties (such as compression, tile sizes etc.)
     * when we already started to write image into the file, for example, have written some its tiles.
     *
     * @return a reference to this object.
     */
    public DetailedIFD freeze() {
        this.frozen = true;
        return this;
    }

    public <M extends Map<Integer, Object>> M removePseudoTags(Supplier<M> mapFactory) {
        Objects.requireNonNull(mapFactory, "Null mapFactory");
        final M result = mapFactory.get();
        for (Map.Entry<Integer, Object> entry : entrySet()) {
            if (!DetailedIFD.isPseudoTag(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public int numberOfEntries() {
        return (int) keySet().stream().filter(key -> !DetailedIFD.isPseudoTag(key)).count();
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
     * Checks that the sizes of this IFD (<tt>ImageWidth</tt> and <tt>ImageLength</tt>) are positive integers
     * in range <tt>1..Integer.MAX_VALUE</tt>. If it is not so, throws {@link FormatException}.
     *
     * @throws FormatException if image width or height is negaitve or <tt>&gt;Integer.MAX_VALUE</tt>.
     */
    public void checkSizesArePositive() throws FormatException {
        int dimX = getImageDimX();
        int dimY = getImageDimY();
        if (dimX <= 0 || dimY <= 0) {
            throw new FormatException("Zero or negative IFD image sizes " + dimX + "x" + dimY + " are not allowed");
            // - important for some classes, processing IFD images, that cannot work with zero-size areas
            // (for example, due to usage of AlgART IRectangularArea)
        }
    }

    public <R> Optional<R> optValue(final int tag, final Class<? extends R> requiredClass) {
        Objects.requireNonNull(requiredClass, "Null requiredClass");
        Object value = get(tag);
        if (!requiredClass.isInstance(value)) {
            // - in particular, if value == null
            return Optional.empty();
        }
        return Optional.of(requiredClass.cast(value));
    }

    public <R> Optional<R> getValue(final int tag, final Class<? extends R> requiredClass) throws FormatException {
        Objects.requireNonNull(requiredClass, "Null requiredClass");
        Object value = get(tag);
        if (value == null) {
            return Optional.empty();
        }
        if (!requiredClass.isInstance(value)) {
            throw new FormatException("TIFF tag " + ifdTagName(tag, true) +
                    " has wrong type " + value.getClass().getSimpleName() +
                    " instead of expected " + requiredClass.getSimpleName());
        }
        return Optional.of(requiredClass.cast(value));
    }

    public <R> R reqValue(final int tag, final Class<? extends R> requiredClass) throws FormatException {
        return getValue(tag, requiredClass).orElseThrow(() -> new FormatException(
                "TIFF tag " + ifdTagName(tag, true) + " is required, but it is absent"));
    }

    public boolean getBoolean(int tag, boolean defaultValue) {
        return optValue(tag, Boolean.class).orElse(defaultValue);
    }

    public int getInt(int tag) throws FormatException {
        return checkedIntValue(reqValue(tag, Number.class), tag);
    }

    public int getInt(int tag, int defaultValue) throws FormatException {
        return checkedIntValue(optValue(tag, Number.class).orElse(defaultValue), tag);
    }

    public long getLong(int tag) throws FormatException {
        return reqValue(tag, Number.class).longValue();
    }

    public long getLong(int tag, int defaultValue) {
        return optValue(tag, Number.class).orElse(defaultValue).longValue();
    }


    // This method is overridden with change of behaviour: it never throws exception and returns false instead.
    @Override
    public boolean isBigTiff() {
        return getBoolean(BIG_TIFF, false);
    }

    // This method is overridden with change of behaviour: it never throws exception and returns false instead.
    @Override
    public boolean isLittleEndian() {
        return getBoolean(LITTLE_ENDIAN, false);
    }

    // This method is overridden to check that result is positive and to avoid exception for illegal compression
    @Override
    public int getSamplesPerPixel() throws FormatException {
        int compressionValue = getInt(IFD.COMPRESSION, 0);
        if (compressionValue == TiffCompression.OLD_JPEG.getCode()) {
            return 3;
            // always 3 channels: RGB
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

    // This method replaces old getStripByteCounts to remove extra support of absence of StripByteCounts
    // and to remove extra doubling result for LZW (may lead to a bug)
    public long[] getTileOrStripByteCounts() throws FormatException {
        final boolean tiled = hasTileInformation();
        final int tag = tiled ? TILE_BYTE_COUNTS : STRIP_BYTE_COUNTS;
        long[] counts = getIFDLongArray(tag);
        if (tiled && counts == null) {
            counts = getIFDLongArray(STRIP_BYTE_COUNTS);
            // - rare situation, when tile byte counts are actually stored in StripByteCounts
        }
        if (counts == null) {
            throw new FormatException("Invalid IFD: no required StripByteCounts/TileByteCounts tag");
        }
        final long numberOfTiles = (long) getTileCountX() * (long) getTileCountY();
        if (counts.length < numberOfTiles) {
            throw new FormatException("StripByteCounts/TileByteCounts length (" + counts.length +
                    ") does not match expected number of strips/tiles (" + numberOfTiles + ")");
        }
        return counts;
    }

    /**
     * Use {@link #getTileOrStripByteCounts()} instead.
     */
    @Deprecated
    @Override
    public long[] getStripByteCounts() throws FormatException {
        return super.getStripByteCounts();
    }

    public long[] cachedTileOrStripByteCounts() throws FormatException {
        long[] result = this.cachedTileOrStripByteCounts;
        if (result == null) {
            this.cachedTileOrStripByteCounts = result = getTileOrStripByteCounts();
        }
        return result;
    }

    public int cachedTileOrStripByteCount(int index) throws FormatException {
        long[] byteCounts = cachedTileOrStripByteCounts();
        if (index < 0) {
            throw new IllegalArgumentException("Negative index = " + index);
        }
        if (index >= byteCounts.length) {
            throw new FormatException((hasTileInformation() ?
                    "Tile index is too big for TileByteCounts" :
                    "Strip index is too big for StripByteCounts") +
                    "array: it contains only " + byteCounts.length + " elements");
        }
        long result = byteCounts[index];
        if (result < 0) {
            throw new FormatException(
                    "Negative value " + result + " in " +
                            (hasTileInformation() ? "TileByteCounts" : "StripByteCounts") + " array");
        }
        if (result > Integer.MAX_VALUE) {
            throw new FormatException("Too large tile/strip #" + index + ": " + result + " bytes > 2^31-1");
        }
        return (int) result;
    }

    // This method replaces old getStripOffsets()
    public long[] getTileOrStripOffsets() throws FormatException {
        final boolean tiled = hasTileInformation();
        final int tag = tiled ? TILE_OFFSETS : STRIP_OFFSETS;
        long[] offsets;
        final OnDemandLongArray compressedOffsets = getOnDemandStripOffsets();
        // - compatibility with old TiffParser feature (can be removed in future versions)
        if (compressedOffsets != null) {
            offsets = new long[(int) compressedOffsets.size()];
            try {
                for (int q = 0; q < offsets.length; q++) {
                    offsets[q] = compressedOffsets.get(q);
                }
            }
            catch (final IOException e) {
                throw new FormatException("Failed to retrieve offset", e);
            }
        } else {
            offsets = getIFDLongArray(tag);
        }
        if (tiled && offsets == null) {
            // - rare situation, when tile offsets are actually stored in StripOffsets
            offsets = getIFDLongArray(STRIP_OFFSETS);
        }
        if (offsets == null) {
            throw new FormatException("Invalid IFD: no required StripOffsets/TileOffsets tag");
        }
        final long numberOfTiles = (long) getTileCountX() * (long) getTileCountY();
        if (offsets.length < numberOfTiles) {
            throw new FormatException("StripByteCounts/TileByteCounts length (" + offsets.length +
                    ") does not match expected number of strips/tiles (" + numberOfTiles + ")");
        }
        // Note: old getStripOffsets method also performed correction:
        // if (offsets[i] < 0) {
        //     offsets[i] += 0x100000000L;
        // }
        // But it is not necessary with new TiffReader: if reads correct 32-bit unsigned values.

        return offsets;
    }

    /**
     * Use {@link #getTileOrStripOffsets()} instead.
     */
    @Deprecated
    @Override
    public long[] getStripOffsets() throws FormatException {
        return super.getStripOffsets();
    }

    public long[] cachedTileOrStripOffsets() throws FormatException {
        long[] result = this.cachedTileOrStripOffsets;
        if (result == null) {
            this.cachedTileOrStripOffsets = result = getTileOrStripOffsets();
        }
        return result;
    }

    public long cachedTileOrStripOffset(int index) throws FormatException {
        long[] offsets = cachedTileOrStripOffsets();
        if (index < 0) {
            throw new IllegalArgumentException("Negative index = " + index);
        }
        if (index >= offsets.length) {
            throw new FormatException((hasTileInformation() ?
                    "Tile index is too big for TileOffsets" :
                    "Strip index is too big for StripOffsets") +
                    "array: it contains only " + offsets.length + " elements");
        }
        final long result = offsets[index];
        if (result < 0) {
            throw new FormatException(
                    "Negative value " + result + " in " +
                            (hasTileInformation() ? "TileOffsets" : "StripOffsets") + " array");
        }
        return result;
    }

    @Override
    public TiffCompression getCompression() throws FormatException {
        final int code = getIFDIntValue(COMPRESSION, TiffCompression.UNCOMPRESSED.getCode());
        try {
            return TiffCompression.get(code);
        } catch (EnumException e) {
            throw new UnsupportedTiffFormatException("Unknown TIFF compression code: " + code);
        }
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

    public boolean hasImageDimensions() {
        return containsKey(IMAGE_WIDTH) && containsKey(IMAGE_LENGTH);
    }

    public int getImageDimX() throws FormatException {
        final int imageWidth = getInt(IMAGE_WIDTH);
        if (imageWidth <= 0) {
            throw new FormatException("Zero or negative image width = " + imageWidth);
            // - impossible in a correct TIFF
        }
        return imageWidth;
    }

    public int getImageDimY() throws FormatException {
        final int imageLength = getInt(IMAGE_LENGTH);
        if (imageLength <= 0) {
            throw new FormatException("Zero or negative image height = " + imageLength);
            // - impossible in a correct TIFF
        }
        return imageLength;
    }

    /**
     * Use {@link #getImageDimX()} instead.
     */
    @Deprecated
    @Override
    public long getImageWidth() throws FormatException {
        return getImageDimX();
    }

    /**
     * Use {@link #getImageDimY()} instead.
     */
    @Deprecated
    @Override
    public long getImageLength() throws FormatException {
        return getImageDimY();
    }

    public int getStripRows() throws FormatException {
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
     * Use {@link #getStripRows()} instead.
     */
    @Deprecated
    @Override
    public long[] getRowsPerStrip() throws FormatException {
        return super.getRowsPerStrip();
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
        if (hasTileInformation()) {
            // - Note: we refuse to handle situation, when TileLength presents, but TileWidth not, or vice versa
            final int tileWidth = getInt(IFD.TILE_WIDTH);
            // - TIFF allows to use values <= 2^32-1, but in any case we cannot allocate Java array for such tile
            if (tileWidth <= 0) {
                throw new FormatException("Zero or negative tile width = " + tileWidth);
                // - impossible in a correct TIFF
            }
            return tileWidth;
        }
        final int imageDimX = getImageDimX();
        return imageDimX == 0 ? 1 : imageDimX;
        // - imageDimX == 0 is checked to be on the safe side
    }


    /**
     * Use {@link #getTileSizeX()} instead.
     */
    @Deprecated
    @Override
    public long getTileWidth() throws FormatException {
        return getTileSizeX();
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
        if (hasTileInformation()) {
            // - Note: we refuse to handle situation, when TileLength presents, but TileWidth not, or vice versa
            final int tileLength = getInt(IFD.TILE_LENGTH);
            if (tileLength <= 0) {
                throw new FormatException("Zero or negative tile length (height) = " + tileLength);
                // - impossible in a correct TIFF
            }
            return tileLength;
        }
        final int stripRows = getStripRows();
        assert stripRows > 0 : "getStripRows() did not check non-positive result";
        return stripRows;
        // - unlike old SCIFIO getTileLength, we do not return 0 if rowsPerStrip==0:
        // it allows to avoid additional checks in a calling code
    }

    /**
     * Use {@link #getTileSizeY()} instead.
     */
    @Deprecated
    @Override
    public long getTileLength() throws FormatException {
        return getTileSizeY();
    }

    public int getTileCountX() throws FormatException {
        int tileSizeX = getTileSizeX();
        if (tileSizeX <= 0) {
            throw new FormatException("Zero or negative tile width = " + tileSizeX);
        }
        final long imageWidth = getImageDimX();
        final long n = (imageWidth + (long) tileSizeX - 1) / tileSizeX;
        assert n <= Integer.MAX_VALUE : "ceil(" + imageWidth + "/" + tileSizeX + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    /**
     * Use {@link #getTileCountX()} method or
     * {@link net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap} class instead.
     */
    @Deprecated
    @Override
    public long getTilesPerRow() throws FormatException {
        return getTileCountX();
    }

    public int getTileCountY() throws FormatException {
        int tileSizeY = getTileSizeY();
        if (tileSizeY <= 0) {
            throw new FormatException("Zero or negative tile height = " + tileSizeY);
        }
        final long imageLength = getImageDimY();
        final long n = (imageLength + (long) tileSizeY - 1) / tileSizeY;
        assert n <= Integer.MAX_VALUE : "ceil(" + imageLength + "/" + tileSizeY + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    /**
     * Use {@link #getTileCountY()} method or
     * {@link net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap} class instead.
     */
    @Deprecated
    @Override
    public long getTilesPerColumn() throws FormatException {
        return getTileCountY();
    }

    /**
     * Checks that all bits per sample (<tt>BitsPerSample</tt> tag) for all channels are equal to the same integer,
     * and returns this integer. If it is not so, returns empty optional result.
     * Note that unequal bits per sample is not supported by all software.
     *
     * <p>Note: {@link TiffReader} class does not strictly require this condition, it requires only
     * equality of number of <i>bytes</i> per sample: see {@link #equalBytesPerSample()}
     * (though the complete support of TIFF with different number of bits is not provided).
     * In comparison, {@link TiffWriter} class really <i>does</i> require this condition: it cannot
     * create TIFF files with different number of bits per channel.
     *
     * @return bits per sample, if this value is the same for all channels, or empty value in other case.
     * @throws FormatException in a case of any problems while parsing IFD, in particular,
     *                         if <tt>BitsPerSample</tt> tag contains zero or negative values.
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
                throw new UnsupportedTiffFormatException("Unsupported TIFF IFD: " +
                        "different number of bytes per samples (" +
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

    public DetailedIFD putImageDimensions(int dimX, int dimY) {
        checkImmutable();
        updateImageDimensions(dimX, dimY);
        return this;
    }

    public DetailedIFD removeImageDimensions() {
        remove(IFD.IMAGE_WIDTH);
        remove(IFD.IMAGE_LENGTH);
        return this;
    }

    public DetailedIFD putPixelInformation(int numberOfChannels, Class<?> elementType) {
        return putPixelInformation(numberOfChannels, elementType, false);
    }

    public DetailedIFD putPixelInformation(int numberOfChannels, Class<?> elementType, boolean signedIntegers) {
        return putPixelInformation(numberOfChannels, TiffTools.elementTypeToPixelType(elementType, signedIntegers));
    }

    /**
     * Puts base pixel type and channels information: BitsPerSample, SampleFormat, SamplesPerPixel
     *
     * @param numberOfChannels number of channels (in other words, number of samples per every pixel).
     * @param pixelType        pixel type.
     * @return a reference to this object.
     */
    public DetailedIFD putPixelInformation(int numberOfChannels, int pixelType) {
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        final int bytesPerSample = FormatTools.getBytesPerPixel(pixelType);
        final boolean signed = FormatTools.isSigned(pixelType);
        final boolean floatingPoint = FormatTools.isFloatingPoint(pixelType);
        putIFDValue(IFD.BITS_PER_SAMPLE, nInts(numberOfChannels, 8 * bytesPerSample));
        if (floatingPoint) {
            putIFDValue(IFD.SAMPLE_FORMAT, nInts(numberOfChannels, DetailedIFD.SAMPLE_FORMAT_IEEEFP));
        } else if (signed) {
            putIFDValue(IFD.SAMPLE_FORMAT, nInts(numberOfChannels, DetailedIFD.SAMPLE_FORMAT_INT));
        } else {
            remove(IFD.SAMPLE_FORMAT);
        }
        putIFDValue(IFD.SAMPLES_PER_PIXEL, numberOfChannels);
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

    /**
     * Returns <tt>true</tt> if IFD contains tags <tt>TileWidth</tt> and <tt>TileLength</tt>.
     * It means that the image is stored in tiled form, but not separated by strips.
     *
     * <p>If IFD contains <b>only one</b> from these tags &mdash; there is <tt>TileWidth</tt>,
     * but <tt>TileLength</tt> is not specified, or vice versa &mdash; this method throws
     * <tt>FormatException</tt>. It allows to guarantee: if this method returns <tt>true</tt>,
     * than the tile sizes are completely specified by these 2 tags and do not depend on the
     * actual image sizes. Note: when this method returns <tt>false</tt>, the tiles sizes,
     * returned by {@link #getTileSizeY()} methods, are determined by
     * <tt>RowsPerStrip</tt> tag, and {@link #getTileSizeX()} is always equal to the value
     * of <tt>ImageWidth</tt> tag.
     *
     * <p>For comparison, old <tt>isTiled()</tt> methods returns <tt>true</tt>
     * if IFD contains tag <tt>TileWidth</tt> <i>and</i> does not contain tag <tt>StripOffsets</tt>.
     * However, some TIFF files use <tt>StripOffsets</tt> and <tt>StripByteCounts</tt> tags even
     * in a case of tiled image, for example, cramps-tile.tif from the known image set <i>libtiffpic</i>
     * (see https://download.osgeo.org/libtiff/ ).
     *
     * @return whether this IFD contain tile size information.
     * @throws FormatException if one of tags <tt>TileWidth</tt> and <tt>TileLength</tt> is present in IFD,
     *                         but the second is absent.
     */
    public boolean hasTileInformation() throws FormatException {
        final boolean hasWidth = containsKey(IFD.TILE_WIDTH);
        final boolean hasLength = containsKey(IFD.TILE_LENGTH);
        if (hasWidth != hasLength) {
            throw new FormatException("Inconsistent tiling information: tile width (TileWidth tag) is " +
                    (hasWidth ? "" : "NOT ") + "specified, but tile height (TileLength tag) is " +
                    (hasLength ? "" : "NOT ") + "specified");
        }
        return hasWidth;
    }

    /**
     * Use {@link #hasTileInformation()} instead.
     */
    @Deprecated
    @Override
    public boolean isTiled() {
        return super.isTiled();
    }

    public DetailedIFD putTileSizes(int tileSizeX, int tileSizeY) {
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

    public DetailedIFD putStripSize(int stripSizeY) {
        if (stripSizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative strip y-size");
        }
        putIFDValue(IFD.ROWS_PER_STRIP, new long[]{stripSizeY});
        return this;
    }

    public DetailedIFD removeStripInformation() {
        remove(IFD.ROWS_PER_STRIP);
        return this;
    }

    public void removeDataPositioning() {
        remove(IFD.STRIP_OFFSETS);
        remove(IFD.STRIP_BYTE_COUNTS);
        remove(IFD.TILE_OFFSETS);
        remove(IFD.TILE_BYTE_COUNTS);
    }

    /**
     * Puts new values for <tt>ImageWidth</tt> and <tt>ImageLength</tt> tags.
     *
     * <p>Note: this method works even when IFD is frozen by {@link #freeze()} method.
     *
     * @param dimX new TIFF image width (<tt>ImageWidth</tt> tag).
     * @param dimY new TIFF image height (<tt>ImageLength</tt> tag).
     */
    public DetailedIFD updateImageDimensions(int dimX, int dimY) {
        if (dimX <= 0) {
            throw new IllegalArgumentException("Zero or negative image width (x-dimension): " + dimX);
        }
        if (dimY <= 0) {
            throw new IllegalArgumentException("Zero or negative image height (y-dimension): " + dimY);
        }
        if (!containsKey(IFD.TILE_WIDTH) || !containsKey(IFD.TILE_LENGTH)) {
            // - we prefer not to throw FormatException here, like in hasTileInformation method
            checkImmutable("Image dimensions cannot be updated in non-tiled TIFF");
        }
        super.put(IFD.IMAGE_WIDTH, dimX);
        super.put(IFD.IMAGE_LENGTH, dimY);
        return this;
    }

    /**
     * Puts new values for <tt>TileOffsets</tt> / <tt>TileByteCounts</tt> tags or
     * <tt>StripOffsets</tt> / <tt>StripByteCounts</tt> tag, depending on result of
     * {@link #hasTileInformation()} methods (<tt>true</tt> or <tt>false</tt> correspondingly).
     *
     * <p>Note: this method works even when IFD is frozen by {@link #freeze()} method.
     *
     * @param offsets    byte offset of each tile/strip in TIFF file.
     * @param byteCounts number of (compressed) bytes in each tile/strip.
     */
    public void updateDataPositioning(long[] offsets, long[] byteCounts) {
        Objects.requireNonNull(offsets, "Null offsets");
        Objects.requireNonNull(byteCounts, "Null byte counts");
        final boolean tiled;
        final long tileCountX;
        final long tileCountY;
        final int numberOfSeparatedPlanes;
        try {
            tiled = hasTileInformation();
            tileCountX = getTileCountX();
            tileCountY = getTileCountY();
            numberOfSeparatedPlanes = isPlanarSeparated() ? getSamplesPerPixel() : 1;
        } catch (FormatException e) {
            throw new IllegalStateException("Illegal IFD: " + e.getMessage(), e);
        }
        final long totalCount = tileCountX * tileCountY * numberOfSeparatedPlanes;
        if (tileCountX * tileCountY > Integer.MAX_VALUE || totalCount > Integer.MAX_VALUE ||
                offsets.length != totalCount || byteCounts.length != totalCount) {
            throw new IllegalArgumentException("Incorrect offsets array (" +
                    offsets.length + " values) or byte-counts array (" + byteCounts.length +
                    " values) not equal to " + totalCount + " - actual number of " +
                    (tiled ? "tiles, " + tileCountX + " x " + tileCountY :
                            "strips, " + tileCountY) +
                    (numberOfSeparatedPlanes == 1 ? "" : " x " + numberOfSeparatedPlanes + " separated channels"));
        }
        super.put(tiled ? IFD.TILE_OFFSETS : IFD.STRIP_OFFSETS, offsets);
        super.put(tiled ? IFD.TILE_BYTE_COUNTS : IFD.STRIP_BYTE_COUNTS, byteCounts);
        // Just in case, let's also remove extra tags:
        super.remove(tiled ? IFD.STRIP_OFFSETS : IFD.TILE_OFFSETS);
        super.remove(tiled ? IFD.STRIP_BYTE_COUNTS : IFD.TILE_BYTE_COUNTS);
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


    @Override
    public String toString() {
        return toString(StringFormat.BRIEF);
    }

    public String toString(StringFormat format) {
        Objects.requireNonNull(format, "Null format");
        final StringBuilder sb = new StringBuilder("IFD");
        sb.append(" (%s)".formatted(subIFDType == null ? "main" : ifdTagName(subIFDType, false)));
        long dimX = 0;
        long dimY = 0;
        int channels = 0;
        int tileSizeX = 1;
        int tileSizeY = 1;
        try {
            sb.append(" ").append(TiffTools.pixelTypeToElementType(getPixelType()).getSimpleName());
            channels = getSamplesPerPixel();
            if (hasImageDimensions()) {
                dimX = getImageDimX();
                dimY = getImageDimY();
                tileSizeX = getTileSizeX();
                tileSizeY = getTileSizeY();
                sb.append("[%dx%dx%d], ".formatted(dimX, dimY, channels));
            } else {
                sb.append("[?x?x%d], ".formatted(channels));
            }
        } catch (Exception e) {
            sb.append(" [cannot detect basic information: ").append(e.getMessage()).append("] ");
        }
        try {
            final long tileCountX = (dimX + (long) tileSizeX - 1) / tileSizeX;
            final long tileCountY = (dimY + (long) tileSizeY - 1) / tileSizeY;
            sb.append("%s, precision %s%s, ".formatted(
                    isLittleEndian() ? "little-endian" : "big-endian",
                    FormatTools.getPixelTypeString(getPixelType()),
                    isBigTiff() ? " [BigTIFF]" : ""));
            if (hasTileInformation()) {
                sb.append("%dx%d=%d tiles %dx%d (last tile %sx%s)".formatted(
                        tileCountX,
                        tileCountY,
                        tileCountX * tileCountY,
                        tileSizeX,
                        tileSizeY,
                        remainderToString(dimX, tileSizeX),
                        remainderToString(dimY, tileSizeY)));
            } else {
                sb.append("%d strips per %d lines (last strip %s, virtual \"tiles\" %dx%d)".formatted(
                        tileCountY,
                        tileSizeY,
                        dimY == tileCountY * tileSizeY ? "full" : remainderToString(dimY, tileSizeY) + " lines",
                        tileSizeX,
                        tileSizeY));
            }
        } catch (Exception e) {
            sb.append(" [cannot detect additional information: ").append(e.getMessage()).append("]");
        }
        try {
            sb.append(isChunked() ? ", chunked" : ", planar");
        } catch (Exception e) {
            sb.append(" [").append(e.getMessage()).append("]");
        }
        if (hasFileOffsetOfReading()) {
            sb.append(", reading offset @%d=0x%X".formatted(fileOffsetOfReading, fileOffsetOfReading));
        }
        if (hasFileOffsetForWriting()) {
            sb.append(", writing offset @%d=0x%X".formatted(fileOffsetForWriting, fileOffsetForWriting));
        }
        if (hasNextIFDOffset()) {
            sb.append(isLastIFD() ? ", LAST" : ", next IFD at @%d=0x%X".formatted(nextIFDOffset, nextIFDOffset));
        }
        if (format == StringFormat.BRIEF) {
            return sb.toString();
        }
        sb.append("; ").append(numberOfEntries()).append(" entries:");
        final Map<Integer, TiffIFDEntry> entries = this.entries;
        final Collection<Integer> keySequence = format.sorted ?
                new TreeSet<>(this.keySet()) : entries != null ?
                entries.keySet() : this.keySet();
        // - entries.keySet provides guaranteed order of keys
        for (Integer tag : keySequence) {
            final Object v = this.get(tag);
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
                    case IFD.FILL_ORDER -> additional = getFillOrder().getName() + " bits order";
                    case IFD.PREDICTOR -> {
                        if (v instanceof Number number) {
                            switch (number.intValue()) {
                                case PREDICTOR_NONE -> additional = "none";
                                case PREDICTOR_HORIZONTAL -> additional = "horizontal subtraction";
                                case PREDICTOR_FLOATING_POINT -> additional = "floating-point subtraction";
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
                appendIFDArray(sb, v, format.compactArrays);
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

    // User does not need this operation: it is performed inside TiffWriter
    void putPhotometricInterpretation(PhotoInterp photometricInterpretation) {
        if (photometricInterpretation == null) {
            remove(IFD.PHOTOMETRIC_INTERPRETATION);
        } else {
            putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, photometricInterpretation.getCode());
        }
    }

    private void checkImmutable() {
        checkImmutable("IFD cannot be modified");
    }

    private void checkImmutable(String nameOfPart) {
        if (frozen) {
            throw new IllegalStateException(nameOfPart + ": it is frozen for future writing TIFF");
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

    private static int checkedIntValue(Number value, int tag) throws FormatException {
        Objects.requireNonNull(value);
        long result = value.longValue();
        if (result > Integer.MAX_VALUE) {
            throw new FormatException("Very large " + ifdTagName(tag, true) +
                    " = " + value + " >= 2^31 is not supported");
        }
        if (result < -Integer.MAX_VALUE) {
            throw new FormatException("Very large (by absolute value) negative " + ifdTagName(tag, true) +
                    " = " + value + " < -2^31 is not supported");
        }
        return (int) result;
    }

    private static int[] nInts(int count, int filler) {
        final int[] result = new int[count];
        Arrays.fill(result, filler);
        return result;
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

    private static void appendIFDArray(StringBuilder sb, Object v, boolean compact) {
        final int len = Array.getLength(v);
        if (v instanceof byte[] bytes) {
            appendIFDBytesArray(sb, bytes, compact);
            return;
        }
        final int left = v instanceof short[] || v instanceof char[] ? 25 : 10;
        final int right = v instanceof short[] || v instanceof char[] ? 10 : 5;
        final int mask = v instanceof short[] ? 0xFFFF : 0;
        for (int k = 0; k < len; k++) {
            if (compact && k == left && len >= left + 5 + right) {
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

    private static void appendIFDBytesArray(StringBuilder sb, byte[] v, boolean compact) {
        final int len = v.length;
        for (int k = 0; k < len; k++) {
            if (compact && k == 20 && len >= 35) {
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
