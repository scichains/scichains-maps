package net.algart.executors.modules.cv.matrices.maps.pyramids.io;

import net.algart.contours.Contours;
import net.algart.math.IRectangularArea;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class ImagePyramidLevelRois {
    private final int numberOfResolutions;
    private final long levelDimX;
    private final long levelDimY;
    private final double scaleX;
    private final double scaleY;
    private final IRectangularArea wholeLevel;
    private IRectangularArea inputRoi;
    private IRectangularArea inputRoiOrWholeLevel;
    private ImagePyramidMetadataJson metadataJson = null;
    private boolean useMetadataRectangles = true;
    private boolean requireNonIntersectingRectangles = false;
    private int minimalROISize = 0;
    private volatile List<IRectangularArea> roiRectanglesCache = null;
    private volatile List<Contours> roiContoursCache = null;

    public ImagePyramidLevelRois(PlanePyramidSource planePyramidSource, int resolutionLevel) {
        numberOfResolutions = planePyramidSource.numberOfResolutions();
        levelDimX = planePyramidSource.width(resolutionLevel);
        levelDimY = planePyramidSource.height(resolutionLevel);
        if (levelDimX <= 0 || levelDimY <= 0) {
            throw new IllegalArgumentException("Unsupported pyramid: zero or negative sizes of level #"
                    + resolutionLevel + " " + levelDimX + "x" + levelDimY);
        }
        if (resolutionLevel == 0) {
            scaleX = scaleY = 1.0;
        } else {
            scaleX = (double) levelDimX / (double) planePyramidSource.width(0);
            scaleY = (double) levelDimY / (double) planePyramidSource.height(0);
        }
        wholeLevel = IRectangularArea.valueOf(0, 0, levelDimX - 1, levelDimY - 1);
        setInputRoi(null);
        // - initializes inputRoi and inputRoiOrWholeLevel fields
    }

    public IRectangularArea getInputRoi() {
        return inputRoi;
    }

    public ImagePyramidLevelRois setInputRoi(IRectangularArea inputRoiOrNull) {
        final IRectangularArea inputRoiOrWholeLevel;
        if (inputRoiOrNull == null) {
            inputRoiOrWholeLevel = wholeLevel;
        } else {
            inputRoiOrWholeLevel = inputRoiOrNull.intersection(wholeLevel);
            if (inputRoiOrWholeLevel == null) {
                throw new IllegalArgumentException("Input ROI " + inputRoiOrNull + " lies outside pyramid level "
                        + levelDimX + "x" + levelDimY);
            }
        }
        this.inputRoi = inputRoiOrNull;
        this.inputRoiOrWholeLevel = inputRoiOrWholeLevel;
        clearCache();
        // - note: here and below we must clear cache AFTER modifying fields, to guarantee
        // that AFTER this method the cache will be recalculated correctly even in a case of multithreading access
        return this;
    }

    public ImagePyramidMetadataJson getMetadataJson() {
        return metadataJson;
    }

    public ImagePyramidLevelRois setMetadataJson(ImagePyramidMetadataJson metadataJson) {
        this.metadataJson = metadataJson;
        clearCache();
        return this;
    }

    public boolean isUseMetadataRectangles() {
        return useMetadataRectangles;
    }

    public ImagePyramidLevelRois setUseMetadataRectangles(boolean useMetadataRectangles) {
        this.useMetadataRectangles = useMetadataRectangles;
        clearCache();
        return this;
    }

    public boolean isRequireNonIntersectingRectangles() {
        return requireNonIntersectingRectangles;
    }

    public ImagePyramidLevelRois setRequireNonIntersectingRectangles(boolean requireNonIntersectingRectangles) {
        this.requireNonIntersectingRectangles = requireNonIntersectingRectangles;
        clearCache();
        return this;
    }

    public int getMinimalROISize() {
        return minimalROISize;
    }

    public ImagePyramidLevelRois setMinimalROISize(int minimalROISize) {
        if (minimalROISize < 0) {
            throw new IllegalArgumentException("Negative minimalROISize");
        }
        this.minimalROISize = minimalROISize;
        clearCache();
        return this;
    }

    public int numberOfResolutions() {
        return numberOfResolutions;
    }

    public long levelDimX() {
        return levelDimX;
    }

    public long levelDimY() {
        return levelDimY;
    }

    public IRectangularArea wholeLevel() {
        return wholeLevel;
    }

    public IRectangularArea inputRoiOrWholeLevel() {
        return inputRoiOrWholeLevel;
    }

    public long inputMinX() {
        return inputRoiOrWholeLevel.minX();
    }

    public long inputMinY() {
        return inputRoiOrWholeLevel.minY();
    }

    public long inputMaxX() {
        return inputRoiOrWholeLevel.maxX();
    }

    public long inputMaxY() {
        return inputRoiOrWholeLevel.maxY();
    }

    public long inputDimX() {
        return inputRoiOrWholeLevel.sizeX();
    }

    public long inputDimY() {
        return inputRoiOrWholeLevel.sizeY();
    }

    public ImagePyramidMetadataJson metadataJson() {
        return metadataJson;
    }

    public List<IRectangularArea> roiRectangles() {
        List<IRectangularArea> roiRectangles = this.roiRectanglesCache;
        if (roiRectangles == null) {
            roiRectangles = metadataJson != null && useMetadataRectangles ?
                    Collections.unmodifiableList(metadataJson.roiRectangles(scaleX, scaleY, inputRoiOrWholeLevel)) :
                    List.of(inputRoiOrWholeLevel);
            if (requireNonIntersectingRectangles) {
                checkIntersectionOfRectangles(roiRectangles);
            }
            if (minimalROISize > 1) {
                roiRectangles = roiRectangles.stream().filter(this::isROILargeEnough).collect(Collectors.toList());
            }
            this.roiRectanglesCache = roiRectangles;
        }
        return roiRectangles;
    }

    public double[] allRoiCentersAndSizes() {
        return ImagePyramidMetadataJson.allRoiCentersAndSizes(roiRectangles());
    }

    public Contours allRoiContours() {
        final Contours allRoiContours = Contours.newInstance();
        final Optional<List<Contours>> contours = roiContours();
        if (contours.isEmpty()) {
            return allRoiContours;
        }
        contours.get().forEach(allRoiContours::addContours);
        return allRoiContours;
    }

    public Optional<List<Contours>> roiContours() {
        List<Contours> roiContours = this.roiContoursCache;
        if (roiContours == null) {
            roiContours = metadataJson != null ?
                    Collections.unmodifiableList(metadataJson.roiContours(scaleX, scaleY)) :
                    null;
            this.roiContoursCache = roiContours;
        }
        return Optional.ofNullable(roiContours);
    }

    public Contours roiContours(int roiIndex) {
        final Optional<List<Contours>> contours = roiContours();
        if (contours.isEmpty()) {
            return Contours.newInstance();
        }
        final List<Contours> contoursList = contours.get();
        if (roiIndex < 0 || roiIndex >= contoursList.size()) {
            throw new IndexOutOfBoundsException("No ROI with index " + roiIndex
                    + " (must be in range 0.." + contoursList.size() + "-1)");
        }
        return contoursList.get(roiIndex);
    }

    // Note: in current implementation, mapSequence is always null when singleFrameMode
    public long framesPerSeries(
            ScanningMapSequence mapSequence,
            long frameSizeX,
            long frameSizeY,
            boolean singleFrameMode) {
        if (mapSequence == null || singleFrameMode) {
            // - so, ROIs from metadata are not used for scanning;
            // we consider only ability of horizontal scanning
            return GridEqualizer.divideCeil(inputDimX(), frameSizeX);
        }
        return mapSequence.lowFrameCount(frameSizeX, frameSizeY, inputDimX(), inputDimY());
        // - Note: we estimate the result on the base of WHOLE pyramid (or input ROI),
        // ignoring possible set of ROIs in metadata. It provides better stability:
        // if we have enough memory for storing frames for processing whole pyramid,
        // than it will be probably enough for all similar pyarmids.
    }

    public long totalNumberOfFrames(long frameSizeX, long frameSizeY, boolean singleFrameMode) {
        return singleFrameMode ?
                1 :
                roiRectangles().stream().mapToLong(r -> numberOfFramesInRectangle(frameSizeX, frameSizeY, r)).sum();
    }

    private void clearCache() {
        roiRectanglesCache = null;
        roiContoursCache = null;
    }

    private String inMetaFileMessage() {
        final Path file = metadataJson == null ? null : metadataJson.getMetadataJsonFile();
        return file == null ? null : " in the file " + file;
    }

    private boolean isROILargeEnough(IRectangularArea r) {
        return r.sizeX() >= minimalROISize && r.sizeY() >= minimalROISize;
    }

    private void checkIntersectionOfRectangles(List<IRectangularArea> roiRectangles) {
        final IRectangularArea[] rectangles = roiRectangles.toArray(new IRectangularArea[0]);
        for (int i = 0; i < rectangles.length; i++) {
            final IRectangularArea r = rectangles[i];
            assert r.coordCount() == 2;
            for (int j = i + 1; j < rectangles.length; j++) {
                if (quickIntersects(r, rectangles[j])) {
                    throw new IllegalArgumentException("ROI rectangles intersects each other: rectangle #"
                            + i + " = " + r + " intersects rectangle #" + j + " = " + rectangles[j]
                            + inMetaFileMessage());
                }
            }
        }
    }

    private static boolean quickIntersects(IRectangularArea r1, IRectangularArea r2) {
        return r2.max(0) >= r1.min(0) && r2.min(0) <= r1.max(0)
                && r2.max(1) >= r1.min(1) && r2.min(1) <= r1.max(1);
    }

    private static long numberOfFramesInRectangle(long frameSizeX, long frameSizeY, IRectangularArea r) {
        return GridEqualizer.divideCeil(r.sizeX(), frameSizeX) * GridEqualizer.divideCeil(r.sizeY(), frameSizeY);
    }

}
