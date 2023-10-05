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
import io.scif.formats.tiff.PhotoInterp;
import org.scijava.util.Bytes;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Objects;

public class JPEGTools {
    private static final boolean USE_LEGACY_DECODE_Y_CB_CR = false;
    // - Should be false for correct behaviour and better performance; necessary for debugging needs only.

    private JPEGTools() {
    }

    /**
     * Analog of <tt>ImageIO.read</tt>. Actually can read any formats, not only JPEG.
     */
    public static BufferedImage readJPEG(InputStream in) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(in);
        if (stream == null) {
            throw new IIOException("Cannot decompress JPEG tile");
        }
        ImageReader reader = JPEGTools.getImageReaderOrNull(stream);
        if (reader == null) {
            return null;
        }
        ImageReadParam param = reader.getDefaultReadParam();
        reader.setInput(stream, true, true);
        try {
            return reader.read(0, param);
        } finally {
            reader.dispose();
        }
    }

    /**
     * Analog of <tt>ImageIO.write</tt> for JPEG.
     */
    public static void writeJPEG(
            BufferedImage image,
            OutputStream out,
            PhotoInterp colorSpace,
            double quality) throws IOException {
        Objects.requireNonNull(image, "Null image");
        Objects.requireNonNull(out, "Null output stream");
        Objects.requireNonNull(colorSpace, "Null color space");
        if (colorSpace != PhotoInterp.Y_CB_CR && colorSpace != PhotoInterp.RGB) {
            throw new IllegalArgumentException("Unsupported color space: " + colorSpace);
        }
        final boolean enforceRGB = colorSpace == PhotoInterp.RGB;

        final ImageOutputStream ios = ImageIO.createImageOutputStream(out);
        final ImageWriter jpegWriter = getJPEGWriter();
        jpegWriter.setOutput(ios);

        final ImageWriteParam writeParam = jpegWriter.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionType("JPEG");
        writeParam.setCompressionQuality((float) quality);
        final ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(image);
        if (enforceRGB) {
            writeParam.setDestinationType(imageTypeSpecifier);
            // - Important! It informs getDefaultImageMetadata to add Adobe and SOF markers,
            // that is detected by JPEGImageWriter and leads to correct outCsType = JPEG.JCS_RGB
        }
        final IIOMetadata metadata = jpegWriter.getDefaultImageMetadata(
                enforceRGB ? null : imageTypeSpecifier,
                writeParam);
        // - Important! imageType = null necessary for RGB, in other case setDestinationType will be ignored!

        final IIOImage iioImage = new IIOImage(image, null, metadata);
        // - metadata necessary (with necessary markers)
        try {
            jpegWriter.write(null, iioImage, writeParam);
        } finally {
            jpegWriter.dispose();
        }
    }

    public static void completeReadingJPEG(byte[][] data, int bandLength, PhotoInterp colorSpace, int[] subsampling)
            throws FormatException {
        Objects.requireNonNull(data,"Null data");
        Objects.requireNonNull(colorSpace, "Null color space");
        Objects.requireNonNull(subsampling, "Null subsampling");
        if (bandLength < 0) {
            throw new IllegalArgumentException("Negative band length");
        }
        final boolean correctionNecessary =
                colorSpace == PhotoInterp.Y_CB_CR
                        && subsampling[0] == 1 && subsampling[1] == 1
                        && data.length == 3;
        //TODO!! check also actual color space
        if (correctionNecessary) {
            // Rare case: YCbCr is encoded with non-standard sub-sampling
            // (maybe, as a result of incorrect detecting as RGB);
            // so, there is no sense to optimize this.
            if (USE_LEGACY_DECODE_Y_CB_CR) {
                decodeYCbCrLegacy(data, bandLength);
                return;
            }

            if (data[0].length != bandLength) {
                // - should not occur
                throw new FormatException("Cannot correct unpacked JPEG: number of bytes per sample in JPEG " +
                        "must be 1, but actually we have " +
                        (double) data[0].length / (double) (long) bandLength + " bytes/sample");
            }
            for (int i = 0; i < data[0].length; i++) {
                final int y = data[0][i] & 0xFF;
                int cb = data[1][i] & 0xFF;
                int cr = data[2][i] & 0xFF;

                cb -= 128;
                cr -= 128;

                final double red = (y + 1.402 * cr);
                final double green = (y - 0.34414 * cb - 0.71414 * cr);
                final double blue = (y + 1.772 * cb);

                data[0][i] = (byte) toUnsignedByte(red);
                data[1][i] = (byte) toUnsignedByte(green);
                data[2][i] = (byte) toUnsignedByte(blue);
            }
        }
    }

    private static void decodeYCbCrLegacy(byte[][] buf, int bandLength) {
        final boolean littleEndian = false;
        // - not important for 8-bit values
        final int nBytes = buf[0].length / bandLength;
        final int mask = (int) (Math.pow(2, nBytes * 8) - 1);
        for (int i = 0; i < buf[0].length; i += nBytes) {
            final int y = Bytes.toInt(buf[0], i, nBytes, littleEndian);
            int cb = Bytes.toInt(buf[1], i, nBytes, littleEndian);
            int cr = Bytes.toInt(buf[2], i, nBytes, littleEndian);

            cb = Math.max(0, cb - 128);
            cr = Math.max(0, cr - 128);

            final int red = (int) (y + 1.402 * cr) & mask;
            final int green = (int) (y - 0.34414 * cb - 0.71414 * cr) & mask;
            final int blue = (int) (y + 1.772 * cb) & mask;

            Bytes.unpack(red, buf[0], i, nBytes, littleEndian);
            Bytes.unpack(green, buf[1], i, nBytes, littleEndian);
            Bytes.unpack(blue, buf[2], i, nBytes, littleEndian);
        }
    }

    public static ImageReader getImageReaderOrNull(Object inputStream) throws IIOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(inputStream);
        return findAWTCodec(readers);
    }

    public static ImageWriter getJPEGWriter() throws IIOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter result = findAWTCodec(writers);
        if (result == null) {
            throw new IIOException("Cannot write JPEG: no necessary registered plugin");
        }
        return result;
    }

    private static <T> T findAWTCodec(Iterator<T> iterator) {
        if (!iterator.hasNext()) {
            return null;
        }
        T first = iterator.next();
        if (isProbableAWTClass(first)) {
            return first;
            // - This is maximally typical behaviour, in particularly, used in ImageIO.read/write.
            // But it can be not the desirable behaviour, when we have some additional plugins
            // like TwelveMonkeys ImageIO, which are registered as the first plugin INSTEAD of built-in
            // Java AWT plugin, because, for example, TwelveMonkeys does not guarantee the identical behaviour;
            // in this case, we should try to find the original AWT plugin.
        }
        while (iterator.hasNext()) {
            T other = iterator.next();
            if (isProbableAWTClass(other)) {
                return other;
            }
        }
        return first;
    }

    private static boolean isProbableAWTClass(Object o) {
        return o != null && o.getClass().getName().startsWith("com.sun.");
    }

    private static int toUnsignedByte(double v) {
        return v < 0.0 ? 0 : v > 255.0 ? 255 : (int) Math.round(v);
    }
}
