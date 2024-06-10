package de.sirywell.bitvectors.instruction;

import de.sirywell.bitvectors.BitVector;

public sealed interface Instruction permits AccessInstruction, RankInstruction, SelectInstruction {

    long run(BitVector bitVector);
}
