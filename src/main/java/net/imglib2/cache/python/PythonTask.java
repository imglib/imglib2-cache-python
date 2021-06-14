package net.imglib2.cache.python;

import jep.SharedInterpreter;

interface PythonTask<T> {
	T execute(SharedInterpreter python) throws Exception;
}
