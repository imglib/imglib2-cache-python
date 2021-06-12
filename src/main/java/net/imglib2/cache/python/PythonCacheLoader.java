package net.imglib2.cache.python;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.CacheLoader;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.ref.GuardedStrongRefLoaderCache;
import net.imglib2.img.NativeImg;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.basictypeaccess.nio.BufferAccess;
import net.imglib2.img.basictypeaccess.nio.ByteBufferAccess;
import net.imglib2.img.basictypeaccess.nio.CharBufferAccess;
import net.imglib2.img.basictypeaccess.nio.DoubleBufferAccess;
import net.imglib2.img.basictypeaccess.nio.FloatBufferAccess;
import net.imglib2.img.basictypeaccess.nio.IntBufferAccess;
import net.imglib2.img.basictypeaccess.nio.LongBufferAccess;
import net.imglib2.img.basictypeaccess.nio.ShortBufferAccess;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

public class PythonCacheLoader<T extends NativeType<T>, A extends BufferAccess<A>> implements CacheLoader<Long, Cell<A>> {
	private final CellGrid grid;
	private final String code;
	private final T t;
	private final A a;
	private final WorkerQueue workerQueue;
	private final List<Function<Interval, Buffer>> inputGenerators;

	public PythonCacheLoader(CellGrid grid, int numWorkers, String code, String init, T t, A a, Function<Interval, Buffer>... inputGenerators) throws InterruptedException {
		this.grid = grid;
		this.code = code;
		this.workerQueue = new WorkerQueue(numWorkers, init);
		this.t = t;
		this.a = a;
		this.inputGenerators = Arrays.asList(inputGenerators);
	}

	@Override
	public Cell<A> get(final Long key) {
		final long[] min = new long[grid.numDimensions()];
		final long[] max = new long[min.length];
		final int[] dim = new int[min.length];
		for (int d = 0; d < min.length; ++d)
			grid.getCellMin(d, key);
		grid.getCellDimensions(key, min, dim);
		for (int d = 0; d < min.length; ++d)
			max[d] = min[d] + dim[d] - 1;
		final Interval interval = new FinalInterval(min, max);

		final ByteBuffer buffer = appropriateDirectBuffer(t, interval);
		final A access = a.newInstance(buffer, true);

		final Buffer[] inputs = this.inputGenerators.stream().map(f -> f.apply(interval)).toArray(Buffer[]::new);


		try {
			workerQueue.submitAndAwaitCopmletion(asTypedBuffer(buffer, t), inputs, key, min, max, code);
		} catch (final InterruptedException e) {
			throw new RuntimeException(e);
		}

		return new Cell<>(dim, min, access);
	}

	public static class RandomAccessibleInput implements Function<Interval, Buffer> {
		private final RandomAccessible<? extends NativeType<?>> source;

		public RandomAccessibleInput(RandomAccessible<? extends NativeType<?>> source) {
			this.source = source;
		}

		@Override
		public Buffer apply(Interval interval) {
			return copyToBuffer((RandomAccessible) source, interval);
		}
	}

	public static <T extends NativeType<T>, A extends BufferAccess<A>> PythonCacheLoader<T, A> withInputs(
			final CellGrid grid,
			final int numWorkers,
			final String code,
			final String init,
			final T t,
			final A a,
			final RandomAccessible<? extends NativeType<?>>... inputs) throws InterruptedException {
		return new PythonCacheLoader<>(grid, numWorkers, code, init, t, a, Stream.of(inputs).map(RandomAccessibleInput::new).toArray(RandomAccessibleInput[]::new));
	}

	private static ByteBuffer appropriateDirectBuffer(final NativeType<?> t, final Interval interval) {
		final double fractionalBytesPerEntity = getFractionalBytesPerEntity(t);
		final long numElements = Intervals.numElements(interval);
		return ByteBuffer.allocateDirect((int) Math.ceil(numElements * fractionalBytesPerEntity));
	}

	private static double getFractionalBytesPerEntity(final NativeType<?> t) {
		return t.getEntitiesPerPixel().getRatio() * getByteCount(t);
	}

