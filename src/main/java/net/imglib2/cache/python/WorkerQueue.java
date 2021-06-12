package net.imglib2.cache.python;

import jep.DirectNDArray;
import jep.JepException;
import jep.SharedInterpreter;

import java.nio.Buffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class WorkerQueue<B extends Buffer> {

	public static class Task<B extends Buffer> {
		private final B buffer;
		private final Buffer[] inputs;
		private final long index;
		private final long[] min;
		private final long[] max;
		private final int[] dims;
		private final String code;
		private final CountDownLatch latch = new CountDownLatch(1);

		public Task(B buffer, Buffer[] inputs, long index, long[] min, long[] max, String code) {
			this.buffer = buffer;
			this.inputs = inputs;
			this.index = index;
			this.min = min;
			this.max = max;
			this.dims = new int[this.min.length];
			for (int d = 0; d < dims.length; ++d)
				this.dims[d] = (int) (this.max[d] - this.min[d] + 1);
			this.code = code;
		}

		public void complete() {
			latch.countDown();
		}

		public void awaitCompletion() throws InterruptedException {
			latch.await();
		}
	}

	private static class Worker<B extends Buffer> implements AutoCloseable {
		private final BlockingQueue<Task<B>> queue;
		private final String init;
		private final Thread workerThread;

		private boolean isClosed = false;
		private final CountDownLatch pythonReady = new CountDownLatch(1);

		Worker(BlockingQueue<Task<B>> queue, String init, String name) throws InterruptedException {
			this.queue = queue;
			this.init = init;
			this.workerThread = new Thread(() -> {
				final SharedInterpreter python;
				try {
					python = new SharedInterpreter();
					initialize(python, this.init);
				} catch (JepException e) {
					throw new RuntimeException(e);
				} finally {
					pythonReady.countDown();
				}

				while (!this.isClosed) {
					final Task<B> task = poll();
					if (task == null)
						continue;

					try {
						final int[] dims = reversedArray(task.dims);
						if (!task.buffer.isDirect())
							throw new RuntimeException("Expected direct buffer but got " + task.buffer);
						python.set("_buf", new DirectNDArray<>(task.buffer, dims));
						python.set("_inputs", Stream.of(task.inputs).map(i -> new DirectNDArray(i, dims)).toArray(DirectNDArray[]::new));
						python.set("_index", task.index);
						python.set("_min", reversedArray(task.min));
						python.set("_max", reversedArray(task.max));
						python.set("_dim", dims);
						python.exec("block = Block(_buf, _inputs, _index, _min, _max, _dim)");
						python.exec(task.code);
					} catch (final JepException e) {
						throw new RuntimeException(e);
					} finally {
						task.complete();
					}
				}
			});
			this.workerThread.setDaemon(true);
			if (name != null)
				this.workerThread.setName(name);
			this.workerThread.start();
			pythonReady.await();
		}


		private Task<B> poll() {
			try {
				return queue.poll(10, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				return null;
			}
		}

		private static void initialize(SharedInterpreter python, String initBlock) throws JepException {
			final String[] lines = {
					"from dataclasses import dataclass",
					"import numpy as np",
					"@dataclass",
					"class Block:",
					"    data: np.ndarray",
					"    inputs: list",
					"    index: int",
					"    min: tuple",
					"    max: tuple",
					"    dim: tuple"
			};
			final String init = String.join("\n", lines);
			python.exec(init);
			if (initBlock != null)
				python.exec(initBlock);
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
		public void close() {
			this.isClosed = true;
		}

	}

	private final String init;
	private final List<Worker<B>> workers = new ArrayList<>();
	private final LinkedBlockingDeque<Task<B>> queue = new LinkedBlockingDeque<>();

	public WorkerQueue(final int numWorkers, final String init) throws InterruptedException {
		this.init = init;
		for (int w = 0; w < numWorkers; ++w) {
			this.workers.add(new Worker<B>(queue, init, "Python-" + w));
		}
	}

	public void submitAndAwaitCopmletion(final B buffer, final Buffer[] inputs, final long index, final long[] min, final long[] max, final String code) throws InterruptedException {
		final Task<B> task = new Task<>(buffer, inputs, index, min, max, code);
		queue.add(task);
		task.awaitCompletion();
	}

	public void close() {
		for (final Worker<B> worker : workers) {
			if (worker != null) worker.close();
		}
	}
}
