# RTB: Race To Binary

This static site presents interactive charts on C/C++ compiler (i.e GCC, Clang) compile times for
different source files across a large time window.
Additional information such as the packaged (compressed) compiler size is also available.

The idea is to track compiler *build time* regressions or improvements over different versions,
this is interesting due to how complex C/C++ has become over the years.

**Select a dataset on the top left ↖ to get started!**

### What is this written in?

This website is implemented in Scala 3 for both the static site generation and the *reactor*.

A standalone Scala program ingests a simple job script, for example:

```shell
#!/bin/bash
# RTB gcc llvm dpcpp
_fixture() { :; } # executed once per job
_setup() { :; }   # executed once for each compiler 
_test() {         # executed multiple times for each compiler
  set -o xtrace
  "$RTB_CXX" $RTB_EXTRA_FLAGS -std=c++17 -O3 -march=native -xc++ - <<EOF
#include <iostream>
int main(int argc, char *argv[]) {
   std::cout << "Hello, World!" << std::endl;
   return EXIT_SUCCESS;
}
EOF
}
_check() { ldd a.out; } # executed once after _test 
```

The script is sourced for each compiler snapshot, and the `_test` function is timed and executed
multiple times.

For the frontend, we use [Laminar](https://github.com/raquo/Laminar) together
with [Bulma](https://bulma.io/) for UI.
Most of the chart is implemented from scratch directly in SVG.