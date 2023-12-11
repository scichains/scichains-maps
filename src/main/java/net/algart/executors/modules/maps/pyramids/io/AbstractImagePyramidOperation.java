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

package net.algart.executors.modules.maps.pyramids.io;

import jakarta.json.Json;
import net.algart.arrays.Arrays;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.bridges.standard.JavaScriptContextContainer;
import net.algart.executors.api.Executor;
import net.algart.executors.api.data.SMat;
import net.algart.executors.modules.core.common.io.FileOperation;
import net.algart.math.IRectangularArea;
import net.algart.maps.pyramids.io.api.PlanePyramidSource;
import net.algart.maps.pyramids.io.api.PlanePyramidSourceFactory;
import net.algart.multimatrix.MultiMatrix;
import net.algart.multimatrix.MultiMatrix2D;

import javax.script.ScriptEngine;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

public abstract class AbstractImagePyramidOperation extends FileOperation {
    public static final String INPUT_PYRAMID_CONFIGURATION = "pyramid_configuration";
    public static final String INPUT_RENDERING_CONFIGURATION = "rendering_configuration";
    public static final String INPUT_ROI = "roi";
    public static final String OUTPUT_SPECIAL_IMAGE = "special_image";
    public static final String OUTPUT_NUMBER_OF_LEVELS = "number_of_levels";
    public static final String OUTPUT_LEVEL_DIM_X = "level_dim_x";
    public static final String OUTPUT_LEVEL_DIM_Y = "level_dim_y";
    public static final String OUTPUT_NUMBER_OF_FRAMES = "number_of_frames";
    public static final String OUTPUT_FRAMES_PER_SERIES = "frames_per_series";
    public static final String OUTPUT_RECOMMENDED_NUMBER_OF_FRAMES_IN_BUFFER =
            "recommended_number_of_frames_in_buffer";
    public static final String OUTPUT_BUILTIN_METADATA = "builtin_metadata";
    public static final String OUTPUT_METADATA = "metadata";
    public static final String OUTPUT_METADATA_ROI_RECTANGLES = "metadata_roi_rectangles";
    public static final String OUTPUT_METADATA_ROI_CONTOURS = "metadata_roi_contours";

    public static final String METADATA_FILE_SUFFIX = ".meta";

    // Unlike universal ScanningMapSequence, this enum describes only scanning the plane pyramid.
    public enum ScanningSequence {
        NONE(null, false),
        ROWS_LEFT_TO_RIGHT(ScanningMapSequence.ROWS_LEFT_TO_RIGHT, false),
        ROWS_BY_SNAKE(ScanningMapSequence.ROWS_BY_SNAKE, true),
        COLUMNS_TOP_TO_BOTTOM(ScanningMapSequence.COLUMNS_TOP_TO_BOTTOM, false),
        COLUMNS_BY_SNAKE(ScanningMapSequence.COLUMNS_BY_SNAKE, true),
        SHORTEST_SIDE(null, false) {
            @Override
            public ScanningMapSequence mapSequence(long totalDimX, long totalDimY) {
                return totalDimX <= totalDimY ?
                        ScanningMapSequence.ROWS_LEFT_TO_RIGHT :
                        ScanningMapSequence.COLUMNS_TOP_TO_BOTTOM;
            }
        },
        SHORTEST_SIDE_BY_SNAKE(null, true) {
            @Override
            public ScanningMapSequence mapSequence(long totalDimX, long totalDimY) {
                return totalDimX >= totalDimY ?
                        ScanningMapSequence.ROWS_BY_SNAKE :
                        ScanningMapSequence.COLUMNS_BY_SNAKE;
            }
        };

        private final ScanningMapSequence mapSequence;
        private final boolean snake;

        ScanningSequence(ScanningMapSequence mapSequence, boolean snake) {
            this.mapSequence = mapSequence;
            this.snake = snake;
        }

        public ScanningMapSequence mapSequence(long totalDimX, long totalDimY) {
            return mapSequence;
        }

        public final ScanningMapSequence mapSequence(ImagePyramidLevelRois levelRois) {
            return mapSequence(levelRois.inputDimX(), levelRois.inputDimY());
        }

        public boolean isUsingSpecifiedCoordinates() {
            return mapSequence == null;
        }

        public boolean isUsingRoi() {
            return !isUsingSpecifiedCoordinates();
        }

        public boolean snake() {
            return snake;
        }
    }

