/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.maps.pyramids.io.formats.sources.svs;

import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.UpdatablePArray;
import net.algart.maps.pyramids.io.api.AbstractPlanePyramidSource;
import net.algart.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.maps.pyramids.io.api.PlanePyramidTools;
import net.algart.maps.pyramids.io.api.sources.RotatingPlanePyramidSource;
import net.algart.maps.pyramids.io.formats.sources.svs.metadata.SVSAdditionalCombiningInfo;
import net.algart.maps.pyramids.io.formats.sources.svs.metadata.SVSImageDescription;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.math.Point;
import net.algart.math.RectangularArea;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffMap;

import java.awt.*;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class SVSPlanePyramidSource extends AbstractPlanePyramidSource implements PlanePyramidSource {
    private static final boolean ENABLE_VIRTUAL_LAYERS = true;
    private static final int SVS_IFD_THUMBNAIL_INDEX = 1;
    private static final int COMBINING_LITTLE_GAP = 3;
    // - the gap is necessary to avoid little 1-2-pixel artifacts (black lines) due to not ideal positioning
    // the pyramid image at our coordinate space (expanded to the sizes of the whole slide)

    // The following code is deprecated. It was necessary as a workarond for the bug in earlier versions
    // of SCIFIO (like 0.11.0): this library did not work while calling from JNI. In this case,
    //      Thread.currentThread().getContextClassLoader()
    // returns null, and it was not processed properly by old class org.scijava.plugin.DefaultPluginFinder
    //
    //    static {
    //        long t1 = System.nanoTime();
    //        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    //        if (contextClassLoader == null) {
    //            Thread.currentThread().setContextClassLoader(SVSPlanePyramidSource.class.getClassLoader());
    //        }
    //        long t2 = System.nanoTime();
    //        debug(1, "SVS reader class initializing SCIFIO: %.3f ms%n", (t2 - t1) * 1e-6);
    //    }

    public static final byte TIFF_FILLER = (byte) 0xF0;
    // - almost white; for SVS, usually it is better idea than black color

    private static final System.Logger LOG = System.getLogger(SVSPlanePyramidSource.class.getName());

    private final Path svsFile;
    private final SVSIFDClassifier ifdClassifier;
    private final List<SVSImageDescription> imageDescriptions;
    private final SVSImageDescription mainImageDescription;
    private final boolean geometrySupported;
    private final boolean combineWithWholeSlide;
    private final boolean virtualLayers;
    private final boolean moticFormat;
    private final int numberOfActualResolutions;
    private final int actualCompression;
    private final int compression;
    private final int numberOfResolutions;
    private final List<long[]> dimensions;
    private final long dimX;
    private final long dimY;
    private final Class<?> elementType;
    private final int bandCount;
    private final Double pixelSizeInMicrons;
    private final Double magnification;
    private final RectangularArea metricWholeSlide;
    private final RectangularArea metricPyramid;
    private final IRectangularArea pixelWholeSlide;
    private final IRectangularArea pixelPyramidAtWholeSlide;
    private final double zeroLevelPixelSize;
    private final LargeDataHolder largeData = new LargeDataHolder();

    private volatile Color dataBorderColor = Color.GRAY;
    private volatile int dataBorderWidth = 0;

    public SVSPlanePyramidSource(Path svsFile) throws IOException {
        this(svsFile, false, null);
    }

    public SVSPlanePyramidSource(
            Path svsFile,
            boolean combineWithWholeSlideRequest,
            SVSAdditionalCombiningInfo additionalCombiningInfo)
            throws IOException {
        Objects.requireNonNull(svsFile, "Null svsFile");
        long t1 = System.nanoTime();
        this.svsFile = svsFile;
        this.largeData.init();
        boolean success = false;
        try {
            final int ifdCount = largeData.maps.size();
            final TiffMap map0 = largeData.maps.get(0);
            this.bandCount = map0.numberOfChannels();
            assert bandCount > 0;
            this.elementType = map0.elementType();
            final long imageDimX = map0.dimX();
            final long imageDimY = map0.dimY();
            this.imageDescriptions = new ArrayList<>();
            for (int k = 0; k < ifdCount; k++) {
                final String description = largeData.maps.get(k).description().orElse(null);
                this.imageDescriptions.add(SVSImageDescription.valueOf(description));
                // Note: though description can be null, SVSImageDescription object will never be null here
            }
            this.mainImageDescription = findMainImageDescription(imageDescriptions);
            if (mainImageDescription != null) {
                this.pixelSizeInMicrons = mainImageDescription.isPixelSizeSupported() ?
                        mainImageDescription.pixelSize() :
                        null;
                this.magnification = mainImageDescription.isMagnificationSupported() ?
                        mainImageDescription.magnification() :
                        null;
            } else {
                this.pixelSizeInMicrons = null;
                this.magnification = null;
            }
            this.moticFormat = detectMotic(largeData.maps, mainImageDescription);
            this.ifdClassifier = new SVSIFDClassifier(largeData.maps);
            LOG.log(System.Logger.Level.DEBUG, () -> "SVS reader classified IFDs: " + ifdClassifier);
            final TiffMap mapMacro = ifdClassifier.hasMacro() ?
                    largeData.maps.get(ifdClassifier.getMacroIndex()) :
                    null;
            final long ifdMacroWidth = mapMacro == null ? -1 : mapMacro.dimX();
            final long ifdMacroHeight = mapMacro == null ? -1 : mapMacro.dimY();
            final Double slideWidthInMicrons = additionalCombiningInfo == null ? null :
                    additionalCombiningInfo.getSlideWidthInMicrons();
            final Double slideHeightInMicrons = additionalCombiningInfo == null ? null :
                    additionalCombiningInfo.getSlideHeightInMicrons();
            this.geometrySupported = mainImageDescription != null
                    && mainImageDescription.isGeometrySupported()
                    && (slideWidthInMicrons != null || slideHeightInMicrons != null);
            this.combineWithWholeSlide = combineWithWholeSlideRequest
                    && mapMacro != null
                    && geometrySupported
                    && mapMacro.elementType() == elementType
                    && mapMacro.numberOfChannels() == bandCount;
            long levelDimX, levelDimY;
            if (combineWithWholeSlide) {
                this.zeroLevelPixelSize = mainImageDescription.pixelSize();
                final double slideWidth = slideWidthInMicrons != null ? slideWidthInMicrons
                        : Math.round(slideHeightInMicrons * (double) ifdMacroWidth / (double) ifdMacroHeight);
                final double slideHeight = slideHeightInMicrons != null ? slideHeightInMicrons
                        : Math.round(slideWidthInMicrons * (double) ifdMacroHeight / (double) ifdMacroWidth);
                this.metricWholeSlide = RectangularArea.valueOf(
                        Point.valueOf(0, 0),
                        Point.valueOf(slideWidth, slideHeight));
                final double imageWidth = imageDimX * zeroLevelPixelSize;
                final double imageHeight = imageDimY * zeroLevelPixelSize;
                final double metricLeft = mainImageDescription.imageOnSlideLeftInMicronsAxisRightward();
                final double metricTop = slideHeight - mainImageDescription.imageOnSlideTopInMicronsAxisUpward();
                this.metricPyramid = RectangularArea.valueOf(
                        Point.valueOf(metricLeft, metricTop),
                        Point.valueOf(metricLeft + imageWidth, metricTop + imageHeight));
                this.pixelWholeSlide = IRectangularArea.valueOf(
                        IPoint.valueOf(0, 0),
                        IPoint.valueOf(ifdMacroWidth - 1, ifdMacroHeight - 1));
                final long pixelLeft = Math.round(ifdMacroWidth * metricLeft / slideWidth);
                final long pixelTop = Math.round(ifdMacroHeight * metricTop / slideHeight);
                this.pixelPyramidAtWholeSlide = IRectangularArea.valueOf(
                        IPoint.valueOf(pixelLeft, pixelTop),
                        IPoint.valueOf(
                                Math.max(pixelLeft,
                                        Math.round(ifdMacroWidth * (metricLeft + imageWidth) / slideWidth) - 1),
                                Math.max(pixelTop,
                                        Math.round(ifdMacroHeight * (metricTop + imageHeight) / slideHeight) - 1)));
                levelDimX = (long) (imageDimX * metricWholeSlide.size(0) / metricPyramid.size(0));
                levelDimY = (long) (imageDimY * metricWholeSlide.size(1) / metricPyramid.size(1));
            } else {
                this.zeroLevelPixelSize = Double.NaN;
                this.metricWholeSlide = null;
                this.metricPyramid = null;
                this.pixelWholeSlide = null;
                this.pixelPyramidAtWholeSlide = null;
                levelDimX = imageDimX;
                levelDimY = imageDimY;
            }

            this.dimX = levelDimX;
            this.dimY = levelDimY;
            if (dimX > Integer.MAX_VALUE || dimY > Integer.MAX_VALUE) {
                throw new TiffException("Too large image " + dimX + "x" + dimY);
                // Impossible if !this.combineWithWholeSlide (already checked by
                // TiffTools.checkThatIfdSizesArePositiveIntegers),
                // very improbable if this.combineWithWholeSlide.
                // This check also allows to be sure that "(long) v" operator above is  executed without overflow.
            }
            LOG.log(System.Logger.Level.DEBUG,
                    () -> String.format(Locale.US, "SVS reader opens image %s: %s[%dx%d]%n"
                                    + "  IFD #0/%d %dx%d, %s-endian, %d bands%n"
                                    + "  combineWithWholeSlide mode: %s (%s); geometrySupported: %s%n"
                                    + "  whole slide area %s (microns);%n"
                                    + "  pyramid area %s (microns);%n"
                                    + "  SVS reader checks IFD #0: IFD compression method: %s; description:%n%s",
                            svsFile,
                            elementType,
                            dimX, dimY, ifdCount,
                            imageDimX, imageDimY, largeData.tiffReader.isLittleEndian() ? "little" : "big", bandCount,
                            combineWithWholeSlide,
                            combineWithWholeSlideRequest ? "requested" : "NOT requested",
                            geometrySupported,
                            metricWholeSlide, metricPyramid, SVSIFDClassifier.compressionToString(map0),
                            printedDescription(0)));
            final ArrayList<long[]> actualDimensions = new ArrayList<>();
            // - these dimensions are not virtual, but in a case of combining with whole slide
            // they are dimensions of whole result, not only IFD
            actualDimensions.add(new long[]{bandCount, this.dimX, this.dimY});
            int actualCompression = 0;
            long pyramidLevelDimX = imageDimX;
            long pyramidLevelDimY = imageDimY;
            for (int k = 1; k < ifdCount; k++) {
                final int index = k;
                final TiffMap map = largeData.maps.get(k);
                if (ifdClassifier.isSpecial(k)) {
                    LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                            "  SVS reader skips special IFD #%d/%d: %s, IFD compression method: %s",
                            index, ifdCount,
                            SVSIFDClassifier.sizesToString(map),
                            SVSIFDClassifier.compressionToString(map)));
                    continue;
                }
                final long newPyramidLevelDimX = map.dimX();
                final long newPyramidLevelDimY = map.dimY();
                if (actualCompression == 0) {
                    actualCompression = PlanePyramidTools.findCompression(
                            new long[]{bandCount, pyramidLevelDimX, pyramidLevelDimY},
                            new long[]{bandCount, newPyramidLevelDimX, newPyramidLevelDimY});
                    if (actualCompression == 0) {
                        throw new IllegalArgumentException("Cannot find suitable compression for first "
                                + "two levels " + levelDimX + "x" + levelDimY + " and "
                                + newPyramidLevelDimX + "x" + newPyramidLevelDimY);
                    }
                    final var ac = actualCompression;
                    LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                            "SVS reader automatically detected compression %d", ac));
                }
                if (combineWithWholeSlide) {
                    levelDimX /= actualCompression;
                    levelDimY /= actualCompression;
                } else {
                    levelDimX = newPyramidLevelDimX;
                    levelDimY = newPyramidLevelDimY;
                }
                if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                    LOG.log(System.Logger.Level.DEBUG, String.format(
                            "  SVS reader checks IFD #%d/%d: "
                                    + "%dx%d, IFD %dx%d; IFD compression method: %s; description: %s",
                            k, ifdCount, levelDimX, levelDimY, newPyramidLevelDimX, newPyramidLevelDimY,
                            SVSIFDClassifier.compressionToString(map), printedDescription(k)));
                }
                if (!PlanePyramidTools.isDimensionsRelationCorrect(
                        new long[]{bandCount, pyramidLevelDimX, pyramidLevelDimY},
                        new long[]{bandCount, newPyramidLevelDimX, newPyramidLevelDimY},
                        actualCompression)) {
                    final int remaining = ifdCount - k;
                    LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                            "SVS reader found incorrect compression; skipping following %d IFDs", remaining));
                    for (int i = k; i < ifdCount; i++) {
                        final int skippedIndex = i;
                        final TiffMap skippedMap = largeData.maps.get(i);
                        if (ifdClassifier.isSpecial(i)) {
                            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                                    "  SVS reader skips special IFD #%d/%d: %s, IFD compression method: %s",
                                    skippedIndex, ifdCount,
                                    SVSIFDClassifier.sizesToString(skippedMap),
                                    SVSIFDClassifier.compressionToString(skippedMap)));
                        } else {
                            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                                    "  SVS reader skips unknown%s IFD #%d/%d: "
                                            + "%s; IFD compression method: %s; description:%n%s",
                                    ifdClassifier.isUnknownSpecial(skippedIndex) ? " (probably special)" : "",
                                    skippedIndex, ifdCount,
                                    SVSIFDClassifier.sizesToString(skippedMap),
                                    SVSIFDClassifier.compressionToString(skippedMap),
                                    printedDescription(skippedIndex)));
                        }
                    }
                    break;
                }
                final Class<?> elementType = map.elementType();
                if (elementType != this.elementType) {
                    throw new TiffException("Invalid element types: \""
                            + elementType + "\" instead of \"" + this.elementType + "\""
                            + " (image description " + map.description().orElse("N/A") + ")");
                }
                if (map.numberOfChannels() != bandCount) {
                    throw new TiffException("Invalid number of samples per pixel: "
                            + map.numberOfChannels() + " instead of " + bandCount
                            + " (image description " + map.description().orElse("N/A") + ")");
                }
                actualDimensions.add(new long[]{bandCount, levelDimX, levelDimY});
                pyramidLevelDimX = newPyramidLevelDimX;
                pyramidLevelDimY = newPyramidLevelDimY;
            }

            this.actualCompression = actualCompression == 0 ? 2 : actualCompression;
            this.numberOfActualResolutions = actualDimensions.size();
            this.virtualLayers = ENABLE_VIRTUAL_LAYERS && combineWithWholeSlide
                    && (this.actualCompression & (this.actualCompression - 1)) == 0;
            // - power of two; note that if this.actualCompression==2, adding virtual layers
            // is still useful for optimizing scaling wholeSlidePyramid on low resolutions
            // (for example, if numberOfActualResolutions==1 and it is a large layer,
            // the external PlanePyramid class will access this source only for this layer)
            if (virtualLayers) {
                this.compression = 2;
                this.numberOfResolutions = PlanePyramidTools.numberOfResolutions(
                        dimX, dimY, this.compression, PlanePyramidTools.MIN_PYRAMID_LEVEL_SIDE);
                assert numberOfResolutions >= 1;
                this.dimensions = new ArrayList<>();
                levelDimX = dimX;
                levelDimY = dimY;
                for (int k = 0; k < numberOfResolutions; k++) {
                    if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                        LOG.log(System.Logger.Level.DEBUG, String.format(
                                "SVS reader adds layer #%d %s%d): %dx%d",
                                k, dimensionsContains(actualDimensions, levelDimX, levelDimY) ?
                                        "(actual layer " :
                                        "(virtual, nearest actual is ",
                                resolutionLevelToActualResolutionLevel(k), levelDimX, levelDimY));
                    }
                    this.dimensions.add(new long[]{bandCount, levelDimX, levelDimY});
                    levelDimX /= 2;
                    levelDimY /= 2;
                }
            } else {
                LOG.log(System.Logger.Level.DEBUG, "SVS reader does not create virtual layers");
                this.compression = this.actualCompression;
                this.numberOfResolutions = numberOfActualResolutions;
                this.dimensions = actualDimensions;
            }
            long t2 = System.nanoTime();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "SVS reader found %d layers with correct inter-layer compression"
                            + " among %d total IFDs; thumbnail %s, label %s, macro %s",
                    this.numberOfActualResolutions, ifdCount,
                    ifdClassifier.hasThumbnail() ? "found in IFD #" + ifdClassifier.getThumbnailIndex() : "NOT found",
                    ifdClassifier.hasLabel() ? "found in IFD #" + ifdClassifier.getLabelIndex() : "NOT found",
                    ifdClassifier.hasMacro() ? "found in IFD #" + ifdClassifier.getMacroIndex() : "NOT found"));
            //            if (DEBUG_LEVEL >= 3) {
