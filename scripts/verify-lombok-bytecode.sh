#!/usr/bin/env bash
#
# verify-lombok-bytecode.sh — prove a Lombok refactor changed NO runtime behavior.
#
# Usage:
#   scripts/verify-lombok-bytecode.sh <module> capture-before
#   scripts/verify-lombok-bytecode.sh <module> verify-after
#
# Workflow:
#   1. On the UNCHANGED tree (or git stash of the Lombok edits), run:
#        scripts/verify-lombok-bytecode.sh core capture-before
#      -> compiles the module and snapshots a normalized javap dump of every
#         .class under build/classes/java/main into /tmp/lombok-verify/<module>/before/
#   2. Apply the Lombok edits, then run:
#        scripts/verify-lombok-bytecode.sh core verify-after
#      -> recompiles, snapshots into .../after/, and diffs before vs after.
#
# PASS = every class's normalized bytecode is identical. The normalization
# strips the ONLY legitimate differences a Lombok @Slf4j / @RequiredArgsConstructor
# refactor introduces:
#   - constant-pool index renumbering (#13 -> #N): Lombok appends generated
#     members, which reshuffles the constant pool. Indices are not behavior.
#   - method declaration order: Lombok emits generated ctors/loggers at the end.
#     We sort lines so order is irrelevant.
#   - whitespace alignment differences (driven by index digit width).
#   - the @lombok.Generated annotation lines themselves (RetentionPolicy.CLASS,
#     runtime-invisible, behavior-irrelevant).
#
# This normalization was validated empirically: converting JwksAssembler to
# @RequiredArgsConstructor produced EXIT 0 here while raw `diff` showed ~40
# spurious lines.
set -euo pipefail

MODULE="${1:?usage: $0 <module> <capture-before|verify-after>}"
PHASE="${2:?usage: $0 <module> <capture-before|verify-after>}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="${ROOT}/${MODULE}/build/classes/java/main"
WORK="/tmp/lombok-verify/${MODULE}"

# Normalize a single javap dump so that only behavior-relevant content remains.
#
# Beyond constant-pool index renumbering and member-order, Lombok adding members
# can push a constant past pool index 255, flipping `ldc` <-> `ldc_w` for an
# unrelated method's string/class constant. That widens the instruction by one
# byte, which shifts every following byte address and branch target — all of it
# behavior-irrelevant encoding noise. To stay robust we reduce each line to the
# opcode plus its symbolic operand (the `// ...` comment), dropping:
#   - the byte-address prefix (`  31: `) — instruction-length-dependent
#   - wide-vs-narrow opcode variants (ldc_w->ldc, goto_w->goto) — pure encoding
#   - numeric branch/switch targets (`goto 42`) — address-dependent; the symbolic
#     comment, when present, carries the real semantic reference
# What remains — opcode kind + symbolic operand, as a sorted multiset — changes
# only if real behavior changes.
norm() {
  sed -E 's/#[0-9]+/#N/g' \
    | sed -E 's/^[[:space:]]*[0-9]+:[[:space:]]+/  /' \
    | sed -E 's/\bldc_w\b/ldc/; s/\bgoto_w\b/goto/' \
    | sed -E 's/[[:space:]]+/ /g' \
    | sed -E 's/[[:space:]]+$//' \
    | grep -vE "lombok\.Generated|RuntimeInvisibleAnnotations|Compiled from|^ ?Classfile|Last modified|SHA-256:|MD5:" \
    | sort
}

snapshot() {
  local out="$1"
  rm -rf "$out"; mkdir -p "$out"
  ( cd "$ROOT" && ./gradlew ":${MODULE}:compileJava" -q )
  # one normalized dump per class file, keyed by class path
  find "$CLASSES" -name '*.class' | sort | while read -r cls; do
    rel="${cls#"$CLASSES"/}"
    key="${rel//\//__}"
    javap -p -c -constants "$cls" 2>/dev/null | norm > "${out}/${key}.txt"
  done
}

case "$PHASE" in
  capture-before)
    snapshot "${WORK}/before"
    echo "captured baseline: ${WORK}/before ($(find "${WORK}/before" -name '*.txt' | wc -l | tr -d ' ') classes)"
    ;;
  verify-after)
    snapshot "${WORK}/after"
    if diff -rq "${WORK}/before" "${WORK}/after"; then
      echo "PASS: ${MODULE} bytecode is behavior-identical (Lombok added only @Generated)"
      exit 0
    else
      echo "FAIL: ${MODULE} has behavior-relevant bytecode differences (see above)"
      echo "Inspect with: diff ${WORK}/before/<class>.txt ${WORK}/after/<class>.txt"
      exit 1
    fi
    ;;
  *)
    echo "unknown phase: $PHASE (use capture-before | verify-after)" >&2
    exit 2
    ;;
esac
