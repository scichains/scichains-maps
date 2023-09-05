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

package net.algart.matrices.maps.pyramids.io.api;

import net.algart.arrays.Arrays;
import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.math.IPoint;
import net.algart.math.IRectangularArea;
import net.algart.math.functions.Func;
import net.algart.matrices.maps.pyramids.io.api.sources.DefaultPlanePyramidSource;

import java.nio.channels.NotYetConnectedException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalDouble;

public interface PlanePyramidSource {
    int DIM_BAND = 0;
    int DIM_WIDTH = 1;
    int DIM_HEIGHT = 2;
    // - note: we don't call this "DIM_X/Y", because in usual matrices dimX is dim(0)

    int DEFAULT_COMPRESSION = Math.max(2,
        Arrays.SystemSettings.getIntProperty(
                "net.algart.matrices.maps.pyramids.io.defaultPyramidCompression", 4));
    long DEFAULT_TILE_DIM = Math.max(16,
        Arrays.SystemSettings.getLongProperty(
                "net.algart.matrices.maps.pyramids.io.tile", 1024));
    long DEFAULT_MINIMAL_PYRAMID_SIZE = 8;

    enum SpecialImageKind {
        /**
         * No image.
         *
         * <p>When passed to {@link #readSpecialMatrix(SpecialImageKind)} method,
         * the result is always empty ({@link Optional#empty()}).
         */
        NONE,
        /**
         * Coarse image of the full slide. Usually contains the {@link #LABEL_ONLY_IMAGE label} as a part.
         *
         * <p>Some implementations of {@link PlanePyramidSource} can use it automatically
         * as the background for the pyramid data.
         */
        WHOLE_SLIDE,

        /**
         * Coarse image with all existing pyramid data. Never contains the {@link #LABEL_ONLY_IMAGE label}.
         * May be used, for example, for visual navigation goals.
         */
        MAP_IMAGE,

        /**
         * The label image: a little photo of some paper on the scan
         * with printed or written information about the slide.
         */
        LABEL_ONLY_IMAGE,

        /**
         * The thumbnail: something like {@link #MAP_IMAGE}, but containing the same information as a regular
         * pyramid (while {@link #MAP_IMAGE} may contain other coarse data). Usually generated automatically
         * by scanner software and looks like last available levels.
         */
        THUMBNAIL_IMAGE,

        /**
         * Simplest universal thumbnail: the last (smallest) available level of the full image.
         * When passed to {@link #readSpecialMatrix(SpecialImageKind)} method,
         * the result is always the full image, returned by
         * <tt>{@link #readFullMatrix readFullMatrix}(numberOfResolutions()-1)</tt>.
         *
         * <p>You may use this variant instead of any other special kind, if
         * {@link #isSpecialMatrixSupported(SpecialImageKind)}
         * informs that a kind is not supported (though it is not a good idea for replacement of
         * {@link #LABEL_ONLY_IMAGE}).
         * But you need to remember that some pyramids has only large layers, so this "thumbnail"
         * can be very large.
         *
         * <p>For this kind, result of {@link #readSpecialMatrix(SpecialImageKind)} method is never empty.
         */
        SMALLEST_LAYER,

        /**
         * Some format-specific special image.
         *
         * <p>If the format does not provide such an image,
         * {@link #readSpecialMatrix(SpecialImageKind)} method returns the empty result for this kind
         * ({@link Optional#empty()}).
         */
        CUSTOM_KIND_1,

        /**
         * Some format-specific special image.
         *
         * <p>If the format does not provide such an image,
         * {@link #readSpecialMatrix(SpecialImageKind)} method returns the empty result for this kind
         * ({@link Optional#empty()}).
         */
        CUSTOM_KIND_2,

        /**
         * Some format-specific special image.
         *
         * <p>If the format does not provide such an image,
         * {@link #readSpecialMatrix(SpecialImageKind)} method returns the empty result for this kind
         * ({@link Optional#empty()}).
         */
        CUSTOM_KIND_3,

        /**
         * Some format-specific special image.
         *
         * <p>If the format does not provide such an image,
         * {@link #readSpecialMatrix(SpecialImageKind)} method returns the empty result for this kind
         * ({@link Optional#empty()}).
         */

        CUSTOM_KIND_4,
        /**
         * Some format-specific special image.
         *
         * <p>If the format does not provide such an image,
         * {@link #readSpecialMatrix(SpecialImageKind)} method returns the empty result for this kind
         * ({@link Optional#empty()}).
         */

