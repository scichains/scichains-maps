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

import net.algart.executors.api.Executor;
import net.algart.executors.api.data.SScalar;
import net.algart.executors.modules.core.common.io.FileOperation;
import net.algart.executors.modules.maps.LongTimeOpeningMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffWriteMap;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public abstract class AbstractTiffOperation extends FileOperation {
    public static final String INPUT_CLOSE_FILE = "close_file";
    public static final String OUTPUT_VALID = "valid";
    public static final String OUTPUT_IFD_INDEX = "ifd_index";
    public static final String OUTPUT_NUMBER_OF_IMAGES = "number_of_images";
    public static final String OUTPUT_IMAGE_DIM_X = "image_dim_x";
    public static final String OUTPUT_IMAGE_DIM_Y = "image_dim_y";
    public static final String OUTPUT_IFD = "ifd";
    public static final String OUTPUT_PRETTY_IFD = "pretty_ifd";
    public static final String OUTPUT_FILE_SIZE = "file_size";
    public static final String OUTPUT_STORED_TILES_COUNT = "stored_tiles_count";
    public static final String OUTPUT_STORED_TILES_MEMORY = "stored_tiles_memory";
    public static final String OUTPUT_CLOSED = "closed";

    public AbstractTiffOperation() {
        addFileOperationPorts();
    }

    public static boolean needToClose(Executor executor, LongTimeOpeningMode openingMode) {
        Objects.requireNonNull(executor, "Null executor");
        Objects.requireNonNull(openingMode, "Null openingMode");
        if (openingMode.isCloseAfterExecute()) {
            return true;
        }
        final SScalar scalar = executor.getInputScalar(INPUT_CLOSE_FILE, true);
        return scalar.toJavaLikeBoolean(false);
    }

    public static void fillReadingOutputInformation(Executor e, TiffReader reader, int ifdIndex) throws IOException {
        Objects.requireNonNull(e, "Null executor");
        Objects.requireNonNull(reader, "Null reader");
        e.setOutputScalar(OUTPUT_VALID, reader.isValidTiff());
        e.removeOutputData(OUTPUT_IMAGE_DIM_X);
        e.removeOutputData(OUTPUT_IMAGE_DIM_Y);
        e.removeOutputData(OUTPUT_FILE_SIZE);
        // - clearing output ports before possible exception
        e.setOutputScalar(OUTPUT_IFD_INDEX, ifdIndex);
        final List<TiffIFD> ifds = reader.allIFDs();
        e.setOutputScalar(OUTPUT_NUMBER_OF_IMAGES, ifds.size());
        if (ifdIndex < ifds.size()) {
            final TiffIFD ifd = ifds.get(ifdIndex);
            e.setOutputScalar(OUTPUT_IMAGE_DIM_X, ifd.getImageDimX());
            e.setOutputScalar(OUTPUT_IMAGE_DIM_Y, ifd.getImageDimY());
            e.setOutputScalarIfNecessary(OUTPUT_IFD, () -> ifd.toString(TiffIFD.StringFormat.JSON));
            e.setOutputScalarIfNecessary(OUTPUT_PRETTY_IFD, () -> ifd.toString(TiffIFD.StringFormat.DETAILED));
        }
        e.setOutputScalar(OUTPUT_FILE_SIZE, reader.fileLength());
    }

    public static void fillWritingOutputInformation(Executor e, TiffWriteMap map) throws IOException {
        @SuppressWarnings("resource") final TiffWriter writer = map.owner();
        Objects.requireNonNull(e, "Null executor");
        Objects.requireNonNull(map, "Null map");
        e.setOutputScalar(OUTPUT_NUMBER_OF_IMAGES, writer.numberOfIFDs());
        e.setOutputScalar(OUTPUT_IMAGE_DIM_X, map.dimX());
        e.setOutputScalar(OUTPUT_IMAGE_DIM_Y, map.dimY());
        e.setOutputScalarIfNecessary(OUTPUT_IFD, () -> map.ifd().toString(TiffIFD.StringFormat.JSON));
        e.setOutputScalarIfNecessary(OUTPUT_PRETTY_IFD, () -> map.ifd().toString(TiffIFD.StringFormat.DETAILED));
        e.setOutputScalar(OUTPUT_FILE_SIZE, writer.fileLength());
        if (e.isOutputNecessary(OUTPUT_STORED_TILES_COUNT) || e.isOutputNecessary(OUTPUT_STORED_TILES_MEMORY)) {
            // - only if requested: in very large maps, this operation can lead to O(N^2) operations
            final long count = map.tiles().stream().filter(t -> !t.isEmpty()).count();
            e.getScalar(OUTPUT_STORED_TILES_COUNT).setTo(count);
            e.getScalar(OUTPUT_STORED_TILES_MEMORY).setTo(count * map.tileSizeInBytes());
        }
    }
}
