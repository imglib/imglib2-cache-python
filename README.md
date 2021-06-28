[![Build status](https://github.com/hanslovsky/imglib2-cache-python/actions/workflows/build.yaml/badge.svg)](https://github.com/hanslovsky/imglib2-cache-python/actions/workflows/build.yaml)

# ImgLib2 Cache Python

The `imglib2-cache-python` package provides a way to integrate CPython into [ImgLib2](https://github.com/imglib/imglib2) for the JVM. It combines [`imglib2-cache`](https://github.com/imglib/imglib2-cache) and [Jep](https://github.com/ninia/jep) into the [`PythonCacheLoader`](src/main/java/net/imglib2/cache/python/PythonCacheLoader.java) that can be used to populate ImgLib2 datastructures like the [`CachedCellImg`](https://github.com/imglib/imglib2-cache/blob/master/src/main/java/net/imglib2/cache/img/CachedCellImg.java) using native CPython code.

## Requirements

ImgLib2 cache requires Java version 8 or later and a CPython interpreter with the [`jep` pacakge](https://pypi.org/project/jep/). At the moment, there is no release of `imglib2-cache-python` available. To use as a dependency in another package, first install into your local maven repository:

``` shell
mvn clean install
```
and then add the appropriate dependency, e.g.

``` xml
<dependency>
	<groupId>net.imglib2</groupId>
	<artifactId>imglib2-cache-python</artifactId>
	<version>0.1.0-SNAPSHOT</version>
</dependency>
```
in `pom.xml` for a maven-based project, or
``` xml
implementation("net.imglib2:imglib2-cache-python:0.1.0-SNAPSHOT")
```
in `build.gradle.kts` for gradle-based projects (you will need to use [`mavenLocal`](https://docs.gradle.org/current/userguide/declaring_repositories.html#sec:case-for-maven-local)).

To install `jep` into your Python interpreter, run
``` shell
python -m pip install jep
```
All packages that are available to that interpreter will also be available for use in Java. If you use a Python interpreter in a non-standard location, e.g. through [Conda](https://conda.io/), you will need to set the `PYTHONHOME` environment variable appropriately.

## Usage

TODO

Examples:
 - [StarDist](src/test/java/net/imglib2/cache/python/examples/StarDist.java)

**Note**: The `CachedCellImg` is backed by a cache that, for some implementations, may relay on the JVM garbage to free unused entries. Direct buffers are used for shared memory access between Java and CPython but their native memory allocation does not count towards the JVM heap, i.e. the garbage collector will not remove unused entries from the cache. To avoid `OutOfMemoryError`s, we recommend using a bounded cache like `GuardedStrongRefLoaderCache`.
