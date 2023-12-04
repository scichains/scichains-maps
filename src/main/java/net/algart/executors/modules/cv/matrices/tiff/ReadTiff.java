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

package net.algart.executors.modules.cv.matrices.tiff;

import net.algart.executors.api.ReadOnlyExecutionInput;
import net.algart.executors.api.data.SMat;
import net.algart.executors.modules.core.common.io.FileOperation;
import net.algart.multimatrix.MultiMatrix;

import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ReadTiff extends FileOperation implements ReadOnlyExecutionInput {
    public static final String OUTPUT_DIM_X = "dim_x";
    public static final String OUTPUT_DIM_Y = "dim_y";
    public static final String OUTPUT_NUMBER_OF_LEVELS = "number_of_levels";
    public static final String OUTPUT_LEVEL_DIM_X = "level_dim_x";
    public static final String OUTPUT_LEVEL_DIM_Y = "level_dim_y";

    private boolean fileExistenceRequired = true;
    private int ifdIndex = 0;
    private boolean wholeLevel = false;
    private long startX = 0;
    private long startY = 0;
    private long sizeX = 1;
    private long sizeY = 1;
    private int numberOfChannels = 0;
    //TODO!! control flags of TiffReader

    public ReadTiff() {
        addFileOperationPorts();
        addInputMat(DEFAULT_INPUT_PORT);
        addOutputMat(DEFAULT_OUTPUT_PORT);
        addOutputScalar(OUTPUT_DIM_X);
        addOutputScalar(OUTPUT_DIM_Y);
    }

    public static ReadTiff getInstance() {
        return new ReadTiff();
    }

    public static ReadTiff getSecureInstance() {
        final ReadTiff result = new ReadTiff();
        result.setSecure(true);
        return result;
    }

    @Override
    public ReadTiff setFile(String file) {
        super.setFile(file);
        return this;
    }

    public boolean isFileExistenceRequired() {
        return fileExistenceRequired;
    }

    public ReadTiff setFileExistenceRequired(boolean fileExistenceRequired) {
        this.fileExistenceRequired = fileExistenceRequired;
        return this;
    }

    public int getIfdIndex() {
        return ifdIndex;
    }

    public ReadTiff setIfdIndex(int ifdIndex) {
        this.ifdIndex = nonNegative(ifdIndex);
        return this;
    }

    public boolean isWholeLevel() {
        return wholeLevel;
    }

    public ReadTiff setWholeLevel(boolean wholeLevel) {
        this.wholeLevel = wholeLevel;
        return this;
    }

    public long getStartX() {
        return startX;
    }

    public ReadTiff setStartX(long startX) {
        this.startX = startX;
        return this;
    }

    public long getStartY() {
        return startY;
    }

    public ReadTiff setStartY(long startY) {
        this.startY = startY;
        return this;
    }

    public long getSizeX() {
        return sizeX;
    }

    public ReadTiff setSizeX(long sizeX) {
        this.sizeX = sizeX;
        return this;
    }

    public long getSizeY() {
        return sizeY;
    }

    public ReadTiff setSizeY(long sizeY) {
        this.sizeY = sizeY;
        return this;
    }

    public int getNumberOfChannels() {
        return numberOfChannels;
    }

    public ReadTiff setNumberOfChannels(int numberOfChannels) {
        this.numberOfChannels = nonNegative(numberOfChannels);
        return this;
    }

    @Override
    public void process() {
        SMat input = getInputMat(defaultInputPortName(), true);
        if (input.isInitialized()) {
            logDebug(() -> "Copying " + input);
            getMat().setTo(input);
        } else {
            MultiMatrix multiMatrix = readTiff(completeFilePath(), isOutputNecessary(DEFAULT_OUTPUT_PORT));
            if (multiMatrix != null) {
                getMat().setTo(multiMatrix);
            } else {
                getMat().remove();
            }
        }
    }

    public MultiMatrix readTiff(Path path) {
        return readTiff(path, true);
    }

    public MultiMatrix readTiff(Path path, boolean doActualReading) {
        Objects.requireNonNull(path, "Null path");
        try {
            if (!Files.isRegularFile(path)) {
                if (fileExistenceRequired) {
                    throw new FileNotFoundException("File not found: " + path);
                } else {
                    return null;
                }
            }
            MultiMatrix result = null;

            if (result == null) throw new UnsupportedOperationException();

            if (numberOfChannels != 0) {
                result = result.asOtherNumberOfChannels(numberOfChannels);
            }
            return result;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

}
