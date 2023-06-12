package net.algart.matrices.maps.pyramids.io.formats.svs.metadata;

public class SVSAttribute {
    private final String name;
    private final String value;

    public SVSAttribute(String name, String value) {
        if (name == null) {
            throw new NullPointerException("Null SVS attribute name");
        }
        if (value == null) {
            throw new NullPointerException("Null SVS attribute value");
        }
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return name + " = " + value;
    }
}
