#!/bin/bash
# RTB gcc llvm

# This job compiles the OpenMP version of BabelStream, the specific source file used is available at:
# https://github.com/UoB-HPC/BabelStream/blob/1b67999/src/omp/OMPStream.cpp

gh_owner="UoB-HPC"
gh_repo="BabelStream"
commit="1b67999"
archive="${gh_repo}_${commit}.zip"

_fixture() {
  if [[ ! -f "$archive" ]]; then
    wget "https://github.com/$gh_owner/$gh_repo/archive/$commit.zip" -O "$archive"
  else
    echo "Cache hit for $archive"
  fi
}

_setup() {
  unzip -q "$archive"
  mv ${gh_repo}-* repo
  export REPO="$PWD/repo"
  echo "Repo: $REPO"
}

_test() {
  set -o xtrace
  "$RTB_CXX" $RTB_EXTRA_FLAGS -Wl,--unresolved-symbols=ignore-all \
    -std=c++17 -march=native -O3 -fopenmp \
    -DNDEBUG -DOMP \
    -I"$REPO/src/" -I"$REPO/src/omp" \
    "$REPO/src/main.cpp" "$REPO/src/omp/OMPStream.cpp"
}

_check() {
  ldd a.out
}
