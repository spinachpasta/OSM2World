package org.osm2world.core.target.common.model;

import java.util.List;

import org.osm2world.core.target.CommonTarget;
import org.osm2world.core.target.Target;
import org.osm2world.core.target.common.MeshTarget;
import org.osm2world.core.target.common.mesh.Mesh;

/**
 * a {@link Model} that still uses "draw" methods of {@link Target}
 * instead of the new {@link #buildMeshes(InstanceParameters)} method.
 * This exists to smooth the transition.
 */
public interface LegacyModel extends Model {

	@Override
	public default List<Mesh> buildMeshes(InstanceParameters params) {
		MeshTarget target = new MeshTarget();
		this.render(target, params);
		return target.getMeshes();
	}

	@Override
	void render(CommonTarget target, InstanceParameters params);

}
