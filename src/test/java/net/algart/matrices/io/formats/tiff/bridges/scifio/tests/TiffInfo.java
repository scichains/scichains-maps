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

package net.algart.matrices.io.formats.tiff.bridges.scifio.tests;

import io.scif.FormatException;
import io.scif.formats.tiff.IFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffInfo {
    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffInfo.class.getName() + " some_tiff_file.tif [firstIFDIndex lastIFDIndex]");
            return;
        }
        final String fileName = args[0];
        final int firstIFDIndex = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        final int lastIFDIndex = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;
        if (fileName.equals(".")) {
            final File[] files = new File(".").listFiles(TiffInfo::isPossiblyTIFF);
            assert files != null;
            System.out.printf("Testing %d files%n", files.length);
            for (File f : files) {
                showTiffInfo(f.toPath(), firstIFDIndex, lastIFDIndex);
            }
        } else {
            showTiffInfo(Paths.get(fileName), firstIFDIndex, lastIFDIndex);
        }
    }

    public static void showTiffInfo(Path tiffFile) throws IOException {
        showTiffInfo(tiffFile, 0, Integer.MAX_VALUE);
    }

    public static void showTiffInfo(Path tiffFile, int firstIFDIndex, int lastIFDIndex) throws IOException {
        try (TiffReader reader = new TiffReader(tiffFile, false)) {
            if (!reader.isValid()) {
                System.out.printf("%nFile %s: not TIFF%n", tiffFile);
            } else {
                reader.setRequireValidTiff(true);
                var ifdList = reader.allIFD();
                final int ifdCount = ifdList.size();
                System.out.printf("%nFile %s: %d IFDs, %s, %s-endian%n",
                        tiffFile,
                        ifdCount,
                        reader.isBigTiff() ? "BIG-TIFF" : "not big-TIFF",
                        reader.getStream().isLittleEndian() ? "little" : "big");
                for (int k = 0; k < ifdCount; k++) {
                    if (k >= firstIFDIndex && k <= lastIFDIndex) {
                        final var ifd = ifdList.get(k);
                        System.out.println(ifdInfo(ifd, k, ifdCount));
                        if (!(ifd.containsKey(IFD.STRIP_BYTE_COUNTS) || ifd.containsKey(IFD.TILE_BYTE_COUNTS))) {
                            throw new IOException("Invalid IFD: does not contain StripByteCounts/TileByteCounts tag");
                        }
                        if (!(ifd.containsKey(IFD.STRIP_OFFSETS) || ifd.containsKey(IFD.TILE_OFFSETS))) {
                            throw new IOException("Invalid IFD: does not contain StripOffsets/TileOffsets tag");
                        }
                    }
                }
            }
            System.out.println();
        }
    }

    public static String ifdInfo(DetailedIFD ifd, int ifdIndex, int ifdCount) {
        final Long offset = ifd != null ? ifd.getOffset() : null;
        return "IFD #%d/%d: %s%n".formatted(ifdIndex, ifdCount, ifd);
    }

    public static String extension(String fileName, String defaultExtension) {
        int p = fileName.lastIndexOf('.');
        if (p == -1) {
            return defaultExtension;
        }
        return fileName.substring(p + 1);
    }

    private static boolean isPossiblyTIFF(File file) {
        if (!file.isFile()) {
            return false;
        }
        final String e = extension(file.getName().toLowerCase(), "txt");
        return !e.equals("txt");
    }

}