    private ImagePyramidFormatKind planePyramidFormat = ImagePyramidFormatKind.AUTO_DETECT_BY_EXTENSION;
    private String customPlanePyramidSourceFactoryClass = "";
    int resolutionLevel = 0;
    private ScanningSequence scanningSequence = ScanningSequence.NONE;
    private SimpleFormula recommendedNumberOfStoredFrames;
    private String recommendedNumberOfStoredFramesP = "";
    private boolean useMetadata = true;
    private boolean useInputROI = true;
    private boolean requireNonIntersectingRectangles = false;
    private int minimalAnalyzedSize = 0;

    private final ScriptEngine context;
    private PlanePyramidSourceFactory sourceFactory = PlanePyramidSourceFactory.Unsupported.INSTANCE;
    private final Object lock = new Object();

    public AbstractImagePyramidOperation() {
        addFileOperationPorts();
        addInputScalar(INPUT_PYRAMID_CONFIGURATION);
        addInputScalar(INPUT_RENDERING_CONFIGURATION);
        addInputNumbers(INPUT_ROI);
        this.context = JavaScriptContextContainer.getInstance().getLocalContext();
        this.recommendedNumberOfStoredFrames = new SimpleFormula(context, "1");
    }

    public final ImagePyramidFormatKind getPlanePyramidFormat() {
        return planePyramidFormat;
    }

    public final AbstractImagePyramidOperation setPlanePyramidFormat(ImagePyramidFormatKind planePyramidFormat) {
        this.planePyramidFormat = nonNull(planePyramidFormat);
        return this;
    }

    public final String getCustomPlanePyramidSourceFactoryClass() {
        return customPlanePyramidSourceFactoryClass;
    }

    public final AbstractImagePyramidOperation setCustomPlanePyramidSourceFactoryClass(
            String customPlanePyramidSourceFactoryClass) {
        this.customPlanePyramidSourceFactoryClass = nonNull(customPlanePyramidSourceFactoryClass);
        return this;
    }

    public final int getResolutionLevel() {
        return resolutionLevel;
    }

    public final AbstractImagePyramidOperation setResolutionLevel(int resolutionLevel) {
        this.resolutionLevel = nonNegative(resolutionLevel);
        return this;
    }

    public final ScanningSequence getScanningSequence() {
        return scanningSequence;
    }

    public final AbstractImagePyramidOperation setScanningSequence(ScanningSequence scanningSequence) {
        this.scanningSequence = nonNull(scanningSequence);
        return this;
    }

    public final String getRecommendedNumberOfStoredFrames() {
        return recommendedNumberOfStoredFrames.formula();
    }

    public final AbstractImagePyramidOperation setRecommendedNumberOfStoredFrames(
            String recommendedNumberOfStoredFrames) {
        this.recommendedNumberOfStoredFrames = new SimpleFormula(context, nonEmpty(recommendedNumberOfStoredFrames));
        return this;
    }

    public final String getRecommendedNumberOfStoredFramesP() {
        return recommendedNumberOfStoredFramesP;
    }

    public final AbstractImagePyramidOperation setRecommendedNumberOfStoredFramesP(String recommendedNumberOfStoredFramesP) {
        this.recommendedNumberOfStoredFramesP = nonNull(recommendedNumberOfStoredFramesP);
        return this;
    }

    public final boolean isUseMetadata() {
        return useMetadata;
    }

    public final AbstractImagePyramidOperation setUseMetadata(boolean useMetadata) {
        this.useMetadata = useMetadata;
        return this;
    }

    public final boolean isUseInputROI() {
        return useInputROI;
    }

    public final AbstractImagePyramidOperation setUseInputROI(boolean useInputROI) {
        this.useInputROI = useInputROI;
        return this;
    }

    public final boolean isRequireNonIntersectingRectangles() {
        return requireNonIntersectingRectangles;
    }

    public final AbstractImagePyramidOperation setRequireNonIntersectingRectangles(
            boolean requireNonIntersectingRectangles) {
        this.requireNonIntersectingRectangles = requireNonIntersectingRectangles;
        return this;
    }

    public int getMinimalAnalyzedSize() {
        return minimalAnalyzedSize;
    }

    public AbstractImagePyramidOperation setMinimalAnalyzedSize(int minimalAnalyzedSize) {
        this.minimalAnalyzedSize = nonNegative(minimalAnalyzedSize);
        return this;
    }

