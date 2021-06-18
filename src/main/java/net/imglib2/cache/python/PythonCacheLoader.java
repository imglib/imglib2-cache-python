package net.imglib2.cache.python;

import jep.DirectNDArray;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.CacheLoader;
import net.imglib2.cache.LoaderCache;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.ref.GuardedStrongRefLoaderCache;
import net.imglib2.img.NativeImg;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.basictypeaccess.ByteAccess;
import net.imglib2.img.basictypeaccess.CharAccess;
import net.imglib2.img.basictypeaccess.DoubleAccess;
import net.imglib2.img.basictypeaccess.FloatAccess;
import net.imglib2.img.basictypeaccess.IntAccess;
import net.imglib2.img.basictypeaccess.LongAccess;
import net.imglib2.img.basictypeaccess.ShortAccess;
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
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class PythonCacheLoader<T extends NativeType<T>, A extends BufferAccess<A>> implements CacheLoader<Long, Cell<A>> {

	public interface InputGenerator {
		DirectNDArray<?> createInputFor(Interval interval);

		static int[] getNDArrayShape(final Interval interval) {
			return reversedArray(Intervals.dimensionsAsIntArray(interval));
		}

		@SuppressWarnings("unchecked")
		static InputGenerator forRandomAccessible(final RandomAccessible<? extends NativeType<?>> source) {
			if (source instanceof ExtendedRandomAccessibleInterval<?, ?>) {
				final CachedCellImg<?, ?> img = getCachedCellImgFromSource(source);
				if (img != null && img.getAccessType() instanceof BufferAccess)
					return new ForCachedCellImg((CachedCellImg<?, ? extends BufferAccess<?>>) img, source);
			}

			if (source instanceof CachedCellImg<?, ?>) {
				final CachedCellImg<?, ?> img = (CachedCellImg<?, ?>) source;
				if (img.getAccessType() instanceof BufferAccess)
					return new ForCachedCellImg((CachedCellImg<?, ? extends BufferAccess<?>>) img);
			}

			return new ForRandomAccessible(source);
		}

		class ForRandomAccessible implements InputGenerator {
			private final RandomAccessible<? extends NativeType<?>> source;

			public ForRandomAccessible(RandomAccessible<? extends NativeType<?>> source) {
				this.source = source;
			}

			@Override
			public DirectNDArray<?> createInputFor(Interval interval) {
				return new DirectNDArray<>(
						copyToBuffer(source, interval),
						getNDArrayShape(interval));
			}
		}

		class ForCachedCellImg implements InputGenerator {
			private final CachedCellImg<?, ? extends BufferAccess<?>> img;
			private final ForRandomAccessible fallback;
			private final int[] cellDimensions;

			public ForCachedCellImg(final CachedCellImg<?, ? extends BufferAccess<?>> img) {
				this(img, img);
			}

			public ForCachedCellImg(
					final CachedCellImg<?, ? extends BufferAccess<?>> img,
					final RandomAccessible<? extends NativeType<?>> fallback
					) {
				this.img = img;
				this.fallback = new ForRandomAccessible(fallback);
				this.cellDimensions = new int[img.numDimensions()];
				this.img.getCellGrid().cellDimensions(this.cellDimensions);
			}


			@Override
			public DirectNDArray<?> createInputFor(final Interval interval) {
				if (isCompatible(interval)) {
					final long[] position = new long[interval.numDimensions()];
					this.img.getCellGrid().getCellPosition(Intervals.minAsLongArray(interval), position);
					final Buffer buffer = (Buffer) this.img.getCells().getAt(position).getData().getCurrentStorageArray();
					if (buffer.isDirect())
						return new DirectNDArray<>(buffer, getNDArrayShape(interval));
				}
				return fallback.createInputFor(interval);
			}

			private boolean isCompatible(final Interval interval) {
				return isMinCompatible(interval) && isDimCompatible(interval);
			}

			private boolean isMinCompatible(final Interval interval) {
				for (int d = 0; d < interval.numDimensions(); ++d)
					if (interval.min(d) % this.cellDimensions[d] != 0)
						return false;
				return true;
			}

			private boolean isDimCompatible(final Interval interval) {
				for (int d = 0; d < interval.numDimensions(); ++d)
					if (interval.dimension(d) != this.cellDimensions[d] && interval.max(d) != this.img.max(d))
						return false;
				return true;
			}
		}
	}

	private final CellGrid grid;
	private final String code;
	private final T t;
	private final A a;
	private final PythonCacheLoaderQueue workerQueue;
	private final Halo halo;
	private final List<? extends InputGenerator> inputGenerators;

	private PythonCacheLoader(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final A a,
			final Halo halo,
			final Collection<? extends InputGenerator> inputGenerators) {
		if (!isCorrectAccessFor(t, a))
			throw new IllegalArgumentException("Access " + a + " not compatible with type " + t);
		this.grid = grid;
		this.code = code;
		this.workerQueue = workerQueue;
		this.t = t;
		this.a = a;
		this.halo = halo == null ? Halo.empty(grid.numDimensions()) : halo;
		this.inputGenerators = new ArrayList<>(inputGenerators);
	}

	private PythonCacheLoader(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final A a,
			final Halo halo,
			final InputGenerator... inputs) {
		this(grid, workerQueue, code, t, a, halo, Arrays.asList(inputs));
	}

	public static <T extends NativeType<T>, A extends BufferAccess<A>> PythonCacheLoader<T, A> fromInputGenerators(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final A a,
			final Halo halo,
			final Collection<? extends InputGenerator> inputGenerators) {
		return new PythonCacheLoader<>(grid, workerQueue, code, t, a, halo, inputGenerators);
	}

	public static <T extends NativeType<T>, A extends BufferAccess<A>> PythonCacheLoader<T, A> fromInputGenerators(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final A a,
			final Halo halo,
			final InputGenerator... inputs) {
		return fromInputGenerators(grid, workerQueue, code, t, a, halo, Arrays.asList(inputs));
	}

	public static <T extends NativeType<T>, A extends BufferAccess<A>> PythonCacheLoader<T, A> fromRandomAccessibles(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final A a,
			final Halo halo,
			final Collection<? extends RandomAccessible<? extends NativeType<?>>> inputs) {
		return fromInputGenerators(grid, workerQueue, code, t, a, halo, inputs.stream().map(InputGenerator::forRandomAccessible).collect(Collectors.toList()));
	}

	public static <T extends NativeType<T>, A extends BufferAccess<A>> PythonCacheLoader<T, A> fromRandomAccessibles(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final A a,
			final Halo halo,
			final RandomAccessible<? extends NativeType<?>>... inputs) {
		return fromRandomAccessibles(grid, workerQueue, code, t, a, halo, Arrays.asList(inputs));
	}

	public static <T extends NativeType<T>> PythonCacheLoader<T, ? extends BufferAccess<?>> fromRandomAccessibles(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final Halo halo,
			final Collection<? extends RandomAccessible<? extends NativeType<?>>> inputs) {
		return fromRandomAccessibles(grid, workerQueue, code, t, (BufferAccess) bufferAccessFor(t), halo, inputs);
	}

	public static <T extends NativeType<T>> PythonCacheLoader<T, ? extends BufferAccess<?>> fromRandomAccessibles(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final Halo halo,
			final RandomAccessible<? extends NativeType<?>>... inputs) {
		return fromRandomAccessibles(grid, workerQueue, code, t, halo, Arrays.asList(inputs));
	}

	public static <T extends NativeType<T>> PythonCacheLoader<T, ? extends BufferAccess<?>> fromRandomAccessibles(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final Collection<? extends RandomAccessible<? extends NativeType<?>>> inputs) {
		return fromRandomAccessibles(grid, workerQueue, code, t, (BufferAccess) bufferAccessFor(t), Halo.empty(grid.numDimensions()), inputs);
	}

	public static <T extends NativeType<T>> PythonCacheLoader<T, ? extends BufferAccess<?>> fromRandomAccessibles(
			final CellGrid grid,
			final PythonCacheLoaderQueue workerQueue,
			final String code,
			final T t,
			final RandomAccessible<? extends NativeType<?>>... inputs) {
		return fromRandomAccessibles(grid, workerQueue, code, t, Arrays.asList(inputs));
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
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
		final Interval extendedInterval = halo.extendInterval(interval);

		final ByteBuffer buffer = appropriateDirectBuffer(t, interval);

		// This redundant cast is necessary to compile with OpenJDK 8. Why?
		final DirectNDArray<?>[] inputs = this.inputGenerators
				.stream()
				.map(g -> g.createInputFor(extendedInterval))
				.toArray(DirectNDArray[]::new);


		boolean isValid = true;
		try {
			final PythonCacheLoaderBlockTask task =
					new PythonCacheLoaderBlockTask(asTypedBuffer(buffer, t), inputs, key, min, max, halo, code);
			workerQueue.submit(task).get();
		} catch (final Exception e) {
			isValid = false;
			e.printStackTrace();
		}

		final A access = a.newInstance(buffer, isValid);
		return new Cell<>(dim, min, access);
	}

	public CachedCellImg<T, A> createCachedCellImg(final LoaderCache<Long, Cell<A>> loaderCache) {
		return new CachedCellImg<>(grid, t, loaderCache.withLoader(this), a);
	}

	public CachedCellImg<T, A> createCachedCellImg(final long maximumSize) {
		return createCachedCellImg(new GuardedStrongRefLoaderCache<>(maximumSize));
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

	private static BufferAccess<?> bufferAccessFor(final NativeType<?> t) {
		switch (t.getNativeTypeFactory().getPrimitiveType()) {
			case BOOLEAN:
			case BYTE:
				return new ByteBufferAccess(1);
			case CHAR:
				return new CharBufferAccess(1);
			case SHORT:
				return new ShortBufferAccess(1);
			case INT:
				return new IntBufferAccess(1);
			case FLOAT:
				return new FloatBufferAccess(1);
			case LONG:
				return new LongBufferAccess(1);
			case DOUBLE:
				return new DoubleBufferAccess(1);
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

	@SuppressWarnings("unchecked")
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

	private static boolean isCorrectAccessFor(final NativeType<?> t, final BufferAccess<?> a) {
		switch (t.getNativeTypeFactory().getPrimitiveType()) {
			case BOOLEAN:
			case BYTE:
				return a instanceof ByteAccess;
			case CHAR:
				return a instanceof CharAccess;
			case SHORT:
				return a instanceof ShortAccess;
			case INT:
				return a instanceof IntAccess;
			case FLOAT:
				return a instanceof FloatAccess;
			case LONG:
				return a instanceof LongAccess;
			case DOUBLE:
				return a instanceof DoubleAccess;
			case UNDEFINED:
			default:
				throw new IllegalArgumentException("Unknown type: " + t);
		}
	}

	public static Buffer copyToBuffer(final RandomAccessible<? extends NativeType<?>> source, final Interval interval) {
		return copyToBuffer(Views.interval(source, interval));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Buffer copyToBuffer(final RandomAccessibleInterval<? extends NativeType<?>> source) {
		final ByteBuffer buffer = appropriateDirectBuffer(Util.getTypeFromInterval(source).createVariable(), source);
		return copyToBuffer((RandomAccessibleInterval) source, buffer);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <T extends NativeType<T>, A extends BufferAccess<A>> Buffer copyToBuffer(
			final RandomAccessibleInterval<T> source,
			final ByteBuffer buffer) {
		final T t = Util.getTypeFromInterval(source).createVariable();
		final A a = asAccess(buffer, t);
		final ArrayImg<T, A> target = new ArrayImg<>(a, Intervals.dimensionsAsLongArray(source), t.getEntitiesPerPixel());
		target.setLinkedType((T) t.getNativeTypeFactory().createLinkedType((NativeImg) target));
		LoopBuilder.setImages(target, source).forEachPixel(T::set);
		return asTypedBuffer(buffer, t);
	}

	private static int[] reversedArray(final int[] array) {
		final int[] reversedArray = new int[array.length];
		for (int i = 0, k = array.length - 1; i < array.length; ++i, --k)
			reversedArray[i] = array[k];
		return reversedArray;
	}

	private static CachedCellImg<?, ?> getCachedCellImgFromSource(final RandomAccessible<? extends NativeType<?>> source) {
		if (source instanceof ExtendedRandomAccessibleInterval<?, ?>) {
			final ExtendedRandomAccessibleInterval<?, ?> rai = (ExtendedRandomAccessibleInterval<?, ?>) source;
			if (rai.getSource() instanceof CachedCellImg<?, ?>)
				return (CachedCellImg<?, ?>) rai.getSource();
		}
		return null;
	}
}