//                System.out.printf("Detailed IFD information:%n");
//                final long[] offsets = largeData.tiffReader.getParser().getIFDOffsets();
//                for (int k = 0; k < ifdCount; k++) {
//                    System.out.printf("  %d (offset %d):%n%s",
//                        k, offsets[k], TiffTools.toString(largeData.tiffReader.getIFDByIndex(k)));
//                }
//            }
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                    "SVS reader instantiating %s: %.3f ms", this, (t2 - t1) * 1e-6));
            success = true;
        } finally {
            if (!success) {
                largeData.freeResources();
            }
        }
    }

    public Color getDataBorderColor() {
        return dataBorderColor;
    }

    public void setDataBorderColor(Color dataBorderColor) {
        if (dataBorderColor == null) {
            throw new NullPointerException("Null dataBorderColor");
        }
        this.dataBorderColor = dataBorderColor;
    }

    public int getDataBorderWidth() {
        return dataBorderWidth;
    }

    public void setDataBorderWidth(int dataBorderWidth) {
        if (dataBorderWidth < 0) {
            throw new IllegalArgumentException("Negative dataBorderWidth");
        }
        this.dataBorderWidth = dataBorderWidth;
    }

    public Path getSvsFile() {
        return svsFile;
    }

    public SVSIFDClassifier getIfdClassifier() {
        return ifdClassifier;
    }

    public long getDimX() {
        return dimX;
    }

    public long getDimY() {
        return dimY;
    }

    public boolean isCombineWithWholeSlide() {
        return combineWithWholeSlide;
    }

    public boolean isGeometrySupported() {
        return geometrySupported;
    }

    // We do not use "get" syntax for correct JSONObject behaviour
    public List<SVSImageDescription> imageDescriptions() {
        return Collections.unmodifiableList(imageDescriptions);
    }

    // We do not use "get" syntax for correct JSONObject behaviour
    public SVSImageDescription mainImageDescription() {
        return mainImageDescription;
    }

    // We do not use "get" syntax for correct JSONObject behaviour
    public RectangularArea metricWholeSlide() {
        return metricWholeSlide;
    }

    // We do not use "get" syntax for correct JSONObject behaviour
    public RectangularArea metricPyramid() {
        return metricPyramid;
    }

    // We do not use "get" syntax for correct JSONObject behaviour
    public IRectangularArea pixelWholeSlide() {
        return pixelWholeSlide;
    }

    // We do not use "get" syntax for correct JSONObject behaviour
    public IRectangularArea pixelPyramidAtWholeSlide() {
        return pixelPyramidAtWholeSlide;
    }


    public boolean isMoticFormat() {
        return moticFormat;
    }

    // Argument may be null for typical usage
    public RotatingPlanePyramidSource.RotationMode recommendedRotation(SpecialImageKind kind) {
        if (moticFormat) {
            return kind == SpecialImageKind.LABEL_ONLY_IMAGE ?
                    RotatingPlanePyramidSource.RotationMode.CLOCKWISE_270 :
                    RotatingPlanePyramidSource.RotationMode.CLOCKWISE_90;
        } else {
            return RotatingPlanePyramidSource.RotationMode.NONE;
        }
    }

    @Override
    public int numberOfResolutions() {
        return numberOfResolutions;
    }

    @Override
    public int compression() {
        return compression;
    }

    @Override
    public int bandCount() {
        return bandCount;
    }

    @Override
    public long[] dimensions(int resolutionLevel) {
        checkResolutionLevel(resolutionLevel);
        return dimensions.get(resolutionLevel).clone();
    }

    @Override
    public long dim(int resolutionLevel, int index) {
        checkResolutionLevel(resolutionLevel);
        return dimensions.get(resolutionLevel)[index];
    }

    @Override
    public boolean isElementTypeSupported() {
        return true;
    }

    @Override
    public Class<?> elementType() throws UnsupportedOperationException {
        return elementType;
    }

    @Override
    public OptionalDouble pixelSizeInMicrons() {
        return pixelSizeInMicrons == null ? OptionalDouble.empty() : OptionalDouble.of(pixelSizeInMicrons);
    }

    @Override
    public OptionalDouble magnification() {
        return magnification == null ? OptionalDouble.empty() : OptionalDouble.of(magnification);
    }

    @Override
    public List<IRectangularArea> zeroLevelActualRectangles() {
        return combineWithWholeSlide ?
                Collections.singletonList(metricPyramidForCombiningAtLevel(0)) :
                null;
    }

    @Override
    public boolean isSpecialMatrixSupported(SpecialImageKind kind) {
        return ifdClassifier.isSpecialMatrixSupported(kind);
    }

    @Override
    public Optional<Matrix<? extends PArray>> readSpecialMatrix(SpecialImageKind kind) {
        Objects.requireNonNull(kind, "Null image kind");
        largeData.writeLock.lock();
        try {
            largeData.init();
            OptionalInt ifdIndex = ifdClassifier.getSpecialKindIndex(kind);
            if (ifdIndex.isEmpty()) {
                LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                        "SVS reader cannot read special image %s: it is not supported", kind));
                return super.readSpecialMatrix(kind);
            }
            final int i = ifdIndex.getAsInt();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "SVS reading special image %s (IFD #%d)", kind, i));
            final TiffIFD ifd = largeData.maps.get(i).ifd();
            final int width = ifd.getImageDimX();
            final int height = ifd.getImageDimY();
            assert width > 0 && height > 0;
            // - already checked in the constructor
            return Optional.of(readData(i, 0, 0, (int) width, (int) height));
        } catch (TiffException e) {
            throw new IOError(PlanePyramidTools.rmiSafeWrapper(e));
        } catch (IOException e) {
            throw new IOError(e);
        } finally {
            largeData.writeLock.unlock();
        }
    }

    @Override
    public Optional<String> metadata() {
        return mainImageDescription == null ? Optional.empty() : Optional.of(mainImageDescription.toString());
    }

    public void loadResources() {
        largeData.writeLock.lock();
        try {
            largeData.init();
        } catch (TiffException e) {
            throw new IOError(PlanePyramidTools.rmiSafeWrapper(e));
        } catch (IOException e) {
            throw new IOError(e);
        } finally {
            largeData.writeLock.unlock();
        }
        super.loadResources();
    }

    @Override
    public void freeResources(FlushMode flushMode) {
        super.freeResources(flushMode);
        largeData.writeLock.lock();
        try {
            largeData.freeResources();
        } finally {
            largeData.writeLock.unlock();
        }
    }

    @Override
    public String toString() {
        return "SVS plane pyramid source for file " + svsFile;
    }

    @Override
    protected Matrix<? extends PArray> readLittleSubMatrix(
            int resolutionLevel, long fromX, long fromY, long toX, long toY) {
        final long[] dim = dimensions(resolutionLevel);
        final long dimX = dim[DIM_WIDTH];
        final long dimY = dim[DIM_HEIGHT];
        assert dimX <= Integer.MAX_VALUE;
        assert dimY <= Integer.MAX_VALUE;
        // - already checked in the constructor
        if (fromX < 0 || fromY < 0 || fromX > toX || fromY > toY || toX > dimX || toY > dimY) {
            throw new IndexOutOfBoundsException("Illegal fromX/fromY/toX/toY: must be in ranges 0.."
                    + dimX + ", 0.." + dimY + ", fromX<=toX, fromY<=toY");
        }
        final int sizeX = (int) (toX - fromX);
        final int sizeY = (int) (toY - fromY);
        assert sizeX == toX - fromX;
        assert sizeY == toY - fromY;
        assert fromX == (int) fromX;
        assert fromY == (int) fromY;
        // - due to the check above
        if (sizeX == 0 || sizeY == 0) {
            return newResultMatrix(toX - fromX, toY - fromY);
        }
        final IRectangularArea requiredArea = IRectangularArea.valueOf(
                IPoint.valueOf(fromX, fromY), IPoint.valueOf(toX - 1, toY - 1));
        if (combineWithWholeSlide) {
            largeData.initWholeSlideSynchronously();
        }
        Lock lock = largeData.readLock;
        lock.lock();
        if (!largeData.initialized()) {
            // switching to stronger lock: not optimal, but safe
            lock.unlock();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "%n%nSVS reader switches to exclusive lock%n%n"));
            lock = largeData.writeLock;
            lock.lock();
        } // if initialized, this status cannot be changed until readLock.unlock(): we can safely read data
        try {
            largeData.init();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "SVS reading %d..%d x %d..%d (%dx%d), level %d from %s",
                    fromX, toX, fromY, toY, toX - fromX, toY - fromY, resolutionLevel, svsFile));
            if (combineWithWholeSlide) {
                final IRectangularArea actualArea = metricPyramidForCombiningAtLevel(resolutionLevel);
                final IRectangularArea borderedArea = expandByBorder(actualArea);
                final boolean combiningNotNecessary = actualArea.contains(requiredArea);
                LOG.log(System.Logger.Level.TRACE, () -> String.format(
                        " Actual area %s, combining%s necessary",
                        actualArea, combiningNotNecessary ? "NOT " : ""));
                if (combiningNotNecessary) {
                    return readAndCompressDataFromLevel(resolutionLevel,
                            (int) (fromX - actualArea.min(0)), (int) (fromY - actualArea.min(1)),
                            sizeX, sizeY);
                }
                final IRectangularArea intersection = actualArea.intersection(requiredArea);
                final IRectangularArea borderedIntersection = borderedArea.intersection(requiredArea);
                final boolean skipCoarseData = isSkipCoarseData();
                if (skipCoarseData && borderedIntersection == null) {
                    LOG.log(System.Logger.Level.DEBUG, "SVS skipping data");
                    return constantMatrixSkippingFiller(elementType(), sizeX, sizeY);
                }

                final Matrix<? extends UpdatablePArray> result = newResultMatrix(sizeX, sizeY);
                Matrix<? extends PArray> wholeSlide = largeData.wholeSlidePyramid.get(0);
                for (int k = 1; k < largeData.wholeSlidePyramid.size(); k++) {
                    final Matrix<? extends PArray> m = largeData.wholeSlidePyramid.get(k);
                    if (m.dim(DIM_WIDTH) >= dimX && m.dim(DIM_HEIGHT) >= dimY) {
                        wholeSlide = m;
                    } else {
                        break;
                    }
                }
                final double scaleX = (double) wholeSlide.dim(DIM_WIDTH) / (double) dimX;
                final double scaleY = (double) wholeSlide.dim(DIM_HEIGHT) / (double) dimY;
                final long fromXAtWholeSlide = Math.round(fromX * scaleX);
                final long fromYAtWholeSlide = Math.round(fromY * scaleY);
                final long toXAtWholeSlide = Math.round(toX * scaleX);
                final long toYAtWholeSlide = Math.round(toY * scaleY);
                // - here we cannot go out from wholeSlide dimensions
                if (skipCoarseData) {
                    fillBySkippingFiller(result, false);
                } else {
                    final Matrix<? extends PArray> subMatrixAtWholeSlide = wholeSlide.subMatrix(
                            0, fromXAtWholeSlide, fromYAtWholeSlide, bandCount, toXAtWholeSlide, toYAtWholeSlide);
                    Matrices.resize(null, Matrices.ResizingMethod.AVERAGING, result, subMatrixAtWholeSlide);
                    // null argument forces usage of all CPU kernels even if ArrayContext recommends only 1 thread
                }
                if (borderedIntersection == null) {
                    return result;
                }
                if (dataBorderWidth > 0) {
                    PlanePyramidTools.fillMatrix(result,
                            borderedIntersection.min(0) - requiredArea.min(0),
                            borderedIntersection.min(1) - requiredArea.min(1),
                            borderedIntersection.max(0) + 1 - requiredArea.min(0),
                            borderedIntersection.max(1) + 1 - requiredArea.min(1),
                            dataBorderColor);
                }
                if (intersection == null) {
                    return result;
                }
                final Matrix<? extends PArray> actual = readAndCompressDataFromLevel(resolutionLevel,
                        (int) (intersection.min(0) - actualArea.min(0)),
                        (int) (intersection.min(1) - actualArea.min(1)),
                        (int) intersection.size(0), (int) intersection.size(1));
                LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                        "SVS combining (level %d): %d..%d x %d..%d (%d x %d) from whole slide image "
                                + "with actual image %s (intersection %d x %d)",
                        resolutionLevel,
                        fromXAtWholeSlide, toXAtWholeSlide, fromYAtWholeSlide, toYAtWholeSlide,
                        toXAtWholeSlide - fromXAtWholeSlide, toYAtWholeSlide - fromYAtWholeSlide,
                        actualArea,
                        intersection.size(0),
                        intersection.size(1)));
                assert requiredArea.size(0) == sizeX;
                assert requiredArea.size(1) == sizeY;
                result.subMatr(
                                0, intersection.min(0) - requiredArea.min(0), intersection.min(1) - requiredArea.min(1),
                                bandCount, intersection.size(0), intersection.size(1))
                        .array().copy(actual.array());
                return result;
            } else {
                return readAndCompressDataFromLevel(resolutionLevel, (int) fromX, (int) fromY, sizeX, sizeY);
            }
        } catch (TiffException e) {
            throw new IOError(PlanePyramidTools.rmiSafeWrapper(e));
        } catch (IOException e) {
            throw new IOError(e);
        } finally {
            lock.unlock();
        }
    }

    private static boolean detectMotic(List<TiffMap> maps, SVSImageDescription mainImageDescription)
            throws TiffException {
        // Warning! It is an evristic algorithm that should be improved in collaboration with Motic!
        if (mainImageDescription != null && mainImageDescription.isGeometrySupported()) {
            // current version of Motic slides does not support geometry
            return false;
        }
        int numberOfLZW = 0;
        for (int ifdIndex = 0; ifdIndex < maps.size(); ifdIndex++) {
            final TiffIFD ifd = maps.get(ifdIndex).ifd();
            if (!SVSIFDClassifier.isSmallImage(ifd) || ifdIndex == SVS_IFD_THUMBNAIL_INDEX) {
                continue;
            }
            if (ifd.getCompressionCode() == TagCompression.LZW.code()) {
                // only Label in standard SVS files
                numberOfLZW++;
            }
        }
        return numberOfLZW == 2;
    }

    private static SVSImageDescription findMainImageDescription(List<SVSImageDescription> imageDescriptions) {
        for (SVSImageDescription description : imageDescriptions) {
            if (description.isImportant()) {
                return description;
            }
        }
        return null;
    }

    private static boolean dimensionsContains(List<long[]> dimensions, long levelDimX, long levelDimY) {
        for (long[] dim : dimensions) {
            if (dim[1] == levelDimX && dim[2] == levelDimY) {
                return true;
            }
        }
        return false;
    }

    private Matrix<? extends PArray> readAndCompressDataFromLevel(
            int resolutionLevel, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        final int actualResolutionLevel = resolutionLevelToActualResolutionLevel(resolutionLevel);
        final long requiredCompression = compression(resolutionLevel);
        final long requiredActualCompression = actualCompression(actualResolutionLevel);
        final long additionalCompression = requiredCompression / requiredActualCompression;
        assert additionalCompression == (int) additionalCompression;
        final int ifdIndex = actualResolutionLevel < SVS_IFD_THUMBNAIL_INDEX ?
                actualResolutionLevel : actualResolutionLevel + 1;
        final int actualSizeX = sizeX * (int) additionalCompression;
        final int actualSizeY = sizeY * (int) additionalCompression;
        final int actualFromY = fromY * (int) additionalCompression;
        final int actualFromX = fromX * (int) additionalCompression;
        final Matrix<? extends PArray> actualMatrix =
                readData(ifdIndex, actualFromX, actualFromY, actualSizeX, actualSizeY);
        if (additionalCompression == 1) {
            return actualMatrix;
        }
        final Matrix<? extends UpdatablePArray> result = newResultMatrix(sizeX, sizeY);
        Matrices.resize(null, Matrices.ResizingMethod.AVERAGING, result, actualMatrix);
        // null argument forces usage of all CPU kernels even if ArrayContext recommends only 1 thread
        return result;
    }

    private Matrix<? extends PArray> readData(int ifdIndex, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        final TiffMap map = largeData.maps.get(ifdIndex);
        map.checkPixelCompatibility(bandCount, TiffSampleType.valueOf(elementType, false));
        return largeData.tiffReader.readMatrix(map, fromX, fromY, sizeX, sizeY);
    }

    private int resolutionLevelToActualResolutionLevel(int resolutionLevel) {
        final long requiredCompression = compression(resolutionLevel);
        long currentCompression = 1;
        int result = 0;
        while (result < numberOfActualResolutions && currentCompression <= requiredCompression) {
            result++;
            currentCompression *= actualCompression;
        }
        return result - 1;
    }

    private long compression(int resolutionLevel) {
        long result = 1;
        for (int k = 0; k < resolutionLevel; k++) {
            result *= compression;
        }
        return result;
    }

    private long actualCompression(int actualResolutionLevel) {
        long result = 1;
        for (int k = 0; k < actualResolutionLevel; k++) {
            result *= actualCompression;
        }
        return result;
    }

    private void checkResolutionLevel(int resolutionLevel) {
        if (resolutionLevel < 0) {
            throw new IllegalArgumentException("Negative resolution level = " + resolutionLevel);
        }
        if (resolutionLevel >= dimensions.size()) {
            throw new IndexOutOfBoundsException("Invalid resolution level " + resolutionLevel
                    + ": must be < " + dimensions.size() + " (number of resolutions)");
        }
    }


    private IRectangularArea metricPyramidForCombiningAtLevel(int resolutionLevel) {
        final double pixelSize = zeroLevelPixelSize * compression(resolutionLevel);
        IPoint min = metricPyramid.min().subtract(metricWholeSlide.min()).multiply(1.0 / pixelSize).toRoundedPoint();
        IPoint max = metricPyramid.max().subtract(metricWholeSlide.min()).multiply(1.0 / pixelSize).toRoundedPoint();
        // - possible overflow is not a problem here: we will just make an empty rectangle
        // like (Long.MAX_VALUE..Long.MAX_VALUE) x (minY..maxY)
        min = min.addToAllCoordinates(COMBINING_LITTLE_GAP);
        max = max.addToAllCoordinates(-COMBINING_LITTLE_GAP - 1L);
        max = max.max(min);
        // - the last operator is usually not necessary (pyramid area is large enough), but in a strange case
        // of very little pyramid it allows to avoid exception in IRectangularArea.valueOf
        return IRectangularArea.valueOf(min, max);
    }

    private IRectangularArea expandByBorder(IRectangularArea area) {
        return IRectangularArea.valueOf(
                area.min().addToAllCoordinates(-dataBorderWidth),
                area.max().addToAllCoordinates(dataBorderWidth));
    }

    private String printedDescription(int index) {
        final StringBuilder sb = new StringBuilder();
        final SVSImageDescription imageDescription = imageDescriptions.get(index);
        final String subFormatTitle = imageDescription.subFormatTitle();
        if (subFormatTitle != null) {
            sb.append(String.format("    [%s]%n", subFormatTitle));
        }
        for (String line : imageDescription.getText()) {
            sb.append(String.format("    %s%n", line));
        }
        final String result = sb.toString();
        return result.endsWith("%n") ? result.substring(0, result.length() - 2) : result;
    }

    // Important! PlanePyramidSource objects are often cloned, usually before every reading data,
    // because this class extends AbstractArrayProcessorWithContextSwitching. It can lead to serious problems.
    // 1) If we shall implement finalize() method in that class, it will often be called in clones also,
    // and it will lead to a bug: files will be closed, though other clones are active yet.
    // 2) If someone will call freeResources() in that class, then all clones will be created,
    // since that time, in this (disposed) state; so all clones will work normally, but very slowly:
    // the files will be reopened every time when PlanePyramid needs to read data and creates a clone for this.
    // LargeDataHolder class resolves all these problems, because the reference to it is shared among all clones.
    private class LargeDataHolder {
        private TiffReader tiffReader = null;
        private List<TiffMap> maps = null;
        private List<Matrix<? extends PArray>> wholeSlidePyramid = null;

        private final Lock readLock, writeLock;

        private LargeDataHolder() {
            final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
            this.writeLock = readWriteLock.writeLock();
            this.readLock = readWriteLock.readLock();
        }

        synchronized boolean initialized() {
            return this.tiffReader != null;
        }

        private synchronized void init() throws IOException {
            if (tiffReader == null) {
                long t1 = System.nanoTime();
                tiffReader = new TiffReader(svsFile).setByteFiller(TIFF_FILLER).setCaching(true);
                tiffReader.setInterleaveResults(true);
                // - should be removed in future versions, returning unpacked planes
                maps = tiffReader.allMaps();
                long t2 = System.nanoTime();
                LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                        "SVS parser opens file %s: %.3f ms", svsFile, (t2 - t1) * 1e-6));
            }
        }

        private synchronized void freeResources() {
            try {
                if (this.tiffReader != null) {
                    long t1 = System.nanoTime();
                    tiffReader.close();
                    long t2 = System.nanoTime();
                    LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                            "SVS parser closes file %s: %.3f ms", svsFile, (t2 - t1) * 1e-6));
                    tiffReader = null;
                    maps = null;
                }
            } catch (IOException e) {
                throw new IOError(e);
            }
        }

        // finalizer/cleaner not necessary: all files are processed in Java and will be finalized automatically

        private synchronized void initWholeSlideSynchronously() {
            if (wholeSlidePyramid == null) {
                final Matrix<? extends PArray> wholeSlide = readSpecialMatrix(SpecialImageKind.WHOLE_SLIDE)
                        .orElseThrow(() -> new AssertionError(
                                "Initialization of whole slide must not be called if WHOLE_SLIDE is not available"));
                wholeSlidePyramid = PlanePyramidTools.buildPyramid(wholeSlide);
            }
        }
    }
}
