package de.sirywell.bitvectors;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public final class MemorySupport {

    private MemorySupport() {

    }

    public static long indexOf(MemorySegment segment, long start, byte b) {
        for (long l = start; l < segment.byteSize(); l++) {
            if (segment.getAtIndex(ValueLayout.JAVA_BYTE, l) == b) {
                return l;
            }
        }
        return -1;
    }

    /**
     *
     * @param segment
     * @param start
     * @param end exclusive
     * @return
     */
    public static long parseLong(MemorySegment segment, long start, long end, int radix) {
        long value = 0;
        for (long l = start; l < end; l++) {
            int v = segment.getAtIndex(ValueLayout.JAVA_BYTE, l) - '0';
            assert v >= 0 && v < radix : "illegal value at index " + l;
            value = value * radix + v;
        }
        return value;
    }
}