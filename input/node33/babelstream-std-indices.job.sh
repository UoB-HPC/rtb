#!/bin/bash
# RTB gcc>=10 llvm

# This job compiles the C++17 PSTL (via std::transform and a parallel execution policy) version of
# BabelStream, the specific source file used is available at:
# https://github.com/UoB-HPC/BabelStream/blob/1b67999/src/std-indices/STDIndicesStream.cpp

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
    -std=c++17 -march=native -O3 \
    -DNDEBUG -DSTD_INDICES \
    -I"$REPO/src/" -I"$REPO/src/std-indices" \
    "$REPO/src/main.cpp" "$REPO/src/std-indices/STDIndicesStream.cpp"
}

_check() {
  ldd a.out
}
