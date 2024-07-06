package de.sirywell.bitvectors;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Threshold;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

class BitVectorTest {

    static Stream<Arguments> bitVectorConstructors() {
        return Stream.of(
                Arguments.of(Named.of("naive", (Function<MemorySegment, BitVector>) (segment -> new NaiveBitVector(segment, segment.byteSize() * 8))))
        );
    }

    @ParameterizedTest
    @MethodSource("bitVectorConstructors")
    void testRankSmall(Function<MemorySegment, BitVector> constructor) {
        MemorySegment segment = MemorySegment.ofArray(new long[]{0, -1});
        BitVector bitVector = constructor.apply(segment);
        assertAll(
                () -> assertEquals(0, bitVector.rank(60, 1)),
                () -> assertEquals(60, bitVector.rank(60, 0)),
                () -> assertEquals(1, bitVector.rank(64, 1)),
                () -> assertEquals(2, bitVector.rank(65, 1)),
                () -> assertEquals(64, bitVector.rank(127, 1))
        );
    }

    @ParameterizedTest
    @MethodSource("bitVectorConstructors")
    void testAccessSmall(Function<MemorySegment, BitVector> constructor) {
        MemorySegment segment = MemorySegment.ofArray(new long[]{0b100110101110001L});
        BitVector bitVector = constructor.apply(segment);
        assertAll(
                () -> assertEquals(1, bitVector.access(0), "0"),
                () -> assertEquals(0, bitVector.access(1), "1"),
                () -> assertEquals(1, bitVector.access(4), "4"),
                () -> assertEquals(1, bitVector.access(5), "5"),
                () -> assertEquals(0, bitVector.access(7), "7")
        );
    }

    @Test
    void testEfficientAccessLarge() {
        MemorySegment source = MemorySegment.ofArray(new Random(0).longs(13371337).toArray());
        MemorySegment target = MemorySegment.ofArray(new long[13371337]);
        EfficientBitVector vector = EfficientBitVector.createEfficientBitVector(Arena.ofAuto(), source, source.byteSize() * 8);
        for (int i = 0; i < vector.bitSize(); i++) {
            long offset = i / 8;
            target.set(JAVA_BYTE, offset, (byte) (target.get(JAVA_BYTE, offset) | vector.access(i) << (i & 7)));
        }
        assertEquals(-1, source.mismatch(target));
    }

    @Test
    void testEfficientRankLarge() {
        long[] array = new Random(0).longs(13371337).toArray();
        // long[] array = LongStream.generate(() -> -1L).limit(13371337).toArray();
        MemorySegment source = MemorySegment.ofArray(array);
        long ones = 0;
        EfficientBitVector vector = EfficientBitVector.createEfficientBitVector(Arena.ofAuto(), source, source.byteSize() * 8);

        for (int i = 0; i < vector.bitSize(); i++) {
            if (vector.access(i) == 1) {
                ones++;
            }
            @Category("BitVector")
            @Name("Rank")
            @Threshold("2000 ns")
            class RankEvent extends Event {
                final long index;

                RankEvent(long index) {
                    this.index = index;
                }
            }
            RankEvent event = new RankEvent(i);
            event.begin();
            check(ones, vector, i);
            event.commit();
        }
    }

    private static void check(long ones, EfficientBitVector vector, int i) {
        assertEquals(ones, vector.rank(i, 1), "at index " + i);
        assertEquals(i - ones, vector.rank(i, 0));
    }
}