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

import io.scif.FormatException;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.UpdatablePArray;
import net.algart.executors.api.Executor;
import net.algart.executors.api.ReadOnlyExecutionInput;
import net.algart.executors.api.data.SMat;
import net.algart.executors.modules.maps.LongTimeOpeningMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.multimatrix.MultiMatrix;

import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class ReadTiff extends AbstractTiffOperation implements ReadOnlyExecutionInput {
    public static final String OUTPUT_VALID = "valid";
    public static final String OUTPUT_DIM_X = "dim_x";
    public static final String OUTPUT_DIM_Y = "dim_y";

    private LongTimeOpeningMode openingMode = LongTimeOpeningMode.OPEN_ON_RESET_AND_FIRST_CALL;
    private boolean requireFileExistence = true;
    private boolean requireValidTiff = true;
    private int ifdIndex = 0;
    private boolean wholeLevel = false;
    private int startX = 0;
    private int startY = 0;
    private int sizeX = 1;
    private int sizeY = 1;
    private boolean truncateByLevel = true;
    // - necessary when we do not know level sizes before 1st call of this function,
    // for example, if we need to read large image fragment-per-fragment
    private int numberOfChannels = 0;
    //TODO!! control flags of TiffReader

    private volatile TiffReader reader = null;

    public ReadTiff() {
        addFileOperationPorts();
        addInputMat(DEFAULT_INPUT_PORT);
        addInputScalar(INPUT_CLOSE_FILE);
        addOutputMat(DEFAULT_OUTPUT_PORT);
        addOutputScalar(OUTPUT_DIM_X);
        addOutputScalar(OUTPUT_DIM_Y);
        addOutputScalar(OUTPUT_NUMBER_OF_LEVELS);
        addOutputScalar(OUTPUT_LEVEL_DIM_X);
        addOutputScalar(OUTPUT_LEVEL_DIM_Y);
        addOutputScalar(OUTPUT_IFD);
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

    public LongTimeOpeningMode getOpeningMode() {
        return openingMode;
    }

    public ReadTiff setOpeningMode(LongTimeOpeningMode openingMode) {
        this.openingMode = nonNull(openingMode);
        return this;
    }

    public boolean isRequireFileExistence() {
        return requireFileExistence;
    }

    public ReadTiff setRequireFileExistence(boolean requireFileExistence) {
        this.requireFileExistence = requireFileExistence;
        return this;
    }

    public boolean isRequireValidTiff() {
        return requireValidTiff;
    }

    public ReadTiff setRequireValidTiff(boolean requireValidTiff) {
        this.requireValidTiff = requireValidTiff;
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

    public int getStartX() {
        return startX;
    }

    public ReadTiff setStartX(int startX) {
        this.startX = startX;
        return this;
    }

    public int getStartY() {
        return startY;
    }

    public ReadTiff setStartY(int startY) {
        this.startY = startY;
        return this;
    }

    public int getSizeX() {
        return sizeX;
    }

    public ReadTiff setSizeX(int sizeX) {
        this.sizeX = sizeX;
        return this;
    }

    public int getSizeY() {
        return sizeY;
    }

    public ReadTiff setSizeY(int sizeY) {
        this.sizeY = sizeY;
        return this;
    }

    public boolean isTruncateByLevel() {
        return truncateByLevel;
    }

    public ReadTiff setTruncateByLevel(boolean truncateByLevel) {
        this.truncateByLevel = truncateByLevel;
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
    public void initialize() {
        if (openingMode.isClosePreviousOnReset()) {
            closeFile();
        }
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
                if (requireFileExistence) {
                    throw new FileNotFoundException("File not found: " + path);
                } else {
                    return null;
                }
            }
            final TiffReader reader = openFile(path);
            if (!reader.isValid()) {
                closeFile();
                return null;
            }
            int fromX = this.startX;
            int fromY = this.startY;
            int toX = fromX + this.sizeX;
            int toY = fromY + this.sizeY;
            //TODO!! truncate if truncateByLevel

            final MultiMatrix result = readMultiMatrix(reader, fromX, fromY, toX, toY);


            final boolean close = needToClose(this, openingMode);
            if (close) {
                closeFile();
            }
            return result;
        } catch (IOException | FormatException e) {
            throw new IOError(e);
        }
    }

    @Override
    public void close() {
        super.close();
        closeFile();
    }


    public TiffReader openFile(Path path) {
        Objects.requireNonNull(path, "Null path");
        TiffReader reader = this.reader;
        if (reader == null) {
            try {
                logDebug(() -> "Opening " + path);
                this.reader = reader = new TiffReader(context(), path, requireValidTiff);
                // - note: the assignments sequence guarantees that this method will not return null
            } catch (IOException | FormatException e) {
                throw new IOError(e);
            }
        }
        fillOutputFileInformation(path);
        // - note: we need to fill output ports here, even if the file was already opened
        fillOutputInformation(this, reader, ifdIndex);
        return reader;
    }

    public static void fillOutputInformation(Executor executor, TiffReader reader, int ifdIndex) {
        Objects.requireNonNull(executor, "Null executor");
        Objects.requireNonNull(reader, "Null reader");
        try {
            final List<TiffIFD> ifds = reader.allIFDs();
            if (executor.hasOutputPort(OUTPUT_NUMBER_OF_LEVELS)) {
                executor.getScalar(OUTPUT_NUMBER_OF_LEVELS).setTo(ifds.size());
            }
            if (ifdIndex < ifds.size()) {
                TiffIFD ifd = ifds.get(ifdIndex);
                if (executor.hasOutputPort(OUTPUT_LEVEL_DIM_X)) {
                    executor.getScalar(OUTPUT_LEVEL_DIM_X).setTo(ifd.getImageDimX());
                }
                if (executor.hasOutputPort(OUTPUT_LEVEL_DIM_Y)) {
                    executor.getScalar(OUTPUT_LEVEL_DIM_Y).setTo(ifd.getImageDimY());
                }
                if (executor.isOutputNecessary(OUTPUT_IFD)) {
                    executor.getScalar(OUTPUT_IFD).setTo(ifd.toString(TiffIFD.StringFormat.JSON));
                }
                if (executor.isOutputNecessary(OUTPUT_PRETTY_IFD)) {
                    executor.getScalar(OUTPUT_PRETTY_IFD).setTo(ifd.toString(TiffIFD.StringFormat.DETAILED));
                }
            }
        } catch (IOException | FormatException e) {
            throw new IOError(e);
        }
    }

    private MultiMatrix readMultiMatrix(TiffReader reader, int fromX, int fromY, int toX, int toY)
            throws IOException, FormatException {
        final TiffMap map = reader.map(ifdIndex);
        final Matrix<? extends PArray> m = reader.readMatrix(map, fromX, fromY, toX, toY);
        MultiMatrix result = MultiMatrix.unpackChannels(m);
        if (numberOfChannels != 0) {
            result = result.asOtherNumberOfChannels(numberOfChannels);
        }
        return result;
    }

    private void closeFile() {
        TiffReader reader = this.reader;
        if (reader != null) {
            this.reader = null;
            logDebug(() -> "Closing " + reader);
            try {
                reader.close();
            } catch (IOException e) {
                throw new IOError(e);
            }
        }
    }
}
