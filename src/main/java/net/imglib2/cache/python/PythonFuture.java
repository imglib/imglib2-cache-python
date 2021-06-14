package net.imglib2.cache.python;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class PythonFuture<T> {
	private final Supplier<T> get;
	private final CountDownLatch latch;

	public PythonFuture(Supplier<T> get, CountDownLatch latch) {
		this.get = get;
		this.latch = latch;
	}

	public T get(final long timeout, final TimeUnit unit) throws InterruptedException {
		latch.await(timeout, unit);
		return get.get();
	}

	public T get() throws InterruptedException {
		latch.await();
		return get.get();
	}
}
