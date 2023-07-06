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
import io.scif.codec.JPEGCodec;
import io.scif.gui.AWTImageTools;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;

public class ExtendedJPEGCodec extends JPEGCodec {
    private boolean jpegInPhotometricRGB = false;

    private double jpegQuality = 1.0;


    public boolean isJpegInPhotometricRGB() {
        return jpegInPhotometricRGB;
    }

    public ExtendedJPEGCodec setJpegInPhotometricRGB(boolean jpegInPhotometricRGB) {
        this.jpegInPhotometricRGB = jpegInPhotometricRGB;
        return this;
    }

    public double getJpegQuality() {
        return jpegQuality;
    }

    public ExtendedJPEGCodec setJpegQuality(double jpegQuality) {
        this.jpegQuality = jpegQuality;
        return this;
    }

    @Override
    public byte[] compress(byte[] data, CodecOptions options) throws FormatException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        if (data.length == 0) {
            return data;
        }

        if (options.bitsPerSample > 8) {
            throw new FormatException("Cannot compress " + options.bitsPerSample + "-bit data in JPEG format " +
                    "(only 8-bit samples allowed)");
        }

        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        final BufferedImage image = AWTImageTools.makeImage(data, options.width,
                options.height, options.channels, options.interleaved,
                options.bitsPerSample / 8, false, options.littleEndian, options.signed);

        try {
            final ImageOutputStream ios = ImageIO.createImageOutputStream(result);
            final ImageWriter jpegWriter = getJPEGWriter();
            jpegWriter.setOutput(ios);

            final ImageWriteParam writeParam = jpegWriter.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionType("JPEG");
            writeParam.setCompressionQuality((float) jpegQuality);
            final ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(image);
            if (jpegInPhotometricRGB) {
                writeParam.setDestinationType(imageTypeSpecifier);
                // - Important! It informs getDefaultImageMetadata to add Adove and SOF markers,
                // that is detected by JPEGImageWriter and leads to correct outCsType = JPEG.JCS_RGB
            }
            final IIOMetadata metadata = jpegWriter.getDefaultImageMetadata(
                    jpegInPhotometricRGB ? null : imageTypeSpecifier,
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

    private static ImageWriter getJPEGWriter() throws IIOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IIOException("Cannot write JPEG");
        }
        return writers.next();
    }
}
