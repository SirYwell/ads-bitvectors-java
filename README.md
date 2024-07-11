# Requirements

This project requires Java 22 to compile and to run.
Java 22 can be installed using https://sdkman.io/, or the packet manager of your OS, or manually
by downloading it from any vendor (e.g. https://jdk.java.net/22/).
GraalVM is *not* recommended due to lacking Vector API support.

# Building

The project can be built by running
```shell
./gradlew build
```

# Running

Once the project is compiled, you can use the `run.sh` to simply run it:
```shell
./run.sh <input-file> <output-file>
```

# Project Overview

The relevant file is [EfficientBitVector.java](/src/main/java/de/sirywell/bitvectors/EfficientBitVector.java).
