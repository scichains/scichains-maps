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

package net.algart.matrices.io.formats.tiff.bridges.scifio;

import io.scif.FormatException;
import io.scif.codec.Codec;
import io.scif.codec.LZWCodec;
import io.scif.codec.PassthroughCodec;
import io.scif.codec.ZlibCodec;
import io.scif.formats.tiff.PhotoInterp;
import io.scif.formats.tiff.TiffCompression;
import net.algart.matrices.io.formats.tiff.bridges.scifio.codecs.ExtendedJPEG2000Codec;
import net.algart.matrices.io.formats.tiff.bridges.scifio.codecs.ExtendedJPEGCodec;
import org.scijava.Context;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * <p>{@link TiffWriter} tries to write only compressions from this list and only if not {@link #isOnlyForReading()},
 * because writing other compressions like ALT_JPEG is not properly supported.
 * But some of these codecs actually do not support writing and throw an exception while attempt to write.
 *
 * <p>{@link TiffReader} tries to read any compression, but may use special logic for compressions from this list.
 */
enum KnownTiffCompression {
    UNCOMPRESSED(TiffCompression.UNCOMPRESSED, PassthroughCodec::new, null),
    LZW(TiffCompression.LZW, LZWCodec::new, null),
    DEFLATE(TiffCompression.DEFLATE, ZlibCodec::new, null),
    JPEG(TiffCompression.JPEG, null, ExtendedJPEGCodec::new),
    // OLD_JPEG(TiffCompression.OLD_JPEG, null, ExtendedJPEGCodec::new, true),
    // - OLD_JPEG does not work: see https://github.com/scifio/scifio/issues/510
    PACK_BITS(TiffCompression.PACK_BITS, null, null),
    JPEG_2000(TiffCompression.JPEG_2000, null, ExtendedJPEG2000Codec::new),
    JPEG_2000_LOSSY(TiffCompression.JPEG_2000_LOSSY, null, ExtendedJPEG2000Codec::new),
    ALT_JPEG_2000(TiffCompression.ALT_JPEG2000, null, ExtendedJPEG2000Codec::new),
    OLYMPUS_JPEG2000(TiffCompression.OLYMPUS_JPEG2000, null, ExtendedJPEG2000Codec::new),
    //TODO!! - check JPEG 2000 compressions after fixing https://github.com/scifio/scifio/issues/495
    NIKON(TiffCompression.NIKON, null, null);

    private final TiffCompression compression;
    private final Supplier<Codec> noContext;
    private final Supplier<Codec> extended;
    private final boolean onlyForReading;

    KnownTiffCompression(
            TiffCompression compression,
            Supplier<Codec> noContext,
            Supplier<Codec> extended) {
        this(compression, noContext, extended, false);
    }

    KnownTiffCompression(
            TiffCompression compression,
            Supplier<Codec> noContext,
            Supplier<Codec> extended,
            boolean onlyForReading) {
        this.compression = compression;
        this.noContext = noContext;
        this.extended = extended;
        this.onlyForReading = onlyForReading;
    }

    public static boolean isYCbCrForNonJpeg(DetailedIFD ifd) throws FormatException {
        Objects.requireNonNull(ifd, "Null IFD");
        TiffCompression compression = ifd.getCompression();
        return isStandard(compression) && !isJpeg(compression) &&
                ifd.getPhotometricInterpretation() == PhotoInterp.Y_CB_CR;
    }

    public static boolean isStandard(TiffCompression compression) {
        Objects.requireNonNull(compression, "Null compression");
        return compression.getCode() <= 10;
    }

    public static boolean isJpeg(TiffCompression compression) {
        Objects.requireNonNull(compression, "Null compression");
        return compression == TiffCompression.JPEG
                || compression == TiffCompression.OLD_JPEG
                || compression == TiffCompression.ALT_JPEG;
        // - actually only TiffCompression.JPEG is surely supported
    }

    public TiffCompression compression() {
        return compression;
    }

    public Codec noContextCodec() {
        return noContext == null ? null : noContext.get();
    }

    /**
     * Extended codec must either be able to work without context, or inform when it actually requires it.
     *
     * @param context SCIFIO context, may be <tt>null</tt>.
     * @return extended codec.
     */
    public Codec extendedCodec(Context context) {
        if (extended == null) {
            return null;
        } else {
            Codec codec = extended.get();
            if (context != null) {
                codec.setContext(context);
            }
            return codec;
        }
    }

    public boolean isOnlyForReading() {
        return onlyForReading;
    }

    public static KnownTiffCompression valueOfOrNull(TiffCompression compression) {
        Objects.requireNonNull(compression, "Null compression");
        for (KnownTiffCompression value : values()) {
            if (value.compression == compression) {
                return value;
            }
        }
        return null;
    }


}
