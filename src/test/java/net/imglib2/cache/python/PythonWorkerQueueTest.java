package net.imglib2.cache.python;

import jep.JepException;
import org.junit.Assert;
import org.junit.Test;

public class PythonWorkerQueueTest {

	@Test
	public void testSimplePythonTask() throws Exception {
		final long value = 42;
		final PythonTask<Long> task = python -> {
			python.exec("a = " + value);
			return python.getValue("a", Long.class);
		};
		try (final PythonWorkerQueue queue = new PythonWorkerQueue()) {
			Assert.assertEquals(value, (long) queue.submit(task).get());
		}
	}

	@Test(expected = JepException.class)
	public void testThrowsExecutionException() throws Exception {
		try (final PythonWorkerQueue queue = new PythonWorkerQueue()) {
			queue.submit(python -> python.exec("1/0")).get();
			Assert.fail("Expected exception in Python exception.");
		}
	}

	@Test(expected = JepException.class)
	public void testThrowsInitException() throws Exception {
		try (final PythonWorkerQueue ignored = new PythonWorkerQueue(1, "1/0")) {
			Assert.fail("Expected exception in interpreter initialization.");
		}
	}

}