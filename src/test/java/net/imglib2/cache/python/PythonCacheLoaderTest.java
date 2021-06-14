package net.imglib2.cache.python;

import jep.JepException;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.ref.GuardedStrongRefLoaderCache;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.basictypeaccess.nio.FloatBufferAccess;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
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
		final PythonCacheLoader<FloatType, FloatBufferAccess> loader = new PythonCacheLoader<>(grid, 3, code, init, new FloatType(), new FloatBufferAccess(1), null, Views.extendZero(range));
		final Cache<Long, Cell<FloatBufferAccess>> cache = new GuardedStrongRefLoaderCache<Long, Cell<FloatBufferAccess>>(30).withLoader(loader);
		final CachedCellImg<FloatType, FloatBufferAccess> img = new CachedCellImg<>(grid, new FloatType(), cache, new FloatBufferAccess());
		final double[] numpyAverages = StreamSupport.stream(Views.flatIterable(img).spliterator(), false).mapToDouble(FloatType::getRealDouble).toArray();
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
		final PythonCacheLoader<FloatType, FloatBufferAccess> loader = new PythonCacheLoader<>(grid, 3, code, init, new FloatType(), new FloatBufferAccess(1), new Halo(2, 3), Views.extendZero(range));
		final Cache<Long, Cell<FloatBufferAccess>> cache = new GuardedStrongRefLoaderCache<Long, Cell<FloatBufferAccess>>(30).withLoader(loader);
		final CachedCellImg<FloatType, FloatBufferAccess> img = new CachedCellImg<>(grid, new FloatType(), cache, new FloatBufferAccess());
		final double[] numpyAverages = StreamSupport.stream(Views.flatIterable(img).spliterator(), false).mapToDouble(FloatType::getRealDouble).toArray();
		Assert.assertArrayEquals(rangeData, numpyAverages, 0.0);
	}

}