package net.imglib2.cache.python;

import jep.JepException;
import jep.SharedInterpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class PythonWorkerQueue implements AutoCloseable {

	private interface PythonConsumer extends Consumer<SharedInterpreter> {

	}

	private static class PythonExecution<T> implements PythonConsumer {

		private final PythonTask<T> task;
		private final CountDownLatch latch = new CountDownLatch(1);
		private Exception e = null;
		private T result = null;

		private PythonExecution(PythonTask<T> task) {
			this.task = task;
		}

		public void execute(final SharedInterpreter python) {
			try {
				this.result = task.execute(python);
			} catch (final Exception e) {
				this.e = e;
			} finally {
				latch.countDown();
			}
		}

		public T getResultOrThrow() throws Exception {
			if (e != null)
				throw e;
			return this.result;
		}

		@Override
		public void accept(SharedInterpreter python) {
			execute(python);
		}
	}

	private static class Worker implements AutoCloseable {
		private final BlockingQueue<PythonConsumer> queue;
		private final String init;
		private final Thread workerThread;

		private boolean isClosed = false;
		private final CountDownLatch pythonReady = new CountDownLatch(1);

		Worker(BlockingQueue<PythonConsumer> queue, String init, String name) throws InterruptedException {
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
					final PythonConsumer task = poll();
					if (task == null)
						continue;

					task.accept(python);
				}
				try {
					python.close();
				} catch (JepException e) {
					throw new RuntimeException(e);
				}
			});
			this.workerThread.setDaemon(true);
			if (name != null)
				this.workerThread.setName(name);
			this.workerThread.start();
			pythonReady.await();
		}


		private PythonConsumer poll() {
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
					"    dim: tuple",
					"    halo: tuple"
			};
			final String init = String.join("\n", lines);
			python.exec(init);
			if (initBlock != null)
				python.exec(initBlock);
		}

		@Override
		public void close() {
			this.isClosed = true;
		}

	}

	private final String init;
	private final List<Worker> workers = new ArrayList<>();
	private final LinkedBlockingDeque<PythonConsumer> queue = new LinkedBlockingDeque<>();

	public PythonWorkerQueue(final int numWorkers, final String init) throws InterruptedException {
		this.init = init;
		for (int w = 0; w < numWorkers; ++w) {
			this.workers.add(new Worker(queue, this.init, "Python-" + w));
		}
	}

	public <T> PythonFuture<T> submit(final PythonTask<T> task) {
		final PythonExecution<T> r = new PythonExecution<>(task);
		this.queue.add(r);
		return new PythonFuture<>(r::getResultOrThrow, r.latch);
	}

	public void close() {
		for (final Worker worker : workers)
			if (worker != null) worker.close();
	}
}
