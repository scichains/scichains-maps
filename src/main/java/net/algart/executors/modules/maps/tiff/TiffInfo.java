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
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import net.algart.executors.api.ReadOnlyExecutionInput;
import net.algart.json.Jsons;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class TiffInfo extends AbstractTiffOperation implements ReadOnlyExecutionInput {
    public static final String OUTPUT_ALL_IFDS = "all_ifds";
    public static final String OUTPUT_PRETTY_ALL_IFDS = "pretty_all_ifds";

    private boolean requireFileExistence = true;
    private boolean requireValidTiff = true;
    private int ifdIndex = 0;

    public TiffInfo() {
        super();
        useVisibleResultParameter();
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
        getScalar(OUTPUT_VALID).setTo(false);
        try {
            if (!Files.isRegularFile(path)) {
                if (requireFileExistence) {
                    throw new FileNotFoundException("File not found: " + path);
                } else {
                    return;
                }
            }
            try (TiffReader reader = new TiffReader(path, requireValidTiff)) {
                fillOutputFileInformation(path);
                fillReadingOutputInformation(this, reader, ifdIndex);
                final List<TiffIFD> ifds = reader.allIFDs();
                if (isOutputNecessary(OUTPUT_ALL_IFDS)) {
                    getScalar(OUTPUT_ALL_IFDS).setTo(Jsons.toPrettyString(allIFDJson(ifds)));
                }
                if (isOutputNecessary(OUTPUT_PRETTY_ALL_IFDS)) {
                    getScalar(OUTPUT_PRETTY_ALL_IFDS).setTo(allIFDPrettyInfo(ifds));
                }
            }
        } catch (IOException | FormatException e) {
            throw new IOError(e);
        }
    }

    public static JsonArray allIFDJson(List<TiffIFD> allIFDs) {
        Objects.requireNonNull(allIFDs, "Null allIFDs");
        final JsonArrayBuilder builder = Json.createArrayBuilder();
        for (TiffIFD ifd : allIFDs) {
            builder.add(Jsons.toJson(ifd.toString(TiffIFD.StringFormat.JSON)));
        }
        return builder.build();
    }

    public static String allIFDPrettyInfo(List<TiffIFD> allIFDs) {
        Objects.requireNonNull(allIFDs, "Null allIFDs");
        StringBuilder sb = new StringBuilder();
        for (int k = 0, n = allIFDs.size(); k < n; k++) {
            if (k > 0) {
                sb.append("%n".formatted());
            }
            sb.append(ifdInfo(allIFDs.get(k), k, n));
        }
        return sb.toString();
    }

    public static String ifdInfo(TiffIFD ifd, int ifdIndex, int ifdCount) {
        Objects.requireNonNull(ifd, "Null IFD");
        return "IFD #%d/%d: %s%n".formatted(
                ifdIndex,
                ifdCount,
                ifd.toString(TiffIFD.StringFormat.DETAILED));
    }
}

