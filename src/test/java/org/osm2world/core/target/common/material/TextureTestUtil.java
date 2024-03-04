package org.osm2world.core.target.common.material;

import static org.osm2world.core.target.common.texcoord.NamedTexCoordFunction.GLOBAL_X_Z;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.function.BiConsumer;

import org.osm2world.core.target.common.material.TextureData.Wrap;
import org.osm2world.core.util.Resolution;

class TextureTestUtil {

	/** prevents instantiation */
	private TextureTestUtil() { }

	static final TextureData drawTestTexture(BiConsumer<Resolution, Graphics2D> drawImpl) {
		return new RuntimeTexture(1, 1, null, null, Wrap.REPEAT, GLOBAL_X_Z) {
			@Override
			protected BufferedImage createBufferedImage() {
				Resolution res = new Resolution(128, 128);
				BufferedImage image = new BufferedImage(res.width, res.height, BufferedImage.TYPE_INT_ARGB);
				drawImpl.accept(res, image.createGraphics());
				return image;
			}
		};
	}

	static final TextureData drawSingleColorTexture(Color color) {
		return drawTestTexture((res, g2d) -> {
			g2d.setBackground(color);
			g2d.clearRect(0, 0, res.width, res.height);
		});
	}

}
