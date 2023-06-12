package net.algart.matrices.maps.pyramids.io.formats.svs.metadata;

import java.util.Collections;
import java.util.List;
import java.util.Set;

class EmptyImageDescription extends SVSImageDescription {
    EmptyImageDescription(String imageDescriptionTagValue) {
    }

    @Override
    public String subFormatTitle() {
        return null;
    }

    @Override
    public Set<String> importantAttributeNames() {
        return Collections.emptySet();
    }

    @Override
    public List<String> importantTextAttributes() {
        return Collections.emptyList();
    }

    @Override
    public boolean isImportant() {
        return false;
    }
}
