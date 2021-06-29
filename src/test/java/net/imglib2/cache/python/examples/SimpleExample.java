package net.imglib2.cache.python.examples;


import jep.JepException;
import net.imglib2.RandomAccessible;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.python.Halo;
import net.imglib2.cache.python.PythonCacheLoader;
import net.imglib2.cache.python.PythonCacheLoaderQueue;
import net.imglib2.img.basictypeaccess.nio.BufferAccess;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.ConstantUtils;
import net.imglib2.view.Views;

public class SimpleExample {
	public static void main(String[] args) throws InterruptedException, JepException {


		final int numWorkers = 3;
		final String init = "# expensive Python initialization, e.g. Tensorflow";
		final PythonCacheLoaderQueue queue = new PythonCacheLoaderQueue(numWorkers, init);

		final long[] dims = {3, 5};
		final int[] blockSize = {2, 3};
		final CellGrid grid = new CellGrid(dims, blockSize);
		final String code = "block.data[...] = sum(block.inputs)";
		final RandomAccessible<? extends NativeType<?>> input1 = ConstantUtils.constantRandomAccessible(new IntType(1), 2);
		final RandomAccessible<? extends NativeType<?>> input2 = ConstantUtils.constantRandomAccessible(new UnsignedShortType(2), 2);

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
		Views.flatIterable(img).forEach(System.out::println);

	}
}
