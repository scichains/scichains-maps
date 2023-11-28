package net.algart.executors.modules.cv.matrices.maps.pyramids.io;

import net.algart.executors.api.data.SMat;
import net.algart.executors.api.data.SScalar;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.multimatrix.MultiMatrix;
import net.algart.multimatrix.MultiMatrix2D;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ReadImagePyramid extends AbstractImagePyramidOperation {
    public static final String INPUT_FILE_LIST = "file_list";
    public static final String OUTPUT_DIM_X = "dim_x";
    public static final String OUTPUT_DIM_Y = "dim_y";
    public static final String OUTPUT_NUMBER_OF_PYRAMIDS = "number_of_pyramids";
    public static final String OUTPUT_RECTANGLE = "rectangle";
    public static final String OUTPUT_RECOMMENDED_EXPANSION = "recommended_expansion";
    public static final String OUTPUT_CURRENT_PYRAMID_INDEX = "current_pyramid_index";
    public static final String OUTPUT_CURRENT_ROI_INDEX = "current_roi_index";
    public static final String OUTPUT_CURRENT_X_INDEX = "current_x_index";
    public static final String OUTPUT_CURRENT_Y_INDEX = "current_y_index";
    public static final String OUTPUT_CURRENT_FRAME_INDEX = "current_frame_index";
    public static final String OUTPUT_CURRENT_METADATA_ROI_CONTOURS = "current_metadata_roi_contours";
    public static final String OUTPUT_FIRST_IN_ROI = "first_in_roi";
    public static final String OUTPUT_LAST_IN_ROI = "last_in_roi";
    public static final String OUTPUT_FIRST_IN_PYRAMID = "first_in_pyramid";
    public static final String OUTPUT_LAST_IN_PYRAMID = "last_in_pyramid";
    public static final String OUTPUT_LAST = "last";
    public static final String OUTPUT_CLOSED = "closed";

    public enum SizeUnit {
        PIXEL() {
            @Override
            long scaleX(ReadImagePyramid e, long x, boolean forSize) {
                return x;
            }

            @Override
            long scaleY(ReadImagePyramid e, long y, boolean forSize) {
                return y;
            }
        },
        PIXEL_OF_SPECIAL_IMAGE() {
            @Override
            long scaleX(ReadImagePyramid e, long x, boolean forSize) {
                x = Math.round(x / (double) (e.specialMatrix.dimX() - 1)
                        * (e.planePyramidSource.width(e.resolutionLevel) - 1));
                return forSize ? Math.max(x, 1) : x;
            }

            @Override
            long scaleY(ReadImagePyramid e, long y, boolean forSize) {
                y = Math.round(y / (double) (e.specialMatrix.dimY() - 1)
                        * (double) (e.planePyramidSource.height(e.resolutionLevel) - 1));
                return forSize ? Math.max(y, 1) : y;
            }
        };

        abstract long scaleX(ReadImagePyramid e, long x, boolean forSize);

        abstract long scaleY(ReadImagePyramid e, long y, boolean forSize);
    }

    private ImagePyramidOpeningMode openingMode = ImagePyramidOpeningMode.OPEN_ON_RESET_AND_FIRST_CALL;
    private boolean closeAfterLast = true;
    private boolean wholeROI = false;
    private long startX = 0;
    private long startY = 0;
    private long sizeX = 1;
    private long sizeY = 1;
    private SizeUnit sizeUnit = SizeUnit.PIXEL;
    private boolean equalizeGrid = false;
    private PlanePyramidSource.SpecialImageKind specialImageKind = PlanePyramidSource.SpecialImageKind.NONE;

    private volatile List<Path> fileList = null;
    private volatile boolean fileListSpecified = false;
    private int currentFileIndex = 0;
    private volatile PlanePyramidSource planePyramidSource = null;
    private volatile MultiMatrix2D specialMatrix = null;
    private volatile ImagePyramidLevelRois selectedLevelRois = null;
    private volatile boolean pyramidOpened = false;
    private volatile List<IRectangularArea> roiRectangles = null;
    private volatile long currentFrameIndex = 0;
    // - used for returning in OUTPUT_CURRENT_FRAME_INDEX only
    private volatile long currentFrameLowIndex = 0;
    private volatile long currentFrameHighIndex = 0;
    private volatile int currentRoiIndex = 0;
    private volatile boolean firstInRoi = false;
    private volatile boolean lastInRoi = false;
    private volatile boolean firstInPyramid = false;
    private volatile boolean lastInPyramid = false;
    private volatile boolean last = false;

    private volatile int selectedResolutionLevel;
    private volatile ScanningSequence selectedScanningSequence;
    private volatile boolean selectedWholeROI;
    private volatile long selectedSizeX;
    private volatile long selectedSizeY;

    private final Object lock = new Object();

    public ReadImagePyramid() {
        useVisibleResultParameter();
        addInputMat(DEFAULT_INPUT_PORT);
        addInputScalar(INPUT_FILE_LIST);
        addOutputMat(DEFAULT_OUTPUT_PORT);
        addOutputMat(OUTPUT_SPECIAL_IMAGE);
        addOutputScalar(OUTPUT_NUMBER_OF_LEVELS);
        addOutputScalar(OUTPUT_LEVEL_DIM_X);
        addOutputScalar(OUTPUT_LEVEL_DIM_Y);
        addOutputScalar(OUTPUT_DIM_X);
        addOutputScalar(OUTPUT_DIM_Y);
        addOutputNumbers(OUTPUT_RECTANGLE);
        addOutputScalar(OUTPUT_NUMBER_OF_PYRAMIDS);
        addOutputScalar(OUTPUT_NUMBER_OF_FRAMES);
        addOutputScalar(OUTPUT_FRAMES_PER_SERIES);
        addOutputScalar(OUTPUT_RECOMMENDED_EXPANSION);
        addOutputScalar(OUTPUT_RECOMMENDED_NUMBER_OF_FRAMES_IN_BUFFER);
        addOutputScalar(OUTPUT_BUILTIN_METADATA);
        addOutputScalar(OUTPUT_METADATA);
        addOutputNumbers(OUTPUT_METADATA_ROI_RECTANGLES);
        addOutputNumbers(OUTPUT_METADATA_ROI_CONTOURS);
        addOutputScalar(OUTPUT_CURRENT_PYRAMID_INDEX);
        addOutputScalar(OUTPUT_CURRENT_ROI_INDEX);
        addOutputScalar(OUTPUT_CURRENT_X_INDEX);
        addOutputScalar(OUTPUT_CURRENT_Y_INDEX);
        addOutputScalar(OUTPUT_CURRENT_FRAME_INDEX);
        addOutputNumbers(OUTPUT_CURRENT_METADATA_ROI_CONTOURS);
        addOutputScalar(OUTPUT_FIRST_IN_ROI);
        addOutputScalar(OUTPUT_LAST_IN_ROI);
        addOutputScalar(OUTPUT_FIRST_IN_PYRAMID);
        addOutputScalar(OUTPUT_LAST_IN_PYRAMID);
        addOutputScalar(OUTPUT_LAST);
        addOutputScalar(OUTPUT_CLOSED);
    }

    public ImagePyramidOpeningMode getOpeningMode() {
        return openingMode;
    }

    public ReadImagePyramid setOpeningMode(ImagePyramidOpeningMode openingMode) {
        this.openingMode = nonNull(openingMode);
        return this;
    }

    public boolean isCloseAfterLast() {
        return closeAfterLast;
    }

    public ReadImagePyramid setCloseAfterLast(boolean closeAfterLast) {
        this.closeAfterLast = closeAfterLast;
        return this;
    }

    public boolean isWholeROI() {
        return wholeROI;
    }

    public ReadImagePyramid setWholeROI(boolean wholeROI) {
        this.wholeROI = wholeROI;
        return this;
    }

    public long getStartX() {
        return startX;
    }

    public ReadImagePyramid setStartX(long startX) {
        this.startX = startX;
        return this;
    }

    public long getStartY() {
        return startY;
    }

    public ReadImagePyramid setStartY(long startY) {
        this.startY = startY;
        return this;
    }

    public long getSizeX() {
        return sizeX;
    }

    public ReadImagePyramid setSizeX(long sizeX) {
        this.sizeX = nonNegative(sizeX);
        return this;
    }

    public long getSizeY() {
        return sizeY;
    }

    public ReadImagePyramid setSizeY(long sizeY) {
        this.sizeY = nonNegative(sizeY);
        return this;
    }

    public SizeUnit getSizeUnit() {
        return sizeUnit;
    }

    public ReadImagePyramid setSizeUnit(SizeUnit sizeUnit) {
        this.sizeUnit = nonNull(sizeUnit);
        return this;
    }

    public SizeUnit sizeUnit() {
        SizeUnit result = sizeUnit;
        if (planePyramidSource == null) {
            throw new IllegalStateException("Cannot use size unit: plane pyramid source is not initialized");
        }
        if (result == SizeUnit.PIXEL_OF_SPECIAL_IMAGE && specialMatrix == null) {
            throw new IllegalArgumentException(
                    "No requested special image, so we cannot use size unit, based on its pixels");
        }
        return result;
    }

    public boolean isEqualizeGrid() {
        return equalizeGrid;
    }

    public ReadImagePyramid setEqualizeGrid(boolean equalizeGrid) {
        this.equalizeGrid = equalizeGrid;
        return this;
    }

    public PlanePyramidSource.SpecialImageKind getSpecialImageKind() {
        return specialImageKind;
    }

    public ReadImagePyramid setSpecialImageKind(PlanePyramidSource.SpecialImageKind specialImageKind) {
        this.specialImageKind = nonNull(specialImageKind);
        return this;
    }

    @Override
    public void initialize() {
        if (openingMode.isClosePreviousOnReset()) {
            closePyramid(false);
        }
        clearFileList();
        // Note: it is not a good idea to prepare full file list here.
        // It is possible that the main function will be called only at some iteration of the loop,
        // not at the first iteration, and then the file list will stay incorrect.
    }

    @Override
    public void process() {
        SMat input = getInputMat(defaultInputPortName(), true);
        if (input.isInitialized()) {
            logDebug(() -> "Copying " + input);
            getMat().setTo(input);
        } else {
            initializeFileList();
            MultiMatrix multiMatrix = readNextPyramid(isOutputNecessary(DEFAULT_OUTPUT_PORT));
            if (multiMatrix != null) {
                getMat().setTo(multiMatrix);
            } else {
                getMat().remove();
            }
        }
    }


    public void clearFileList() {
        this.fileList = null;
        this.currentFileIndex = 0;
    }

    public void initializeFileList() {
        if (this.fileList == null) {
            String inputFileList = getInputScalar(INPUT_FILE_LIST, true).getValue();
            this.fileListSpecified = inputFileList != null;
            if (inputFileList != null) {
                final List<String> lines = SScalar.splitJsonOrTrimmedLines(inputFileList);
                if (lines.isEmpty()) {
                    throw new IllegalArgumentException("Empty file list is passed");
                }
                this.fileList = lines.stream().map(Paths::get).collect(Collectors.toList());
            } else {
                this.fileList = List.of(completeFilePath());
            }
            this.currentFileIndex = 0;
        }
    }

    public MultiMatrix readNextPyramid(boolean doActualReading) {
        final int n = fileList.size();
        assert currentFileIndex < n : "invalid index in file list: " + currentFileIndex + " >= " + n;
        final MultiMatrix result = readPyramid(fileList.get(currentFileIndex), doActualReading);

        getScalar(OUTPUT_NUMBER_OF_PYRAMIDS).setTo(n);
        getScalar(OUTPUT_CURRENT_PYRAMID_INDEX).setTo(currentFileIndex);
        if (fileListSpecified) {
            if (lastInPyramid) {
                currentFileIndex++;
                last = currentFileIndex >= n;
                if (last) {
                    currentFileIndex = 0;
                }
                getScalar(OUTPUT_LAST).setTo(last);
            }
        }
        // if there is no file list, we stay previous logic of setting "last" when there is no mapSequence:
        // checking right-bottom pixel
        return result;
    }

    public MultiMatrix readPyramid(Path path) {
        return readPyramid(path, true);
    }

    public MultiMatrix readPyramid(Path path, boolean doActualReading) {
        // - to be on the safe side: guarantee that it will not change inside this method
        if (openingMode.isClosePreviousOnExecute()) {
            closePyramid(false);
        }
        try {
            openPyramid(path);
            checkSelectedGeometry();
            final boolean wholeROI = this.selectedWholeROI;
            final long sizeX = selectedSizeX;
            final long sizeY = selectedSizeY;
            if (!wholeROI) {
                checkFrameSize(sizeX, sizeY);
            }
            final ScanningMapSequence mapSequence = wholeROI ?
                    null :
                    selectedScanningSequence.mapSequence(selectedLevelRois);
            if (mapSequence != null && roiRectangles.isEmpty()) {
                final int m = getMinimalAnalyzedSize();
                throw new IllegalStateException("There are no "
                        + (m == 0 ? "non-empty ROIs" : "ROIs greater than " + m + "x" + m)
                        + " at level " + selectedResolutionLevel
                        + " (" + selectedLevelRois.levelDimX() + "x" + selectedLevelRois.levelDimY()
                        + ") of the pyramid " + path);
            }
            final IRectangularArea actualRoi = mapSequence != null ?
                    roiRectangles.get(currentRoiIndex) :
                    selectedLevelRois.inputRoiOrWholeLevel();
            final IRectangularArea area = findAreaToRead(mapSequence, actualRoi);
            final MultiMatrix result =
                    doActualReading ?
                            readSource(planePyramidSource, selectedResolutionLevel, area) :
                            null;
            getScalar(OUTPUT_DIM_X).setTo(result == null ? 0 : result.dim(0));
            getScalar(OUTPUT_DIM_Y).setTo(result == null ? 0 : result.dim(1));
            if (specialMatrix != null) {
                getMat(OUTPUT_SPECIAL_IMAGE).setTo(specialMatrix);
            } else {
                getMat(OUTPUT_SPECIAL_IMAGE).remove();
            }

            getScalar(OUTPUT_NUMBER_OF_FRAMES).setTo(selectedLevelRois.totalNumberOfFrames(sizeX, sizeY, wholeROI));
            getNumbers(OUTPUT_RECTANGLE).setTo(area);
            final long framesPerSeries = selectedLevelRois.framesPerSeries(mapSequence, sizeX, sizeY, wholeROI);
            final DiagonalDirectionOnMap recommendedFrameExpansion;
            if (mapSequence != null) {
                final long roiDimX = actualRoi.sizeX();
                final long roiDimY = actualRoi.sizeY();
                recommendedFrameExpansion = mapSequence.recommendedFrameExpansion(
                        currentFrameLowIndex, currentFrameHighIndex, sizeX, sizeY, roiDimX, roiDimY);
                getScalar(OUTPUT_CURRENT_ROI_INDEX).setTo(currentRoiIndex);
                getScalar(OUTPUT_CURRENT_X_INDEX).setTo(mapSequence.xFrameIndex(
                        currentFrameLowIndex, currentFrameHighIndex, sizeX, sizeY, roiDimX, roiDimY));
                getScalar(OUTPUT_CURRENT_Y_INDEX).setTo(mapSequence.yFrameIndex(
                        currentFrameLowIndex, currentFrameHighIndex, sizeX, sizeY, roiDimX, roiDimY));
                getScalar(OUTPUT_CURRENT_FRAME_INDEX).setTo(currentFrameIndex);
                if (isOutputNecessary(OUTPUT_CURRENT_METADATA_ROI_CONTOURS)) {
                    getNumbers(OUTPUT_CURRENT_METADATA_ROI_CONTOURS)
                            .setTo(selectedLevelRois.roiContours(currentRoiIndex));
                }
                getScalar(OUTPUT_FRAMES_PER_SERIES).setTo(framesPerSeries);
                firstInRoi = currentFrameLowIndex == 0 && currentFrameHighIndex == 0;
                firstInPyramid = firstInRoi && currentRoiIndex == 0;
                nextSequentialIndex(mapSequence, sizeX, sizeY, roiDimX, roiDimY);
                // - note: it must be called AFTER filling results on the base of current low/high indexes
                lastInRoi = currentFrameLowIndex == 0 && currentFrameHighIndex == 0;
                lastInPyramid = lastInRoi && currentRoiIndex == 0;
                last = lastInPyramid;
            } else {
                // - in particular, if wholeROI
                recommendedFrameExpansion = DiagonalDirectionOnMap.LEFT_UP;
                firstInRoi = lastInRoi = true;
                firstInPyramid = lastInPyramid = true;
                last = area.maxX() >= selectedLevelRois.inputMaxX() && area.maxY() >= selectedLevelRois.inputMaxY();
                // - most probable assumption for the loop, organized by external means
            }
            getScalar(OUTPUT_RECOMMENDED_EXPANSION).setTo(recommendedFrameExpansion);
            fillOutputNumberOfStoredFrames(framesPerSeries, wholeROI);
            getScalar(OUTPUT_FIRST_IN_ROI).setTo(firstInRoi);
            getScalar(OUTPUT_LAST_IN_ROI).setTo(lastInRoi);
            getScalar(OUTPUT_FIRST_IN_PYRAMID).setTo(firstInPyramid);
            getScalar(OUTPUT_LAST_IN_PYRAMID).setTo(lastInPyramid);
            getScalar(OUTPUT_LAST).setTo(last);
            final boolean close = openingMode.isCloseAfterExecute() || (lastInPyramid && closeAfterLast);
            if (close) {
                closePyramid(false);
            }
            getScalar(OUTPUT_CLOSED).setTo(close);
            return result;
        } catch (IOError | RuntimeException e) {
            closePyramid(true);
            closeSourceFactory();
            // - closing can be important to allow the user to fix the problem;
            // moreover, in a case the error it is better to free all possible connected resources
            throw e;
        }
    }

    public long pixelStartX() {
        return sizeUnit().scaleX(this, startX, false);
    }

    public long pixelStartY() {
        return sizeUnit().scaleY(this, startY, false);
    }

    public long pixelSizeX() {
        return sizeUnit().scaleX(this, sizeX, true);
    }

    public long pixelSizeY() {
        return sizeUnit().scaleY(this, sizeY, true);
    }


    public boolean isFirstInRoi() {
        return firstInRoi;
    }

    public boolean isLastInRoi() {
        return lastInRoi;
    }

    public boolean isFirstInPyramid() {
        return firstInPyramid;
    }

    public boolean isLastInPyramid() {
        return lastInPyramid;
    }

    public boolean isLast() {
        return last;
    }

    public void openPyramid(Path path) {
        Objects.requireNonNull(path, "Null path");
        if (!pyramidOpened) {
            try {
                logDebug(() -> "Opening " + path);
                pyramidOpened = true;
                planePyramidSource = newPlanePyramidSource(path);
                specialMatrix = readSpecialMatrix(planePyramidSource, specialImageKind);
                // - specialMatrix is necessary already in selectGeometry(),
                // to provide correct usage of sizeUnit
                final ImagePyramidMetadataJson metadataJson = readMetadataOrNull(path);
                selectGeometry();
                selectedLevelRois = newLevelRois(planePyramidSource, metadataJson);
                roiRectangles = selectedLevelRois.roiRectangles();
                currentFrameIndex = 0;
                currentFrameHighIndex = 0;
                currentFrameLowIndex = 0;
                currentRoiIndex = 0;
            } catch (IOException e) {
                throw new IOError(e);
            }
        }
        fillOutputFileInformation(path);
        fillOutputInformation(planePyramidSource, selectedLevelRois);
        // - note: we need to fill output ports here, even if pyramid was already opened
    }

    @Override
    public void close() {
        super.close();
        closePyramid(true);
    }

    private void closePyramid(boolean longTermResources) {
        final boolean pyramidOpened = this.pyramidOpened;
        final PlanePyramidSource planePyramidSource = this.planePyramidSource;
        if (pyramidOpened || longTermResources) {
            if (planePyramidSource != null) {
                if (longTermResources) {
                    logInfo(() -> "Closing " + planePyramidSource + " (also long-term resources)");
                    planePyramidSource.freeResources(PlanePyramidSource.FlushMode.FLUSH_LONG_TERM_RESOURCES);
                } else {
                    logDebug(() -> "Closing " + planePyramidSource);
                    planePyramidSource.freeResources(PlanePyramidSource.FlushMode.STANDARD);
                }
                // - but this.planePyramidSource stays to be non-zero, to allow longTermResources closing in future
            }
            this.selectedLevelRois = null;
            this.pyramidOpened = false;
        }
    }

    private void selectGeometry() {
        if (resolutionLevel >= planePyramidSource.numberOfResolutions()) {
            throw new IllegalArgumentException("Too big index of resolution level "
                    + resolutionLevel + ": there are only "
                    + planePyramidSource.numberOfResolutions() + " resolutions");
        }
        if (!wholeROI && (sizeX == 0 || sizeY == 0)) {
            throw new IllegalStateException("Zero " + (sizeX == 0 ? "x" : "y")
                    + "-size (zero values are allowed only if the flag \"Read whole ROI\" is set)");
        }
        selectedResolutionLevel = resolutionLevel;
        selectedScanningSequence = getScanningSequence();
        selectedWholeROI = wholeROI;
        selectedSizeX = pixelSizeX();
        selectedSizeY = pixelSizeY();
    }

    private void checkSelectedGeometry() {
        if (resolutionLevel != selectedResolutionLevel) {
            throw new IllegalStateException("Illegal change of resolution level: " + resolutionLevel + " != "
                    + selectedResolutionLevel + " (it must NOT be changed before closing current pyramid)");
        }
        if (getScanningSequence() != selectedScanningSequence) {
            throw new IllegalStateException("Illegal change of scanning sequence: " + getScanningSequence() + " != "
                    + selectedScanningSequence + " (it must NOT be changed before closing current pyramid)");
        }
        if (pixelSizeX() != selectedSizeX || pixelSizeY() != selectedSizeY) {
            throw new IllegalStateException("Illegal change of frame size: "
                    + pixelSizeX() + "x" + pixelSizeY() + " != "
                    + selectedSizeX + "x" + selectedSizeY
                    + " (it must NOT be changed before closing current pyramid)");
        }
        if (wholeROI != selectedWholeROI) {
            throw new IllegalStateException("Illegal change of read-whole-ROI flag: " + wholeROI + " != "
                    + selectedWholeROI + " (it must NOT be changed before closing current pyramid)");
        }
    }

    private IRectangularArea findAreaToRead(ScanningMapSequence mapSequence, IRectangularArea actualRoi) {
        if (wholeROI) {
            assert mapSequence == null;
            return selectedLevelRois.inputRoiOrWholeLevel();
        }
        long sizeX = selectedSizeX;
        long sizeY = selectedSizeY;
        assert sizeX > 0;
        assert sizeY > 0;
        final IRectangularArea area;
        if (mapSequence != null) {
            sizeX = Math.min(sizeX, actualRoi.sizeX());
            sizeY = Math.min(sizeY, actualRoi.sizeY());
            // - note: it is NOT clipping selected area by ROI, it is just reducing sizes
            // (to be on the safe side) for correct calculating framePosition()
            if (equalizeGrid) {
                sizeX = GridEqualizer.equalizeGrid(actualRoi.sizeX(), sizeX);
                sizeY = GridEqualizer.equalizeGrid(actualRoi.sizeY(), sizeY);
            }
            final IPoint start = mapSequence.framePosition(
                            currentFrameLowIndex, currentFrameHighIndex, sizeX, sizeY, actualRoi.sizeX(), actualRoi.sizeY())
                    .addExact(actualRoi.min());
            final long endX = Math.min(start.x() + sizeX - 1, actualRoi.maxX());
            final long endY = Math.min(start.y() + sizeY - 1, actualRoi.maxY());
            if (endX < start.x() || endY < start.y()) {
                throw new AssertionError("Empty area to read " + start
                        + "..(" + endX + ", " + endY + ") inside " + actualRoi);
            }
            area = IRectangularArea.valueOf(start.x(), start.y(), endX, endY);
        } else {
            final long startX = pixelStartX();
            final long startY = pixelStartY();
            area = IRectangularArea.valueOf(startX, startY, startX + sizeX - 1, startY + sizeY - 1);
        }
        IRectangularArea result = area.intersection(selectedLevelRois.wholeLevel());
        if (result == null) {
            throw new IllegalArgumentException("Empty area cannot be read from pyramid: " + area
                    + " is fully outside the pyramid layer " + selectedLevelRois.wholeLevel());
        }
        return result;
    }

    private void nextSequentialIndex(
            ScanningMapSequence mapSequence,
            long sizeX,
            long sizeY,
            long roiDimX,
            long roiDimY) {
        assert mapSequence != null;
        final long lowFrameCount = mapSequence.lowFrameCount(sizeX, sizeY, roiDimX, roiDimY);
        final long highFrameCount = mapSequence.highFrameCount(sizeX, sizeY, roiDimX, roiDimY);
        synchronized (lock) {
            currentFrameIndex++;
            currentFrameLowIndex++;
            if (currentFrameLowIndex >= lowFrameCount) {
                currentFrameLowIndex = 0;
                currentFrameHighIndex++;
                if (currentFrameHighIndex >= highFrameCount) {
                    currentFrameHighIndex = 0;
                    currentRoiIndex++;
                    if (currentRoiIndex >= roiRectangles.size()) {
                        // - cyclic repeat from the very beginning
                        currentRoiIndex = 0;
                        currentFrameIndex = 0;
                    }
                }
            }
        }
    }
}
