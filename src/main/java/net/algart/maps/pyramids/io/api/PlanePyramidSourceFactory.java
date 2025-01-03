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

import java.io.IOException;

/**
 * Factory allowing to construct {@link PlanePyramidSource} instance on the base
 * of the path to an external resource, where the pyramid is stored,
 * and some additional configuration information.
 * This interface should have different implementations for different formats of image pyramids.
 */
public interface PlanePyramidSourceFactory extends AutoCloseable {
    class Unsupported implements PlanePyramidSourceFactory {
        /**
         * Some instance of this class.
         * Note: this class is not a singleton, because any class of plane pyramid source factory should allow
         * creating new instances.
         */
        public static final Unsupported INSTANCE = new Unsupported();

        @Override
        public PlanePyramidSource newPlanePyramidSource(
                String pyramidPath,
                String pyramidConfiguration,
                String renderingConfiguration) {
            throw new UnsupportedOperationException("No suitable plane pyramid source factory for " + pyramidPath);
        }

        @Override
        public String toString() {
            return "Unsupported (a factory that cannot create any plane pyramid source)";
        }
    }

    /**
     * Creates new plane pyramid source, providing access to the pyramid, stored in the given place,
     * with possible using additional recommendations, described in
     * <code>pyramidConfiguration</code> and <code>renderingConfiguration</code> arguments.
     *
     * <p>The <code>pyramidPath</code> can be any specifier of some external resource, like URL,
     * but usually it is a path to some disk file or subdirectory (for example, a path to .TIFF file).
     *
     * <p>The <code>pyramidConfiguration</code> and <code>renderingConfiguration</code> arguments can use any format,
     * but we recommend to use JSON format for this string.
     * Most existing implementations expect correct JSON format here.
     * Syntax errors in this file should be ignored or lead to <code>IOException</code>, like format errors
     * in the data file.
     *
     * @param pyramidPath            path to an external resource, where the pyramid is stored;
     *                               usually a disk path to some directory.
     * @param pyramidConfiguration   some additional information, describing the pyramid and necessary behaviour
     *                               of the resulting pyramid source, which relates to the given data and
     *                               cannot be changed dynamically.
     * @param renderingConfiguration some additional information for customizing behaviour of the resulting
     *                               pyramid source, which can vary in future for the same data file.
     * @return new pyramid source, providing access to the pyramid at the specified path.
     * @throws NullPointerException if one of the arguments is <code>null</code>.
     * @throws IOException          if some I/O problems occur while opening pyramid, and also in a case
     *                              of invalid format of the files containing the pyramid or
     *                              of the passed <code>renderingConfiguration</code> description.
     */
    PlanePyramidSource newPlanePyramidSource(
            String pyramidPath,
            String pyramidConfiguration,
            String renderingConfiguration)
            throws IOException;


    /**
     * Frees resources, possibly associated with this factory and probably necessary for plane pyramid sources,
     * created by it. We mention resources, shared between many plane pyramid sources of this type,
     * like cache, service threads etc. &mdash; not resources, associated with a specific pyramid (like opened file).
     *
     * <p>Note: after calling this method, all plane pyramid sources, created by this factory, can work incorrectly!
     * <p>Default implementation of this method does nothing.
     */
    @Override
    default void close() {
    }
}
