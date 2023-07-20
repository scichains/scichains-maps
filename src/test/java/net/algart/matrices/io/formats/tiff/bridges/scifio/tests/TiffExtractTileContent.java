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
import io.scif.SCIFIO;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffParser;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffTile;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffTileIndex;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffExtractTileContent {
    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 5) {
            System.out.println("Usage:");
            System.out.println("    " + TiffExtractTileContent.class.getName() +
                    " some_tiff_file result.jpg/png/dat ifdIndex tileCol tileRow");
            return;
        }
        final Path tiffFile = Paths.get(args[startArgIndex++]);
        final Path resultFile = Paths.get(args[startArgIndex++]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final int col = Integer.parseInt(args[startArgIndex++]);
        final int row = Integer.parseInt(args[startArgIndex]);

        TiffInfo.showTiffInfo(tiffFile);

        final SCIFIO scifio = new SCIFIO();
        try (final Context context = scifio.getContext()) {
            final TiffParser parser = TiffParser.getInstance(context, tiffFile);
            System.out.printf("Opening %s by %s...%n", tiffFile, parser);
            final DetailedIFD ifd = parser.ifd(ifdIndex);
            System.out.printf("IFD #%d: %s%n", ifdIndex, ifd);
            TiffTileIndex tileIndex = new TiffTileIndex(ifd, col, row);
            byte[] bytes = parser.readEncodedTile(tileIndex).getData();
            System.out.printf("Saving tile %s in %s...%n", tileIndex, resultFile);
            Files.write(resultFile, bytes);
            TiffTile tile = null;
            try {
                tile = parser.readTile(tileIndex);
                System.out.printf("Decoding the same (for verification): %s%n", tile);
            } catch (FormatException | IOException e) {
                System.err.printf("Cannot decode tile: %s%n", e);
            }
        }
        System.out.println("Done");
    }
}
