package net.imglib2.cache.python;

import jep.JepException;

public class PythonCacheLoaderQueue extends PythonWorkerQueue {

	private static final String INIT_BLOCK = String.join(
			"\n",
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
	);

	public PythonCacheLoaderQueue() throws InterruptedException, JepException {
		this(1);
	}

	public PythonCacheLoaderQueue(int numWorkers) throws InterruptedException, JepException {
		this(numWorkers, null);
	}

	public PythonCacheLoaderQueue(int numWorkers, String init) throws InterruptedException, JepException {
		super(numWorkers, String.join("\n", INIT_BLOCK, init == null ? "" : init));
	}
}
