package org.osm2world.core.target.common;

import org.osm2world.core.math.TriangleXYZ;
import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.math.VectorXZ;
import org.osm2world.core.target.common.material.Material;
import org.osm2world.core.target.common.material.Material.Interpolation;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

import static org.osm2world.core.math.algorithms.NormalCalculationUtil.*;
import static org.osm2world.core.target.common.Primitive.Type.*;

/**
 * superclass for targets that are based on OpenGL primitives.
 * These targets will treat different primitives similarly:
 * They convert them all to a list of vertices
 * and represent the primitive type using an enum or flags.
 */
public abstract class PrimitiveTarget extends AbstractTarget {

	/**
	 * @param vs       vertices that form the primitive
	 * @param normals  normal vector for each vertex; same size as vs
	 * @param texCoordLists  texture coordinates for each texture layer,
	 *                       each list has the same size as vs
	 */
	abstract protected void drawPrimitive(Primitive.Type type, Material material,
			List<VectorXYZ> vs, List<VectorXYZ> normals,
			List<List<VectorXZ>> texCoordLists);

	@Override
	public void drawTriangleStrip(@Nonnull Material material, @Nonnull List<VectorXYZ> vs,
								  @Nonnull List<List<VectorXZ>> texCoordLists) {
		boolean smooth = (material.getInterpolation() == Interpolation.SMOOTH);
		drawPrimitive(TRIANGLE_STRIP, material, vs,
				calculateTriangleStripNormals(vs, smooth),
				texCoordLists);
	}

	@Override
	public void drawTriangleFan(@Nonnull Material material, @Nonnull List<VectorXYZ> vs,
								@Nonnull List<List<VectorXZ>> texCoordLists) {
		boolean smooth = (material.getInterpolation() == Interpolation.SMOOTH);
		drawPrimitive(TRIANGLE_FAN, material, vs,
				calculateTriangleFanNormals(vs, smooth),
				texCoordLists);
	}

	@Override
	public void drawTriangles(@Nonnull Material material,
							  @Nonnull List<? extends TriangleXYZ> triangles,
							  @Nonnull List<List<VectorXZ>> texCoordLists) {
		drawTriangles(material, triangles,
				calculateTriangleNormals(triangles, material.getInterpolation() == Interpolation.SMOOTH),
				texCoordLists);
	}

	@Override
	public void drawTriangles(@Nonnull Material material,
							  @Nonnull List<? extends TriangleXYZ> triangles,
							  @Nonnull List<VectorXYZ> normals,
							  @Nonnull List<List<VectorXZ>> texCoordLists) {

		List<VectorXYZ> vectors = new ArrayList<>(triangles.size() * 3);

		for (TriangleXYZ triangle : triangles) {
			vectors.add(triangle.v1);
			vectors.add(triangle.v2);
			vectors.add(triangle.v3);
		}

		drawPrimitive(TRIANGLES, material, vectors, normals, texCoordLists);

	}

}