        CUSTOM_KIND_5,

        /**
         * Impossible image kind, that should be never returned for correct special images, but can be returned
         * by some methods to indicate special situation (for example, when it is not a special image).
         *
         * <p>When passed to {@link #readSpecialMatrix(SpecialImageKind)} method,
         * the result is always empty ({@link Optional#empty()}), as for {@link #NONE} kind.
         */
        INVALID
    }

    enum FlushMode {
        /**
         * Like {@link #STANDARD}, but permits loss of some data.
         * Can be useful, for example, if you want to cancel long-time operation
         * that writes some large object.
         */
        QUICK_WITH_POSSIBLE_LOSS_OF_DATA() {
            @Override
            public boolean dataMustBeFlushed() {
                return false;
            }

            @Override
            public boolean forcePhysicalWriting() {
                return false;
            }

            @Override
            public boolean flushLongTermResources() {
                return false;
            }
        },

        /**
         * Usual flushing, suitable for <tt>close()</tt> and similar methods.
         * Should close associated files, flush non-written data to disk etc.
         */
        STANDARD() {
            @Override
            public boolean dataMustBeFlushed() {
                return true;
            }

            @Override
            public boolean forcePhysicalWriting() {
                return false;
            }

            @Override
            public boolean flushLongTermResources() {
                return false;
            }
        },

        /**
         * Forces strong freeing any resources, associated with this object and with a group of similar objects.
         * If some buffers are not written yet to disk, should enforce physical flushing them to disk by means of
         * corresponding OS calls. If there are some service threads, necessary for correct work with this type
         * of sources and created automatically while using this source, should try to stop these threads.
         * However, next usage of this kind of sources after such flushing may require additional time.
         *
         * <p>This mode is suitable if the user wants to finish working with his task, for example, closes
         * the window or stops an application. Note: pyramid sources, based on SCIFIO library
         * (until version 0.44 including), <b>must</b> use this flushing mode, in other case
         * that library will prevent closing the application.
         */
        FLUSH_LONG_TERM_RESOURCES() {
            @Override
            public boolean dataMustBeFlushed() {
                return true;
            }

            @Override
            public boolean forcePhysicalWriting() {
                return true;
            }

            @Override
            public boolean flushLongTermResources() {
                return true;
            }
        };

        public abstract boolean dataMustBeFlushed();

        public abstract boolean forcePhysicalWriting();

        public abstract boolean flushLongTermResources();
    }

    enum AveragingMode {
        /**
         * For binary data means simple, for other types means averaging.
         * Note that in a case of BIT_TO_BYTE the source data are already converted to bytes (if a pyramid
         * contains more than 1 level).
         * Recommended for algorithmic needs.
         */
        DEFAULT,
        /**
         * For binary data means conversion to bytes + averaging, for other types means averaging.
         * Recommended for viewers.
         */
        AVERAGING,
        /**
         * Step decimation. Never averages pixels.
         */
        SIMPLE,
        /**
         * Like {@link #MIN}, but affects binary data only (for other types performs averaging).
         */
        AND,
        /**
         * Like {@link #MAX}, but affects binary data only (for other types performs averaging).
         */
        OR,
        MIN,
        MAX;

        public Matrices.ResizingMethod averagingMethod(Matrix<?> matrix) {
            switch (this) {
                case AND:
                    if (matrix.elementType() != boolean.class) {
                        break;
                    }
                    // else equivalent to the following MIN
                case MIN:
                    return AveragingMode.AVERAGING_MIN;
                case OR:
                    if (matrix.elementType() != boolean.class) {
                        break;
                    }
                    // else equivalent to the following MAX
                case MAX:
                    return AveragingMode.AVERAGING_MAX;
                case DEFAULT:
                case AVERAGING:
                    if (matrix.elementType() != boolean.class) {
                        break;
                    } else {
                        return Matrices.ResizingMethod.SIMPLE;
                    }
                case SIMPLE:
                    return Matrices.ResizingMethod.SIMPLE;
            }
            return Matrices.ResizingMethod.POLYLINEAR_AVERAGING;
        }

        private static final Matrices.ResizingMethod.Averaging AVERAGING_MIN =
            new Matrices.ResizingMethod.Averaging(Matrices.InterpolationMethod.STEP_FUNCTION) {
                @Override
                protected Func getAveragingFunc(long[] apertureDim) {
                    return Func.MIN;
                }
            };

