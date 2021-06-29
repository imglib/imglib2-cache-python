[![Build status](https://github.com/hanslovsky/imglib2-cache-python/actions/workflows/build.yaml/badge.svg)](https://github.com/hanslovsky/imglib2-cache-python/actions/workflows/build.yaml)

# ImgLib2 Cache Python

The `imglib2-cache-python` package provides a way to integrate CPython into [ImgLib2](https://github.com/imglib/imglib2) for the JVM. It combines [`imglib2-cache`](https://github.com/imglib/imglib2-cache) and [Jep](https://github.com/ninia/jep) into the [`PythonCacheLoader`](src/main/java/net/imglib2/cache/python/PythonCacheLoader.java) that can be used to populate ImgLib2 datastructures like the [`CachedCellImg`](https://github.com/imglib/imglib2-cache/blob/master/src/main/java/net/imglib2/cache/img/CachedCellImg.java) using native CPython code.

This package is still in an exploratory phase and interface-breaking changes may occur.

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
All packages that are available to that interpreter will also be available for use in Java. If you use a Python interpreter in a non-standard location, e.g. through [Conda](https://conda.io/), you will need to set the `PYTHONHOME` environment variable appropriately. In all cases, [`numpy`](https://numpy.org/) must be installed.

## Usage

To create Python-backed `CachedCellImg`, first create a `PythonCacheLoaderQueue`:

``` java
import net.imglib2.cache.python.PythonCacheLoaderQueue;

final int numWorkers = 3;
final String init = "# expensive Python initialization, e.g. Tensorflow";
final PythonCacheLoaderQueue queue = new PythonCacheLoaderQueue(numWorkers, init);
```
The optional constructor parameters `numWorkers` and `init` specify the number of Python interpreters to execute requests in parallel (the GIL still applies) and a Python code block that gets executed on each Python interpreter upon initialization, respectively. The queue is used by the `PythonCacheloader` to load data for individual grid cells. A `CachedCellImg` can be conveniently created with the `PythonCacheLoader.createCachedCellImg` method.

``` java
import net.imglib2.RandomAccessible;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.python.Halo;
import net.imglib2.cache.python.PythonCacheloader;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.numeric.integer.LongType;

final long[] dims = //
final int[] blockSize = //
final CellGrid grid = new CellGrid(dims, blockSize);
final String code = "# Python code to populate cell data. The output should be written into block.data, e.g. block.data[...] = 42";
final RandomAccessible<? extends NativeType<?>> input1 = //
final RandomAccessible<? extends NativeType<?>> input2 = //

final PythonCacheLoader<LongType, ? extends BufferAccess<?>> loader = PythonCacheLoader.fromRandomAccessibles(
				grid,
                queue,
                code,
                new LongType(),
                Halo.empty(grid.numDimensions()),
                input1,
                input2
);

final int maximumCacheSize = 30;
final CachedCellImg<LongType, ? extends BufferAccess<?>> img = loader.createCachedCellImg(maximumCacheSize);
```
The dimensions (`dims`) and block size (`blockSize`) define the cell grid of the `CachedCellImg` (`img`). The `loader` generates data for each of the cells of `img` on demand. Cells are cached in a Cache with at most `maximumCacheSize` entries. The `code` defines how the data for a cell is populated in Python. The type of the data must be specified in the loader (in this case, it is `LongType`) and a halo can be added if padding is needed to compute the cell data. Optional `RandomAccessible`s can be passed as inputs, if needed (`input1`, `input2`, ...). In general, block sized cells of the inputs are copied into [direct/native buffers](https://docs.oracle.com/javase/7/docs/api/java/nio/ByteBuffer.html#allocateDirect(int)) that are then passed into the Python code as [`numpy.ndarray`s](https://numpy.org/doc/stable/reference/generated/numpy.ndarray.html). For any input that is an (extended) `CachedCellImg<?, ? extends BufferAccess<?>>` that is backed by direct/native buffers and has a compatible blockSize, a copy can be avoided. All relevant variables can be accessed from the Python `code` through the `block` variable of type `Block`, defined as

``` python
from dataclasses import dataclass
import numpy as np
@dataclass
class Block:
    data: np.ndarray
    inputs: list
    index: int
    min: tuple
    max: tuple
    dim: tuple
    halo: tuple
```
with the follwoing members:

| Member   | Description |
| -------- | ----------- |
| `data`   | Holds the cell/block data. Write output into this `ndarray`. |
| `inputs` | List of `ndarray`s that hold input data (if any). |
| `index`  | Block index within the cell grid. |
| `min`    | Minimum coordinate of block. |
| `max`    | Maximum coordinate of block. |
| `dim`    | Dimension (shape) of block.  |
| `halo`   | Slicing to crop any arrays, if necessary to remove padding. |



Please refer to these working examples:
 - [SimpleExample](src/test/java/net/imglib2/cache/python/examples/SimpleExample.java)
 - [StarDist](src/test/java/net/imglib2/cache/python/examples/StarDist.java)

**Note**: The `CachedCellImg` is backed by a cache that, for some implementations, may relay on the JVM garbage collector to free unused entries. Direct buffers are used for shared memory access between Java and CPython but their native memory allocation does not count towards the JVM heap, i.e. the garbage collector will not remove unused entries from the cache. To avoid `OutOfMemoryError`s, we recommend using a bounded cache like `GuardedStrongRefLoaderCache`.