    @Override
    public void close() {
        super.close();
        closeSourceFactory();
    }

    public static MultiMatrix readSource(PlanePyramidSource source, int resolutionLevel, IRectangularArea area) {
        final long fromX = area.minX();
        final long fromY = area.minY();
        final long toX = area.maxX() + 1;
        final long toY = area.maxY() + 1;
        final Matrix<? extends PArray> matrix = source.readSubMatrix(
                resolutionLevel, fromX, fromY, toX, toY);
        return MultiMatrix.valueOf2DRGBA(SMat.unpackBandsFromSequentialSamples(matrix));
    }

    public static MultiMatrix2D readSpecialMatrix(
            PlanePyramidSource planePyramidSource,
            PlanePyramidSource.SpecialImageKind specialImageKind) {
        final Optional<Matrix<? extends PArray>> matrix = planePyramidSource.readSpecialMatrix(specialImageKind);
        return matrix.map(m -> MultiMatrix.valueOf2DRGBA(SMat.unpackBandsFromSequentialSamples(m))).orElse(null);
    }

    public ImagePyramidMetadataJson readMetadataOrNull(Path planePyramidPath) throws IOException {
        if (!useMetadata) {
            return null;
        }
        final Path metadataFile = Paths.get(planePyramidPath + METADATA_FILE_SUFFIX);
        return Files.exists(metadataFile) ? ImagePyramidMetadataJson.read(metadataFile) : null;
    }

    public PlanePyramidSource newPlanePyramidSource(Path planePyramidPath) throws IOException {
        Objects.requireNonNull(planePyramidPath, "Null path");
        final String emptyJson = Json.createObjectBuilder().build().toString();
        final String pyramidConfiguration = getInputScalar(INPUT_PYRAMID_CONFIGURATION, true)
                .getValueOrDefault(emptyJson);
        final String rendererConfiguration = getInputScalar(INPUT_RENDERING_CONFIGURATION, true)
                .getValueOrDefault(emptyJson);
        if (!Files.exists(planePyramidPath)) {
            throw new FileNotFoundException("Pyramid file/folder \"" + planePyramidPath + "\" not found");
        }
        resetPlanePyramidSourceFactory(planePyramidPath);
        return sourceFactory.newPlanePyramidSource(
                planePyramidPath.toAbsolutePath().toString(), pyramidConfiguration, rendererConfiguration);
    }

    public final void resetPlanePyramidSourceFactory(Path path) {
        synchronized (lock) {
            final ImagePyramidFormatKind format = this.planePyramidFormat;
            final String result = customPlanePyramidSourceFactoryClass.trim();
            final String className = sourceFactoryClassName(path, format, result);
            assert className != null && !className.isEmpty() :
                    "empty result of planePyramidSourceFactoryClassName: " + className;
            if (className.equals(sourceFactory.getClass().getName())) {
                return;
            }
            final Object newFactoryObject;
            try {
                newFactoryObject = Class.forName(className).getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Plane pyramid source factory class not found: " + className, e);
            } catch (InstantiationException | IllegalAccessException
                     | InvocationTargetException | NoSuchMethodException e) {
                throw new IllegalArgumentException(
                        "Invalid plane pyramid source factory class " + className
                                + ": " + e.getMessage(), e);
            }
            if (!(newFactoryObject instanceof PlanePyramidSourceFactory newFactory)) {
                throw new IllegalArgumentException("Class  " + className + " is not a plane pyramid source factory");
            }
            closeSourceFactory();
            sourceFactory = newFactory;
        }
    }

    public final void closeSourceFactory() {
        sourceFactory.close();
        sourceFactory = PlanePyramidSourceFactory.Unsupported.INSTANCE;
    }

    public static String sourceFactoryClassName(
            Path path,
            ImagePyramidFormatKind format,
            String customClassName) {
        Objects.requireNonNull(path, "Null path");
        if (format.isAutoDetection()) {
            format = ImagePyramidFormatKind.valueOfExtension(extension(path.toString(), null));
        }
        if (format.hasFactory()) {
            return format.getFactoryClassName();
        }
        if (customClassName.isEmpty()) {
            throw new IllegalArgumentException("Custom factory class must be specified");
        }
        return customClassName;
    }

    public ImagePyramidLevelRois newLevelRois(PlanePyramidSource source, ImagePyramidMetadataJson metadataJson) {
        return newLevelRois(
                source,
                getInputNumbers(INPUT_ROI, true).toIRectangularArea(),
                metadataJson);
    }

