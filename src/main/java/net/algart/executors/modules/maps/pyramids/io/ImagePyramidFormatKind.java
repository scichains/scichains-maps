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

package net.algart.executors.modules.maps.pyramids.io;

import net.algart.arrays.Arrays;
import net.algart.maps.pyramids.io.api.sources.ImageIOPlanePyramidSourceFactory;
import net.algart.maps.pyramids.io.formats.sources.svs.SVSPlanePyramidSourceFactory;

import java.util.Objects;

public enum ImagePyramidFormatKind {
    AUTO_DETECT_BY_EXTENSION(null),
    JAVA_IMAGEIO(ImageIOPlanePyramidSourceFactory.class.getName()),
    SVS(factory(
            new String[]{"net.algart.maps.pyramids.io.formats.sources.factories.SVS"},
            SVSPlanePyramidSourceFactory.class),
            // - note that the last properties has priority
            "svs"),
    CUSTOM(null);

    //    "net.algart.pyramid.svs.server.SVSPlanePyramidSourceFactory"
    private final String factoryClassName;
    private final String[] extensions;

    ImagePyramidFormatKind(String factoryClassName, String... extensions) {
        this.factoryClassName = factoryClassName;
        this.extensions = Objects.requireNonNull(extensions);
    }

    public boolean isAutoDetection() {
        return this == AUTO_DETECT_BY_EXTENSION;
    }

    public boolean hasFactory() {
        return factoryClassName != null;
    }

    public String getFactoryClassName() {
        return factoryClassName;
    }

    public String[] getExtensions() {
        return extensions.clone();
    }

    public static ImagePyramidFormatKind valueOfExtension(String extension) {
        for (ImagePyramidFormatKind format : values()) {
            if (format.extensions != null) {
                for (String e : format.extensions) {
                    if (e.equalsIgnoreCase(extension)) {
                        return format;
                    }
                }
            }
        }
        return JAVA_IMAGEIO;
        // - default case: a lot of non-pyramid formats
    }

    private static String factory(String[] propertyNames, Class<?> defaultFactory) {
        Objects.requireNonNull(defaultFactory, "Null defaultFactory");
        for (int i = propertyNames.length - 1; i >= 0; i--) {
            final String result = Arrays.SystemSettings.getStringProperty(propertyNames[i], null);
            if (result != null) {
                return result;
            }
        }
        return defaultFactory.getName();
    }
}
