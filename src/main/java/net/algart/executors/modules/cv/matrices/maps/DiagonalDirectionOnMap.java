package net.algart.executors.modules.cv.matrices.maps;

import net.algart.math.IPoint;

public enum DiagonalDirectionOnMap {
    LEFT_UP {
        @Override
        public IPoint shift(long x, long y) {
            return IPoint.valueOf(-x, -y);
        }
    },
    RIGHT_UP {
        @Override
        public IPoint shift(long x, long y) {
            return IPoint.valueOf(x, -y);
        }
    },
    LEFT_DOWN {
        @Override
        public IPoint shift(long x, long y) {
            return IPoint.valueOf(-x, y);
        }
    },
    RIGHT_DOWN {
        @Override
        public IPoint shift(long x, long y) {
            return IPoint.valueOf(x, y);
        }
    };

    public abstract IPoint shift(long x, long y);
}
