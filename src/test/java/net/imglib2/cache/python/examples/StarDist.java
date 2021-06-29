package net.imglib2.cache.python.examples;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import ij.ImagePlus;
import ij.io.Opener;
import jep.JepException;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.python.Halo;
import net.imglib2.cache.python.PythonCacheLoader;
import net.imglib2.cache.python.PythonCacheLoaderQueue;
import net.imglib2.img.Img;
import net.imglib2.img.basictypeaccess.nio.BufferAccess;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;

import java.io.IOException;
import java.io.InputStream;

/**
 * Example for making predictions using stardist: https://github.com/stardist/stardist
 * Requires installation of Python with numpy, jep, tensorflow, and stardist packages installed.
 * It may be necessary to set PYTHONHOME appropriately.
 */
public class StarDist {
	public static void main(String... args) throws InterruptedException, JepException, IOException {
		final String init = String.join(
				"\n",
				"from stardist.data import test_image_nuclei_2d",
				"from stardist.models import StarDist2D",
				"from stardist.plot import render_label",
				"from csbdeep.utils import normalize",
				"model = StarDist2D.from_pretrained('2D_versatile_fluo')"
		);
		final String code = String.join(
				"\n",
				"labels, _ = model.predict_instances(normalize(block.inputs[0]))",
				"block.data[...] = labels[block.halo]"
		);
		final long[] dims = {512, 512};
		final int[] bs = {80, 90};
		final CellGrid grid = new CellGrid(dims, bs);
		final ImagePlus rawImp = getExampleImage();
		final Img<UnsignedShortType> raw = ImageJFunctions.wrapShort(rawImp);
		final PythonCacheLoader<LongType, ? extends BufferAccess<?>> loader = PythonCacheLoader.fromRandomAccessibles(
				grid,
				new PythonCacheLoaderQueue(3, init),
				code,
				new LongType(),
				new Halo(10, 10),
				Views.extendZero(raw));
		final CachedCellImg<LongType, ? extends BufferAccess<?>> img = loader.createCachedCellImg(30);

		final BdvStackSource<?> bdv = BdvFunctions.show(
				VolatileViews.wrapAsVolatile(img, new SharedQueue(10, 1)),
				"stardist",
				BdvOptions.options().numRenderingThreads(10).is2D());
		bdv.setDisplayRange(0.0, 8.0);
	}

	private static ImagePlus getExampleImage() throws IOException {
		// The test image is the test_image_nuclei_2d from stardist.data.
		// https://github.com/stardist/stardist#pretrained-models-for-2d
		try(final InputStream is = StarDist.class.getClassLoader().getResourceAsStream("test-image-nuclei-2d.tif")) {
			final Opener opener = new Opener();
			return opener.openTiff(is, "Example Image");
		}
	}
}
