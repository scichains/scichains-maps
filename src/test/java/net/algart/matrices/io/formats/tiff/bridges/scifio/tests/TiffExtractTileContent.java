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
import net.algart.matrices.io.formats.tiff.bridges.scifio.*;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffMap;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTile;
import net.algart.matrices.io.formats.tiff.bridges.scifio.tiles.TiffTileIndex;
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
                    " some_tiff_file result.jpg/png/dat ifdIndex tileCol tileRow [separatedPlaneIndex]");
            System.out.println("Note: you should choose file extension, corresponding to IFD compression format.");
            return;
        }
        final Path tiffFile = Paths.get(args[startArgIndex++]);
        final Path resultFile = Paths.get(args[startArgIndex++]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final int col = Integer.parseInt(args[startArgIndex++]);
        final int row = Integer.parseInt(args[startArgIndex++]);
        final int separatedPlaneIndex = startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 0;

        new TiffInfo().showTiffInfo(tiffFile);

        final SCIFIO scifio = new SCIFIO();
        try (final Context context = scifio.getContext()) {
            final TiffReader reader = new TiffReader(context, tiffFile);
            System.out.printf("Opening %s by %s...%n", tiffFile, reader);
            final DetailedIFD ifd = reader.ifd(ifdIndex);
            System.out.printf("IFD #%d: %s%n", ifdIndex, ifd);
            final TiffMap map = new TiffMap(ifd);
            final TiffTileIndex tileIndex = map.multiplaneIndex(separatedPlaneIndex, col, row);
            TiffTile tile = reader.readEncodedTile(tileIndex);
            reader.prepareEncodedTileForDecoding(tile);
            System.out.printf("Loaded tile:%n    %s%n", tile);
            if (!tile.isEmpty()) {
                System.out.printf("    Compression format: %s%n", ifd.getCompression().getCodecName());
                byte[] bytes = tile.getData();
                System.out.printf("Tile saved in %s%n", resultFile);
                Files.write(resultFile, bytes);
                try {
                    tile = reader.readTile(tileIndex);
                    System.out.printf("Decoding the same (for verification): %s%n", tile);
                } catch (FormatException | IOException e) {
                    System.err.printf("Cannot decode tile: %s%n", e);
                }
            }
        }
        System.out.println("Done");
    }
}
