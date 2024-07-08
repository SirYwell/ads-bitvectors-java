package de.sirywell.bitvectors;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;

import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SequenceLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.BitSet;

import static de.sirywell.bitvectors.SimdSupport.BYTE_SPECIES;
import static de.sirywell.bitvectors.SimdSupport.LONG_SPECIES;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.insertCoordinates;
import static java.nio.ByteOrder.nativeOrder;

/**
 * This is an implementation of a bit vector with efficient {@code rank} and {@code select} lookups.
 * Fast {@code rank} rankLookup is achieved by a 2-layered rankLookup mechanism.
 * For each 65536 bit block ({@code level0}), we store a 64-bit number with the count of {@code 1} bits
 * from the beginning of the bit vector up to (exclusive) the block.
 * For each 256 bit block ({@code level1}), we store an 8-bit number with the count of {@code 1} bits
 * in the current block.
 *
 * @param segment
 * @param rankLookup
 * @param bitSize
 */
record EfficientBitVector(MemorySegment segment, MemorySegment rankLookup, long bitSize) implements BitVector {
    public static final String RANK_SUPER_BLOCK_NAME = "superBlock";
    public static final String RANK_BLOCK_SEQUENCE_NAME = "blockSequence";
    public static final String RANK_BLOCK_OVERFLOW = "overflow";
    private static final StructLayout CACHE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName(RANK_SUPER_BLOCK_NAME),
            MemoryLayout.sequenceLayout(256, ValueLayout.JAVA_BYTE).withName(RANK_BLOCK_SEQUENCE_NAME),
            MemoryLayout.sequenceLayout(32, ValueLayout.JAVA_BYTE).withName(RANK_BLOCK_OVERFLOW)
    );
    // we can initialize the layout with a large size to use it for any rank rankLookup segment
    private static final SequenceLayout RANK_CACHE_LIST = MemoryLayout.sequenceLayout(
            Long.MAX_VALUE / CACHE_LAYOUT.byteSize(),
            CACHE_LAYOUT
    );
    private static final VarHandle RANK_SUPER_BLOCK_VALUE_HANDLE = insertCoordinates(RANK_CACHE_LIST.varHandle(
            MemoryLayout.PathElement.sequenceElement(),
            MemoryLayout.PathElement.groupElement(RANK_SUPER_BLOCK_NAME)
    ), 1, 0);
    private static final VarHandle RANK_BLOCK_VALUE_HANDLE = insertCoordinates(RANK_CACHE_LIST.varHandle(
            MemoryLayout.PathElement.sequenceElement(),
            MemoryLayout.PathElement.groupElement(RANK_BLOCK_SEQUENCE_NAME),
            MemoryLayout.PathElement.sequenceElement()
    ), 1, 0);
    private static final MethodHandle RANK_BLOCK_VALUE_OFFSET_HANDLE = insertArguments(
            RANK_CACHE_LIST.byteOffsetHandle(
                    MemoryLayout.PathElement.sequenceElement(),
                    MemoryLayout.PathElement.groupElement(RANK_BLOCK_SEQUENCE_NAME),
                    MemoryLayout.PathElement.sequenceElement()
            ), 0, 0);
    private static final MethodHandle RANK_BLOCK_OVERFLOW_BITSET_OFFSET_HANDLE = insertArguments(
            RANK_CACHE_LIST.byteOffsetHandle(
                    MemoryLayout.PathElement.sequenceElement(),
                    MemoryLayout.PathElement.groupElement(RANK_BLOCK_OVERFLOW)
            ), 0, 0);
    private static final long RANK_SUPER_BLOCK_SIZE = 1 << 16; // in bits
    private static final long RANK_BLOCK_SIZE = 1 << 8; // in bits
    public static final ShortVector ZERO = ShortVector.zero(SimdSupport.SHORT_SPECIES);
    public static final ByteOrder ORDER = nativeOrder();

    static EfficientBitVector createEfficientBitVector(Arena arena, MemorySegment segment, long bitSize) {
        long nOfSuperBlocks = Math.ceilDiv(bitSize, RANK_SUPER_BLOCK_SIZE);
        MemorySegment rankLookup = arena.allocate(CACHE_LAYOUT, nOfSuperBlocks);
        long onesSum = 0;
        BitSet overflow = new BitSet(256);
        for (long superBlock = 0; superBlock < nOfSuperBlocks; superBlock++) {
            RANK_SUPER_BLOCK_VALUE_HANDLE.set(rankLookup, superBlock, onesSum);
            for (
                    long block = superBlock * RANK_SUPER_BLOCK_SIZE, local = 0;
                    block < (superBlock + 1) * RANK_SUPER_BLOCK_SIZE;
                    block += BYTE_SPECIES.vectorBitSize(), local++
            ) {
                long offset = block / 8;
                VectorMask<Byte> loadMask = BYTE_SPECIES.indexInRange(offset, segment.byteSize());
                ByteVector vector = ByteVector.fromMemorySegment(BYTE_SPECIES, segment, offset, ORDER, loadMask);
                long unsignedBitCount = vector.reinterpretAsLongs()
                        .lanewise(VectorOperators.BIT_COUNT)
                        .reduceLanes(VectorOperators.ADD);
                if (unsignedBitCount == 256) {
                    overflow.set((int) local);
                }
                RANK_BLOCK_VALUE_HANDLE.set(rankLookup, superBlock, local, (byte) unsignedBitCount);
                onesSum += unsignedBitCount;
            }
            flushOverflowBitset(overflow, superBlock, rankLookup);
        }
        return new EfficientBitVector(segment, rankLookup, bitSize);
    }

    private static void flushOverflowBitset(BitSet overflow, long superBlock, MemorySegment rankLookup) {
        long[] array = overflow.toLongArray();
        long offset = rankOverflowOffset(superBlock);
        MemorySegment.copy(array, 0, rankLookup, ValueLayout.JAVA_LONG, offset, array.length);
        overflow.clear();
    }

    private static long rankOverflowOffset(long superBlock) {
        try {
            return (long) RANK_BLOCK_OVERFLOW_BITSET_OFFSET_HANDLE.invokeExact(superBlock);
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public long rank(long index, int bit) {
        assert (bit | 1) == 1 : "bit must be 0 or 1";
        assert index >= 0 && index < segment.byteSize() * Byte.SIZE : "rank must be in bounds";
        long superBlockIndex = index / RANK_SUPER_BLOCK_SIZE;
        long remainingBitsInSuperBlock = index % RANK_SUPER_BLOCK_SIZE;
        long onesBefore = (long) RANK_SUPER_BLOCK_VALUE_HANDLE.get(this.rankLookup, superBlockIndex);

        onesBefore += sumBlocksUntil(superBlockIndex, remainingBitsInSuperBlock);
        long targetByteIndex = index / Byte.SIZE; // flooring div
        onesBefore += countBits(targetByteIndex, index / RANK_BLOCK_SIZE);
        byte last = segment.get(ValueLayout.JAVA_BYTE, targetByteIndex);
        // take the lowest bits only, but rank is inclusive
        int inclusiveMask = ~(-1 << (((index & 7) + 1)));
        int add = Integer.bitCount(last & inclusiveMask);
        long ones = onesBefore + add;
        if (bit == 0) {
            return index - ones;
        }
        return ones;
    }

    private long countBits(long lastIncludedByteIndex, long actualBlockPos) {
        long blockIndex = actualBlockPos * BYTE_SPECIES.vectorByteSize();
        VectorMask<Byte> loadMask = BYTE_SPECIES.indexInRange(blockIndex, lastIncludedByteIndex);
        LongVector manyBits = ByteVector.fromMemorySegment(
                BYTE_SPECIES,
                segment,
                blockIndex,
                nativeOrder(),
                loadMask
        ).reinterpretAsLongs();

        return manyBits.lanewise(VectorOperators.BIT_COUNT).reduceLanes(VectorOperators.ADD);
    }

    private long sumBlocksUntil(long superBlockIndex, long remainingBitsInSuperBlock) {
        long offset = valueOffsetStart(superBlockIndex);
        long blockBytesToProcess = remainingBitsInSuperBlock / RANK_BLOCK_SIZE; // exclusive bound
        ShortVector h = ZERO;
        for (long n = 0; n < blockBytesToProcess; n += BYTE_SPECIES.vectorByteSize()) {
            ByteVector vector = byteVector(offset, n, offset + blockBytesToProcess);
            h = h.add(sumPairwise(vector));
        }
        long overflowOffset = rankOverflowOffset(superBlockIndex);
        long longsToProcess = blockBytesToProcess / Long.SIZE;
        long vectorBitMask = (1L << longsToProcess) - 1;
        VectorMask<Long> fullLongs = VectorMask.fromLong(LONG_SPECIES, vectorBitMask);
        LongVector overflowLongs = LongVector
                .fromMemorySegment(LONG_SPECIES, rankLookup, overflowOffset, ORDER, fullLongs)
                .lanewise(VectorOperators.BIT_COUNT);
        long overflowBits = (1L << blockBytesToProcess % Long.SIZE) - 1;
        long value = rankLookup.get(ValueLayout.JAVA_LONG, overflowOffset + longsToProcess * Long.BYTES);
        int trailing = Long.bitCount(overflowBits & value);
        long overflow = (overflowLongs.reduceLanes(VectorOperators.ADD) + trailing) * 256;
        return (h.reduceLanes(VectorOperators.ADD) & 0xFFFF) + overflow;
    }

    private ByteVector byteVector(long offset, long index, long upperBound) {
        long fullIndex = offset + index;
        VectorMask<Byte> loadMask = BYTE_SPECIES.indexInRange(fullIndex, upperBound);
        return ByteVector.fromMemorySegment(BYTE_SPECIES, rankLookup, fullIndex, nativeOrder(), loadMask);
    }

    private static ShortVector sumPairwise(ByteVector a) {
        // a = [a0, a1, a2, a3, a4,     ...]
        // ->
        // b = [ a0s  ,  a2s  ,  a4s  , ...]
        // +
        // c = [ a1s  ,  a3s  ,  a5s  , ...]
        // = result
        ShortVector b = a.reinterpretAsShorts().and((short) 0xFF);
        ShortVector c = a.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8).and((short) 0xFF);
        return b.add(c);
    }

    private static long valueOffsetStart(long superBlockIndex) {
        try {
            return (long) RANK_BLOCK_VALUE_OFFSET_HANDLE.invokeExact(superBlockIndex, 0L);
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
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
        return (b >>> (index & 7)) & 1;
    }

    @Override
    public long memoryUsage() {
        return segment.byteSize();
    }

    @Override
    public void print(PrintStream output) {
        for (long l = 0; l < bitSize; l++) {
            output.print(access(l));
        }
    }
}
