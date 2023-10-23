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
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffReader;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffFalsifyDimensions {
    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 4) {
            System.out.println("Usage:");
            System.out.println("    " + TiffFalsifyDimensions.class.getName()
                    + " target.tif ifdIndex newWidth newLength");
            System.out.println("where newWidth/newLength are new sizes of this IFD " +
                    "(but you should not try to increase total number of pixels)");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final int newDimX = Integer.parseInt(args[startArgIndex++]);
        final int newDimY = Integer.parseInt(args[startArgIndex]);

        try (TiffReader reader = new TiffReader(targetFile);
             TiffWriter writer = new TiffWriter(targetFile)) {

            writer.startExistingFile();

            System.out.printf("Transforming %s...%n", targetFile);
            final DetailedIFD ifd = reader.readSingleIFD(ifdIndex);
            ifd.setFileOffsetForWriting(ifd.getFileOffsetForReading());
            ifd.putImageDimensions(newDimX, newDimY);
            writer.rewriteIFD(ifd, false);
            // - replacing dimensions
        }
        System.out.println("Done");
    }
}