    public ImagePyramidLevelRois newLevelRois(
            PlanePyramidSource source,
            IRectangularArea inputRoi,
            ImagePyramidMetadataJson metadataJson) {
        Objects.requireNonNull(source, "Null source");
        return new ImagePyramidLevelRois(source, resolutionLevel)
                .setMetadataJson(metadataJson)
                .setInputRoi(useInputROI ? inputRoi : null)
                // .setUseMetadataRectangles(useMetadata)
                // - deprecated solution: it is better to avoid using metadata at all when !useMetadata
                .setRequireNonIntersectingRectangles(requireNonIntersectingRectangles)
                .setMinimalROISize(minimalAnalyzedSize);
    }

    public void checkFrameSize(long sizeX, long sizeY) {
        if (sizeX < minimalAnalyzedSize || sizeY < minimalAnalyzedSize) {
            throw new IllegalArgumentException(
                    "Frame sizes " + sizeX + "x" + sizeY + " are too small: "
                            + "they must not be less than the minimal analyzed size "
                            + minimalAnalyzedSize + "x" + minimalAnalyzedSize);
        }
    }

    public int recommendedNumberOfStoredFrames(long numberOfFramesPerSeries) {
        recommendedNumberOfStoredFrames.putVariable("m", numberOfFramesPerSeries);
        recommendedNumberOfStoredFrames.putVariable("p",
                parseNumberIfPossible(recommendedNumberOfStoredFramesP));
        recommendedNumberOfStoredFrames.putVariable("snake", scanningSequence.snake());
        final int result = Arrays.round32(recommendedNumberOfStoredFrames.evalDouble());
        if (result <= 0) {
            throw new IllegalArgumentException("Number of stored frames must be positive, but it is " + result);
        }
        return result;
    }

    public void fillOutputNumberOfStoredFrames(long numberOfFramesPerSeries, boolean singleFrameMode) {
        getScalar(OUTPUT_RECOMMENDED_NUMBER_OF_FRAMES_IN_BUFFER).setTo(
                singleFrameMode ? 1 : recommendedNumberOfStoredFrames(numberOfFramesPerSeries));
    }

    public void fillOutputInformation(PlanePyramidSource source, ImagePyramidLevelRois levelRois) {
        fillOutputInformation(this, source, levelRois);
    }

    public static void fillOutputInformation(
            Executor executor,
            PlanePyramidSource source,
            ImagePyramidLevelRois levelRois) {
        Objects.requireNonNull(executor, "Null executor");
        Objects.requireNonNull(levelRois, "Null levelRois");
        if (executor.hasOutputPort(OUTPUT_NUMBER_OF_LEVELS)) {
            executor.getScalar(OUTPUT_NUMBER_OF_LEVELS).setTo(levelRois.numberOfResolutions());
        }
        if (executor.hasOutputPort(OUTPUT_LEVEL_DIM_X)) {
            executor.getScalar(OUTPUT_LEVEL_DIM_X).setTo(levelRois.levelDimX());
        }
        if (executor.hasOutputPort(OUTPUT_LEVEL_DIM_Y)) {
            executor.getScalar(OUTPUT_LEVEL_DIM_Y).setTo(levelRois.levelDimY());
        }
        if (source != null && executor.isOutputNecessary(OUTPUT_BUILTIN_METADATA)) {
            executor.getScalar(OUTPUT_BUILTIN_METADATA).setTo(source.metadata());
        }
        final ImagePyramidMetadataJson metadataJson = levelRois.metadataJson();
        if (executor.isOutputNecessary(OUTPUT_METADATA) && metadataJson != null) {
            executor.getScalar(OUTPUT_METADATA).setTo(metadataJson.jsonString());
        }
        if (executor.isOutputNecessary(OUTPUT_METADATA_ROI_RECTANGLES)) {
            executor.getNumbers(OUTPUT_METADATA_ROI_RECTANGLES).setToArray(
                    levelRois.allRoiCentersAndSizes(), 4);
        }
        if (executor.isOutputNecessary(OUTPUT_METADATA_ROI_CONTOURS)) {
            executor.getNumbers(OUTPUT_METADATA_ROI_CONTOURS).setTo(levelRois.allRoiContours());
        }
    }

    private static Object parseNumberIfPossible(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException ignored) {
        }
        return s;
    }
}
