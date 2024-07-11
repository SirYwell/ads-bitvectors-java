package de.sirywell.bitvectors;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Generator for the inputs used in the document. All mentioned inputs were generated with seed 42.
 */
public class InputGenerator {

    public static void main(String[] args) throws IOException {
        Console console = System.console();
        int instr = Integer.parseInt(console.readLine("Instructions: "));
        long bvs = Long.parseLong(console.readLine("Bit Vector Size: "));
        String seed = console.readLine("Seed: ");
        int instructionMode = Integer.parseInt(console.readLine("Instruction Mode (-1: All, 0: Access, 1: Rank, 2: Select): "));
        String fileName = console.readLine("File Name: ");

        RandomGenerator rng = RandomGeneratorFactory.getDefault().create(seed.getBytes(StandardCharsets.UTF_8));
        Path path = Path.of(fileName);
        try (var writer = Files.newBufferedWriter(path)) {
            writer.append(String.valueOf(instr)).append("\n");
            for (long l = 0; l < bvs; l++) {
                writer.write(rng.nextBoolean() ? "0" : "1");
            }
            writer.write("\n");
            for (int i = 0; i < instr; i++) {
                switch (instructionMode == -1 ? rng.nextInt(3) : instructionMode) {
                    case 0 -> writer.append("access ").append(String.valueOf(rng.nextLong(bvs)));
                    case 1 -> writer.append("rank ")
                            .append(String.valueOf(rng.nextInt(2)))
                            .append(" ").append(String.valueOf(rng.nextLong(bvs)));
                    case 2 -> writer.append("select ")
                            .append(String.valueOf(rng.nextInt(2)))
                            .append(" ").append(String.valueOf(rng.nextLong(1, bvs)));
                }
                writer.append("\n");
            }
        }
    }
}
