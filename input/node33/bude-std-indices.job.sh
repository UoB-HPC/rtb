#!/bin/bash
# RTB gcc llvm

# This job compiles the C++17 PSTL (via std::for_each and a parallel execution policy) version of
# miniBUDE, the specific source file used is available at:
# https://github.com/UoB-HPC/miniBUDE/blob/ecd62a6/src/std-indices/fasten.hpp

gh_owner="UoB-HPC"
gh_repo="miniBUDE"
commit="ecd62a6"
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
    -std=c++17 -march=native -Ofast \
    -DNDEBUG -DSTD_INDICES -DUSE_PPWI=1,2,4,8,16,32,64,128 \
    -I"$REPO/src/std-indices" \
    "$REPO/src/main.cpp"
}

_check() {
  ldd a.out
}
