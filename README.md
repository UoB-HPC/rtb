# RTB

RTB is a set of tools for tracking compilers metrics (e.g. compiler size, compile time, etc.) over a
large time window.
The idea is to track compiler build time regressions or improvements over different versions, this
is interesting due to how complex modern compilers (e.g. GCC and LLVM) has become over the years.

## Reactor

For example:

```shell
java -jar rtb-assembly.jar --providers gcc,llvm --runner local:my-job.job --repeat 3
```

## Webapp

The webapp presents interactive charts on the data collected from the reactor.
