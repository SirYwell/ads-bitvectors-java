package de.sirywell.bitvectors;

import java.io.PrintStream;

public interface BitVector extends AutoCloseable {

    long rank(long index, int bit);

    long select(long rank, int bit);

    int access(long index);

    /**
     * {@return the number of used bytes}
     */
    long memoryUsage();

    @Override
    void close();

    void print(PrintStream output);
}