	private static int getByteCount(final NativeType<?> t) {
		switch (t.getNativeTypeFactory().getPrimitiveType()) {
			case BOOLEAN:
			case BYTE:
			case CHAR:
				return 1;
			case SHORT:
				return 2;
			case INT:
			case FLOAT:
				return 4;
			case LONG:
			case DOUBLE:
				return 8;
			case UNDEFINED:
			default:
				throw new IllegalArgumentException("Unknown type: " + t);
		}
	}

	private static Buffer asTypedBuffer(final ByteBuffer buffer, final NativeType<?> t) {
		switch (t.getNativeTypeFactory().getPrimitiveType()) {
			case BOOLEAN:
			case BYTE:
				return buffer;
			case CHAR:
				return buffer.asCharBuffer();
			case SHORT:
				return buffer.asShortBuffer();
			case INT:
				return buffer.asIntBuffer();
			case FLOAT:
				return buffer.asFloatBuffer();
			case LONG:
				return buffer.asLongBuffer();
			case DOUBLE:
				return buffer.asDoubleBuffer();
			case UNDEFINED:
			default:
				throw new IllegalArgumentException("Unknown type: " + t);
		}
	}

	private static <A extends BufferAccess<A>> A asAccess(final ByteBuffer buffer, final NativeType<?> t) {
		switch (t.getNativeTypeFactory().getPrimitiveType()) {
			case BOOLEAN:
			case BYTE:
				return (A) new ByteBufferAccess(buffer, true);
			case CHAR:
				return (A) new CharBufferAccess(buffer, true);
			case SHORT:
				return (A) new ShortBufferAccess(buffer, true);
			case INT:
				return (A) new IntBufferAccess(buffer, true);
			case FLOAT:
				return (A) new FloatBufferAccess(buffer, true);
			case LONG:
				return (A) new LongBufferAccess(buffer, true);
			case DOUBLE:
				return (A) new DoubleBufferAccess(buffer, true);
			case UNDEFINED:
			default:
				throw new IllegalArgumentException("Unknown type: " + t);
		}
	}

	private static <T extends NativeType<T>> Buffer copyToBuffer(final RandomAccessible<T> source, final Interval interval) {
		return copyToBuffer(Views.interval(source, interval));
	}

	private static <T extends NativeType<T>> Buffer copyToBuffer(final RandomAccessibleInterval<T> source) {
		final ByteBuffer buffer = appropriateDirectBuffer(Util.getTypeFromInterval(source).createVariable(), source);
		return copyToBuffer(source, buffer);
	}

	private static <T extends NativeType<T>, A extends BufferAccess<A>> Buffer copyToBuffer(final RandomAccessibleInterval<T> source, final ByteBuffer buffer) {
		final T t = Util.getTypeFromInterval(source).createVariable();
		final A a = asAccess(buffer, t);
		final ArrayImg<T, A> target = new ArrayImg<>(a, Intervals.dimensionsAsLongArray(source), t.getEntitiesPerPixel());
		target.setLinkedType((T) t.getNativeTypeFactory().createLinkedType((NativeImg) target));
		LoopBuilder.setImages(target, source).forEachPixel(T::set);
		return asTypedBuffer(buffer, t);
	}

	public static void main(String... args) throws InterruptedException {
		final String init = "import numpy as np";
		final String code = String.join(
				"\n",
				"block.data[...] = np.mean(block.inputs[0])"
		);
		final long[] dims = {5, 6};
		final int[] bs = {5, 1};
		final CellGrid grid = new CellGrid(dims, bs);
		final ArrayImg<DoubleType, DoubleArray> range = ArrayImgs.doubles(dims);
		final Cursor<DoubleType> rangeCursor = range.cursor();
		for (int i = 0; rangeCursor.hasNext(); ++i)
			rangeCursor.next().setReal(i);
		final PythonCacheLoader<FloatType, FloatBufferAccess> loader = PythonCacheLoader.withInputs(grid, 3, code, init, new FloatType(), new FloatBufferAccess(1), Views.extendZero(range));
		final Cache<Long, Cell<FloatBufferAccess>> cache = new GuardedStrongRefLoaderCache<Long, Cell<FloatBufferAccess>>(30).withLoader(loader);
		final CachedCellImg<FloatType, FloatBufferAccess> img = new CachedCellImg<>(grid, new FloatType(), cache, new FloatBufferAccess());
		LoopBuilder.setImages(range, img).forEachPixel((i1, i2) -> System.out.println(i1 + " " + i2));
	}
}