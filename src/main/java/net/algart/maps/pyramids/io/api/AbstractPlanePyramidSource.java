/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.maps.pyramids.io.api;

import net.algart.arrays.Arrays;
import net.algart.arrays.*;
import net.algart.maps.pyramids.io.api.sources.RotatingPlanePyramidSource;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.math.Range;
import net.algart.math.functions.LinearFunc;

import java.awt.*;
import java.nio.channels.NotYetConnectedException;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractPlanePyramidSource implements PlanePyramidSource {
    private static final int MAX_NON_TILED_READING_DIM = Math.max(16, Arrays.SystemSettings.getIntProperty(
            "net.algart.maps.pyramids.io.maxNonTiledReadingDim", 4096));
    // 64 MB for packed int ARGB
    private static final int READING_TILE_DIM = (int) Math.min(16384, Arrays.SystemSettings.getLongProperty(
            "net.algart.maps.pyramids.io.readingTile", 2 * DEFAULT_TILE_DIM));
    private static final long TILE_CACHING_MEMORY = Math.max(16, Arrays.SystemSettings.getLongProperty(
            "net.algart.maps.pyramids.io.tileCachingMemory", 67108864));
    // 64 MB (+1 possible additional tile)

    public enum TileDirection {
        RIGHT_DOWN() {
            @Override
            IRectangularArea findTile(long tileDim, long dimX, long dimY, long x, long y) {
                assert dimX > 0 && dimY > 0;
                assert tileDim > 0;
                assert x >= 0 && y >= 0 && x < dimX && y < dimY;
                long minX = x - x % tileDim;
                long minY = y - y % tileDim;
                long maxX = Math.min(minX + tileDim, dimX) - 1;
                long maxY = Math.min(minY + tileDim, dimY) - 1;
                return IRectangularArea.of(IPoint.of(minX, minY), IPoint.of(maxX, maxY));
            }
        },
        LEFT_DOWN() {
            @Override
            IRectangularArea findTile(long tileDim, long dimX, long dimY, long x, long y) {
                assert dimX > 0 && dimY > 0;
                assert tileDim > 0;
                assert x >= 0 && y >= 0 && x < dimX && y < dimY;
                x = dimX - 1 - x;
                long minX = x - x % tileDim;
                long minY = y - y % tileDim;
                long maxX = Math.min(minX + tileDim, dimX) - 1;
                long maxY = Math.min(minY + tileDim, dimY) - 1;
                return IRectangularArea.of(
                        IPoint.of(dimX - 1 - maxX, minY),
                        IPoint.of(dimX - 1 - minX, maxY));
            }
        },
        RIGHT_UP() {
            @Override
            IRectangularArea findTile(long tileDim, long dimX, long dimY, long x, long y) {
                assert dimX > 0 && dimY > 0;
                assert tileDim > 0;
                assert x >= 0 && y >= 0 && x < dimX && y < dimY;
                y = dimY - 1 - y;
                long minX = x - x % tileDim;
                long minY = y - y % tileDim;
                long maxX = Math.min(minX + tileDim, dimX) - 1;
                long maxY = Math.min(minY + tileDim, dimY) - 1;
                return IRectangularArea.of(
                        IPoint.of(minX, dimY - 1 - maxY),
                        IPoint.of(maxX, dimY - 1 - minY));
            }
        },
        LEFT_UP() {
            @Override
            IRectangularArea findTile(long tileDim, long dimX, long dimY, long x, long y) {
                assert dimX > 0 && dimY > 0;
                assert tileDim > 0;
                assert x >= 0 && y >= 0 && x < dimX && y < dimY;
                x = dimX - 1 - x;
                y = dimY - 1 - y;
                long minX = x - x % tileDim;
                long minY = y - y % tileDim;
                long maxX = Math.min(minX + tileDim, dimX) - 1;
                long maxY = Math.min(minY + tileDim, dimY) - 1;
                return IRectangularArea.of(
                        IPoint.of(dimX - 1 - maxX, dimY - 1 - maxY),
                        IPoint.of(dimX - 1 - minX, dimY - 1 - minY));
            }
        };

        abstract IRectangularArea findTile(long tileDim, long dimX, long dimY, long x, long y);
    }

    public enum LabelPosition {
        LEFT_OF_THE_MAP,
        RIGHT_OF_THE_MAP
    }

    private static final System.Logger LOG = System.getLogger(AbstractPlanePyramidSource.class.getName());

    private MemoryModel memoryModel = Arrays.SMM;
    private boolean skipCoarseData = false;
    private double skippingFiller = 0.0;

    private TileDirection tileCacheDirection = null;
    private volatile long tileCachingMemory = TILE_CACHING_MEMORY;

    private volatile RotatingPlanePyramidSource.RotationMode labelRotation =
            RotatingPlanePyramidSource.RotationMode.NONE;
    private volatile Color labelRotationBackground = Color.GRAY;

    private final AtomicReference<TileCache> tileCacheContainer = new AtomicReference<TileCache>();
    // - here must be a reference, not a field: this object is usually cloned, and corrections in a clone
    // do not affect the original; but this reference is shared with all clones

    private final SpeedInfo speedInfo = new SpeedInfo();

    protected AbstractPlanePyramidSource() {
    }

    public final MemoryModel getMemoryModel() {
        return memoryModel;
    }

    public final void setMemoryModel(MemoryModel memoryModel) {
        this.memoryModel = Objects.requireNonNull(memoryModel, "Null memoryModel");
    }

    /**
     * Enforces some plane pyramid sources to skip (usually to stay zero) coarse data, which should not
     * be processed by image processing algorithms, like {@link SpecialImageKind#WHOLE_SLIDE}.
     *
     * @return the flag; <code>false</code> by default.
     */
    public final boolean isSkipCoarseData() {
        return skipCoarseData;
    }

    public final void setSkipCoarseData(boolean skipCoarseData) {
        this.skipCoarseData = skipCoarseData;
    }

    public final double getSkippingFiller() {
        return skippingFiller;
    }

    public final void setSkippingFiller(double skippingFiller) {
        this.skippingFiller = skippingFiller;
    }


    public abstract int numberOfResolutions();

    public int compression() {
        return PlanePyramidTools.defaultCompression(this);
    }

    public abstract int bandCount();

    public boolean isResolutionLevelAvailable(int resolutionLevel) {
        return true;
    }

    public boolean[] getResolutionLevelsAvailability() {
        boolean[] result = new boolean[numberOfResolutions()];
        for (int k = 0; k < result.length; k++) {
            result[k] = isResolutionLevelAvailable(k);
        }
        return result;
    }

    public abstract long[] dimensions(int resolutionLevel) throws NoSuchElementException;

    public abstract long dim(int resolutionLevel, int index);

    public boolean isElementTypeSupported() {
        return false;
    }

    public Class<?> elementType() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("elementType() method is not supported by "
                + super.getClass()); // avoiding IDEA bug
    }

    @Override
    public List<List<List<IPoint>>> zeroLevelActualAreaBoundaries() {
        final List<IRectangularArea> rectangles = zeroLevelActualRectangles();
        if (rectangles == null) {
            return null;
        }
        final List<List<List<IPoint>>> result = new ArrayList<List<List<IPoint>>>();
        for (IRectangularArea rectangle : rectangles) {
            final List<IPoint> vertices = new ArrayList<IPoint>();
            vertices.add(rectangle.min());
            vertices.add(IPoint.of(rectangle.max(0), rectangle.min(1)));
            vertices.add(rectangle.max());
            vertices.add(IPoint.of(rectangle.min(0), rectangle.max(1)));
            result.add(Collections.singletonList(vertices));
        }
        return result;
    }

    // Note: this implementation cannot read data outside the full matrix
    public Matrix<? extends PArray> readSubMatrix(
            int resolutionLevel, long fromX, long fromY, long toX, long toY)
            throws NoSuchElementException, NotYetConnectedException {
        final int bandCount = bandCount();
        final long[] dimensions = dimensions(resolutionLevel);
        checkSubMatrixRanges(dimensions, fromX, fromY, toX, toY, false);
        final long totalElements = Arrays.longMul(bandCount, toX - fromX, toY - fromY);
        assert totalElements != Long.MIN_VALUE; // because of the check above
        if (fromX == toX || fromY == toY
                || (!isTileCachingEnabled() && Math.max(toX - fromX, toY - fromY) <= MAX_NON_TILED_READING_DIM)) {
            return readSubMatrixViaTileCache(resolutionLevel, fromX, fromY, toX, toY, null);
        }
        Matrix<? extends UpdatablePArray> result = null;
        long readyElements = 0;
        TileDirection direction = isTileCachingEnabled() ? getTileCacheDirection() : TileDirection.RIGHT_DOWN;
        final long dimX = dimensions[1];
        final long dimY = dimensions[2];
        final int readingTileDim = readingTileDim();
        IRectangularArea leftTile; // - the left tile in each row
        for (long y = fromY; y < toY; y = leftTile.max(1) + 1) {
            leftTile = direction.findTile(readingTileDim, dimX, dimY, fromX, y);
            IRectangularArea tile;
            for (long x = fromX; x < toX; x = tile.max(0) + 1) {
                tile = direction.findTile(readingTileDim, dimX, dimY, x, y);
                final long tileFromX = Math.max(tile.min(0), fromX);
                final long tileFromY = Math.max(tile.min(1), fromY);
                final long tileToX = Math.min(tile.max(0) + 1, toX);
                final long tileToY = Math.min(tile.max(1) + 1, toY);
                assert tileFromX <= tileToX;
                assert tileFromY <= tileToY;
                final Matrix<? extends PArray> m = readSubMatrixViaTileCache(
                        resolutionLevel,
                        tileFromX, tileFromY, tileToX, tileToY,
                        tile);
                if (fromX == tileFromX && fromY == tileFromY && toX == tileToX && toY == tileToY) {
                    // it is the only tile which should be loaded: we already have the final result
                    assert result == null : "Unexpected non-null result = " + result + " for tile "
                            + tileFromX + ".." + tileToX + "x" + tileFromY + ".." + tileToY;
                    LOG.log(System.Logger.Level.TRACE, () -> AbstractPlanePyramidSource.class.getSimpleName()
                            + " quickly returned result: " + m);
                    return m;
                }
                if (result == null) {
                    final MemoryModel mm = Arrays.sizeOf(m.elementType(), totalElements) <=
                            Arrays.SystemSettings.maxTempJavaMemory() ?
                            Arrays.SMM :
                            memoryModel;
                    result = mm.newMatrix(UpdatablePArray.class,
                            m.elementType(), bandCount, toX - fromX, toY - fromY);
                    if (!SimpleMemoryModel.isSimpleArray(result.array())) {
                        result = result.tile(bandCount, DEFAULT_TILE_DIM, DEFAULT_TILE_DIM);
                    }
                    LOG.log(System.Logger.Level.TRACE, AbstractPlanePyramidSource.class.getSimpleName()
                            + " created result " + result);
                }
                final Matrix<? extends UpdatablePArray> subMatrix = result.subMatrix(
                        0, tileFromX - fromX, tileFromY - fromY,
                        bandCount, tileToX - fromX, tileToY - fromY);
                if (!m.dimEquals(subMatrix)) {
                    throw new AssertionError("Internal bug in readSubMatrixViaCache: "
                            + "incorrect dimensions of the result "
                            + m.dim(0) + "x" + m.dim(1) + "x" + m.dim(2)
                            + " instead of " + bandCount + "x" + (tileToX - tileFromX) + "x" + (tileToY - tileFromY));
                }
                subMatrix.array().copy(m.array());
                readyElements += m.size();
            }
        }
        return result;
    }

    public Matrix<? extends PArray> readFullMatrix(int resolutionLevel)
            throws NoSuchElementException, NotYetConnectedException, UnsupportedOperationException {
        if (!isFullMatrixSupported()) {
            throw new UnsupportedOperationException("readFullMatrix method is not supported");
        }
        final long[] dimensions = dimensions(resolutionLevel);
        return readSubMatrix(resolutionLevel, 0, 0, dimensions[1], dimensions[2]);
    }

    public boolean isSpecialMatrixSupported(SpecialImageKind kind) {
        return false;
    }

    public Optional<Matrix<? extends PArray>> readSpecialMatrix(SpecialImageKind kind)
            throws NotYetConnectedException {
        Objects.requireNonNull(kind, "Null image kind");
        if (kind == SpecialImageKind.SMALLEST_LAYER) {
            int resolutionLevel = numberOfResolutions() - 1;
            final long[] dimensions = dimensions(resolutionLevel);
            return Optional.of(readSubMatrix(resolutionLevel, 0, 0, dimensions[1], dimensions[2]));
        } else {
            return Optional.empty();
        }
    }

    public boolean isDataReady() {
        return true;
    }

    /**
     * This implementation does nothing.
     *
     * <p>If your implementation overrides this method, it must call <code>super.loadResources</code> at the end &mdash;
     * because it is possible that future version will do something useful.
     */
    public void loadResources() {
    }

    /**
     * This implementation frees the tile cache.
     *
     * @param flushMode possible strategy of freeing resources (ignored by this implementation).
     */
    public void freeResources(FlushMode flushMode) {
        synchronized (tileCacheContainer) {
            if (tileCacheContainer.get() != null) {
                LOG.log(System.Logger.Level.DEBUG, () ->
                        AbstractPlanePyramidSource.class.getSimpleName() + " is freeing tile cache");
                tileCacheContainer.set(null);
            }
        }
    }

    public void fillBySkippingFiller(Matrix<? extends UpdatablePArray> matrix, boolean fillWhenZero) {
        if (skipCoarseData) {
            if (fillWhenZero || skippingFiller != 0.0) {
                final double filler = skippingFiller * matrix.array().maxPossibleValue(1.0);
                matrix.array().fill(filler);
            }
        }
    }

    public Matrix<? extends PArray> constantMatrixSkippingFiller(Class<?> elementType, long dimX, long dimY) {
        final Class<PArray> arrayType = Arrays.type(PArray.class, elementType);
        final double filler = skippingFiller * Arrays.maxPossibleValue(arrayType, 1.0);
        return Matrices.constantMatrix(filler, arrayType, bandCount(), dimX, dimY);
    }

    public final boolean isTileCachingEnabled() {
        return tileCacheDirection != null;
    }

    public final TileDirection getTileCacheDirection() {
        if (this.tileCacheDirection == null) {
            throw new IllegalStateException("Tile caching is disabled: cannot read tile cache direction");
        }
        return this.tileCacheDirection;
    }

    public final void enableTileCaching(TileDirection tileDirection) {
        if (tileDirection == null) {
            throw new NullPointerException("Null tileCacheDirection argument");
        }
        this.tileCacheDirection = tileDirection;
    }

    public final void disableTileCaching() {
        this.tileCacheDirection = null;
    }

    /**
     * Returns the size in bytes of the cache, used when  {@link #isTileCachingEnabled()}.
     * The real amount memory, used by this instance of this class, can me little greater (approximately
     * by the size of 1 tile).
     *
     * <p>The initial value is retrieved from the system property
     * "<code>net.algart.maps.pyramids.io.maxNonTiledReadingDim</code>",
     * if it exists and contains a valid integer number.
     * If there is no such property, or if it contains not a number,
     * or if some exception occurred while calling <code>Long.getLong</code>,
     * the default value <code>67108864</code> (64&nbsp;MB) is used.
     *
     * @return the amount of memory in bytes, which is allowed to use for caching by this instance.
     */
    public final long getTileCachingMemory() {
        return tileCachingMemory;
    }

    public final void setTileCachingMemory(long tileCachingMemory) {
        if (tileCachingMemory < 0) {
            throw new IllegalArgumentException("Negative tileCachingMemory");
        }
        this.tileCachingMemory = tileCachingMemory;
    }

    public final RotatingPlanePyramidSource.RotationMode getLabelRotation() {
        return labelRotation;
    }

    public final void setLabelRotation(RotatingPlanePyramidSource.RotationMode labelRotation) {
        if (labelRotation == null) {
            throw new NullPointerException("Null labelRotation");
        }
        this.labelRotation = labelRotation;
    }

    public final Color getLabelRotationBackground() {
        return labelRotationBackground;
    }

    public final void setLabelRotationBackground(Color labelRotationBackground) {
        if (labelRotationBackground == null) {
            throw new NullPointerException("Null labelRotationBackground");
        }
        this.labelRotationBackground = labelRotationBackground;
    }

    public final void checkSubMatrixRanges(
            int resolutionLevel, long fromX, long fromY, long toX, long toY,
            boolean require31BitSize) {
        checkSubMatrixRanges(dimensions(resolutionLevel), fromX, fromY, toX, toY, require31BitSize);
    }

    /**
     * Returns the size (width and height) of a tile, used for cache (when {@link #isTileCachingEnabled()}
     * and for splitting large submatrix for reading into smaller tiles, read by {@link #readLittleSubMatrix}.
     *
     * <p>The default implementation retrieves the value from the system property
     * "<code>net.algart.maps.pyramids.io.tileCachingMemory</code>",
     * if it exists and contains a valid integer number.
     * If there is no such property, or if it contains not a number,
     * or if some exception occurred while calling <code>Long.getLong</code>,
     * this method returns the default value <code>4096</code>.
     *
     * <p>The result must be positive.
     *
     * <p>The result must be stable (constant) for the given instance.
     *
     * <p>This method must work quickly.
     *
     * @return the width/height of tiles for caching and splitting large read submatrix.
     */
    protected int readingTileDim() {
        return READING_TILE_DIM;
    }

    protected abstract Matrix<? extends PArray> readLittleSubMatrix(
            int resolutionLevel, long fromX, long fromY, long toX, long toY)
            throws NoSuchElementException, NotYetConnectedException;

    protected final Matrix<? extends PArray> makeWholeSlideFromLabelAndMap(LabelPosition labelPosition) {
        if (labelPosition == null) {
            throw new NullPointerException("Null labelPosition");
        }
        long t1 = System.nanoTime();
        final MapOrLabelParallelReader labelReader = new MapOrLabelParallelReader(SpecialImageKind.LABEL_ONLY_IMAGE);
        final MapOrLabelParallelReader mapReader = new MapOrLabelParallelReader(SpecialImageKind.MAP_IMAGE);
        final Thread labelReaderThread = new Thread(labelReader);
        final Thread mapReaderThread = new Thread(mapReader);
        labelReaderThread.start();
        mapReaderThread.start();
        try {
            labelReaderThread.join();
            mapReaderThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final long labelDimX = labelReader.data.dim(DIM_WIDTH);
        final long labelDimY = labelReader.data.dim(DIM_HEIGHT);
        final long mapDimX = mapReader.data.dim(DIM_WIDTH);
        final long mapDimY = mapReader.data.dim(DIM_HEIGHT);
        long t2 = System.nanoTime();
        final long wholeSlideDimX = wholeSlideDimX(mapDimX, mapDimY, labelDimX, labelDimY);
        final long wholeSlideDimY = mapDimY;
        final int bandCount = bandCount();
        final Matrix<? extends UpdatablePArray> result = Arrays.SMM.newByteMatrix(
                bandCount, wholeSlideDimX, wholeSlideDimY);
        // There is no sense to use better precision for a special matrix;
        // at the same time, better precision can lead to problems while saving the generated
        // whole slide image into some formats, for example, into BufferedImage or JPEG file
        final long resultDimX = result.dim(DIM_WIDTH);
        long t3 = System.nanoTime();
        Matrices.copy(null,
                result.subMatr(
                        0, labelPosition == LabelPosition.RIGHT_OF_THE_MAP ? 0 : resultDimX - mapDimX, 0,
                        bandCount, mapDimX, mapDimY),
                mapReader.data
        );
        long t4 = System.nanoTime();
        Matrices.resize(null,
                Matrices.ResizingMethod.POLYLINEAR_AVERAGING,
                result.subMatr(
                        0, labelPosition == LabelPosition.RIGHT_OF_THE_MAP ? mapDimX : 0, 0,
                        bandCount, resultDimX - mapDimX, mapDimY),
                labelReader.data
        );
        long t5 = System.nanoTime();
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US, "Combining MAP and LABEL: %.3f ms "
                        + "(%.3f parallel reading "
                        + "[%.3f reading LABEL + %.3f rotating LABEL + %.3f + "
                        + "%.3f correcting LABEL || %.3f reading MAP] + "
                        + "%.3f allocating result + %.3f inserting MAP + %.3f inserting resized LABEL)",
                (t4 - t1) * 1e-6,
                (t2 - t1) * 1e-6,
                labelReader.readingTime * 1e-6,
                labelReader.rotatingTime * 1e-6,
                labelReader.correctingBitDepthTime * 1e-6,
                labelReader.correctingBandCountTime * 1e-6,
                mapReader.readingTime * 1e-6,
                (t3 - t2) * 1e-6,
                (t4 - t3) * 1e-6,
                (t5 - t4) * 1e-6));
        return result;
    }

    protected final Matrix<UpdatablePArray> newResultMatrix(long dimX, long dimY) {
        Matrix<UpdatablePArray> result = memoryModel.newMatrix(
                Arrays.SystemSettings.maxTempJavaMemory(),
                UpdatablePArray.class,
                elementType(),
                bandCount(), dimX, dimY);
        if (!SimpleMemoryModel.isSimpleArray(result.array())) {
            result = result.tile(result.dim(0), 1024, 1024);
        }
        return result;
    }

    protected final Matrix<UpdatablePArray> newFilledResultMatrix(long dimX, long dimY, Color backgroundColor) {
        final Matrix<UpdatablePArray> result = newResultMatrix(dimX, dimY);
        if (isSkipCoarseData()) {
            fillBySkippingFiller(result, false);
        } else {
            PlanePyramidTools.fillMatrix(result, backgroundColor);
        }
        return result;
    }

    public static List<IRectangularArea> defaultZeroLevelActualRectangles(PlanePyramidSource source) {
        if (source == null) {
            throw new NullPointerException("Null source argument");
        }
        final long[] dimensions = source.dimensions(0);
        if (dimensions[DIM_WIDTH] == 0 || dimensions[DIM_HEIGHT] == 0) {
            return null;
        } else {
            return Collections.singletonList(IRectangularArea.of(
                    IPoint.of(0, 0),
                    IPoint.of(dimensions[DIM_WIDTH] - 1, dimensions[DIM_HEIGHT - 1])));
        }
    }

    public static void checkSubMatrixRanges(
            long[] dimensions, long fromX, long fromY, long toX, long toY,
            boolean require31BitSize) {
        if (Arrays.longMul(dimensions) == Long.MIN_VALUE) {
            throw new TooLargeArrayException("Product of all dimensions >Long.MAX_VALUE");
        }
        final long dimX = dimensions[1];
        final long dimY = dimensions[2];
        final long bandCount = dimensions[0];
        if (fromX < 0 || fromY < 0 || fromX > toX || fromY > toY || toX > dimX || toY > dimY) {
            throw new IndexOutOfBoundsException("Illegal fromX..toX=" + fromX + ".." + toX
                    + " or fromY..toY=" + fromY + ".." + toY + ": must be in ranges 0.."
                    + dimX + ", 0.." + dimY + ", fromX<=toX, fromY<=toY");
        }
        // Be careful: fromX == toX == dimX are NOT incorrect arguments, as any situation fromx < toX == dimX
        if (require31BitSize) {
            if (toX - fromX > Integer.MAX_VALUE || toY - fromY > Integer.MAX_VALUE ||
                    (toX - fromX) * (toY - fromY) >= Integer.MAX_VALUE / bandCount) {
                throw new IllegalArgumentException("Too large rectangle " + (toX - fromX) + "x" + (toY - fromY));
            }
        }
    }

    public static long wholeSlideDimX(long mapDimX, long mapDimY, long labelDimX, long labelDimY) {
        return mapDimX + Math.round(labelDimX * (double) mapDimY / (double) labelDimY);
    }

    private Matrix<? extends PArray> readSubMatrixViaTileCache(
            int resolutionLevel, long fromX, long fromY, long toX, long toY, IRectangularArea containingTile)
            throws NoSuchElementException, NotYetConnectedException {
        if (fromX == toX || fromY == toY || !isTileCachingEnabled()) {
            return callAndCheckReadLittleSubMatrix(resolutionLevel, fromX, fromY, toX, toY);
        }
        if (containingTile == null) {
            throw new AssertionError("Internal bug in readSubMatrix implementation: "
                    + "tile cache is enabled, by the containing tile is null (not calculated?)");
        }
        if (!(containingTile.min(0) <= fromX && toX <= containingTile.max(0) + 1
                && containingTile.min(1) <= fromY && toY <= containingTile.max(1) + 1
                && fromX < toX && fromY < toY)) {
            throw new AssertionError("Internal bug in readSubMatrix implementation: "
                    + "the containing tile " + containingTile + " does not contain the required area "
                    + fromX + ".." + (toX - 1) + " x " + fromY + ".." + (toY - 1) + " or this area is negative");
        }
        Matrix<? extends PArray> tileData;
        synchronized (tileCacheContainer) {
            if (tileCacheContainer.get() == null) {
                tileCacheContainer.set(new TileCache(readingTileDim(), tileCachingMemory));
            }
            tileData = tileCacheContainer.get().getTile(resolutionLevel, containingTile);
            if (tileData == null) {
                tileData = callAndCheckReadLittleSubMatrix(
                        resolutionLevel,
                        containingTile.min(0),
                        containingTile.min(1),
                        containingTile.max(0) + 1,
                        containingTile.max(1) + 1);
                if (!(SimpleMemoryModel.isSimpleArray(tileData.array()) || Arrays.isNCopies(tileData.array()))) {
                    tileData = tileData.matrix(tileData.array().updatableClone(Arrays.SMM));
                }
                tileCacheContainer.get().putTile(resolutionLevel, containingTile, tileData);
            }
        }
        return tileData.subMatrix(
                0, fromX - containingTile.min(0), fromY - containingTile.min(1),
                tileData.dim(0), toX - containingTile.min(0), toY - containingTile.min(1));
    }

    private Matrix<? extends PArray> callAndCheckReadLittleSubMatrix(
            int resolutionLevel, long fromX, long fromY, long toX, long toY)
            throws NoSuchElementException, NotYetConnectedException {
        long t1 = System.nanoTime();
        final Matrix<? extends PArray> m = readLittleSubMatrix(resolutionLevel, fromX, fromY, toX, toY);
        long t2 = System.nanoTime();
        if (m.dim(0) != bandCount() || m.dim(1) != toX - fromX || m.dim(2) != toY - fromY) {
            throw new AssertionError("Illegal implementation of readLittleSubMatrix: "
                    + "incorrect dimensions of the result " + m.dim(0) + "x" + m.dim(1) + "x" + m.dim(2)
                    + " instead of " + bandCount() + "x" + (toX - fromX) + "x" + (toY - fromY));
        }
        final String averageSpeed = speedInfo.update(Matrices.sizeOf(m), t2 - t1);
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                "%s has read (level %d): "
                        + "%d..%d x %d..%d (%d x %d) in %.5f ms, %.3f MB/sec, average %s (reader: %s)",
                AbstractPlanePyramidSource.class.getSimpleName(),
                resolutionLevel, fromX, toX, fromY, toY, toX - fromX, toY - fromY,
                (t2 - t1) * 1e-6, Matrices.sizeOf(m) / 1048576.0 / ((t2 - t1) * 1e-9), averageSpeed,
                super.getClass().getSimpleName()
        ));
        return m;
    }

    private Matrix<? extends PArray> rotateLabelImage(final Matrix<? extends PArray> label) {
        if (labelRotation == RotatingPlanePyramidSource.RotationMode.NONE) {
            return label;
        }
        final Matrix<? extends PArray> rotated = labelRotation.asRotated(label);
        long newWidth = rotated.dim(DIM_WIDTH);
        long newHeight = rotated.dim(DIM_HEIGHT);
        if (newWidth > label.dim(DIM_WIDTH)) {
            newHeight *= (double) label.dim(DIM_WIDTH) / newWidth;
            newWidth = label.dim(DIM_WIDTH);
        }
        if (newHeight > label.dim(DIM_HEIGHT)) {
            newWidth *= (double) label.dim(DIM_HEIGHT) / newHeight;
            newHeight = label.dim(DIM_HEIGHT);
        }
        assert newWidth <= label.dim(DIM_WIDTH);
        assert newHeight <= label.dim(DIM_HEIGHT);
        final Matrix<UpdatablePArray> result = Arrays.SMM.newMatrix(UpdatablePArray.class, label);
        PlanePyramidTools.fillMatrix(result, labelRotationBackground);
        final Matrix<UpdatablePArray> centralArea = result.subMatr(
                0, (result.dim(DIM_WIDTH) - newWidth) / 2, (result.dim(DIM_HEIGHT) - newHeight) / 2,
                result.dim(0), newWidth, newHeight);
        Matrices.resize(null, Matrices.ResizingMethod.POLYLINEAR_AVERAGING, centralArea, rotated);
        return result;
    }

    protected final class WholeSlideScaler {
        private final int suitableWholeSlideLevel;
        private final Matrix<? extends PArray> suitableWholeSlide;
        private final double scaleX;
        private final double scaleY;

        Matrix<? extends UpdatablePArray> resultBackground = null;
        volatile long fromXAtSlide;
        volatile long fromYAtSlide;
        volatile long toXAtSlide;
        volatile long toYAtSlide;
        long scalingTime = 0;

        public WholeSlideScaler(List<Matrix<? extends PArray>> wholeSlidePyramid, int resolutionLevel) {
            if (wholeSlidePyramid == null) {
                throw new NullPointerException("Null wholeSlidePyramid");
            }
            final long[] dim = dimensions(resolutionLevel);
            final long levelDimX = dim[DIM_WIDTH];
            final long levelDimY = dim[DIM_HEIGHT];
            int suitableLevel = 0;
            for (int k = 1; k < wholeSlidePyramid.size(); k++) {
                Matrix<? extends PArray> m = wholeSlidePyramid.get(k);
                if (m.dim(DIM_WIDTH) >= levelDimX && m.dim(DIM_HEIGHT) >= levelDimY) {
                    suitableLevel = k;
                } else {
                    break;
                }
            }
            this.suitableWholeSlideLevel = suitableLevel;
            Matrix<? extends PArray> matrix = wholeSlidePyramid.get(suitableLevel);
            final Class<?> requiredElementType = elementType();
            if (requiredElementType != matrix.elementType()) {
                Class<PArray> destType = Arrays.type(PArray.class, requiredElementType);
                final Range srcRange = Range.valueOf(0.0, matrix.array().maxPossibleValue(1.0));
                final Range destRange = Range.valueOf(0.0, Arrays.maxPossibleValue(destType, 1.0));
                matrix = Matrices.asFuncMatrix(LinearFunc.getInstance(destRange, srcRange), destType, matrix);
            }
            this.suitableWholeSlide = matrix;
            this.scaleX = (double) suitableWholeSlide.dim(DIM_WIDTH) / (double) levelDimX;
            this.scaleY = (double) suitableWholeSlide.dim(DIM_HEIGHT) / (double) levelDimY;
        }

        public double scaleX() {
            return scaleX;
        }

        public double scaleY() {
            return scaleY;
        }

        public Matrix<? extends UpdatablePArray> scaleWholeSlide(IRectangularArea area) {
            return scaleWholeSlide(area.min(0), area.min(1), area.max(0) + 1, area.max(1) + 1);
        }

        public Matrix<? extends UpdatablePArray> scaleWholeSlide(
                final long fromX,
                final long fromY,
                final long toX,
                final long toY) {
            long t1 = System.nanoTime();
            this.resultBackground = newResultMatrix(toX - fromX, toY - fromY);
            if (fromX == toX || fromY == toY) {
                return resultBackground;
            }
            fromXAtSlide = Math.round(fromX * scaleX);
            fromYAtSlide = Math.round(fromY * scaleY);
            toXAtSlide = Math.round(toX * scaleX);
            toYAtSlide = Math.round(toY * scaleY);
            Matrix<? extends PArray> subMatrixAtWholeSlide = suitableWholeSlide.subMatrix(
                    0, fromXAtSlide, fromYAtSlide, bandCount(), toXAtSlide, toYAtSlide,
                    Matrix.ContinuationMode.ZERO_CONSTANT);
            // - Continuation is necessary for a case of calling this method for area outside the whole slide.
            // The background outside the whole image stays undefined (zero).
            if (isSkipCoarseData()) {
                fillBySkippingFiller(this.resultBackground, false);
            } else {
                Matrices.resize(null, Matrices.ResizingMethod.SIMPLE, this.resultBackground, subMatrixAtWholeSlide);
                // null argument forces usage of all CPU kernels
            }
            long t2 = System.nanoTime();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                    "Scaling background %s %d..%dx%d..%d (%d x %d), "
                            + "at whole-slide pyramid: level %d, %d..%dx%d..%d (%d x %d): %.3f ms, %.3f MB/sec)",
                    isSkipCoarseData() ? "(filling)" : "(scaling whole-slide image)",
                    fromX, toX, fromY, toY, toX - fromX, toY - fromY,
                    suitableWholeSlideLevel,
                    fromXAtSlide, toXAtSlide, fromYAtSlide, toYAtSlide,
                    toXAtSlide - fromXAtSlide, toYAtSlide - fromYAtSlide,
                    (t2 - t1) * 1e-6, Matrices.sizeOf(this.resultBackground) / 1048576.0 / ((t2 - t1) * 1e-9)
            ));
            this.scalingTime += t2 - t1;
            return resultBackground;
        }

        public Matrix<? extends UpdatablePArray> resultBackground() {
            return resultBackground;
        }

        public boolean done() {
            return resultBackground != null;
        }

        public long scalingTime() {
            return scalingTime;
        }
    }

    private static final class TileCacheIndex {
        final int resolutionLevel;
        final IRectangularArea tile;

        private TileCacheIndex(int resolutionLevel, IRectangularArea tile) {
            assert tile != null;
            this.resolutionLevel = resolutionLevel;
            this.tile = tile;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TileCacheIndex)) {
                return false;
            }
            TileCacheIndex that = (TileCacheIndex) o;
            return resolutionLevel == that.resolutionLevel && tile.equals(that.tile);
        }

        @Override
        public int hashCode() {
            int result = resolutionLevel;
            result = 31 * result + tile.hashCode();
            return result;
        }
    }

    private class MapOrLabelParallelReader implements Runnable {
        final SpecialImageKind kind;

        Matrix<? extends PArray> data = null;
        long readingTime = 0;
        long rotatingTime = 0;
        long correctingBandCountTime = 0;
        long correctingBitDepthTime = 0;

        private MapOrLabelParallelReader(SpecialImageKind kind) {
            this.kind = kind;
        }

        @Override
        public void run() {
            long t1 = System.nanoTime();
            data = PlanePyramidTools.readSpecialOrSmallestMatrix(AbstractPlanePyramidSource.this, kind);
            long t2 = System.nanoTime();
            if (data.elementType() != byte.class) {
                final Matrix<? extends UpdatablePArray> newData = Arrays.SMM.newByteMatrix(data.dimensions());
                final Range srcRange = Range.valueOf(0.0, data.array().maxPossibleValue(1.0));
                final Range destRange = Range.valueOf(0.0, newData.array().maxPossibleValue(1.0));
                Matrices.applyFunc(null, LinearFunc.getInstance(destRange, srcRange), newData, data);
                data = newData;
            }
            long t3 = System.nanoTime();
            if (kind == SpecialImageKind.LABEL_ONLY_IMAGE) {
                data = rotateLabelImage(data);
            }
            long t4 = System.nanoTime();
            final int requiredBandCount = bandCount();
            if (data.dim(DIM_BAND) != requiredBandCount) {
                final Matrix<UpdatablePArray> newData = Arrays.SMM.newMatrix(
                        UpdatablePArray.class, data.elementType(),
                        requiredBandCount, data.dim(DIM_WIDTH), data.dim(DIM_HEIGHT));
                Matrices.resize(null, Matrices.ResizingMethod.SIMPLE, newData, data);
                // - really it is not geometrical resizing, it is only the changing
                // the number of color bands in the coordinate #0
                data = newData;
            }
            long t5 = System.nanoTime();
            this.readingTime += t2 - t1;
            this.correctingBitDepthTime += t3 - t2;
            this.rotatingTime += t4 - t3;
            this.correctingBandCountTime += t5 - t4;
        }
    }

    private static class TileCache {
        final int tileDim;
        final long tileCachingMemory;
        final TileCacheHashMap tileCacheHashMap;

        private TileCache(int tileDim, long tileCachingMemory) {
            this.tileDim = tileDim;
            this.tileCachingMemory = tileCachingMemory;
            this.tileCacheHashMap = new TileCacheHashMap();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                    AbstractPlanePyramidSource.class.getSimpleName()
                            + " is creating tile cache for tiles %dx%d, memory limit %.2f MB",
                    tileDim, tileDim, tileCachingMemory / 1048576.0
            ));
        }

        Matrix<? extends PArray> getTile(int resolutionLevel, IRectangularArea tile) {
            Matrix<? extends PArray> result = tileCacheHashMap.get(new TileCacheIndex(resolutionLevel, tile));
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "  " + AbstractPlanePyramidSource.class.getSimpleName()
                            + " has " + (result != null ? "loaded data from the cache" : "NOT FOUND data in the cache")
                            + " (level %d): %s",
                    resolutionLevel, tile
            ));
            return result;
        }

        void putTile(int resolutionLevel, IRectangularArea tile, Matrix<? extends PArray> matrix) {
            Matrix<? extends PArray> prev = tileCacheHashMap.put(new TileCacheIndex(resolutionLevel, tile), matrix);
            if (prev == null) {
                LOG.log(System.Logger.Level.TRACE, () -> String.format(
                        "  " + AbstractPlanePyramidSource.class.getSimpleName()
                                + " has stored data in the cache (level %d): %s",
                        resolutionLevel, tile
                ));
            }
        }

        private class TileCacheHashMap extends LinkedHashMap<TileCacheIndex, Matrix<? extends PArray>> {
            private TileCacheHashMap() {
                super(16, 0.75f, true);
            }

            @Override
            protected boolean removeEldestEntry(Map.Entry<TileCacheIndex, Matrix<? extends PArray>> eldest) {
                boolean result = usedMemory() > tileCachingMemory;
                if (result) {
                    LOG.log(System.Logger.Level.DEBUG, () -> AbstractPlanePyramidSource.class.getSimpleName()
                            + " will remove the eldest entry from the cache");
                }
                return result;
            }

            private double usedMemory() {
                double sum = 0.0;
                for (Matrix<? extends PArray> m : values()) {
                    sum += Matrices.sizeOf(m);
                }
                return sum;
            }
        }
    }

    private static class SpeedInfo {
        double totalMemory = 0.0;
        double elapsedTime = 0.0;

        public String update(long memory, long time) {
            final String result;
            synchronized (this) {
                totalMemory += memory;
                elapsedTime += time;
                final long t = System.currentTimeMillis();
                result = String.format(Locale.US,
                        "%.1f MB / %.3f sec = %.3f MB/sec",
                        totalMemory / 1048576.0,
                        elapsedTime * 1e-9,
                        totalMemory / 1048576.0 / (elapsedTime * 1e-9));
            }
            return result;
        }
    }
}
