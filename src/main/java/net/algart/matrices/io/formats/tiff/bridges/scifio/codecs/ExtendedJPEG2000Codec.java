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

package net.algart.matrices.io.formats.tiff.bridges.scifio.codecs;

import io.scif.FormatException;
import io.scif.codec.CodecOptions;
import io.scif.codec.JPEG2000Codec;
import io.scif.codec.JPEG2000CodecOptions;
import io.scif.gui.AWTImageTools;
import io.scif.media.imageio.plugins.jpeg2000.J2KImageReadParam;
import io.scif.media.imageioimpl.plugins.jpeg2000.J2KImageReader;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

// This class needs to avoid the bug in SCIFIO: https://github.com/scifio/scifio/issues/495
public class ExtendedJPEG2000Codec extends JPEG2000Codec {

    // Almost exact copy of super.decompress
    // We do not override compress method, hoping for fixing that bug soon
    @Override
    public byte[] decompress(byte[] buf, CodecOptions options) throws FormatException {
        if (options == null || !(options instanceof JPEG2000CodecOptions)) {
            options = JPEG2000CodecOptions.getDefaultOptions(options);
        }
        else {
            options = new JPEG2000CodecOptions(options);
        }

        byte[][] single = null;
        WritableRaster b = null;
        int bpp = options.bitsPerSample / 8;

        try {
            final ByteArrayInputStream bis = new ByteArrayInputStream(buf);
            b = (WritableRaster) readRaster(bis,
                (JPEG2000CodecOptions) options);
            // - instead of:
            // b = (WritableRaster) this.jaiIIOService.readRaster(bis,
            //        (JPEG2000CodecOptions) options);
            single = AWTImageTools.getPixelBytes(b, options.littleEndian);
            bpp = single[0].length / (b.getWidth() * b.getHeight());

            bis.close();
            b = null;
        }
        catch (final IOException e) {
            throw new FormatException("Could not decompress JPEG2000 image. Please " +
                "make sure that jai_imageio.jar is installed.", e);
        }
//        catch (final ServiceException e) {
//            throw new FormatException("Could not decompress JPEG2000 image. Please " +
//                "make sure that jai_imageio.jar is installed.", e);
//        }

        if (single.length == 1) return single[0];
        final byte[] rtn = new byte[single.length * single[0].length];
        if (options.interleaved) {
            int next = 0;
            for (int i = 0; i < single[0].length / bpp; i++) {
                for (int j = 0; j < single.length; j++) {
                    for (int bb = 0; bb < bpp; bb++) {
                        rtn[next++] = single[j][i * bpp + bb];
                    }
                }
            }
        }
        else {
            for (int i = 0; i < single.length; i++) {
                System.arraycopy(single[i], 0, rtn, i * single[0].length,
                    single[i].length);
            }
        }
        single = null;

        return rtn;
    }

    private static Raster readRaster(final InputStream in, final JPEG2000CodecOptions options) throws IOException {
        final J2KImageReader reader = new J2KImageReader(null);
        final MemoryCacheImageInputStream mciis = new MemoryCacheImageInputStream(
                in);
        reader.setInput(mciis, false, true);
        final J2KImageReadParam param = (J2KImageReadParam) reader
                .getDefaultReadParam();
        if (options.resolution != null) {
            param.setResolution(options.resolution.intValue());
        }
        return reader.readRaster(0, param);
    }
}
