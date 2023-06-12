/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2019 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.scifio.tiff;

import io.scif.FormatException;
import io.scif.codec.CodecOptions;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.PhotoInterp;
import io.scif.formats.tiff.TiffCompression;
import io.scif.formats.tiff.TiffSaver;
import net.algart.scifio.tiff.improvements.ExtendedTiffSaver;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;

import java.io.IOException;
import java.nio.file.Path;

public final class SequentialTiffWriter {
    private final TiffSaver saver;
    private final DataHandle<Location> out;

    private volatile int imageWidth = -1;
    private volatile int imageHeight = -1;
    private volatile boolean tiling = false;
    private volatile int tileWidth = -1;
    private volatile int tileHeight = -1;
    private volatile boolean interleaved = true;
    private volatile TiffCompression compression = null;
    private volatile PhotoInterp photometricInterpretation = null;
    private volatile String software;

    public SequentialTiffWriter(Context context, Path tiffFile) throws IOException {
        this.saver = new ExtendedTiffSaver(context, tiffFile);
        this.out = this.saver.getStream();
        this.saver.setWritingSequentially(true);
    }

    public TiffSaver getSaver() {
        return saver;
    }

    public boolean isBigTiff() {
        return saver.isBigTiff();
    }

    public SequentialTiffWriter setBigTiff(boolean bigTiff) {
        saver.setBigTiff(bigTiff);
        return this;
    }

    public boolean isLittleEndian() {
        return saver.isLittleEndian();
    }

    public SequentialTiffWriter setLittleEndian(boolean littleEndian) {
        saver.setLittleEndian(littleEndian);
        return this;
    }

    public int getImageWidth() {
        if (imageWidth < 0) {
            throw new IllegalStateException("Image sizes not set");
        }
        return imageWidth;
    }

    public int getImageHeight() {
        if (imageHeight < 0) {
            throw new IllegalStateException("Image sizes not set");
        }
        return imageHeight;
    }

    public SequentialTiffWriter setImageSizes(int imageWidth, int imageHeight) {
        if (imageWidth < 0) {
            throw new IllegalArgumentException("Negative imageWidth");
        }
        if (imageHeight < 0) {
            throw new IllegalArgumentException("Negative imageHeight");
        }
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        return this;
    }

    public boolean isTiling() {
        return tiling;
    }

    public SequentialTiffWriter setTiling(boolean tiling) {
        this.tiling = tiling;
        return this;
    }

    public int getTileWidth() {
        if (tileWidth < 0) {
            throw new IllegalStateException("Tile sizes not set");
        }
        return tileWidth;
    }

    public int getTileHeight() {
        if (tileHeight < 0) {
            throw new IllegalStateException("Tile sizes not set");
        }
        return tileHeight;
    }

    public SequentialTiffWriter setTileSizes(int tileWidth, int tileHeight) {
        if (tileWidth < 0) {
            throw new IllegalArgumentException("Negative tile width");
        }
        if (tileHeight < 0) {
            throw new IllegalArgumentException("Negative tile height");
        }
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        return this;
    }

    public boolean isInterleaved() {
        return interleaved;
    }

    public SequentialTiffWriter setInterleaved(boolean interleaved) {
        this.interleaved = interleaved;
        return this;
    }

    public TiffCompression getCompression() {
        return compression;
    }

    public SequentialTiffWriter setCompression(TiffCompression compression) {
        if (compression == null) {
            throw new NullPointerException("Null compression");
        }
        this.compression = compression;
        return this;
    }

    public PhotoInterp getPhotometricInterpretation() {
        return photometricInterpretation;
    }

    public SequentialTiffWriter setPhotometricInterpretation(PhotoInterp photometricInterpretation) {
        if (photometricInterpretation == null) {
            throw new NullPointerException("Null photometric interpretation");
        }
        this.photometricInterpretation = photometricInterpretation;
        return this;
    }

    public String getSoftware() {
        return software;
    }

