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

import net.algart.executors.api.Executor;
import net.algart.executors.modules.core.common.io.FileOperation;
import net.algart.executors.modules.maps.LongTimeOpeningMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffMap;

import java.io.IOError;
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

    private final Object lock = new Object();

    public AbstractTiffOperation() {
        addFileOperationPorts();
    }

    public static boolean needToClose(Executor executor, LongTimeOpeningMode openingMode) {
        Objects.requireNonNull(executor, "Null executor");
        Objects.requireNonNull(openingMode, "Null openingMode");
        if (openingMode.isCloseAfterExecute()) {
            return true;
        }
        final String s = executor.getInputScalar(INPUT_CLOSE_FILE, true).getValue();
        return Boolean.parseBoolean(s);
    }

    public static void fillReadingOutputInformation(Executor e, TiffReader reader, int ifdIndex) throws IOException {
        Objects.requireNonNull(e, "Null executor");
        Objects.requireNonNull(reader, "Null reader");
        if (e.hasOutputPort(OUTPUT_VALID)) {
            e.getScalar(OUTPUT_VALID).setTo(reader.isValid());
        }
        final List<TiffIFD> ifds = reader.allIFDs();
        if (e.hasOutputPort(OUTPUT_IFD_INDEX)) {
            e.getScalar(OUTPUT_IFD_INDEX).setTo(ifdIndex);
        }
        if (e.hasOutputPort(OUTPUT_NUMBER_OF_IMAGES)) {
            e.getScalar(OUTPUT_NUMBER_OF_IMAGES).setTo(ifds.size());
        }
        if (ifdIndex < ifds.size()) {
            TiffIFD ifd = ifds.get(ifdIndex);
            if (e.hasOutputPort(OUTPUT_IMAGE_DIM_X)) {
                e.getScalar(OUTPUT_IMAGE_DIM_X).setTo(ifd.getImageDimX());
            }
            if (e.hasOutputPort(OUTPUT_IMAGE_DIM_Y)) {
                e.getScalar(OUTPUT_IMAGE_DIM_Y).setTo(ifd.getImageDimY());
            }
            if (e.isOutputNecessary(OUTPUT_IFD)) {
                e.getScalar(OUTPUT_IFD).setTo(ifd.toString(TiffIFD.StringFormat.JSON));
            }
            if (e.isOutputNecessary(OUTPUT_PRETTY_IFD)) {
                e.getScalar(OUTPUT_PRETTY_IFD).setTo(ifd.toString(TiffIFD.StringFormat.DETAILED));
            }
        }
        if (e.hasOutputPort(OUTPUT_FILE_SIZE)) {
            e.getScalar(OUTPUT_FILE_SIZE).setTo(reader.stream().length());
        }
    }

    public static void fillWritingOutputInformation(Executor e, TiffWriter writer, TiffMap map) throws IOException {
        Objects.requireNonNull(e, "Null executor");
        Objects.requireNonNull(writer, "Null writer");
        Objects.requireNonNull(map, "Null map");
        if (e.hasOutputPort(OUTPUT_NUMBER_OF_IMAGES)) {
            e.getScalar(OUTPUT_NUMBER_OF_IMAGES).setTo(writer.numberOfIFDs());
        }
        if (e.hasOutputPort(OUTPUT_IMAGE_DIM_X)) {
            e.getScalar(OUTPUT_IMAGE_DIM_X).setTo(map.dimX());
        }
        if (e.hasOutputPort(OUTPUT_IMAGE_DIM_Y)) {
            e.getScalar(OUTPUT_IMAGE_DIM_Y).setTo(map.dimY());
        }
        if (e.isOutputNecessary(OUTPUT_IFD)) {
            e.getScalar(OUTPUT_IFD).setTo(map.ifd().toString(TiffIFD.StringFormat.JSON));
        }
        if (e.isOutputNecessary(OUTPUT_PRETTY_IFD)) {
            e.getScalar(OUTPUT_PRETTY_IFD).setTo(map.ifd().toString(TiffIFD.StringFormat.DETAILED));
        }
        if (e.hasOutputPort(OUTPUT_FILE_SIZE)) {
            e.getScalar(OUTPUT_FILE_SIZE).setTo(writer.stream().length());
        }
        if (e.isOutputNecessary(OUTPUT_STORED_TILES_COUNT) || e.isOutputNecessary(OUTPUT_STORED_TILES_MEMORY)) {
            // - only if requested: in very large maps, this operation can lead to O(N^2) operations
            final long count = map.tiles().stream().filter(t -> !t.isEmpty()).count();
            e.getScalar(OUTPUT_STORED_TILES_COUNT).setTo(count);
            e.getScalar(OUTPUT_STORED_TILES_MEMORY).setTo(count * map.tileSizeInBytes());
        }
    }

}
