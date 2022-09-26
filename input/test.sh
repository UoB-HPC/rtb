#!/bin/bash

input_path=$1

# job
# workdir
#

export CXX=$(which g++)
export CC=$(which gcc)

# shellcheck disable=SC1090
source "$input_path"

input_filename=$(basename -- "$input_path")
input_name="${input_filename%.*}"

dir="/dev/shm/$input_name"

rm -rf "$dir"

echo "Using test dir: $dir"
mkdir -p "$dir"

output="$PWD/output/$input_name"

mkdir -p "$output"

: >"$output/log"
: >"$output/platform"
uname -a >>"$output/platform"
lscpu >>"$output/platform"

TIMEFORMAT='{"real":%R,"user":%U,"sys":%S}'
(
  cd "$dir" || (echo "Cannot enter $dir" && exit 1)
  echo "# setup" >>"$output/log"
  _setup &>>"$output/log"

  for i in {0..5}; do
    echo "# run $i" >>"$output/log"
    (
      set -o xtrace
      (time _test &>>"$output/log") &>"$output/time.$i.json"
    )
  done
  echo "# check" >>"$output/log"
  _check &>>"$output/log"
)

#ls -lah "$dir"
