package net.imglib2.cache.python;

import java.util.concurrent.CountDownLatch;

public class PythonFuture<T> {

	public interface ResultProvider<T> {
		T getResult() throws Exception;
	}

	private final ResultProvider<T> get;
	private final CountDownLatch latch;

	public PythonFuture(ResultProvider<T> get, CountDownLatch latch) {
		this.get = get;
		this.latch = latch;
	}

	public T get() throws Exception {
		latch.await();
		return get.getResult();
	}
}