        private static final Matrices.ResizingMethod.Averaging AVERAGING_MAX =
            new Matrices.ResizingMethod.Averaging(Matrices.InterpolationMethod.STEP_FUNCTION) {
                @Override
                protected Func getAveragingFunc(long[] apertureDim) {
                    return Func.MAX;
                }
            };
    }

    int numberOfResolutions();

    // Relation of sizes of the level #k and the level #k+1
    int compression();

    // Usually 1, 3, 4; must be positive; works very quickly
    int bandCount();

    boolean[] getResolutionLevelsAvailability();

    boolean isResolutionLevelAvailable(int resolutionLevel);

    /**
     * <p>Returns dimensions of any pyramid level. Number of elements in the result arrays is always 3:</p>
     * <ul>
     * <li>first element <tt>result[0]</tt> is always equal to {@link #bandCount()};</li>
     * <li>the element <tt>result[{@link #DIM_WIDTH}]</tt> is the <i>x</i>-dimension of the level in pixels;
     * <li>the element <tt>result[{@link #DIM_HEIGHT}]</tt> is the <i>y</i>-dimension of the level in pixels.
     * </ul>
     *
     * <p>This method always returns a new Java array and never returns a reference to an internal field.</p>
     *
     * @param resolutionLevel the level of pyramid; zero level (<tt>resolutionLevel=0</tt>) corresponds
     *                        to the best resolution.
     * @return dimensions of the specified pyramid level.
     * @throws NoSuchElementException if <tt>!{@link #isResolutionLevelAvailable(int)
     *                                isResolutionLevelAvailable(resolutionLevel)}</tt>
     */
    long[] dimensions(int resolutionLevel)
        throws NoSuchElementException;

    /**
     * Returns the dimension <tt>#index</tt> of the given pyramid level.
     * Equivalent to <tt>{@link #dimensions(int) dimensions(resolutionLevel)}[index]</tt>,
     * but works faster.
     *
     * @param index the index of dimension.
     * @return  the dimension <tt>#index</tt> of this level.
     * @throws IndexOutOfBoundsException if <tt>index&lt;0</tt> or <tt>index&gt;2</tt>.
     */
    default long dim(int resolutionLevel, int index) {
        return dimensions(resolutionLevel)[index];
    }

    default long width(int resolutionLevel) {
        return dim(resolutionLevel, DIM_WIDTH);
    }

    default long height(int resolutionLevel) {
        return dim(resolutionLevel, DIM_HEIGHT);
    }

    /**
     * Returns <tt>true</tt> if {@link #elementType()} method works properly.
     * In other case, that method throws <tt>UnsupportedOperationException</tt>.
     * In particular, returns <tt>true</tt> in  {@link DefaultPlanePyramidSource}.
     *
     * @return whether {@link #elementType()} is supported.
     */
    boolean isElementTypeSupported();

    Class<?> elementType() throws UnsupportedOperationException;

    default OptionalDouble pixelSizeInMicrons() {
        return OptionalDouble.empty();
    }

    default OptionalDouble magnification() {
        return OptionalDouble.empty();
    }

    /**
     * <p>Returns a set of all areas, which are 2D rectangles, filled by actual data, at the zero level.
     * All other areas should be considered to be a background and may be not passed to
     * image analysis algorithms.</p>
     *
     * <p>Some pyramids, including the default implementation in {@link net.algart.matrices.maps.pyramids.io.api.AbstractPlanePyramidSource}, do not
     * support this feature. In this case, the method returns <tt>null</tt>. This situation may be
     * interpreted as if we have only 1 actual area, corresponding to the whole pyramid
     * from (0,&nbsp;0) to <nobr>({@link #dimensions(int) dimensions}(0)[{@link #DIM_WIDTH}]&minus;1,
     * {@link #dimensions(int) dimensions}(0)[{@link #DIM_HEIGHT}]&minus;1).</p>
     *
     * @return a list of all areas (2D rectangles), filled by actual data, at the level #0,
     * or <tt>null</tt> if it is not supported.
     */
    default List<IRectangularArea> zeroLevelActualRectangles() {
        return null;
    }

