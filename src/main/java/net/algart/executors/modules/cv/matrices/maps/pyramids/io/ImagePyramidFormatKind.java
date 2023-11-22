package net.algart.executors.modules.cv.matrices.maps.pyramids.io;

import net.algart.executors.api.SystemEnvironment;
import net.algart.matrices.maps.pyramids.io.api.PlanePyramidSourceFactory;
import net.algart.matrices.maps.pyramids.io.api.sources.ImageIOPlanePyramidSourceFactory;

import java.util.Objects;

public enum ImagePyramidFormatKind {
    AUTO_DETECT_BY_EXTENSION(null),
    JAVA_IMAGEIO(ImageIOPlanePyramidSourceFactory.class.getName()),
    SVS(factory(
            "net.algart.matrices.maps.pyramids.io.formats.sources.factories.SVS",
            // - first name is used in new scichains-maps module
            "net.algart.matrices.maps.pyramids.io.api.sources.factories.SVS"),
            // - last properties has priority
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

    private static String factory(String... propertyNames) {
        for (int i = propertyNames.length - 1; i >= 0; i--) {
            final String result = SystemEnvironment.getStringProperty(propertyNames[i]);
            if (result != null) {
                return result;
            }
        }
        return PlanePyramidSourceFactory.Unsupported.class.getName();
    }
}
