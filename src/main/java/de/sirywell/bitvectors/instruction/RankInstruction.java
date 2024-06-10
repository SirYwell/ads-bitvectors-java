package de.sirywell.bitvectors.instruction;

import de.sirywell.bitvectors.BitVector;

public record RankInstruction(long index, int bit) implements Instruction {
    @Override
    public long run(BitVector bitVector) {
        return bitVector.rank(index, bit);
    }
}
