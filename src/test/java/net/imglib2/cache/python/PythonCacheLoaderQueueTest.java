package net.imglib2.cache.python;

import org.junit.Assert;
import org.junit.Test;

public class PythonCacheLoaderQueueTest {

	@Test
	public void testBlockClass() throws Exception {
		final PythonTask<Boolean> task = python -> {
			python.exec("has_class = 'Block' in dir()");
			return python.getValue("has_class", Boolean.class);
		};

		try(final PythonCacheLoaderQueue queue = new PythonCacheLoaderQueue(1)) {
			final boolean hasBlockClass = queue.submit(task).get();
			Assert.assertTrue(hasBlockClass);
		}
	}

}