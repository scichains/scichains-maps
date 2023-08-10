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

package net.algart.matrices.io.formats.tiff.bridges.scifio.tests.misc;

import io.scif.formats.tiff.IFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.DetailedIFD;
import net.algart.matrices.io.formats.tiff.bridges.scifio.TiffReader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class ReadIFDTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + ReadIFDTest.class.getName() + " tiff_file.tiff ifdIndex");
            return;
        }

        final Path file = Paths.get(args[0]);
        final int ifdIndex = Integer.parseInt(args[1]);

        TiffReader reader = new TiffReader(null, file, false);
        for (int test = 1; test <= 10; test++) {
            System.out.printf("%nTest %d:%n", test);
            long t1 = System.nanoTime();
            long[] offsets = reader.readIFDOffsets();
            long t2 = System.nanoTime();
            System.out.printf("Offsets: %s (%.6f mcs)%n", Arrays.toString(offsets), (t2 - t1) * 1e-3);

            t1 = System.nanoTime();
            long offset = reader.readIFDOffset(ifdIndex);
            t2 = System.nanoTime();
            System.out.printf("Offset #%d: %d (%.6f mcs)%n", ifdIndex, offset, (t2 - t1) * 1e-3);

            t1 = System.nanoTime();
            List<DetailedIFD> ifds = reader.allIFDs();
            t2 = System.nanoTime();
            System.out.printf("Number of IFDs: %d (%.6f mcs)%n", ifds.size(), (t2 - t1) * 1e-3);

            t1 = System.nanoTime();
            DetailedIFD firstIFD = reader.firstIFD();
            t2 = System.nanoTime();
//        IFD firstIFD = new TiffParser(new SCIFIO().getContext(), new FileLocation(file.toFile())).getFirstIFD();
            System.out.printf("First IFD: %s (%.6f mcs)%n", firstIFD.toString(false), (t2 - t1) * 1e-3);

            t1 = System.nanoTime();
            offset = reader.readIFDOffset(ifdIndex);
            DetailedIFD ifd = offset < 0 ? null : reader.readIFDAtOffset(offset);
            t2 = System.nanoTime();
            System.out.printf("IFD #%d: %s (%.6f mcs)%n",
                    ifdIndex, ifd == null ? null : ifd.toString(false), (t2 - t1) * 1e-3);
        }

        reader.close();
    }
}