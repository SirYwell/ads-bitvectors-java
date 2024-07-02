package de.sirywell.bitvectors;

import java.io.PrintStream;

public sealed interface BitVector permits EfficientBitVector, NaiveBitVector {

    long rank(long index, int bit);

    long select(long rank, int bit);

    int access(long index);

    /**
     * {@return the number of used bytes}
     */
    long memoryUsage();

    void print(PrintStream output);
}