    public SequentialTiffWriter setSoftware(String software) {
        this.software = software;
        return this;
    }

    public SequentialTiffWriter setCodecOptions(CodecOptions options) {
        options = new CodecOptions(options);
        options.littleEndian = saver.isLittleEndian();
        saver.setCodecOptions(options);
        return this;
    }

    public SequentialTiffWriter open() throws IOException {
        saver.writeHeader();
        return this;
    }

    //TODO!! check that it is not opened
    public void writeSeveralTilesOrStrips(
        final byte[] data, final IFD ifd,
        final int pixelType, int bandCount,
        final int lefTopX, final int leftTopY,
        final int width, final int height,
        final boolean lastTileOrStripInImage,
        final boolean lastImageInTiff) throws FormatException, IOException
    {
        if (compression == null) {
            throw new IllegalStateException("Compression not set");
        }
        ifd.putIFDValue(IFD.IMAGE_WIDTH, (long) getImageWidth());
        ifd.putIFDValue(IFD.IMAGE_LENGTH, (long) getImageHeight());
        ifd.remove(IFD.STRIP_OFFSETS);
        ifd.remove(IFD.STRIP_BYTE_COUNTS);
        ifd.remove(IFD.TILE_OFFSETS);
        ifd.remove(IFD.TILE_BYTE_COUNTS);
        // - enforces TiffSaver to recalculate these fields
        if (tiling) {
            ifd.putIFDValue(IFD.TILE_WIDTH, (long) getTileWidth());
            ifd.putIFDValue(IFD.TILE_LENGTH, (long) getTileHeight());
        } else {
            ifd.remove(IFD.TILE_WIDTH);
            ifd.remove(IFD.TILE_LENGTH);
        }
        ifd.putIFDValue(IFD.PLANAR_CONFIGURATION, interleaved ? 1 : 2);
        ifd.putIFDValue(IFD.COMPRESSION, compression.getCode());
        ifd.putIFDValue(IFD.LITTLE_ENDIAN, isLittleEndian());
        if (photometricInterpretation != null) {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, photometricInterpretation.getCode());
        }
        if (software != null) {
            ifd.putIFDValue(IFD.SOFTWARE, software);
        }
        final long fp = out.offset();
        final PhotoInterp requestedPhotoInterp = ifd.containsKey(IFD.PHOTOMETRIC_INTERPRETATION) ?
            ifd.getPhotometricInterpretation() :
            null;
        saver.writeImage(
                data, ifd,
                -1, pixelType, lefTopX, leftTopY, width, height, lastImageInTiff, bandCount,
                false);
        // - copyDirectly = true is a BUG for PLANAR_CONFIG_SEPARATE
        // - planeIndex = -1 is not used in Writing-Sequentially mode
        if (lastTileOrStripInImage
            && bandCount > 1
            && requestedPhotoInterp == PhotoInterp.Y_CB_CR
            && ifd.getPhotometricInterpretation() == PhotoInterp.RGB)
        {
            switch (ifd.getCompression()) {
                case JPEG:
                case OLD_JPEG: {
                    out.seek(fp);
                    ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, requestedPhotoInterp.getCode());
                    saver.writeIFD(ifd, lastImageInTiff ? 0 : out.length());
                    // - I don't know why, but we need to replace RGB photometric, automatically
                    // set by TiffSaver, with YCbCr, in other case this image is shown incorrectly.
                    // We must do this here, not before writeImage: writeImage automatically sets it to RGB.
                    break;
                }
            }
        }
        if (requestedPhotoInterp != null) {
            ifd.putIFDValue(IFD.PHOTOMETRIC_INTERPRETATION, requestedPhotoInterp.getCode());
            // - restoring photometric, overwritten by TiffSaver
        }
        out.seek(lastTileOrStripInImage ? out.length() : fp);
        // - this stupid SCIFIO class requires this little help to work correctly
    }

    public void close() throws IOException {
        out.close();
    }

}
