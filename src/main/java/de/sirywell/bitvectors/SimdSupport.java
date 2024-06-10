package de.sirywell.bitvectors;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;

public final class SimdSupport {
    public static final VectorSpecies<Long> LONG_SPECIES = LongVector.SPECIES_PREFERRED;
    public static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

}
