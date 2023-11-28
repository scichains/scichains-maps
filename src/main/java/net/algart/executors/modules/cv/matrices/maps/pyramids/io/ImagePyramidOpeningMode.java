package net.algart.executors.modules.cv.matrices.maps.pyramids.io;

enum ImagePyramidOpeningMode {
    OPEN_AND_CLOSE(false, true, true),
    OPEN(false, true, false),
    OPEN_ON_RESET_AND_FIRST_CALL(true, false, false),
    OPEN_ON_FIRST_CALL(false, false, false);

    private final boolean closePreviousOnReset;
    private final boolean closePreviousOnExecute;
    private final boolean closeAfterExecute;

    ImagePyramidOpeningMode(boolean closePreviousOnReset, boolean closePreviousOnExecute, boolean closeAfterExecute) {
        this.closePreviousOnReset = closePreviousOnReset;
        this.closePreviousOnExecute = closePreviousOnExecute;
        this.closeAfterExecute = closeAfterExecute;
    }

    public boolean isClosePreviousOnReset() {
        return closePreviousOnReset;
    }

    public boolean isClosePreviousOnExecute() {
        return closePreviousOnExecute;
    }

    public boolean isCloseAfterExecute() {
        return closeAfterExecute;
    }
}
