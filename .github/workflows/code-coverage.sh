#!/usr/bin/env bash

set -e

[ -f target/site/jacoco/jacoco.csv ]
INSTRUCTION_MISSED=$(cut -d, -f4 target/site/jacoco/jacoco.csv | tail -n+2 | paste -sd+ | bc)
INSTRUCTION_COVERED=$(cut -d, -f5 target/site/jacoco/jacoco.csv | tail -n+2 | paste -sd+ | bc)
INSTRUCTION_TOTAL=$((INSTRUCTION_MISSED + INSTRUCTION_COVERED))
COVERAGE=$(echo "$INSTRUCTION_COVERED * 1.0 / $INSTRUCTION_TOTAL" | bc -l)
THRESHOLD=0.8
FAILED=$(echo "$COVERAGE<$THRESHOLD" | bc -l)
echo Code coverage is $COVERAGE
if [ "$FAILED" -eq "1" ]; then
    echo Code coverage $COVERAGE does not exceed threshold $THRESHOLD. >&2
    exit 1
fi
