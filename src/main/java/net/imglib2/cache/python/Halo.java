package net.imglib2.cache.python;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.util.Intervals;

public class Halo {
	private final int[] lower;
	private final int[] upper;
	private final int nDim;

	public Halo(int[] lower, int[] upper) {
		this.lower = lower.clone();
		this.upper = upper.clone();
		this.nDim = this.lower.length;
		assert this.nDim == this.upper.length;
	}

	public Halo(int... halo) {
		this(halo, halo);
	}

	public Interval extendInterval(final Interval interval) {
		assert this.nDim == interval.numDimensions();
		final long[] min = Intervals.minAsLongArray(interval);
		final long[] max = Intervals.maxAsLongArray(interval);
		for (int d = 0; d < nDim; ++d) {
			min[d] -= lower[d];
			max[d] += upper[d];
		}
		return new FinalInterval(min, max);
	}

	public int[] adjustDimensions(final int[] dims) {
		final int[] adjustedDims = new int[dims.length];
		for (int d = 0; d < dims.length; ++d)
			adjustedDims[d] = dims[d] + lower[d] + upper[d];
		return adjustedDims;
	}

	public int[] getLowerCopy() {
		return this.lower.clone();
	}

	public int[] getUpperCopy() {
		return this.upper.clone();
	}

	public static Halo empty(final int nDim) {
		return new Halo(new int[nDim]);
	}
}
