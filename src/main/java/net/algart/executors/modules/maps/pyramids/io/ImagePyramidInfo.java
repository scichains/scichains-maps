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

package net.algart.executors.modules.maps.pyramids.io;

import net.algart.executors.api.ReadOnlyExecutionInput;
import net.algart.maps.pyramids.io.api.PlanePyramidSource;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class ImagePyramidInfo extends AbstractImagePyramidOperation implements ReadOnlyExecutionInput {
    private long sizeX = 1;
    private long sizeY = 1;

    public ImagePyramidInfo() {
        super();
        addOutputScalar(DEFAULT_OUTPUT_PORT);
        // - note that this port is not specified in the JSON model: it is used only for showing visual result
        addOutputScalar(OUTPUT_NUMBER_OF_LEVELS);
        addOutputScalar(OUTPUT_LEVEL_DIM_X);
        addOutputScalar(OUTPUT_LEVEL_DIM_Y);
        addOutputScalar(OUTPUT_NUMBER_OF_FRAMES);
        addOutputScalar(OUTPUT_FRAMES_PER_SERIES);
        addOutputScalar(OUTPUT_RECOMMENDED_NUMBER_OF_FRAMES_IN_BUFFER);
        addOutputScalar(OUTPUT_BUILTIN_METADATA);
        addOutputScalar(OUTPUT_METADATA);
        addOutputNumbers(OUTPUT_METADATA_ROI_RECTANGLES);
        addOutputNumbers(OUTPUT_METADATA_ROI_CONTOURS);
   }

    public long getSizeX() {
        return sizeX;
    }

    public ImagePyramidInfo setSizeX(long sizeX) {
        this.sizeX = positive(sizeX);
        return this;
    }

    public long getSizeY() {
        return sizeY;
    }

    public ImagePyramidInfo setSizeY(long sizeY) {
        this.sizeY = positive(sizeY);
        return this;
    }

    @Override
    public void process() {
        testPlanePyramid(completeFilePath());
    }

    public void testPlanePyramid(Path path) {
        Objects.requireNonNull(path, "Null path");
        getScalar().setTo("Information about the pyramid " + path);
        PlanePyramidSource planePyramidSource = null;
        try {
            planePyramidSource = openPyramid(path);
            final ImagePyramidMetadataJson metadataJson = readMetadataOrNull(path);
            final ImagePyramidLevelRois levelRois = newLevelRois(planePyramidSource, metadataJson);
            fillOutputInformation(planePyramidSource, levelRois);
            getScalar(OUTPUT_NUMBER_OF_FRAMES).setTo(
                    levelRois.totalNumberOfFrames(sizeX, sizeY, false));
            final ScanningMapSequence mapSequence = getScanningSequence().mapSequence(levelRois);
            final long framesPerSeries = levelRois.framesPerSeries(mapSequence, sizeX, sizeY, false);
            if (mapSequence != null) {
                getScalar(OUTPUT_FRAMES_PER_SERIES).setTo(framesPerSeries);
            }
            fillOutputNumberOfStoredFrames(framesPerSeries, false);
        } catch (IOException e) {
            throw new IOError(e);
        } finally {
            if (planePyramidSource != null) {
                planePyramidSource.freeResources(PlanePyramidSource.FlushMode.STANDARD);
            }
        }
    }

    private PlanePyramidSource openPyramid(Path path) throws IOException {
        Objects.requireNonNull(path, "Null path");
        logDebug(() -> "Opening " + path);
        final PlanePyramidSource result = newPlanePyramidSource(path);
        if (resolutionLevel >= result.numberOfResolutions()) {
            throw new IllegalArgumentException("Too big index of resolution level: there are only "
                    + result.numberOfResolutions() + " resolutions");
        }
        return result;
    }
}
