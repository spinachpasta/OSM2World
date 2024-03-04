package org.osm2world.core.world.modules;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toList;
import static org.osm2world.core.map_data.creation.EmptyTerrainBuilder.EMPTY_SURFACE_VALUE;
import static org.osm2world.core.map_elevation.creation.EleConstraintEnforcer.ConstraintType.MIN;
import static org.osm2world.core.map_elevation.data.GroundState.*;
import static org.osm2world.core.target.common.texcoord.NamedTexCoordFunction.GLOBAL_X_Z;
import static org.osm2world.core.target.common.texcoord.TexCoordUtil.triangleTexCoordLists;

import java.util.*;

import org.osm2world.core.map_data.creation.EmptyTerrainBuilder;
import org.osm2world.core.map_data.data.MapArea;
import org.osm2world.core.map_data.data.Tag;
import org.osm2world.core.map_data.data.TagSet;
import org.osm2world.core.map_data.data.overlaps.MapOverlap;
import org.osm2world.core.map_data.data.overlaps.MapOverlapType;
import org.osm2world.core.map_elevation.creation.EleConstraintEnforcer;
import org.osm2world.core.map_elevation.data.EleConnector;
import org.osm2world.core.map_elevation.data.GroundState;
import org.osm2world.core.math.*;
import org.osm2world.core.math.algorithms.CAGUtil;
import org.osm2world.core.math.algorithms.TriangulationUtil;
import org.osm2world.core.math.shapes.PolygonShapeXZ;
import org.osm2world.core.target.Target;
import org.osm2world.core.target.common.material.Material;
import org.osm2world.core.target.common.material.Materials;
import org.osm2world.core.world.data.AbstractAreaWorldObject;
import org.osm2world.core.world.data.LegacyWorldObject;
import org.osm2world.core.world.data.TerrainBoundaryWorldObject;
import org.osm2world.core.world.data.WorldObject;
import org.osm2world.core.world.modules.common.AbstractModule;

/**
 * adds generic areas with surface information to the world.
 * Is based on surface information on otherwise unknown/unspecified areas.
 */
public class SurfaceAreaModule extends AbstractModule {

	/** assumptions about default surfaces for certain tags */
	public static final Map<Tag, String> defaultSurfaceMap = new HashMap<>();

	static {
		defaultSurfaceMap.put(new Tag("landcover", "grass"), "grass");
		defaultSurfaceMap.put(new Tag("landcover", "gravel"), "gravel");
		defaultSurfaceMap.put(new Tag("landcover", "ground"), "ground");
		defaultSurfaceMap.put(new Tag("landuse", "construction"), "ground");
		defaultSurfaceMap.put(new Tag("landuse", "grass"), "grass");
		defaultSurfaceMap.put(new Tag("landuse", "meadow"), "grass");
		defaultSurfaceMap.put(new Tag("leisure", "pitch"), "ground");
		defaultSurfaceMap.put(new Tag("natural", "bare_rock"), "rock");
		defaultSurfaceMap.put(new Tag("natural", "beach"), "sand");
		defaultSurfaceMap.put(new Tag("natural", "fell"), "grass");
		defaultSurfaceMap.put(new Tag("natural", "glacier"), "snow");
		defaultSurfaceMap.put(new Tag("natural", "grassland"), "grass");
		defaultSurfaceMap.put(new Tag("natural", "mud"), "ground");
		defaultSurfaceMap.put(new Tag("natural", "sand"), "sand");
		defaultSurfaceMap.put(new Tag("natural", "shingle"), "pebblestone");
		defaultSurfaceMap.put(new Tag("natural", "scree"), "scree");
		defaultSurfaceMap.put(new Tag("natural", "scrub"), "scrub");
	}

	@Override
	protected void applyToArea(MapArea area) {

		if (!area.getRepresentations().isEmpty()) return;

		TagSet tags = area.getTags();

		if (tags.containsKey("surface")) {
			area.addRepresentation(new SurfaceArea(area, tags.getValue("surface")));
		} else {

			for (Tag tagWithDefault : defaultSurfaceMap.keySet()) {
				if (tags.contains(tagWithDefault)) {
					area.addRepresentation(new SurfaceArea(
							area, defaultSurfaceMap.get(tagWithDefault)));
				}
			}

		}

	}

