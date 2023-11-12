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
import io.scif.codec.*;
import io.scif.formats.tiff.PhotoInterp;
import io.scif.gui.AWTImageTools;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleInputStream;
import org.scijava.io.location.Location;
import org.scijava.plugin.Parameter;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public class ExtendedJPEGCodec extends AbstractCodec {

    @Parameter
    private CodecService codecService;

    @Override
    public byte[] compress(byte[] data, CodecOptions options) throws FormatException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        if (data.length == 0) {
            return data;
        }

        if (options.channels != 1 && options.channels != 3) {
            throw new FormatException("JPEG compression for " + options.channels + " channels is not supported");
        }
        if (options.bitsPerSample != 8) {
            throw new FormatException("JPEG compression for " + options.bitsPerSample +
                    "-bit data is not supported (only 8-bit samples allowed)");
        }
        final PhotoInterp colorSpace = options instanceof ExtendedJPEGCodecOptions extended ?
                extended.getPhotometricInterpretation() :
                PhotoInterp.Y_CB_CR;

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final BufferedImage image = AWTImageTools.makeImage(data, options.width,
                options.height, options.channels, options.interleaved,
                options.bitsPerSample / 8, false, options.littleEndian, options.signed);

        try {
            JPEGTools.writeJPEG(image, output, colorSpace, options.quality);
        } catch (final IOException e) {
            throw new FormatException("Cannot compress JPEG data", e);
        }
        return output.toByteArray();
    }

    @Override
    public byte[] decompress(final DataHandle<Location> in, CodecOptions options) throws FormatException, IOException {
        //TODO!! optimize AWTImageTools.getPixelBytes
        final long offset = in.offset();
        JPEGTools.ImageInformation imageInformation;
        try (InputStream input = new BufferedInputStream(new DataHandleInputStream<>(in), 8192)) {
            imageInformation = JPEGTools.readJPEG(input);
        } catch (final IOException exc) {
            // probably a lossless JPEG; delegate to LosslessJPEGCodec
            if (codecService == null) {
                throw new IllegalStateException(
                        "Decompressing unusual JPEG (probably lossless) requires specifying non-null SCIFIO context");
            }
            in.seek(offset);
            final Codec codec = codecService.getCodec(LosslessJPEGCodec.class);
            return codec.decompress(in, options);
        }
        if (imageInformation == null) {
            throw new FormatException("Cannot read JPEG image: unknown format");
            // - for example, OLD_JPEG
        }

        if (options == null) {
            options = CodecOptions.getDefaultOptions();
        }

        BufferedImage bi = imageInformation.bufferedImage();
        final byte[][] data = AWTImageTools.getPixelBytes(bi, options.littleEndian);

        if (options instanceof ExtendedJPEGCodecOptions extended) {
            JPEGTools.completeReadingJPEG(
                    imageInformation,
                    data,
                    extended.getPhotometricInterpretation(),
                    extended.getYCbCrSubsampling());
        }

        int bandSize = data[0].length;
        byte[] result = new byte[data.length * bandSize];
        if (data.length == 1) {
            result = data[0];
        } else {
            if (options.interleaved) {
                int next = 0;
                for (int i = 0; i < bandSize; i++) {
                    for (byte[] bytes : data) {
                        result[next++] = bytes[i];
                    }
                }
            } else {
                for (int i = 0; i < data.length; i++) {
                    System.arraycopy(data[i], 0, result, i * bandSize, bandSize);
                }
            }
        }
        return result;
    }
}
