package de.sirywell.bitvectors.instruction;

import de.sirywell.bitvectors.BitVector;

public record AccessInstruction(long index) implements Instruction {
    @Override
    public long run(BitVector bitVector) {
        return bitVector.access(index);
    }
}