	public static class SurfaceArea extends AbstractAreaWorldObject
			implements TerrainBoundaryWorldObject, LegacyWorldObject {

		private final String surface;

		private List<TriangleXZ> triangulationXZ;

		public SurfaceArea(MapArea area, String surface) {
			super(area);
			this.surface = surface;
		}

		@Override
		public void renderTo(Target target) {

			Material material = null;

			if (surface.equals(EMPTY_SURFACE_VALUE)) {
				material = Materials.TERRAIN_DEFAULT;
			} else {
				material = Materials.getSurfaceMaterial(surface);
			}

			if (material != null) {

				List<TriangleXYZ> triangles = getTriangulation();

				if (!triangles.isEmpty()) {
					target.drawTriangles(material, triangles,
							triangleTexCoordLists(triangles, material, GLOBAL_X_Z));
				}

			}

		}

		/**
		 * calculates the true ground footprint of this area by removing
		 * area covered by other overlapping features, then triangulates it
		 * into counterclockwise triangles.
		 */
		@Override
		protected List<TriangleXZ> getTriangulationXZ() {

			if (triangulationXZ != null) {
				return triangulationXZ;
			}

			boolean isEmptyTerrain = surface.equals(EMPTY_SURFACE_VALUE);

			/* collect the outlines of overlapping ground polygons and other polygons,
			 * and EleConnectors within the area */

			List<PolygonShapeXZ> subtractPolys = new ArrayList<>();
			List<PolygonShapeXZ> allPolys = new ArrayList<>();

			List<VectorXZ> eleConnectorPoints = new ArrayList<>();

			if (this.getGroundState() == ON) { // do not, e.g., subtract ground level objects if this is a rooftop lawn

				for (MapOverlap<?, ?> overlap : area.getOverlaps()) {
					for (WorldObject otherWO : overlap.getOther(area).getRepresentations()) {

						// TODO: A world object might overlap even if the OSM element does not (e.g. a wide highway=* way)

						if (otherWO.getGroundState() == GroundState.ON) {

							if (otherWO instanceof SurfaceArea && !isEmptyTerrain) {
								// empty terrain has lowest priority
								continue;
							}

							if (overlap.type == MapOverlapType.CONTAIN
									&& overlap.e1 == this.area) {
								// completely within other element, no ground area left
								return emptyList();
							}

							if (otherWO instanceof TerrainBoundaryWorldObject) {

								TerrainBoundaryWorldObject terrainBoundary = (TerrainBoundaryWorldObject)otherWO;

								Collection<PolygonShapeXZ> outlinePolygons = terrainBoundary.getTerrainBoundariesXZ();

								for (PolygonShapeXZ outlinePolygon : outlinePolygons) {

									subtractPolys.add(outlinePolygon);
									allPolys.add(outlinePolygon);

									for (EleConnector eleConnector : otherWO.getEleConnectors()) {

										if (!outlinePolygon.getRings().stream().anyMatch(
												p-> p.vertices().contains(eleConnector.pos))) {
											eleConnectorPoints.add(eleConnector.pos);
										}

									}

								}

							} else {
								for (EleConnector eleConnector : otherWO.getEleConnectors()) {
									eleConnectorPoints.add(eleConnector.pos);
								}
							}

						} else {

							for (EleConnector eleConnector : otherWO.getEleConnectors()) {

								if (eleConnector.reference == null) {
									/* workaround to avoid using connectors at intersections,
									 * which might fall on area segments
									 * //TODO cleaner solution
									 */
									continue;
								}

								eleConnectorPoints.add(eleConnector.pos);
							}

							if (otherWO.getOutlinePolygonXZ() != null) {
								allPolys.add(otherWO.getOutlinePolygonXZ());
							}

						}

					}
				}

			}

			/* add a grid of points within the area for smoother surface shapes */

			VectorGridXZ pointGrid = new VectorGridXZ(
					area.boundingBox(),
					EmptyTerrainBuilder.POINT_GRID_DIST);

			for (VectorXZ point : pointGrid) {

				//don't insert if it is e.g. on top of a tunnel;
				//otherwise there would be no minimum vertical distance

				boolean safe = true;

				for (PolygonShapeXZ polygon : allPolys) {
					if (polygon.contains(point)) {
						safe = false;
						break;
					}
				}

				if (safe) {
					eleConnectorPoints.add(point);
				}

			}

			/* create "leftover" polygons by subtracting the existing ones */

			Collection<PolygonWithHolesXZ> polygons;

			if (subtractPolys.isEmpty()) {

				/* SUGGEST (performance) handle the common "empty terrain cell"
				 * special case more efficiently, also regarding point raster? */

				polygons = singleton(area.getPolygon());

			} else {

				polygons = CAGUtil.subtractPolygons(
						area.getOuterPolygon(), subtractPolys);

			}

			/* triangulate, using elevation information from all participants */

			triangulationXZ = new ArrayList<TriangleXZ>();

			for (PolygonWithHolesXZ polygon : polygons) {
				List<VectorXZ> points = eleConnectorPoints.stream().filter(polygon::contains).collect(toList());
				triangulationXZ.addAll(TriangulationUtil.triangulate(polygon, points));
			}

			return triangulationXZ;

		}

		@Override
		public void defineEleConstraints(EleConstraintEnforcer enforcer) {

			super.defineEleConstraints(enforcer);

			/** add vertical distance to connectors above and below */

			for (MapOverlap<?, ?> overlap : area.getOverlaps()) {
			for (WorldObject otherWO : overlap.getOther(area).getRepresentations()) {

				for (EleConnector eleConnector : otherWO.getEleConnectors()) {

					EleConnector ownConnector = getEleConnectors().getConnector(eleConnector.pos);

					if (ownConnector == null) continue;

					if (eleConnector.groundState == ABOVE) {

						enforcer.requireVerticalDistance(
								MIN, 1,
								eleConnector, ownConnector); //TODO actual clearing

					} else if (eleConnector.groundState == BELOW) {

						enforcer.requireVerticalDistance(
								MIN, 10,
								ownConnector, eleConnector); //TODO actual clearing

					}

				}

			}
			}

		}

		@Override
		public PolygonXYZ getOutlinePolygon() {
			if (surface.equals(EMPTY_SURFACE_VALUE)) {
				// avoid interfering with e.g. tree placement
				return null;
			} else {
				return super.getOutlinePolygon();
			}
		}

		@Override
		public PolygonWithHolesXZ getOutlinePolygonXZ() {
			if (surface.equals(EMPTY_SURFACE_VALUE)) {
				// avoid interfering with e.g. tree placement
				return null;
			} else {
				return super.getOutlinePolygonXZ();
			}
		}

		@Override
		public GroundState getGroundState() {
			if (BridgeModule.isBridge(area.getTags())) {
				return GroundState.ABOVE;
			} else if (TunnelModule.isTunnel(area.getTags())) {
				return GroundState.BELOW;
			} else {
				return super.getGroundState();
			}
		}

	}

}
