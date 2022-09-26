package uob_hpc.rtb

import java.time.{LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

object SnapshotProviders {
  
  val DPCPP = new GHReleaseProvider(
    name = "dpcpp",
    repoAndOwner = "intel/llvm",
    tagsToKeys = releaseTags =>
      releaseTags
        .collect { case tag @ s"sycl-nightly/$basicIsoDate" =>
          tag -> LocalDate
            .parse(basicIsoDate, DateTimeFormatter.BASIC_ISO_DATE)
            .atStartOfDay(ZoneOffset.UTC)
        }
        .groupBy { case (_, d) => d.get(IsoFields.WEEK_BASED_YEAR) -> d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
        .toVector
        .sortBy(_._1)
        .flatMap { case (_, xs) => xs.maxByOption(_._2.toEpochSecond) } // last snapshot of the week
        .map { case (tag, date) => tag -> Key("dpcpp", "nightly", date.toLocalDate, None) },
    tagAndKeyToAssetUri = (_, _) => "dpcpp-compiler.tar.gz",
    pathAndKeyToPrelude = (p, _) => s"""
         |tar xf $p
         |
         |export root="$$PWD/dpcpp_compiler"
		 |echo "Compiler root:$$root"
		 |
         |export PATH="$$root/bin:$${PATH:-}"
         |export CPATH="$$root/include:$${CPATH:-}"
         |export LIBRARY_PATH="$$root/lib:$${LIBRARY_PATH:-}"
         |
         |export RTB_CXX="$$root/bin/clang++"
         |export RTB_CC="$$root/bin/clang"
		 |
         |"$$RTB_CXX" -v
         |""".stripMargin
  )

  val LLVM = new GHReleaseProvider(
    name = "llvm",
    repoAndOwner = "tom91136/snapshots",
    tagsToKeys = _.collect {
      case tag @ s"$name-$version+${isoBasicDate}Z+$commit" if name == "llvm" =>
        tag -> Key(name, version, LocalDate.parse(isoBasicDate), Some(commit))
    },
    tagAndKeyToAssetUri = (tag, _) => s"${tag.replace('+', '.')}.tar.xz",
    pathAndKeyToPrelude = (p, _) => s"""
         |tar xf $p
         |
         |root=$$(readlink -f opt/*)
         |echo "Compiler root:$$root"
         |
         |export PATH="$$root/bin:$${PATH:-}"
         |export CPATH="$$root/include:$${CPATH:-}"
         |export LIBRARY_PATH="$$root/lib:$${LIBRARY_PATH:-}"
         |
         |export RTB_CXX="$$root/bin/clang++"
         |export RTB_CC="$$root/bin/clang"
         |
         |"$$RTB_CXX" -v
         |""".stripMargin
  )

  val GCC = new GHReleaseProvider(
    name = "gcc",
    repoAndOwner = "tom91136/snapshots",
    tagsToKeys = _.collect {
      case tag @ s"$name-$version+${isoBasicDate}Z+$commit" if name == "gcc" =>
        tag -> Key(name, version, LocalDate.parse(isoBasicDate), Some(commit))
    },
    tagAndKeyToAssetUri = (tag, _) => s"${tag.replace('+', '.')}.tar.xz",
    pathAndKeyToPrelude = (p, _) => s"""
         |tar xf $p
         |
         |root=$$(readlink -f opt/*)
         |echo "Compiler root:$$root"
         |
         |# Ubuntu doesn't find crt{1,i}.so unless we add it to library path manually
         |if [[ -d "/usr/lib/x86_64-linux-gnu" ]]; then
         |  export LIBRARY_PATH="$${LIBRARY_PATH:-}:/usr/lib/x86_64-linux-gnu"
         |fi
         |
         |export PATH="$$root/bin:$${PATH:-}"
         |export CPATH="$$root/include:$${CPATH:-}"
         |export LIBRARY_PATH="$$root/lib:$${LIBRARY_PATH:-}"
         |
         |export RTB_CXX="$$root/bin/g++"
         |export RTB_CC="$$root/bin/gcc"
         |
         |"$$RTB_CXX" -v
         |""".stripMargin
  )

  val All: Vector[GHReleaseProvider] = Vector(DPCPP, GCC, LLVM)

}
