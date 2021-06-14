package net.imglib2.cache.python;

import jep.DirectNDArray;
import jep.JepException;
import jep.SharedInterpreter;

import java.nio.Buffer;
import java.util.stream.Stream;

public class PythonCacheLoaderBlockTask implements PythonTask<Void> {
	private final Buffer buffer;
	private final Buffer[] inputs;
	private final long index;
	private final long[] min;
	private final long[] max;
	private final int[] dims;
	private final Halo halo;
	private final String code;

	public PythonCacheLoaderBlockTask(Buffer buffer, Buffer[] inputs, long index, long[] min, long[] max, Halo halo, String code) {
		this.buffer = buffer;
		this.inputs = inputs;
		this.index = index;
		this.min = min;
		this.max = max;
		this.halo = halo;
		this.dims = new int[this.min.length];
		for (int d = 0; d < dims.length; ++d)
			this.dims[d] = (int) (this.max[d] - this.min[d] + 1);
		this.code = code;
	}

	private static long[] reversedArray(final long[] array) {
		final long[] reversedArray = new long[array.length];
		for (int i = 0, k = array.length - 1; i < array.length; ++i, --k)
			reversedArray[i] = array[k];
		return reversedArray;
	}

	private static int[] reversedArray(final int[] array) {
		final int[] reversedArray = new int[array.length];
		for (int i = 0, k = array.length - 1; i < array.length; ++i, --k)
			reversedArray[i] = array[k];
		return reversedArray;
	}

	@Override
	public Void execute(SharedInterpreter python) throws JepException {
		final int[] dims = reversedArray(this.dims);
		final int[] adjustedDims = reversedArray(halo.adjustDimensions(this.dims));
		if (!buffer.isDirect())
			throw new RuntimeException("Expected direct buffer but got " + buffer);
		python.set("_buf", new DirectNDArray<>(buffer, dims));
		python.set("_inputs", Stream.of(inputs).map(i -> new DirectNDArray<>(i, adjustedDims)).toArray(DirectNDArray[]::new));
		python.set("_index", index);
		python.set("_min", reversedArray(min));
		python.set("_max", reversedArray(max));
		python.set("_dim", dims);
		python.set("_halo_lower", halo.getLowerCopy());
		python.set("_halo_upper", halo.getUpperCopy());
		python.exec("_halo = tuple(slice(l, -u) for l, u in zip(_halo_lower, _halo_upper))[::-1]");
		python.exec("block = Block(_buf, _inputs, _index, _min, _max, _dim, _halo)");
		python.exec(code);
		python.exec("del block, _buf, _inputs, _min, _max, _dim, _halo_lower, _halo_upper");
		return null;
	}
}
