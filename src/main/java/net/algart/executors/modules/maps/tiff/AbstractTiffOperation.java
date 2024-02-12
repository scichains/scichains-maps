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

    public static void fillReadingOutputInformation(Executor executor, TiffReader reader, int ifdIndex) {
        Objects.requireNonNull(executor, "Null executor");
        Objects.requireNonNull(reader, "Null reader");
        try {
            if (executor.hasOutputPort(OUTPUT_VALID)) {
                executor.getScalar(OUTPUT_VALID).setTo(reader.isValid());
            }
            final List<TiffIFD> ifds = reader.allIFDs();
            if (executor.hasOutputPort(OUTPUT_IFD_INDEX)) {
                executor.getScalar(OUTPUT_IFD_INDEX).setTo(ifdIndex);
            }
            if (executor.hasOutputPort(OUTPUT_NUMBER_OF_IMAGES)) {
                executor.getScalar(OUTPUT_NUMBER_OF_IMAGES).setTo(ifds.size());
            }
            if (ifdIndex < ifds.size()) {
                TiffIFD ifd = ifds.get(ifdIndex);
                if (executor.hasOutputPort(OUTPUT_IMAGE_DIM_X)) {
                    executor.getScalar(OUTPUT_IMAGE_DIM_X).setTo(ifd.getImageDimX());
                }
                if (executor.hasOutputPort(OUTPUT_IMAGE_DIM_Y)) {
                    executor.getScalar(OUTPUT_IMAGE_DIM_Y).setTo(ifd.getImageDimY());
                }
                if (executor.isOutputNecessary(OUTPUT_IFD)) {
                    executor.getScalar(OUTPUT_IFD).setTo(ifd.toString(TiffIFD.StringFormat.JSON));
                }
                if (executor.isOutputNecessary(OUTPUT_PRETTY_IFD)) {
                    executor.getScalar(OUTPUT_PRETTY_IFD).setTo(ifd.toString(TiffIFD.StringFormat.DETAILED));
                }
            }
            if (executor.hasOutputPort(OUTPUT_FILE_SIZE)) {
                executor.getScalar(OUTPUT_FILE_SIZE).setTo(reader.getStream().length());
            }
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static void fillWritingOutputInformation(Executor executor, TiffWriter writer, TiffMap map) {
        Objects.requireNonNull(executor, "Null executor");
        Objects.requireNonNull(writer, "Null writer");
        Objects.requireNonNull(map, "Null map");
        if (executor.hasOutputPort(OUTPUT_NUMBER_OF_IMAGES)) {
            executor.getScalar(OUTPUT_NUMBER_OF_IMAGES).setTo(writer.numberOfIFDs());
        }
        if (executor.hasOutputPort(OUTPUT_IMAGE_DIM_X)) {
            executor.getScalar(OUTPUT_IMAGE_DIM_X).setTo(map.dimX());
        }
        if (executor.hasOutputPort(OUTPUT_IMAGE_DIM_Y)) {
            executor.getScalar(OUTPUT_IMAGE_DIM_Y).setTo(map.dimY());
        }
        if (executor.isOutputNecessary(OUTPUT_IFD)) {
            executor.getScalar(OUTPUT_IFD).setTo(map.ifd().toString(TiffIFD.StringFormat.JSON));
        }
        if (executor.isOutputNecessary(OUTPUT_PRETTY_IFD)) {
            executor.getScalar(OUTPUT_PRETTY_IFD).setTo(map.ifd().toString(TiffIFD.StringFormat.DETAILED));
        }
        try {
            if (executor.hasOutputPort(OUTPUT_FILE_SIZE)) {
                executor.getScalar(OUTPUT_FILE_SIZE).setTo(writer.getStream().length());
            }
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

}