    /**
     * <p>Returns a set of all areas, filled by actual data, at the zero level, in a form of a list
     * of polygons, represented by their consecutive vertices.
     * All other areas should be considered to be a background and may be not passed to
     * image analysis algorithms.</p>
     *
     * <p>More precisely, each element <b>P</b> of the returned list, i.e. <tt>List&lt;List&lt;IPoint&gt;&gt;</tt>,
     * corresponds to one connected 2D polygon. The structure of this element <b>P</b> is the following:</p>
     *
     * <ul>
     * <li><b>P</b><tt>.get(0)</tt> is the list of sequential vertices of the polygon; each vertex
     * appears in this list only once;</li>
     * <li><b>P</b><tt>.get(1)</tt>, <b>P</b><tt>.get(2)</tt>, ..., <b>P</b><tt>.get(m)</tt>, where
     * <tt>m=</tt><b>P</b><tt>.size()</tt> describe sequential vertices of all polygonal holes,
     * that may appear in the polygon. If the polygon has no holes (very probable case) or if
     * their detection is not supported, the list <b>P</b> contains only 1 element (<tt>m=1</tt>).
     * </li>
     * </ul>
     *
     * <p>The default implementation in {@link AbstractPlanePyramidSource} calls {@link #zeroLevelActualRectangles()}
     * and converts each rectangle to the list <b>P</b>, containing only 1 element, and this element
     * is the list of 4 vertices of the rectangle. If {@link #zeroLevelActualRectangles()} returns <tt>null</tt>,
     * the default implementation also returns <tt>null</tt>.</p>
     *
     * @return the list of all polygonal areas, filled by actual data, at the level #0, and their
     * holes (if they exist), or <tt>null</tt> if this ability is not supported.
     */
    default List<List<List<IPoint>>> zeroLevelActualAreaBoundaries() {
        return null;
    }

    Matrix<? extends PArray> readSubMatrix(int resolutionLevel, long fromX, long fromY, long toX, long toY)
        throws NoSuchElementException, NotYetConnectedException;
    // throws if !isResolutionLevelAvailable(resolutionLevel), if !isDataReady()

    default boolean isFullMatrixSupported() {
        return true;
    }

    // Works faster than equivalent readSubMatrix call if possible;
    // but may work very slowly in compressed implementations
    Matrix<? extends PArray> readFullMatrix(int resolutionLevel)
        throws NoSuchElementException, NotYetConnectedException, UnsupportedOperationException;
    // throws if !isResolutionLevelAvailable(resolutionLevel),
    // if !isDataReady(), if !isFullMatrixSupported()

    /**
     * Returns <tt>true</tt> if this special image kind is provided by current format.
     * <p>Default implementation in <tt>AbstractPlanePyramidSource</tt> return <tt>false</tt>.
     *
     * @param kind the kind of special image.
     * @return whether this kind is supported; <tt>false</tt> by default.
     */
    boolean isSpecialMatrixSupported(SpecialImageKind kind);

    /**
     * Reads special image according specified kind.
     * <p>If the argument is {@link SpecialImageKind#NONE}, return <tt>null</tt>. In all other cases
     * returns some non-null result.
     * <p>If there is no appropriate image, returns {@link Optional#empty()}.</tt>.
     *
     * @param kind what special image is requested.
     * @return special image or {@link Optional#empty()} if this kind is not supported.
     */
    Optional<Matrix<? extends PArray>> readSpecialMatrix(SpecialImageKind kind)
        throws NotYetConnectedException;

    /**
     * Returns true if the data at all available levels of this source are available.
     * In other case, {@link #readSubMatrix(int, long, long, long, long)} and {@link #readFullMatrix(int)}
     * method can throw <tt>NotYetConnectedException</tt> (but also, maybe, work properly),
     * <p>Note: all other methods, including {@link #dimensions(int)}, must work without
     * <tt>NotYetConnectedException</tt> even if this method returns <tt>false</tt>.
     *
     * @return whether the data at all available levels of this source are available.
     */
    boolean isDataReady();

    /**
     * Returns some additional information about this pyramid or {@link Optional#empty()} if it is not supported.
     * The returned string should be formatted according JSON standard.
     *
     * @return additional information about the pyramid or empty result.
     */
    default Optional<String> metadata() {
        return Optional.empty();
    }

    /**
     * Re-initializes object and loads all necessary resources.
     *
     * <p>This method should be called before using this object, if
     * {@link #freeResources(FlushMode)} was called before.
     * If you will not directly call this method, all will work normally,
     * but some classes may work more slowly.
     * For example, some classes may work with clones of this object,
     * so, if it is not initialized, then all its clones will be also non-initialized
     * and will be reinitialized while every usage.</p>
     */
    void loadResources();

    void freeResources(FlushMode flushMode);
}
