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
import io.scif.gui.AWTImageTools;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleInputStream;
import org.scijava.io.location.Location;
import org.scijava.plugin.Parameter;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
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

        if (options.bitsPerSample != 8) {
            throw new FormatException("Cannot compress " + options.bitsPerSample + "-bit data in JPEG format " +
                    "(only 8-bit samples allowed)");
        }
        final boolean photometricRGB = options instanceof ExtendedJPEGCodecOptions o && o.isPhotometricRGB();
        final double quality = options.quality;

        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        final BufferedImage image = AWTImageTools.makeImage(data, options.width,
                options.height, options.channels, options.interleaved,
                options.bitsPerSample / 8, false, options.littleEndian, options.signed);

        try {
            final ImageOutputStream ios = ImageIO.createImageOutputStream(result);
            final ImageWriter jpegWriter = JPEGTools.getJPEGWriter();
            jpegWriter.setOutput(ios);

            final ImageWriteParam writeParam = jpegWriter.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionType("JPEG");
            writeParam.setCompressionQuality((float) quality);
            final ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(image);
            if (photometricRGB) {
                writeParam.setDestinationType(imageTypeSpecifier);
                // - Important! It informs getDefaultImageMetadata to add Adobe and SOF markers,
                // that is detected by JPEGImageWriter and leads to correct outCsType = JPEG.JCS_RGB
            }
            final IIOMetadata metadata = jpegWriter.getDefaultImageMetadata(
                    photometricRGB ? null : imageTypeSpecifier,
                    writeParam);
            // - Important! imageType = null necessary for RGB, in other case setDestinationType will be ignored!

            final IIOImage iioImage = new IIOImage(image, null, metadata);
            // - metadata necessary (with necessary markers)
            jpegWriter.write(null, iioImage, writeParam);
        } catch (final IOException e) {
            throw new FormatException("Cannot compress JPEG data", e);
        }
        return result.toByteArray();
    }

    @Override
    public byte[] decompress(final DataHandle<Location> in, CodecOptions options) throws FormatException, IOException {
        //TODO!! optimize AWTImageTools.getPixelBytes
        final long offset = in.offset();
        BufferedImage b = null;
        try (InputStream input = new BufferedInputStream(new DataHandleInputStream<>(in), 8192);
             ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            if (stream == null) {
                throw new IIOException("Cannot decompress JPEG tile");
            }
            ImageReader reader = JPEGTools.getJPEGReaderOrNull(stream);
            if (reader != null) {
                ImageReadParam param = reader.getDefaultReadParam();
                reader.setInput(stream, true, true);
                try {
                    b = reader.read(0, param);
                } finally {
                    reader.dispose();
                }
            }
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
        if (b == null) {
            throw new FormatException("Cannot read JPEG image: unknown format");
            // - for example, OLD_JPEG
        }

        if (options == null) {
            options = CodecOptions.getDefaultOptions();
        }

        final byte[][] buf = AWTImageTools.getPixelBytes(b, options.littleEndian);

        if (options instanceof ExtendedJPEGCodecOptions extendedOptions) {
            JPEGTools.decodeYCbCr(b, buf, extendedOptions);
        }

        int bandSize = buf[0].length;
        byte[] result = new byte[buf.length * bandSize];
        if (buf.length == 1) {
            result = buf[0];
        } else {
            if (options.interleaved) {
                int next = 0;
                for (int i = 0; i < bandSize; i++) {
                    for (byte[] bytes : buf) {
                        result[next++] = bytes[i];
                    }
                }
            } else {
                for (int i = 0; i < buf.length; i++) {
                    System.arraycopy(buf[i], 0, result, i * bandSize, bandSize);
                }
            }
        }
        return result;
    }
}
