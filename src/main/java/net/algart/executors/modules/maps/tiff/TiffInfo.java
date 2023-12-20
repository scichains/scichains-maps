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

package net.algart.executors.modules.maps.tiff;

import net.algart.executors.api.ReadOnlyExecutionInput;
import net.algart.executors.modules.maps.pyramids.io.AbstractImagePyramidOperation;
import net.algart.executors.modules.maps.pyramids.io.ImagePyramidLevelRois;
import net.algart.executors.modules.maps.pyramids.io.ImagePyramidMetadataJson;
import net.algart.executors.modules.maps.pyramids.io.ScanningMapSequence;
import net.algart.maps.pyramids.io.api.PlanePyramidSource;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class TiffInfo extends AbstractTiffOperation implements ReadOnlyExecutionInput {
    public static final String OUTPUT_ALL_IFDS = "all_ifds";
    public static final String OUTPUT_PRETTY_ALL_IFDS = "pretty_all_ifds";

    private boolean requireFileExistence = true;
    private boolean requireValidTiff = true;
    private int ifdIndex = 0;

    public TiffInfo() {
        super();
        setDefaultOutputScalar(OUTPUT_PRETTY_ALL_IFDS);
        addOutputScalar(OUTPUT_VALID);
        addOutputScalar(OUTPUT_NUMBER_OF_IMAGES);
        addOutputScalar(OUTPUT_IFD);
        addOutputScalar(OUTPUT_PRETTY_IFD);
   }

    public boolean isRequireFileExistence() {
        return requireFileExistence;
    }

    public TiffInfo setRequireFileExistence(boolean requireFileExistence) {
        this.requireFileExistence = requireFileExistence;
        return this;
    }

    public boolean isRequireValidTiff() {
        return requireValidTiff;
    }

    public TiffInfo setRequireValidTiff(boolean requireValidTiff) {
        this.requireValidTiff = requireValidTiff;
        return this;
    }

    public int getIfdIndex() {
        return ifdIndex;
    }

    public TiffInfo setIfdIndex(int ifdIndex) {
        this.ifdIndex = nonNegative(ifdIndex);
        return this;
    }

    @Override
    public void process() {
        testTiff(completeFilePath());
    }

    public void testTiff(Path path) {
        Objects.requireNonNull(path, "Null path");

    }

}
