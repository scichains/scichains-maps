/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.executors.api.ReadOnlyExecutionInput;
import net.algart.executors.api.data.SMat;
import net.algart.executors.modules.maps.LongTimeOpeningMode;
import net.algart.math.IRectangularArea;
import net.algart.matrices.tiff.TiffOpenMode;
import net.algart.matrices.tiff.TiffReader;
import net.algart.multimatrix.MultiMatrix;
import net.algart.multimatrix.MultiMatrix2D;

import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ReadTiff extends AbstractTiffOperation implements ReadOnlyExecutionInput {
    public static final String OUTPUT_DIM_X = "dim_x";
    public static final String OUTPUT_DIM_Y = "dim_y";
    public static final String OUTPUT_RECTANGLE = "rectangle";

    private LongTimeOpeningMode openingMode = LongTimeOpeningMode.OPEN_AND_CLOSE;
    // - note: default value in this CLASS (not in the executor model) SHOULD be very simple,
    // because this class may be used without full setup of all parameter, for example, in InputReadTiff model
    private boolean requireFileExistence = true;
    private boolean requireTiff = true;
    private int ifdIndex = 0;
    private boolean wholeImage = true;
    private int x = 0;
    private int y = 0;
    private int sizeX = 1;
    private int sizeY = 1;
    private boolean cropToImage = true;
    // - necessary when we do not know level sizes before 1st call of this function,
    // for example, if we need to read large image fragment-per-fragment
    private boolean caching = false;
    private boolean autoUnpackBitsToBytes = false;
    private boolean autoScaleWhenIncreasingBitDepth = true;
    private boolean autoCorrectInvertedBrightness = false;
    private boolean cropTilesToImageBoundaries = true;
    private int numberOfChannels = 0;

    private volatile TiffReader reader = null;

    public ReadTiff() {
        useVisibleResultParameter();
        addInputMat(DEFAULT_INPUT_PORT);
        addInputScalar(INPUT_CLOSE_FILE);
        addOutputMat(DEFAULT_OUTPUT_PORT);
        addOutputScalar(OUTPUT_DIM_X);
        addOutputScalar(OUTPUT_DIM_Y);
        addOutputScalar(OUTPUT_VALID);
        addOutputScalar(OUTPUT_IFD_INDEX);
        addOutputScalar(OUTPUT_NUMBER_OF_IMAGES);
        addOutputScalar(OUTPUT_IMAGE_DIM_X);
        addOutputScalar(OUTPUT_IMAGE_DIM_Y);
        addOutputNumbers(OUTPUT_RECTANGLE);
        addOutputScalar(OUTPUT_IFD);
        addOutputScalar(OUTPUT_PRETTY_IFD);
        addOutputScalar(OUTPUT_FILE_SIZE);
        addOutputScalar(OUTPUT_CLOSED);
    }

    public static ReadTiff getInstance() {
        return new ReadTiff();
    }

    // Used in InputReadTiff model
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

    public boolean isRequireTiff() {
        return requireTiff;
    }

    public ReadTiff setRequireTiff(boolean requireTiff) {
        this.requireTiff = requireTiff;
        return this;
    }

    public int getIfdIndex() {
        return ifdIndex;
    }

    public ReadTiff setIfdIndex(int ifdIndex) {
        this.ifdIndex = nonNegative(ifdIndex);
        return this;
    }

    public boolean isWholeImage() {
        return wholeImage;
    }

    public ReadTiff setWholeImage(boolean wholeImage) {
        this.wholeImage = wholeImage;
        return this;
    }

    public int getX() {
        return x;
    }

    public ReadTiff setX(int x) {
        this.x = x;
        return this;
    }

    public int getY() {
        return y;
    }

    public ReadTiff setY(int y) {
        this.y = y;
        return this;
    }

    public int getSizeX() {
        return sizeX;
    }

    public ReadTiff setSizeX(int sizeX) {
        this.sizeX = nonNegative(sizeX);
        return this;
    }

    public int getSizeY() {
        return sizeY;
    }

    public ReadTiff setSizeY(int sizeY) {
        this.sizeY = nonNegative(sizeY);
        return this;
    }

    public boolean isCropToImage() {
        return cropToImage;
    }

    public ReadTiff setCropToImage(boolean cropToImage) {
        this.cropToImage = cropToImage;
        return this;
    }

    public boolean isCaching() {
        return caching;
    }

    public ReadTiff setCaching(boolean caching) {
        this.caching = caching;
        return this;
    }

    public boolean isAutoUnpackBitsToBytes() {
        return autoUnpackBitsToBytes;
    }

    public ReadTiff setAutoUnpackBitsToBytes(boolean autoUnpackBitsToBytes) {
        this.autoUnpackBitsToBytes = autoUnpackBitsToBytes;
        return this;
    }

    public boolean isAutoScaleWhenIncreasingBitDepth() {
        return autoScaleWhenIncreasingBitDepth;
    }

    public ReadTiff setAutoScaleWhenIncreasingBitDepth(boolean autoScaleWhenIncreasingBitDepth) {
        this.autoScaleWhenIncreasingBitDepth = autoScaleWhenIncreasingBitDepth;
        return this;
    }

    public boolean isAutoCorrectInvertedBrightness() {
        return autoCorrectInvertedBrightness;
    }

    public ReadTiff setAutoCorrectInvertedBrightness(boolean autoCorrectInvertedBrightness) {
        this.autoCorrectInvertedBrightness = autoCorrectInvertedBrightness;
        return this;
    }

    public boolean isCropTilesToImageBoundaries() {
        return cropTilesToImageBoundaries;
    }

    public ReadTiff setCropTilesToImageBoundaries(boolean cropTilesToImageBoundaries) {
        this.cropTilesToImageBoundaries = cropTilesToImageBoundaries;
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
            closeReader();
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
            getScalar(OUTPUT_VALID).setTo(false);
            getScalar(OUTPUT_DIM_X).remove();
            getScalar(OUTPUT_DIM_Y).remove();
            getNumbers(OUTPUT_RECTANGLE).remove();
            if (!Files.isRegularFile(path)) {
                if (requireFileExistence) {
                    throw new FileNotFoundException("File not found: " + path);
                } else {
                    return null;
                }
            }
            final TiffReader reader = openFile(path);
            fillReadingOutputInformation(this, reader, ifdIndex);
            if (!reader.isValidTiff()) {
                closeReader();
                return null;
            }
            final MultiMatrix2D multiMatrix = doActualReading ?
                    readMultiMatrix(reader) :
                    null;
            final boolean close = needToClose(this, openingMode);
            if (close) {
                closeReader();
            }
            return multiMatrix;
        } catch (IOException e) {
            getScalar(OUTPUT_VALID).setTo(false);
            closeReader();
            // - closing can be important to allow the user to fix the problem;
            // moreover, in a case the error it is better to free all possible connected resources
            if (requireTiff) {
                throw new IOError(e);
            } else {
                LOG.log(System.Logger.Level.INFO, "IGNORING EXCEPTION while reading TIFF " + path +
                        ", IFD #" + ifdIndex + ":\n      " + e);
                getScalar(OUTPUT_PRETTY_IFD).setTo(e.toString());
                return null;
            }
        } finally {
            getScalar(OUTPUT_CLOSED).setTo(reader == null);
        }
    }

    @Override
    public void close() {
        super.close();
        closeReader();
    }


    public TiffReader openFile(Path path) throws IOException {
        Objects.requireNonNull(path, "Null path");
        logDebug(() -> "Reading " + path);
        TiffReader reader = this.reader;
        if (reader == null) {
            reader = new TiffReader(path, TiffOpenMode.ofRequireTiff(requireTiff)).setCaching(caching);
            reader.setAutoUnpackBits(TiffReader.UnpackBits.of(autoUnpackBitsToBytes));
            reader.setAutoScaleWhenIncreasingBitDepth(autoScaleWhenIncreasingBitDepth);
            reader.setAutoCorrectInvertedBrightness(autoCorrectInvertedBrightness);
            reader.setCropTilesToImageBoundaries(cropTilesToImageBoundaries);
            this.reader = reader;
            // - note: the assignments sequence guarantees that this method will not return null
        }
        fillOutputFileInformation(path);
        // - note: we need to fill output ports here, even if the file was already opened
        return reader;
    }

    private MultiMatrix2D readMultiMatrix(TiffReader reader) throws IOException {
        final var map = reader.newMap(ifdIndex);
        int fromX = this.x;
        int fromY = this.y;
        int toX = fromX + this.sizeX;
        int toY = fromY + this.sizeY;
        if (wholeImage) {
            fromX = 0;
            fromY = 0;
            toX = map.dimX();
            toY = map.dimY();
        } else if (cropToImage) {
            fromX = Math.max(fromX, 0);
            fromY = Math.max(fromY, 0);
            toX = Math.min(toX, map.dimX());
            toY = Math.min(toY, map.dimY());
            if (fromX >= toX || fromY >= toY) {
                return null;
            }
        }
        final Matrix<? extends PArray> m = reader.readMatrix(map, fromX, fromY, toX, toY);
        MultiMatrix2D result = MultiMatrix.of2DMerged(m);
        if (numberOfChannels != 0) {
            result = result.asOtherNumberOfChannels(numberOfChannels);
        }
        getScalar(OUTPUT_DIM_X).setTo(toX - fromX);
        getScalar(OUTPUT_DIM_Y).setTo(toY - fromY);
        getNumbers(OUTPUT_RECTANGLE).setTo(IRectangularArea.valueOf(fromX, fromY, toX - 1, toY - 1));
        return result;
    }

    private void closeReader() {
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
