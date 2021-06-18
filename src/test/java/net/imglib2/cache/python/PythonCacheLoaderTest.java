package net.imglib2.cache.python;

import jep.DirectNDArray;
import jep.JepException;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.img.basictypeaccess.nio.BufferAccess;
import net.imglib2.img.basictypeaccess.nio.ShortBufferAccess;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.StreamSupport;

public class PythonCacheLoaderTest {

	/**
	 * This test requires installation of Python with numpy and jep packages installed.
	 * It may be necessary to set PYTHONHOME appropriately.
	 */
	@Test
	public void testNumpyCall() throws InterruptedException, JepException {
		final double[] rangeData = {
				0, 1, 2, 3, 4,
				5, 6, 7, 8, 9
		};
		final double[] averages = {
				1.0, 1.0, 1.0, 3.5, 3.5,
				6.0, 6.0, 6.0, 8.5, 8.5
		};
		final int[] bs = {3, 1};
		final long[] dims = {5, 2};

		final String init = "import numpy as np";
		final String code = String.join(
				"\n",
				"block.data[...] = np.mean(block.inputs[0])"
		);

		final CellGrid grid = new CellGrid(dims, bs);
		final ArrayImg<DoubleType, DoubleArray> range = ArrayImgs.doubles(rangeData, dims);
		final PythonCacheLoader<DoubleType, ? extends BufferAccess<?>> loader = PythonCacheLoader
				.fromRandomAccessibles(grid, new PythonCacheLoaderQueue(3, init), code, new DoubleType(), Views.extendZero(range));
		final CachedCellImg<DoubleType, ? extends BufferAccess<?>> img = loader.createCachedCellImg(30);
		final double[] numpyAverages = StreamSupport.stream(Views.flatIterable(img).spliterator(), false).mapToDouble(DoubleType::getRealDouble).toArray();
			Assert.assertArrayEquals(averages, numpyAverages, 0.0);
	}

	/**
	 * This test requires installation of Python with numpy and jep packages installed.
	 * It may be necessary to set PYTHONHOME appropriately.
	 */
	@Test
	public void testHalo() throws InterruptedException, JepException {
		final double[] rangeData = {
				0, 1, 2, 3, 4,
				5, 6, 7, 8, 9
		};
		final int[] bs = {3, 1};
		final long[] dims = {5, 2};

		final String init = "import numpy as np";
		final String code = String.join(
				"\n",
				"block.data[...] = block.inputs[0][block.halo]"
		);

		final CellGrid grid = new CellGrid(dims, bs);
		final ArrayImg<DoubleType, DoubleArray> range = ArrayImgs.doubles(rangeData, dims);
		final PythonCacheLoader<FloatType, ? extends BufferAccess<?>> loader = PythonCacheLoader.fromRandomAccessibles(grid, new PythonCacheLoaderQueue(3, init), code, new FloatType(), new Halo(2, 3), Views.extendZero(range));
		final CachedCellImg<FloatType, ? extends BufferAccess<?>> img = loader.createCachedCellImg(30);
		final double[] numpyAverages = StreamSupport.stream(Views.flatIterable(img).spliterator(), false).mapToDouble(FloatType::getRealDouble).toArray();
		Assert.assertArrayEquals(rangeData, numpyAverages, 0.0);
	}

	@Test
	public void testReuseNativeBuffer() throws InterruptedException, JepException {
		final CellGrid grid = new CellGrid(new long[] {2}, new int[] {1});
		try(final PythonCacheLoaderQueue queue = new PythonCacheLoaderQueue()) {
			// create non-zero data
			final CachedCellImg<ByteType, ? extends BufferAccess<?>> input = PythonCacheLoader
					.fromRandomAccessibles(grid, queue, "block.data[...] = block.index + 1", new ByteType(), Halo.empty(1))
					.createCachedCellImg(3);
			// check that input data is populated as expected.
			Assert.assertArrayEquals(new byte[] {1, 2}, toPrimitiveByteArray(input));

			// create output that does not change input buffers
			final CachedCellImg<LongType, ? extends BufferAccess<?>> output1 = PythonCacheLoader
					.fromRandomAccessibles(grid, queue, "block.data[...] = block.inputs[0]", new LongType(), Halo.empty(1), input)
					.createCachedCellImg(3);
			// check that input data is unchanged
			Assert.assertArrayEquals(new byte[] {1, 2}, toPrimitiveByteArray(input));

			// check that output data is as expected
			Assert.assertArrayEquals(new long[] {1, 2}, toPrimitiveLongArray(output1));

			// create output that does manipulate input buffers
			// this is not a realistic scenario but it tests that copies are avoided if a native buffer is passed
			// as input with appropriate cell size
			final CachedCellImg<IntType, ? extends BufferAccess<?>> output2 = PythonCacheLoader
					.fromRandomAccessibles(grid, queue, "block.inputs[0][...] = -1", new IntType(), Halo.empty(1), Views.extendZero(input), output1)
					.createCachedCellImg(3);
			output2.forEach(RealType::getRealDouble);
			// check that input data is now all -1
			Assert.assertArrayEquals(new byte[] {-1, -1}, toPrimitiveByteArray(input));
		}
	}

	private static byte[] toPrimitiveByteArray(final RandomAccessibleInterval<ByteType> rai) {
		final byte[] primitiveArray = new byte[(int) Intervals.numElements(rai)];
		final Cursor<ByteType> c = Views.flatIterable(rai).cursor();
		for (int i = 0; c.hasNext(); ++i)
			primitiveArray[i] = c.next().get();
		return primitiveArray;
	}

	private static long[] toPrimitiveLongArray(final RandomAccessibleInterval<? extends IntegerType<?>> rai) {
		final long[] primitiveArray = new long[(int) Intervals.numElements(rai)];
		final Cursor<? extends IntegerType<?>> c = Views.flatIterable(rai).cursor();
		for (int i = 0; c.hasNext(); ++i)
			primitiveArray[i] = c.next().getIntegerLong();
		return primitiveArray;
	}

	@Test
	public void testInconsistentNumDimensions() throws InterruptedException, JepException {
		final short[] inputData = {
				1, 5, 3,
				4, 2, 6
		};
		final ArrayImg<ShortType, ShortArray> inputImg = ArrayImgs.shorts(inputData, 3, 2);
		final PythonCacheLoader.InputGenerator input = interval -> {
			final FinalInterval extendedInterval = Intervals.addDimension(interval, 0, 1);
			return new DirectNDArray<>(
					PythonCacheLoader.copyToBuffer(inputImg, extendedInterval),
					PythonCacheLoader.InputGenerator.getNDArrayShape(extendedInterval)
			);
		};
		final CellGrid grid = new CellGrid(new long[] {3}, new int[] {2});
		try(final PythonCacheLoaderQueue queue = new PythonCacheLoaderQueue(1, "import numpy as np")) {
			final PythonCacheLoader<ShortType, ShortBufferAccess> outputLoader = PythonCacheLoader.fromInputGenerators(
					grid,
					queue,
					"block.data[...] = np.max(block.inputs[0], axis=0)",
					new ShortType(),
					new ShortBufferAccess(1),
					Halo.empty(1), input);
			final CachedCellImg<ShortType, ShortBufferAccess> output = outputLoader.createCachedCellImg(3);
			Assert.assertArrayEquals(new long[] {4, 5, 6}, toPrimitiveLongArray(output));
		}

	}

}