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

import static de.sirywell.bitvectors.SimdSupport.BYTE_SPECIES;
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
    private static final StructLayout CACHE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName(RANK_SUPER_BLOCK_NAME),
            MemoryLayout.sequenceLayout(256, ValueLayout.JAVA_BYTE).withName(RANK_BLOCK_SEQUENCE_NAME)
    );
    // we can initialize the layout with a large size to use it for any rank rankLookup segment
    private static final SequenceLayout RANK_CACHE_LIST = MemoryLayout.sequenceLayout(Long.MAX_VALUE / CACHE_LAYOUT.byteSize(), CACHE_LAYOUT);
    private static final VarHandle RANK_SUPER_BLOCK_VALUE_HANDLE = insertCoordinates(RANK_CACHE_LIST.varHandle(
            MemoryLayout.PathElement.sequenceElement(),
            MemoryLayout.PathElement.groupElement(RANK_SUPER_BLOCK_NAME)
    ), 1, 0);
    private static final VarHandle RANK_BLOCK_VALUE_HANDLE = insertCoordinates(RANK_CACHE_LIST.varHandle(
            MemoryLayout.PathElement.sequenceElement(),
            MemoryLayout.PathElement.groupElement(RANK_BLOCK_SEQUENCE_NAME),
            MemoryLayout.PathElement.sequenceElement()
    ), 1, 0);
    private static final MethodHandle RANK_BLOCK_VALUE_OFFSET_HANDLE = insertArguments(RANK_CACHE_LIST.byteOffsetHandle(
            MemoryLayout.PathElement.sequenceElement(),
            MemoryLayout.PathElement.groupElement(RANK_BLOCK_SEQUENCE_NAME),
            MemoryLayout.PathElement.sequenceElement()
    ), 0, 0);
    private static final long RANK_SUPER_BLOCK_SIZE = 1 << 16; // in bits
    private static final long RANK_BLOCK_SIZE = 1 << 8; // in bits

    static EfficientBitVector createEfficientBitVector(Arena arena, MemorySegment segment, long bitSize) {
        long nOfSuperBlocks = Math.ceilDiv(bitSize, RANK_SUPER_BLOCK_SIZE);
        MemorySegment rankLookup = arena.allocate(CACHE_LAYOUT, nOfSuperBlocks);
        long onesSum = 0;
        for (long superBlock = 0; superBlock < nOfSuperBlocks; superBlock++) {
            RANK_SUPER_BLOCK_VALUE_HANDLE.set(rankLookup, superBlock, onesSum);
            for (
                    long block = superBlock * RANK_SUPER_BLOCK_SIZE, local = 0;
                    block < (superBlock + 1) * RANK_SUPER_BLOCK_SIZE;
                    block += BYTE_SPECIES.vectorBitSize(), local++
            ) {
                VectorMask<Byte> loadMask = BYTE_SPECIES.indexInRange(block, segment.byteSize());
                ByteVector vector = ByteVector.fromMemorySegment(BYTE_SPECIES, segment, block / 8, nativeOrder(), loadMask);
                byte unsignedBitCount = vector.lanewise(VectorOperators.BIT_COUNT).reduceLanes(VectorOperators.ADD);
                RANK_BLOCK_VALUE_HANDLE.set(rankLookup, superBlock, local, unsignedBitCount);
                onesSum += unsignedBitCount & 0xFF;
            }
        }
        return new EfficientBitVector(segment, rankLookup, bitSize);
    }

    @Override
    public long rank(long index, int bit) {
        assert (bit | 1) == 1 : "bit must be 0 or 1";
        assert index >= 0 && index < segment.byteSize() * Byte.SIZE : "rank must be in bounds";
        // we LOAD the vector byte wise - this allows us to directly exclude the last byte.
        // the last byte requires an additional mask [0, rank & 7] to not extract too many bits.
        // to count bits, we convert to long vectors - this allows us to do BIT_COUNT on longs.
        // doing BIT_COUNT on bytes would run into limits if one lane encounters 8 * 256 = 2048 bits
        // but using long, the limit is 64 * 2^64 per lane
        long superBlockIndex = index / RANK_SUPER_BLOCK_SIZE;
        long remainingBitsInSuperBlock = index % RANK_SUPER_BLOCK_SIZE;
        long onesBefore = (long) RANK_SUPER_BLOCK_VALUE_HANDLE.get(this.rankLookup, superBlockIndex);

        onesBefore += sumBlocksUntil(superBlockIndex, remainingBitsInSuperBlock);
        long targetByteIndex = index / Byte.SIZE;
        onesBefore += sumPartialBlock(targetByteIndex, index / RANK_BLOCK_SIZE);
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

    private long sumPartialBlock(long lastIncludedByteIndex, long actualBlockPos) {
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
        long width = BYTE_SPECIES.vectorByteSize();
        long blockBytesToProcess = remainingBitsInSuperBlock / RANK_BLOCK_SIZE; // TODO including or excluding??
        ShortVector h0 = processHalf(offset, width, offset + blockBytesToProcess);
        if (blockBytesToProcess > 128) {
            offset = offset + 8 * width;
            h0.add(processHalf(offset, width, offset + blockBytesToProcess));
        }
        return h0.reduceLanes(VectorOperators.ADD) & 0xFFFF;
    }

    private ShortVector processHalf(long offset, long width, long upperBound) {
        ByteVector a0 = byteVector(offset, 0, width, upperBound);
        ByteVector a1 = byteVector(offset, 1, width, upperBound);
        ByteVector a2 = byteVector(offset, 2, width, upperBound);
        ByteVector a3 = byteVector(offset, 3, width, upperBound);
        ByteVector a4 = byteVector(offset, 4, width, upperBound);
        ByteVector a5 = byteVector(offset, 5, width, upperBound);
        ByteVector a6 = byteVector(offset, 6, width, upperBound);
        ByteVector a7 = byteVector(offset, 7, width, upperBound);

        ShortVector d0 = sumPairwise(a0);
        ShortVector d1 = sumPairwise(a1);
        ShortVector d2 = sumPairwise(a2);
        ShortVector d3 = sumPairwise(a3);
        ShortVector d4 = sumPairwise(a4);
        ShortVector d5 = sumPairwise(a5);
        ShortVector d6 = sumPairwise(a6);
        ShortVector d7 = sumPairwise(a7);

        ShortVector e01 = d0.add(d1);
        ShortVector e23 = d2.add(d3);
        ShortVector e45 = d4.add(d5);
        ShortVector e67 = d6.add(d7);
        ShortVector f0123 = e01.add(e23);
        ShortVector f4567 = e45.add(e67);
        return f0123.add(f4567);
    }

    private ByteVector byteVector(long offset, int index, long width, long upperBound) {
        long fullIndex = offset + index * width;
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
