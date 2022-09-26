#!/bin/bash
# RTB dpcpp

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
    -std=c++17 -march=native -Ofast -fsycl \
    -DNDEBUG -DCL_TARGET_OPENCL_VERSION=220 -DSYCL -DUSE_PPWI=1,2,4,8,16,32,64,128 \
    -I"$REPO/src/sycl" \
    "$REPO/src/main.cpp"
}

_check() {
  ldd a.out
}
