package de.sirywell.bitvectors;

import de.sirywell.bitvectors.instruction.AccessInstruction;
import de.sirywell.bitvectors.instruction.Instruction;
import de.sirywell.bitvectors.instruction.RankInstruction;
import de.sirywell.bitvectors.instruction.SelectInstruction;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SequenceLayout;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

public class Main {
    private static final byte NEWLINE = '\n';

    static volatile Object escape;
    public static void main(String[] args) throws IOException {
        assert args.length == 2 : "usage: <input_file> <output_file>";
        Path inputFile = Path.of(args[0]);
        Path outputFile = Path.of(args[1]);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bitVectorSegment;
            Instruction[] instructions;
            long vecLen;
            try (FileChannel fileChannel = FileChannel.open(inputFile, StandardOpenOption.READ)) {
                MemorySegment file = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size(), arena);
                long firstLineEnd = MemorySupport.indexOf(file, 0, NEWLINE);
                long n = MemorySupport.parseLong(file, 0, firstLineEnd, 10);
                long vecStart = firstLineEnd + 1;
                // we can either go through the file twice
                // or dynamically increase the size of the bitvector...
                // let's do the former
                long vecEnd = MemorySupport.indexOf(file, vecStart, NEWLINE);
                vecLen = vecEnd - firstLineEnd - 1;
                bitVectorSegment = loadBitVector(vecLen, arena, vecEnd, vecStart, file);
                long instructionsStart = vecEnd + 1;
                instructions = parseInstructions(instructionsStart, file, n);
                file.unload();
            }
            Instant start = Instant.now();
            BitVector bitVector = EfficientBitVector.createEfficientBitVector(arena, bitVectorSegment, vecLen);
            long[] results = runAll(instructions, bitVector);
            Duration duration = Duration.between(start, Instant.now());
            String collect = Arrays.stream(results)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining(System.lineSeparator()));
            Files.writeString(outputFile, collect);
            System.out.println("RESULT name=hannes_greule time=" + duration.toMillis() + " space=" + bitVector.memoryUsage() * Byte.SIZE);
        }
    }

    private static long[] runAll(Instruction[] instructions, BitVector bitVector) {
        long[] results = new long[instructions.length];
        for (int stress = 0; stress < Integer.getInteger("ads.stress", 1); stress++) {
            for (int i = 0; i < instructions.length; i++) {
                // we don't use a polymorphic method here because C2 only inlines call sites with <= 2 types
                // ref: https://shipilev.net/blog/2015/black-magic-method-dispatch/
                // but we want optimizations to recognize bitVector as constant
                results[i] = switch (instructions[i]) {
                    case AccessInstruction(long index) -> bitVector.access(index);
                    case RankInstruction(long index, int bit) -> bitVector.rank(index, bit);
                    case SelectInstruction(long rank, int bit) -> bitVector.select(rank, bit);
                };
            }
            escape = results;
        }
        return results;
    }

    private static MemorySegment loadBitVector(long vecLen, Arena arena, long vecEnd, long vecStart, MemorySegment file) {
        // alignment chosen in hope for most efficient loads
        SequenceLayout layout = MemoryLayout.sequenceLayout(Math.ceilDiv(vecLen, 64), ValueLayout.JAVA_LONG)
                .withByteAlignment(64);
        MemorySegment bitVectorSegment = arena.allocate(layout);
        for (long l = 0; l < vecLen; l += 64) {
            long end = Math.min(vecEnd, vecStart + l + 64);
            long next = MemorySupport.parseLong(file, vecStart + l, end, 2);
            next = Long.reverse(next);
            if (end == vecEnd) {
                next >>>= (64 - vecLen);
            }
            // 7 6 5 4 3 2 1 0 15 14 13 12 11 10 9 8 ... 63 62 61 60 59 58 57 56
            bitVectorSegment.set(ValueLayout.JAVA_LONG, l / 8, next);
        }
        return bitVectorSegment;
    }

    private static Instruction[] parseInstructions(long instructionsStart, MemorySegment file, long n) {
        Instruction[] instructions = new Instruction[Math.toIntExact(n)];
        long lineStart = instructionsStart;
        for (int i = 0; i < n; i++) {
            switch (file.getAtIndex(ValueLayout.JAVA_BYTE, lineStart)) {
                case 'a' -> {
                    long offset = lineStart + 7;
                    long end = MemorySupport.indexOf(file, offset, NEWLINE);
                    long index = MemorySupport.parseLong(file, offset, end, 10);
                    instructions[i] = new AccessInstruction(index);
                    lineStart = end + 1;
                }
                case 'r' -> {
                    long offset = lineStart + 5;
                    int bit = (int) MemorySupport.parseLong(file, offset, offset + 1, 2);
                    offset += 2;
                    long end = MemorySupport.indexOf(file, offset, NEWLINE);
                    long index = MemorySupport.parseLong(file, offset, end, 10);
                    instructions[i] = new RankInstruction(index, bit);
                    lineStart = end + 1;
                }
                case 's' -> {
                    long offset = lineStart + 7;
                    int bit = (int) MemorySupport.parseLong(file, offset, offset + 1, 2);
                    offset += 2;
                    long end = MemorySupport.indexOf(file, offset, NEWLINE);
                    long index = MemorySupport.parseLong(file, offset, end, 10);
                    instructions[i] = new SelectInstruction(index, bit);
                    lineStart = end + 1;
                }
            }
        }
        return instructions;
    }
}