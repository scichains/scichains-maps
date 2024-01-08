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

package net.algart.executors.modules.maps.tiff;

import net.algart.executors.api.ReadOnlyExecutionInput;
import net.algart.executors.api.data.SMat;
import net.algart.executors.modules.maps.LongTimeOpeningMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.multimatrix.MultiMatrix2D;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class WriteTiff extends AbstractTiffOperation implements ReadOnlyExecutionInput {
    public static final String OUTPUT_IFD_INDEX = "ifd_index";

    public enum ByteOrder {
        BIG_ENDIAN(java.nio.ByteOrder.BIG_ENDIAN),
        LITTLE_ENDIAN(java.nio.ByteOrder.LITTLE_ENDIAN),
        NATIVE(java.nio.ByteOrder.nativeOrder());

        private final java.nio.ByteOrder order;

        ByteOrder(java.nio.ByteOrder order) {
            this.order = order;
        }

        public java.nio.ByteOrder order() {
            return order;
        }

        public boolean isLittleEndian() {
            return order == java.nio.ByteOrder.LITTLE_ENDIAN;
        }
    }

    private LongTimeOpeningMode openingMode = LongTimeOpeningMode.OPEN_AND_CLOSE;
    private boolean appendIFDToExistingTiff = true;
    private boolean bigTiff = true;
    private ByteOrder byteOrder = ByteOrder.NATIVE;
    private boolean resizable = true;
    private int imageDimX = 0;
    private int imageDimY = 0;
    private int x = 0;
    private int y = 0;
    private boolean flushASAP = false;

    private volatile TiffWriter writer = null;
    private volatile TiffMap map = null;
    // - note: "volatile" does not provide correct protection here! This just reduces possible problems

    public WriteTiff() {
        useVisibleResultParameter();
        setDefaultOutputScalar(OUTPUT_ABSOLUTE_PATH);
        addInputMat(DEFAULT_INPUT_PORT);
        addInputScalar(INPUT_CLOSE_FILE);
        addOutputMat(DEFAULT_OUTPUT_PORT);
        addOutputScalar(OUTPUT_VALID);
        addOutputScalar(OUTPUT_NUMBER_OF_IMAGES);
        addOutputScalar(OUTPUT_IMAGE_DIM_X);
        addOutputScalar(OUTPUT_IMAGE_DIM_Y);
        addOutputScalar(OUTPUT_IFD_INDEX);
        addOutputScalar(OUTPUT_IFD);
        addOutputScalar(OUTPUT_PRETTY_IFD);
    }

    @Override
    public WriteTiff setFile(String file) {
        super.setFile(file);
        return this;
    }

    public LongTimeOpeningMode getOpeningMode() {
        return openingMode;
    }

    public WriteTiff setOpeningMode(LongTimeOpeningMode openingMode) {
        this.openingMode = nonNull(openingMode);
        return this;
    }

    public boolean isAppendIFDToExistingTiff() {
        return appendIFDToExistingTiff;
    }

    public WriteTiff setAppendIFDToExistingTiff(boolean appendIFDToExistingTiff) {
        this.appendIFDToExistingTiff = appendIFDToExistingTiff;
        return this;
    }

    public boolean isBigTiff() {
        return bigTiff;
    }

    public WriteTiff setBigTiff(boolean bigTiff) {
        this.bigTiff = bigTiff;
        return this;
    }

    public ByteOrder getByteOrder() {
        return byteOrder;
    }

    public WriteTiff setByteOrder(ByteOrder byteOrder) {
        this.byteOrder = byteOrder;
        return this;
    }

    public boolean isResizable() {
        return resizable;
    }

    public WriteTiff setResizable(boolean resizable) {
        this.resizable = resizable;
        return this;
    }

    public int getImageDimX() {
        return imageDimX;
    }

    public WriteTiff setImageDimX(int imageDimX) {
        this.imageDimX = nonNegative(imageDimX);
        return this;
    }

    public WriteTiff setImageDimX(String imageDimX) {
        return setImageDimX(intOrDefault(imageDimX, 0));
    }

    public int getImageDimY() {
        return imageDimY;
    }

    public WriteTiff setImageDimY(int imageDimY) {
        this.imageDimY = nonNegative(imageDimY);
        return this;
    }

    public WriteTiff setImageDimY(String imageDimY) {
        return setImageDimY(intOrDefault(imageDimY, 0));
    }

    public int getX() {
        return x;
    }

    public WriteTiff setX(int x) {
        this.x = x;
        return this;
    }

    public int getY() {
        return y;
    }

    public WriteTiff setY(int y) {
        this.y = y;
        return this;
    }

    public boolean isFlushASAP() {
        return flushASAP;
    }

    public WriteTiff setFlushASAP(boolean flushASAP) {
        this.flushASAP = flushASAP;
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
        SMat input = getInputMat(defaultInputPortName());
        // Note: unlike standard WriteImage, the input is always required.
        // This helps to simplify logic of opening (we need to know image characteristics).
        if (input.isInitialized()) {
            logDebug(() -> "Copying " + input);
            getMat().setTo(input);
        } else {
            writeTiff(completeFilePath(), input.toMultiMatrix2D());
        }
    }

    public void writeTiff(Path path, MultiMatrix2D matrix) {
        Objects.requireNonNull(path, "Null path");
        Objects.requireNonNull(matrix, "Null matrix");
        try {
            getScalar(OUTPUT_VALID).setTo(false);
            openFile(path, matrix);
            writeMultiMatrix(matrix);
            final boolean close = needToClose(this, openingMode);
            if (close) {
                closeFile();
            }
        } catch (IOException e) {
            closeFile();
            closeContext();
            // - closing can be important to allow the user to fix the problem;
            // moreover, in a case the error it is better to free all possible connected resources
            throw new IOError(e);
        }
    }

    @Override
    public void close() {
        super.close();
        closeFile();
    }


    public void openFile(Path path, MultiMatrix2D firstMatrix) throws IOException {
        Objects.requireNonNull(path, "Null path");
        logDebug(() -> "Writing " + path);
        if (writer == null) {
            writer = new TiffWriter(context(), path, !appendIFDToExistingTiff);
            writer.setBigTiff(bigTiff);
            writer.setLittleEndian(byteOrder.isLittleEndian());
            writer.open(true);
            map = writer.newMap(configure(firstMatrix));

            // - note: the assignments sequence guarantees that this method will not return null
        }
        fillOutputFileInformation(path);
        // - note: we need to fill output ports here, even if the file was already opened
//        fillReadingOutputInformation(this, writer, ifdIndex);
    }

    private TiffIFD configure(MultiMatrix2D firstMatrix) {
        TiffIFD ifd = new TiffIFD();
        ifd.putPixelInformation(firstMatrix.numberOfChannels(), firstMatrix.elementType());
        if (!resizable) {
            if (imageDimX == 0 || imageDimY == 0) {
                throw new IllegalArgumentException("Zero image dimensions " + imageDimX + "x" + imageDimY
                        + " are allowed only in resizable mode (in this case they are ignored)");
            }
            ifd.putImageDimensions(imageDimX, imageDimY);
        }
        return ifd;
    }

    private void writeMultiMatrix(MultiMatrix2D matrix) throws IOException {
        List<TiffTile> updated = writer.updateMatrix(map, matrix.packChannels(), x, y);
        if (flushASAP) {
            writer.writeCompletedTiles(updated);
        }
    }

    private void closeFile() {
        if (writer != null) {
            this.writer = null;
            logDebug(() -> "Closing " + writer);
            try {
                writer.complete(map);
                writer.close();
            } catch (IOException e) {
                throw new IOError(e);
            }
        }
    }
}
