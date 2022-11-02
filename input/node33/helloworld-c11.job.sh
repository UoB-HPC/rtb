#!/bin/bash
# RTB gcc llvm dpcpp

# A basic hello world baseline in C

_fixture() { :; }

_setup() { :; }

_test() {
  set -o xtrace
  "$RTB_CC" $RTB_EXTRA_FLAGS -std=c11 -O3 -march=native -xc - <<EOF
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char *argv[]) {
   printf("Hello, World!\n");
   return EXIT_SUCCESS;
}
EOF
}

_check() {
  ldd a.out
}
