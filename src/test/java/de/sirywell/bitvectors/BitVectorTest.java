package de.sirywell.bitvectors;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.util.function.Function;
import java.util.stream.Stream;

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
}