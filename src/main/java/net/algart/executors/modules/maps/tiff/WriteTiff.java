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
import net.algart.executors.api.ExecutionVisibleResultsInformation;
import net.algart.executors.api.Port;
import net.algart.executors.api.ReadOnlyExecutionInput;
import net.algart.executors.api.data.SMat;
import net.algart.executors.modules.maps.LongTimeOpeningMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagPredictor;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.multimatrix.MultiMatrix2D;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
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
    private boolean deleteFileOnError = true;
    private boolean bigTiff = false;
    private ByteOrder byteOrder = ByteOrder.NATIVE;
    private TagCompression compression = TagCompression.NONE;
    private boolean preferRGB = false;
    private Double quality = null;
    private boolean prediction = false;
    private boolean signedIntegers = false;
    private String imageDescription = "";
    private boolean resizable = true;
    private int imageDimX = 0;
    private int imageDimY = 0;
    private int x = 0;
    private int y = 0;
    private boolean tiled = true;
    private int tileSizeX = 256;
    private int tileSizeY = 256;
    private Integer stripSizeY = null;
    private boolean flushASAP = true;

    private volatile TiffWriter writer = null;
    private volatile TiffMap map = null;
    // - note: "volatile" does not provide correct protection here! This just reduces possible problems

    public WriteTiff() {
        defaultOutputPortName(OUTPUT_ABSOLUTE_PATH);
        addInputMat(DEFAULT_INPUT_PORT);
        addInputScalar(INPUT_CLOSE_FILE);
        addOutputScalar(OUTPUT_IFD_INDEX);
        addOutputScalar(OUTPUT_NUMBER_OF_IMAGES);
        addOutputScalar(OUTPUT_IMAGE_DIM_X);
        addOutputScalar(OUTPUT_IMAGE_DIM_Y);
        addOutputScalar(OUTPUT_IFD);
        addOutputScalar(OUTPUT_PRETTY_IFD);
        addOutputScalar(OUTPUT_FILE_SIZE);
        addOutputScalar(OUTPUT_STORED_TILES_COUNT);
        addOutputScalar(OUTPUT_STORED_TILES_MEMORY);
        addOutputScalar(OUTPUT_CLOSED);
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

    public boolean isDeleteFileOnError() {
        return deleteFileOnError;
    }

    public WriteTiff setDeleteFileOnError(boolean deleteFileOnError) {
        this.deleteFileOnError = deleteFileOnError;
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

    public boolean isPrediction() {
        return prediction;
    }

    public WriteTiff setPrediction(boolean prediction) {
        this.prediction = prediction;
        return this;
    }

    public boolean isSignedIntegers() {
        return signedIntegers;
    }

    public WriteTiff setSignedIntegers(boolean signedIntegers) {
        this.signedIntegers = signedIntegers;
        return this;
    }

    public String getImageDescription() {
        return imageDescription;
    }

    public WriteTiff setImageDescription(String imageDescription) {
        this.imageDescription = nonNull(imageDescription);
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
        SMat input = getInputMat(true);
        // - the input CAN be skipped, if we just want to close file,
        // but it is REQUIRED while the first call (this will be checked later in openFile method)
        writeTiff(completeFilePath(), input.toMultiMatrix2D());
    }

    public void writeTiff(Path path, MultiMatrix2D multiMatrix) {
        Objects.requireNonNull(path, "Null path");
        final Matrix<? extends PArray> m = multiMatrix == null ? null : multiMatrix.mergeChannels();
        try {
            final boolean needToClose = needToClose(this, openingMode);
            openFile(path, m, needToClose);
            getScalar(OUTPUT_IFD_INDEX).setTo(writer.numberOfIFDs());
            // - numberOfIFDs() BEFORE writing is the index of resulting IFD
            writeMatrix(m, needToClose);
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
            if (deleteFileOnError) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw new IOError(e);
        } catch (RuntimeException e) {
            // - some not-too-good codecs may throw strange exceptions instead of IOException,
            // and we need to close file to allow user to continue work (for example, to delete it)
            closeFileOnError();
            throw e;
        } finally {
            getScalar(OUTPUT_CLOSED).setTo(writer == null);
        }
    }

    @Override
    public void close() {
        super.close();
        closeFileOnError();
    }

    public void openFile(Path path, Matrix<? extends PArray> firstMatrix, boolean needToClose) throws IOException {
        Objects.requireNonNull(path, "Null path");
        logDebug(() -> "Writing " + path);
        if (this.writer == null) {
            if (firstMatrix == null) {
                throw new IllegalArgumentException("The input matrix is not specified, " +
                        "but this matrix is required when the TIFF file is initially opened " +
                        "(although it can be omitted on subsequent calls when the file is already opened)");
            }
            TiffWriter writer = null;
            TiffMap map;
            try {
                writer = new TiffWriter(path, false);
                writer.setBigTiff(bigTiff);
                writer.setLittleEndian(byteOrder.isLittleEndian());
                writer.setPreferRGB(preferRGB);
                writer.create(appendIFDToExistingTiff);
                final boolean dimensionsRequired = !(needToClose && x == 0 && y == 0);
                final TiffIFD ifd = configure(writer, firstMatrix, dimensionsRequired);
                map = writer.newMap(ifd, resizable && dimensionsRequired);
                // - if not dimensionsRequired (because this is single-writing call),
                // we prefer to ignore "resizable" parameter (TIFF file will be more efficient)
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

    @Override
    public ExecutionVisibleResultsInformation visibleResultsInformation() {
        return defaultVisibleResultsInformation(Port.Type.INPUT, DEFAULT_INPUT_PORT);
    }

    // Corrects settings, that can be different for different IFDs or tiles.
    private void correctForImage() {
        writer.setQuality(quality);
        // - in the current version, this is the only settings, which may vary from one IFD to another
    }

    private TiffIFD configure(TiffWriter writer, Matrix<? extends PArray> firstMatrix, boolean dimensionsRequired) {
        TiffIFD ifd = writer.newIFD(tiled);
        if (tiled) {
            ifd.putTileSizes(tileSizeX, tileSizeY);
        } else if (stripSizeY != null) {
            ifd.putOrRemoveStripSize(stripSizeY == 0 ? null : stripSizeY);
        }
        ifd.putCompression(compression);
        ifd.putMatrixInformation(firstMatrix, signedIntegers);
        // - even if resizable, it is not a problem to start from sizes if the 1st matrix
        ifd.putPredictor(prediction && firstMatrix.elementType() != boolean.class ?
                TagPredictor.HORIZONTAL :
                TagPredictor.NONE);

        if (!resizable) {
            if (imageDimX != 0 && imageDimY != 0) {
                ifd.putImageDimensions(imageDimX, imageDimY);
                // - overrides firstMatrix sizes
            } else {
                if (dimensionsRequired) {
                    throw new IllegalArgumentException("You must specify full image sizes " +
                            "(you may omit this only in resizable mode or while single writing at (0,0) position)");
                }
            }
        }
        final String description = this.imageDescription.trim();
        if (!description.isEmpty()) {
            ifd.putImageDescription(description);
        }
        return ifd;
    }

    private void writeMatrix(Matrix<? extends PArray> matrix, boolean needToClose) throws IOException {
        correctForImage();
        if (matrix != null) {
            List<TiffTile> updated = writer.updateMatrix(map, matrix, x, y);
            if (flushASAP && !needToClose) {
                int count = writer.writeCompletedTiles(updated);
                logDebug(() -> "Flushing " + count + " from " + updated.size() + " changed tiles");
            }
        }
    }

    private void closeWriter(boolean fillOutput) throws IOException {
        if (writer != null) {
            int count = writer.complete(map);
            logDebug(() -> "Completing writing " + count + " tiles");
            if (fillOutput) {
                fillWritingOutputInformation(this, writer, map);
            }
            logDebug(() -> "Closing " + writer);
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
