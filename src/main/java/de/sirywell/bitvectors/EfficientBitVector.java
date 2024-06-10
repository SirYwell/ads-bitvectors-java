package de.sirywell.bitvectors;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;

import java.io.PrintStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static java.nio.ByteOrder.nativeOrder;

// TODO
public record EfficientBitVector(MemorySegment segment, long bitSize) implements BitVector {

    public EfficientBitVector {
        assert bitSize / 8 == segment.byteSize() : "bit size must match byte size";
    }

    @Override
    public long rank(long index, int bit) {
        assert (bit | 1) == 1 : "bit must be 0 or 1";
        assert index >= 0 && index < segment.byteSize() * Byte.SIZE : "index must be in bounds";
        // we LOAD the vector byte wise - this allows us to directly exclude the last byte.
        // the last byte requires an additional mask [0, index & 7] to not extract too many bits.
        // to count bits, we convert to long vectors - this allows us to do BIT_COUNT on longs.
        // doing BIT_COUNT on bytes would run into limits if one lane encounters 8 * 256 = 2048 bits
        // but using long, the limit is 64 * 2^64 per lane
        LongVector sum = LongVector.zero(SimdSupport.LONG_SPECIES);
        long targetByteIndex = index / Byte.SIZE;
        for (long currentByteIndex = 0; currentByteIndex < targetByteIndex; currentByteIndex += SimdSupport.LONG_SPECIES.vectorByteSize()) {
            VectorMask<Byte> loadMask = SimdSupport.BYTE_SPECIES.indexInRange(0, targetByteIndex);
            LongVector manyBits = ByteVector.fromMemorySegment(
                            SimdSupport.BYTE_SPECIES,
                            segment,
                            currentByteIndex,
                            nativeOrder(),
                            loadMask)
                    .reinterpretAsLongs();
            sum = sum.add(manyBits.lanewise(VectorOperators.BIT_COUNT));

        }
        long ones = sum.reduceLanes(VectorOperators.ADD);
        byte last = segment.get(ValueLayout.JAVA_BYTE, targetByteIndex);
        // take the lowest bits only, but index is inclusive
        int inclusiveMask = ~(-1 << (((index & 7) + 1)));
        int add = Integer.bitCount(last & inclusiveMask);
        ones += add;
        if (bit == 0) {
            return index - ones;
        }
        return ones;
    }

    @Override
    public long select(long rank, int bit) {
        // extremely naive... maybe at least use binary search?
        for (long i = 0; i < bitSize; i++) {
            if (rank(i, bit) == rank) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int access(long index) {
        byte b = segment.get(ValueLayout.JAVA_BYTE, index / Byte.SIZE);
        // compress is slow on some (micro) architectures, maybe replace?
        return Integer.compress(b, 1 << (index & 7));
    }

    @Override
    public long memoryUsage() {
        return segment.byteSize();
    }

    @Override
    public void close() {

    }

    @Override
    public void print(PrintStream output) {
        for (long l = 0; l < bitSize; l++) {
            output.print(access(l));
        }
    }
}
