#!/bin/bash
# RTB dpcpp

_fixture() { :; }

_setup() { :; }

_test() {
  set -o xtrace
  "$RTB_CXX" $RTB_EXTRA_FLAGS -Wl,--unresolved-symbols=ignore-all \
    -std=c++17 -O3 \
    -fsycl -DCL_TARGET_OPENCL_VERSION=220 -DNDEBUG \
    -xc++ - <<EOF
#include <iostream>
#include <CL/sycl.hpp>

int main(int argc, char *argv[]) {
   std::cout << "Hello, World!" << std::endl;
   return EXIT_SUCCESS;
}
EOF
}

_check() {
  ldd a.out
}
