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

import io.scif.codec.CodecOptions;
import io.scif.formats.tiff.PhotoInterp;

import java.util.Objects;

public class ExtendedJPEGCodecOptions extends CodecOptions {
    /**
     * Value of TIFF tag PhotometricInterpretation (READ/WRITE).
     */
    private PhotoInterp photometricInterpretation = PhotoInterp.Y_CB_CR;
    /**
     * Value of TIFF tag YCbCrSubSampling (READ).
     */
    private int[] yCbCrSubsampling = {2, 2};

    public ExtendedJPEGCodecOptions(final CodecOptions options) {
        super(options);
        if (options instanceof ExtendedJPEGCodecOptions extended) {
            photometricInterpretation = extended.photometricInterpretation;
            yCbCrSubsampling = extended.yCbCrSubsampling == null ? null : extended.yCbCrSubsampling.clone();
        }
    }

    public PhotoInterp getPhotometricInterpretation() {
        return photometricInterpretation;
    }

    public ExtendedJPEGCodecOptions setPhotometricInterpretation(PhotoInterp photometricInterpretation) {
        this.photometricInterpretation = Objects.requireNonNull(photometricInterpretation,
                "Null photometricInterpretation");
        return this;
    }

    public int[] getYCbCrSubsampling() {
        return yCbCrSubsampling.clone();
    }

    public ExtendedJPEGCodecOptions setYCbCrSubsampling(int[] yCbCrSubsampling) {
        this.yCbCrSubsampling = Objects.requireNonNull(yCbCrSubsampling, "Null yCbCrSubsampling").clone();
        return this;
    }

    public double getQuality() {
        return quality;
    }

    public ExtendedJPEGCodecOptions setQuality(double quality) {
        this.quality = quality;
        return this;
    }
}
