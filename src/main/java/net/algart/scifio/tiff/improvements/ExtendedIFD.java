package net.algart.scifio.tiff.improvements;

import io.scif.FormatException;
import io.scif.formats.tiff.FillOrder;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDType;
import org.scijava.log.LogService;

import java.lang.reflect.Array;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

//!! Better analog of IFD (should be merged with the main IFD)
public class ExtendedIFD extends IFD {
    public static final int ICC_PROFILE = 34675;
    public static final int IMAGE_DEPTH = 32997;

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
    public long getTilesPerRow() throws FormatException {
        return getTilesPerRow(getTileSizeX());
    }

    public int getTilesPerRow(int tileWidth) throws FormatException {
        if (tileWidth < 0) {
            throw new IllegalArgumentException("Negative tile width = " + tileWidth);
        }
        final long imageWidth = getImageWidth();
        if (imageWidth < 0) {
            throw new IllegalArgumentException("Negative image width = " + imageWidth);
        }
        assert imageWidth <= Integer.MAX_VALUE : "getImageWidth() did not check 31-bit result";
        if (tileWidth == 0) {
            return 0;
            // - it is better than throwing exception in this case (probably it is result
            // of getTileWidth() logic in a strange case getImageWidth()==0)
        }
        long n = imageWidth / tileWidth;
        if (n * tileWidth < imageWidth) {
            n++;
        }
        assert n <= Integer.MAX_VALUE : "ceil(" + imageWidth + "/" + tileWidth + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    //!! Better analog of IFD.getTilesPerColumn() (but it makes sense to change result type to "int")
    public long getTilesPerColumn() throws FormatException {
        return getTilesPerColumn(getTileSizeY());
    }

    public int getTilesPerColumn(int tileHeight) throws FormatException {
        if (tileHeight < 0) {
            throw new IllegalArgumentException("Negative tile height = " + tileHeight);
        }
        final long imageLength = getImageLength();
        if (imageLength < 0) {
            throw new IllegalArgumentException("Negative image height = " + imageLength);
        }
        assert imageLength <= Integer.MAX_VALUE : "getImageLength() did not check 31-bit result";
        if (tileHeight == 0) {
            return 0;
            // - it is better than throwing exception in this case (probably it is result
            // of getTileLength() logic in a strange case getImageLength()==0 or getRowsPerStrip()=={0,0,...})
        }
        long n = imageLength / tileHeight;
        if (n * tileHeight < imageLength) {
            n++;
        }
        assert n <= Integer.MAX_VALUE : "ceil(" + imageLength + "/" + tileHeight + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        final Map<Integer, IFDType> types = this.types;
        final Map<Integer, Object> sortedIFD = new TreeMap<>(this);
        for (Map.Entry<Integer, Object> entry : sortedIFD.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(String.format("%n"));
            }
            final Integer tag = entry.getKey();
            final Object value = entry.getValue();
            Object additional = null;
            try {
                switch (tag) {
                    case IFD.PHOTOMETRIC_INTERPRETATION -> additional = getPhotometricInterpretation();
                    case IFD.COMPRESSION -> additional = getCompression();
                    case IFD.PLANAR_CONFIGURATION -> {
                        if (value instanceof Number number) {
                            switch (number.intValue()) {
                                case PLANAR_CONFIG_CONTIGUOUSLY_CHUNKED -> additional = "chunky";
                                case PLANAR_CONFIG_SEPARATE -> additional = "planar";
                            }
                        }
                    }
                    case IFD.SAMPLE_FORMAT -> {
                        if (value instanceof Number number) {
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
            } catch (FormatException e) {
                additional = e;
            }
            sb.append("    ").append(ifdTagName(tag)).append(" = ");
            if (value != null && value.getClass().isArray()) {
                final int len = Array.getLength(value);
                sb.append(value.getClass().getComponentType().getSimpleName()).append("[").append(len).append("]");
                if (len <= 16) {
                    sb.append(" {").append(Array.get(value, 0));
                    for (int k = 1; k < len; k++) {
                        sb.append("; ").append(Array.get(value, k));
                    }
                    sb.append("}");
                }
            } else {
                sb.append(value);
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
}
