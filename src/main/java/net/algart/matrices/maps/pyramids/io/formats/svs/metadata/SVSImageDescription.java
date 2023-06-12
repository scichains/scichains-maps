package net.algart.matrices.maps.pyramids.io.formats.svs.metadata;

import io.scif.FormatException;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import net.algart.json.Jsons;
import net.algart.matrices.maps.pyramids.io.formats.svs.SVSPlanePyramidSource;

import java.util.*;

public abstract class SVSImageDescription {
    final List<String> text = new ArrayList<>();
    final Map<String, SVSAttribute> attributes = new LinkedHashMap<>();

    SVSImageDescription() {
    }

    public static SVSImageDescription valueOf(String imageDescriptionTagValue) {
        SVSImageDescription result = new StandardSVSImageDescription(imageDescriptionTagValue);
        if (result.isImportant()) {
            return result;
        }
        return new EmptyImageDescription(imageDescriptionTagValue);
    }

    public final List<String> getText() {
        return Collections.unmodifiableList(text);
    }

    public final Map<String, SVSAttribute> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public abstract String subFormatTitle();

    public abstract Set<String> importantAttributeNames();

    public abstract List<String> importantTextAttributes();

    public abstract boolean isImportant();

    public boolean isPixelSizeSupported() {
        return false;
    }

    public double pixelSize() throws FormatException {
        throw new UnsupportedOperationException();
    }

    public boolean isMagnificationSupported() {
        return false;
    }

    public double magnification() throws FormatException {
        throw new UnsupportedOperationException();
    }

    public boolean isGeometrySupported() {
        return false;
    }

    public double imageOnSlideLeftInMicronsAxisRightward() throws FormatException {
        throw new UnsupportedOperationException();
    }

    public double imageOnSlideTopInMicronsAxisUpward() throws FormatException {
        throw new UnsupportedOperationException();
    }

    public boolean correctCombiningInfoUsingFoundBorder(
            SVSAdditionalCombiningInfo additionalCombiningInfo,
            SVSPlanePyramidSource source,
            long borderLeftTopX,
            long borderLeftTopY) {
        return false;
    }

    public final JsonObject toJson() {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("text", Json.createArrayBuilder(text).build());
        final JsonObjectBuilder attributesBuilder = Json.createObjectBuilder();
        for (SVSAttribute attribute : attributes.values()) {
            attributesBuilder.add(attribute.getName(), attribute.getValue());
        }
        builder.add("attributes", attributesBuilder.build());
        if (isPixelSizeSupported()) {
            try {
                Jsons.addDouble(builder, "pixelSize", pixelSize());
            } catch (FormatException e) {
                builder.add("pixelSize", "format error: " + e);
            }
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return Jsons.toPrettyString(toJson());
    }
}
