package net.imglib2.cache.python;

import jep.SharedInterpreter;

interface PythonTask<T> {
	T execute(SharedInterpreter python) throws Exception;

	interface Runnable extends PythonTask<Void> {
		void run(SharedInterpreter python) throws Exception;

		@Override
		default Void execute(final SharedInterpreter python) throws Exception {
			run(python);
			return null;
		}
	}
}
