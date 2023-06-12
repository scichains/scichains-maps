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

package net.algart.scifio.tiff.tests;

import io.scif.FormatException;
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDList;
import net.algart.scifio.tiff.improvements.ExtendedIFD;
import net.algart.scifio.tiff.improvements.ExtendedTiffParser;
import org.scijava.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffInfo {
    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffInfo.class.getName() + " some_tiff_file.tif");
            return;
        }
        final String fileName = args[0];
        if (fileName.equals("*")) {
            final File[] files = new File(".").listFiles();
            assert files != null;
            System.out.printf("Testing %d files%n", files.length);
            for (File f : files) {
                showTiffInfo(f.toPath());
            }
        } else {
            showTiffInfo(Paths.get(fileName));
        }
    }

    public static void showTiffInfo(Path tiffFile) throws IOException {
        try (Context context = new SCIFIO().getContext()) {
            ExtendedTiffParser parser = new ExtendedTiffParser(context, tiffFile);
            IFDList ifdList = parser.getIFDs();
            final int ifdCount = ifdList.size();
            System.out.printf("%nFile %s: %d IFDs, %s, %s-endian%n",
                    tiffFile,
                    ifdCount,
                    parser.isBigTiff() ? "BIG-TIFF" : "not big-TIFF",
                    parser.getStream().isLittleEndian() ? "little" : "big");
            for (int k = 0; k < ifdCount; k++) {
                final IFD ifd = ifdList.get(k);
                System.out.println(ifdInfo(ifd, k, ifdCount));
            }
            parser.close();
            System.out.println();
        }
    }

    public static String ifdInfo(IFD ifd, int ifdIndex, int ifdCount) {
        Long offset = ifd instanceof ExtendedIFD extendedIFD ? extendedIFD.getOffset() : null;
        try {
            return "IFD #%d/%d%s %s[%dx%d], %s, %s:%n%s%n".formatted(
                    ifdIndex, ifdCount,
                    offset == null ?
                            "" :
                            " (offset %d=0x%X)".formatted(offset, offset),
                    ExtendedTiffParser.javaElementType(ifd.getPixelType()).getSimpleName(),
                    ifd.getImageWidth(), ifd.getImageLength(),
                    ifd.isTiled() ?
                            "%dx%d=%d tiles".formatted(
                                    ifd.getTilesPerRow(), ifd.getTilesPerColumn(),
                                    ifd.getTilesPerRow() * ifd.getTilesPerColumn()) :
                            "%d strips per %d lines (virtual \"tiles\" %dx%d)".formatted(
                                    ifd.getTilesPerColumn(), ifd.getTileLength(),
                                    ifd.getTileWidth(), ifd.getTileLength()),
                    ifd.getPlanarConfiguration() == ExtendedIFD.PLANAR_CONFIG_CONTIGUOUSLY_CHUNKED ? "chunky" : "planar",
                    ifd.toString());
        } catch (FormatException e) {
            return " IFD #%d/%d: parsing error! %s".formatted(ifdIndex, ifdCount, e.getMessage());
        }
    }
}
