#!/bin/bash
# RTB dpcpp

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
    -std=c++17 -march=native -O3 -fsycl \
    -DNDEBUG -DSYCL \
    -I"$REPO/src/" -I"$REPO/src/sycl" \
    "$REPO/src/main.cpp" "$REPO/src/sycl/SYCLStream.cpp"
}

_check() {
  ldd a.out
}
