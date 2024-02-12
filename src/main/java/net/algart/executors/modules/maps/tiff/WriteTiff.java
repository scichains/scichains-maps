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

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.executors.api.ReadOnlyExecutionInput;
import net.algart.executors.api.data.SMat;
import net.algart.executors.modules.maps.LongTimeOpeningMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.multimatrix.MultiMatrix2D;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class WriteTiff extends AbstractTiffOperation implements ReadOnlyExecutionInput {
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
    private boolean appendIFDToExistingTiff = false;
    private boolean bigTiff = false;
    private ByteOrder byteOrder = ByteOrder.NATIVE;
    private TagCompression compression = TagCompression.UNCOMPRESSED;
    private boolean preferRGB = false;
    private Double quality = null;
    private boolean signedIntegers = false;
    private boolean resizable = true;
    private int imageDimX = 0;
    private int imageDimY = 0;
    private int x = 0;
    private int y = 0;
    private boolean tiled = true;
    private int tileSizeX = 256;
    private int tileSizeY = 256;
    private Integer stripSizeY = null;
    private boolean flushASAP = false;

    private volatile TiffWriter writer = null;
    private volatile TiffMap map = null;
    // - note: "volatile" does not provide correct protection here! This just reduces possible problems

    public WriteTiff() {
        useVisibleResultParameter();
        defaultOutputPortName(OUTPUT_ABSOLUTE_PATH);
        addInputMat(DEFAULT_INPUT_PORT);
        addInputScalar(INPUT_CLOSE_FILE);
        addOutputScalar(OUTPUT_VALID);
        addOutputScalar(OUTPUT_IFD_INDEX);
        addOutputScalar(OUTPUT_NUMBER_OF_IMAGES);
        addOutputScalar(OUTPUT_IMAGE_DIM_X);
        addOutputScalar(OUTPUT_IMAGE_DIM_Y);
        addOutputScalar(OUTPUT_IFD);
        addOutputScalar(OUTPUT_PRETTY_IFD);
        addOutputScalar(OUTPUT_FILE_SIZE);
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

    public TagCompression getCompression() {
        return compression;
    }

    public WriteTiff setCompression(TagCompression compression) {
        this.compression = nonNull(compression);
        return this;
    }

    public boolean isPreferRGB() {
        return preferRGB;
    }

    public WriteTiff setPreferRGB(boolean preferRGB) {
        this.preferRGB = preferRGB;
        return this;
    }

    public Double getQuality() {
        return quality;
    }

    public WriteTiff setQuality(Double quality) {
        this.quality = quality;
        return this;
    }

    public boolean isSignedIntegers() {
        return signedIntegers;
    }

    public WriteTiff setSignedIntegers(boolean signedIntegers) {
        this.signedIntegers = signedIntegers;
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

    public boolean isTiled() {
        return tiled;
    }

    public WriteTiff setTiled(boolean tiled) {
        this.tiled = tiled;
        return this;
    }

    public int getTileSizeX() {
        return tileSizeX;
    }

    public WriteTiff setTileSizeX(int tileSizeX) {
        this.tileSizeX = positive(tileSizeX);
        return this;
    }

    public int getTileSizeY() {
        return tileSizeY;
    }

    public WriteTiff setTileSizeY(int tileSizeY) {
        this.tileSizeY = positive(tileSizeY);
        return this;
    }

    public Integer getStripSizeY() {
        return stripSizeY;
    }

    public WriteTiff setStripSizeY(Integer stripSizeY) {
        this.stripSizeY = stripSizeY == null ? null : nonNegative(stripSizeY);
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
            try {
                closeWriter(false);
            } catch (IOException e) {
                throw new IOError(e);
            }
        }
    }

    @Override
    public void process() {
        SMat input = getInputMat(defaultInputPortName());
        // Note: unlike standard WriteImage, the input is always required.
        // This helps to simplify logic of opening (we need to know image characteristics).
        writeTiff(completeFilePath(), input.toMultiMatrix2D());
    }

    public void writeTiff(Path path, MultiMatrix2D matrix) {
        Objects.requireNonNull(path, "Null path");
        Objects.requireNonNull(matrix, "Null matrix");
        try {
            final boolean needToClose = needToClose(this, openingMode);
            Matrix<? extends PArray> m = matrix.packChannels();
            openFile(path, m, needToClose);
            getScalar(OUTPUT_IFD_INDEX).setTo(writer.numberOfIFDs());
            // - BEFORE writing
            writeMatrix(m);
            if (needToClose) {
                closeWriter(true);
            } else {
                fillWritingOutputInformation(this, writer, map);
                // - AFTER writing
            }
        } catch (IOException e) {
            closeFileOnError();
            // - closing is important to allow the user to fix the problem (for example, delete the file);
            // moreover, in a case the error it is better to free all possible connected resources
            throw new IOError(e);
        } catch (RuntimeException e) {
            // - some not-too-good codecs may throw strange exceptions instead of IOException,
            // and we need to close file to allow user to continue work (for example, to delete it)
            closeFileOnError();
            throw e;
        }
    }

    @Override
    public void close() {
        super.close();
        closeFileOnError();
    }

    public void openFile(Path path, Matrix<? extends PArray> firstMatrix, boolean singleWriteOnly) throws IOException {
        Objects.requireNonNull(path, "Null path");
        logDebug(() -> "Writing " + path);
        if (this.writer == null) {
            TiffWriter writer = null;
            TiffMap map;
            try {
                writer = new TiffWriter(path, !appendIFDToExistingTiff);
                // - will be ignored in a case of any exception
                writer.setBigTiff(bigTiff);
                writer.setLittleEndian(byteOrder.isLittleEndian());
                writer.setPreferRGB(preferRGB);
                writer.open(true);
                final TiffIFD ifd = configure(writer, firstMatrix, singleWriteOnly);
                map = writer.newMap(ifd, resizable && !singleWriteOnly);
                writer.writeForward(map);
            } catch (IOException | RuntimeException e) {
                if (writer != null) {
                    writer.close();
                }
                throw e;
            }
            this.writer = writer;
            this.map = map;
            correctForImage();
        }
        fillOutputFileInformation(path);
        // - note: we need to fill output ports here, even if the file was already opened
        AbstractTiffOperation.fillWritingOutputInformation(this, writer, map);
    }

    // Corrects settings, that can be different for different IFDs or tiles.
    private void correctForImage() {
        writer.setOrRemoveQuality(quality);
    }

    private TiffIFD configure(TiffWriter writer, Matrix<? extends PArray> firstMatrix, boolean singleWriteOnly) {
        TiffIFD ifd = writer.newIFD(tiled);
        if (tiled) {
            ifd.putTileSizes(tileSizeX, tileSizeY);
        } else if (stripSizeY != null) {
            ifd.putOrRemoveStripSize(stripSizeY == 0 ? null : stripSizeY);
        }
        ifd.putCompression(compression);
        ifd.putMatrixInformation(firstMatrix, signedIntegers);
        // - even if resizable, it is not a problem to start from sizes if the 1st matrix

        if (!resizable) {
            if (imageDimX != 0 && imageDimY != 0) {
                ifd.putImageDimensions(imageDimX, imageDimY);
                // - overrides firstMatrix sizes
            } else {
                if (!singleWriteOnly) {
                    throw new IllegalArgumentException("Zero image dimensions " + imageDimX + "x" + imageDimY
                            + " are allowed only in resizable mode or while single writing");
                }
            }
        }
        return ifd;
    }

    private void writeMatrix(Matrix<? extends PArray> matrix) throws IOException {
        correctForImage();
        List<TiffTile> updated = writer.updateMatrix(map, matrix, x, y);
        if (flushASAP) {
            writer.writeCompletedTiles(updated);
        }
    }

    private void closeWriter(boolean fillOutput) throws IOException {
        if (writer != null) {
            logDebug(() -> "Closing " + writer);
            writer.complete(map);
            if (fillOutput) {
                fillWritingOutputInformation(this, writer, map);
            }
            writer.close();
            // - If there were some exception before this moment,
            // writer/map will stay unchanged!
            writer = null;
            map = null;
        }
    }

    private void closeFileOnError() {
        if (writer != null) {
            logDebug(() -> "Closing " + writer + " (ERROR)");
            try {
                writer.close();
            } catch (IOException e) {
                throw new IOError(e);
            } finally {
                writer = null;
                map = null;
                // - not try to write more in a case of exception
            }
        }
    }
}
