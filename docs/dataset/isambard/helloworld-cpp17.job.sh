#!/bin/bash
# RTB gcc llvm dpcpp

_fixture() { :; }

_setup() { :; }

_test() {
  set -o xtrace
  "$RTB_CXX" $RTB_EXTRA_FLAGS -std=c++17 -O3 -march=native -xc++ - <<EOF
#include <iostream>

int main(int argc, char *argv[]) {
   std::cout << "Hello, World!" << std::endl;
   return EXIT_SUCCESS;
}
EOF
}

_check() {
  ldd a.out
}
