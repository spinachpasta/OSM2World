package org.osm2world.core.world.modules;

import static java.util.Arrays.asList;
import static org.osm2world.core.target.common.material.Materials.TREE_CROWN;
import static org.osm2world.core.target.common.material.Materials.TREE_TRUNK;
import static org.osm2world.core.world.modules.common.WorldModuleGeometryUtil.filterWorldObjectCollisions;
import static org.osm2world.core.world.modules.common.WorldModuleParseUtil.parseHeight;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import org.apache.commons.configuration.Configuration;
import org.osm2world.core.map_data.data.*;
import org.osm2world.core.map_data.data.overlaps.MapOverlap;
import org.osm2world.core.map_elevation.creation.EleConstraintEnforcer;
import org.osm2world.core.map_elevation.data.EleConnector;
import org.osm2world.core.map_elevation.data.GroundState;
import org.osm2world.core.math.GeometryUtil;
import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.math.VectorXZ;
import org.osm2world.core.target.Target;
import org.osm2world.core.target.common.FaceTarget;
import org.osm2world.core.target.common.material.Material;
import org.osm2world.core.target.common.material.Materials;
import org.osm2world.core.target.common.mesh.ExtrusionGeometry;
import org.osm2world.core.target.common.mesh.Mesh;
import org.osm2world.core.target.common.model.InstanceParameters;
import org.osm2world.core.target.common.model.Model;
import org.osm2world.core.target.povray.POVRayTarget;
import org.osm2world.core.target.povray.RenderableToPOVRay;
import org.osm2world.core.world.data.*;
import org.osm2world.core.world.modules.common.ConfigurableWorldModule;
import org.osm2world.core.world.modules.common.WorldModuleBillboardUtil;

/**
 * adds trees, tree rows, tree groups and forests to the world
 */
public class TreeModule extends ConfigurableWorldModule {

	private static final List<String> LEAF_TYPE_KEYS = asList("leaf_type", "wood");

	private static enum LeafType {

		BROADLEAVED("broadleaved", "deciduous"),
		NEEDLELEAVED("needleleaved", "coniferous");

		private final List<String> values;

		private LeafType(String... values) {
			this.values = asList(values);
		}

		public static LeafType getValue(TagSet tags) {
			for (LeafType type : values()) {
				if (tags.containsAny(LEAF_TYPE_KEYS, type.values)) {
					return type;
				}
			}
			return null;
		}

	}

	private static final List<String> LEAF_CYCLE_KEYS = asList("leaf_cycle", "wood");

	private static enum LeafCycle {

		EVERGREEN("evergreen"),
		DECIDUOUS("deciduous"),
		SEMI_EVERGREEN("semi_evergreen"),
		SEMI_DECIDUOUS("semi_deciduous");

		private final List<String> values;

		private LeafCycle(String... values) {
			this.values = asList(values);
		}

		public static LeafCycle getValue(TagSet tags) {
			for (LeafCycle type : values()) {
				if (tags.containsAny(LEAF_CYCLE_KEYS, type.values)) {
					return type;
				}
			}
			return null;
		}

	}

	private static enum TreeSpecies {

		APPLE_TREE("malus");

		private final String value;

		private TreeSpecies(String value) {
			this.value = value;
		}

		public static TreeSpecies getValue(TagSet tags) {

			String speciesString = tags.getValue("species");

			if (speciesString != null) {

				for (TreeSpecies species : values()) {
					if (speciesString.contains(species.value)) {
						return species;
					}
				}

			}

			// default to apple trees for orchards

			if (tags.contains("landuse", "orchard")) {
				return APPLE_TREE;
			} else {
				return null;
			}

		}

	}

	private boolean useBillboards = false;
	private double defaultTreeHeight = 10;
	private double defaultTreeHeightForest = 20;

	@Override
	public void setConfiguration(Configuration config) {
		super.setConfiguration(config);
		useBillboards = config.getBoolean("useBillboards", false);
		defaultTreeHeight = config.getDouble("defaultTreeHeight", 10);
		defaultTreeHeightForest = config.getDouble("defaultTreeHeightForest", 20);
	}

	@Override
	public final void applyTo(MapData mapData) {

		for (MapNode node : mapData.getMapNodes()) {

			if (node.getTags().contains("natural", "tree")) {
				node.addRepresentation(new Tree(node));
			}

		}

		for (MapWaySegment segment : mapData.getMapWaySegments()) {
			if (segment.getTags().contains(new Tag("natural", "tree_row"))) {
				segment.addRepresentation(new TreeRow(segment));
			}
		}

		for (MapArea area : mapData.getMapAreas()) {

			if (area.getTags().contains("natural", "wood")
					|| area.getTags().contains("landuse", "forest")
					|| area.getTags().contains("landcover", "trees")
					|| area.getTags().containsKey("wood")
					|| area.getTags().contains("landuse", "orchard")) {
				area.addRepresentation(new Forest(area, mapData));
			}

		}

	}

	private static final float TREE_RADIUS_PER_HEIGHT = 0.2f;

	/**
	 * parse height (for forests, add some random factor)
	 */
	private double getTreeHeight(MapElement element,
			boolean isConiferousTree, boolean isFruitTree) {

		float heightFactor = 1;
		if (element instanceof MapArea) {
			heightFactor = 0.5f + 0.75f * (float)Math.random();
		}

		double defaultHeight = defaultTreeHeight;
		if (element instanceof MapArea && !isFruitTree) {
			defaultHeight = defaultTreeHeightForest;
		}

		return heightFactor *
				parseHeight(element.getTags(), (float)defaultHeight);

	}

	private POVRayTarget previousDeclarationTarget = null;

	private void addTreeDeclarationsTo(POVRayTarget target) {
		if (target != previousDeclarationTarget) {

			//TODO support any combination of leaf type and leaf cycle

			previousDeclarationTarget = target;

			target.append("#ifndef (broad_leaved_tree)\n");
			target.append("#declare broad_leaved_tree = object { union {\n");
			target.drawModel(new TreeGeometryModel(LeafType.BROADLEAVED, LeafCycle.DECIDUOUS, null),
					new InstanceParameters(VectorXYZ.NULL_VECTOR, 0, 1.0, null, null));
			target.append("} }\n#end\n\n");

			target.append("#ifndef (coniferous_tree)\n");
			target.append("#declare coniferous_tree = object { union {\n");
			target.drawModel(new TreeGeometryModel(LeafType.NEEDLELEAVED, LeafCycle.EVERGREEN, null),
					new InstanceParameters(VectorXYZ.NULL_VECTOR, 0, 1.0, null, null));
			target.append("} }\n#end\n\n");

		}
	}

	private void renderTreePovrayModel(POVRayTarget target, MapElement element, VectorXYZ pos,
			LeafType leafType, LeafCycle leafCycle, TreeSpecies species) {

		boolean isConiferousTree = (leafType == LeafType.NEEDLELEAVED);

		double height = getTreeHeight(element, isConiferousTree, false);

		//rotate randomly for variation
		float yRotation = (float) Math.random() * 360;

		//add union of stem and leaves
		if (isConiferousTree) {
			target.append("object { coniferous_tree rotate ");
		} else {
			target.append("object { broad_leaved_tree rotate ");
		}

		target.append(Float.toString(yRotation));
		target.append("*y scale ");
		target.append(height);
		target.append(" translate ");
		target.appendVector(pos.x, 0, pos.z);
		target.append(" }\n");

	}

	private void renderTreeModel(Target target, MapElement element, VectorXYZ base,
			LeafType leafType, LeafCycle leafCycle, TreeSpecies species) {

		// "random" decision to flip the tree texture based on z coord
		boolean mirrored = (long)(base.getZ() * 1000) % 2 == 0;

		// if leaf type is unknown, make another "random" decision based on x coord
		if (leafType == null) {
			if ((long)(base.getX() * 1000) % 2 == 0) {
				leafType = LeafType.NEEDLELEAVED;
			} else {
				leafType = LeafType.BROADLEAVED;
			}
		}

		double height = getTreeHeight(element, leafType == LeafType.NEEDLELEAVED, species != null);

		TreeModel model = null;

		for (TreeModel existingModel : existingModels) {
			if (existingModel.leafType() == leafType
					&& existingModel.leafCycle() == leafCycle
					&& existingModel.species() == species
					&& existingModel.mirrored() == mirrored
					&& (existingModel instanceof TreeBillboardModel) == useBillboards) {
				model = existingModel;
				break;
			}
		}

		if (model == null) {
			model = useBillboards
					? new TreeBillboardModel(leafType, leafCycle, species, mirrored)
					: new TreeGeometryModel(leafType, leafCycle, species);
			existingModels.add(model);
		}

		target.drawModel(model, new InstanceParameters(base, 0, height, null, null));

	}

	private interface TreeModel extends Model {

		LeafType leafType();
		LeafCycle leafCycle();
		@Nullable TreeSpecies species();
		boolean mirrored();

	}

	private record TreeBillboardModel(
			LeafType leafType,
			LeafCycle leafCycle,
			@Nullable TreeSpecies species,
			boolean mirrored
	) implements TreeModel {

		@Override
		public List<Mesh> buildMeshes(InstanceParameters params) {

			Material material = species == TreeSpecies.APPLE_TREE
					? Materials.TREE_BILLBOARD_BROAD_LEAVED_FRUIT
					: leafType == LeafType.NEEDLELEAVED
					? Materials.TREE_BILLBOARD_CONIFEROUS
					: Materials.TREE_BILLBOARD_BROAD_LEAVED;

			return WorldModuleBillboardUtil.buildCrosstree(material, params.position(),
					(species != null ? 1.0 : 0.5) * params.height(), params.height(), mirrored);

		}

	}

	private record TreeGeometryModel(
			LeafType leafType,
			LeafCycle leafCycle,
			@Nullable TreeSpecies species
	) implements TreeModel {

		@Override
		public boolean mirrored() {
			return false;
		}

		@Override
		public List<Mesh> buildMeshes(InstanceParameters params) {

			double height = params.height();
			VectorXYZ posXYZ = params.position();

			boolean coniferous = (leafType == LeafType.NEEDLELEAVED);

			double stemRatio = coniferous?0.3:0.5;
			double radius = height*TREE_RADIUS_PER_HEIGHT;

			ExtrusionGeometry trunk = ExtrusionGeometry.createColumn(null,
					posXYZ, height*stemRatio,radius / 4, radius / 5,
					false, true, null, TREE_TRUNK.getTextureDimensions());

			ExtrusionGeometry crown = ExtrusionGeometry.createColumn(null,
					posXYZ.addY(height*stemRatio), height*(1-stemRatio), radius, coniferous ? 0 : radius,
					true, true, null, TREE_CROWN.getTextureDimensions());

			return List.of(
					new Mesh(trunk, TREE_TRUNK),
					new Mesh(crown, TREE_CROWN)
			);

		}

	}

	private final List<TreeModel> existingModels = new ArrayList<>();

	public class Tree extends NoOutlineNodeWorldObject implements RenderableToPOVRay, LegacyWorldObject {

		private final LeafType leafType;
		private final LeafCycle leafCycle;
		private final TreeSpecies species;

		public Tree(MapNode node) {

			super(node);

			LeafType leafType = LeafType.getValue(node.getTags());
			LeafCycle leafCycle = LeafCycle.getValue(node.getTags());
			TreeSpecies species = TreeSpecies.getValue(node.getTags());

			Optional<MapWaySegment> parentTreeRow = node.getConnectedWaySegments().stream()
					.filter(s -> s.getTags().contains("natural", "tree_row")).findAny();
			if (parentTreeRow.isPresent()) {
				// inherit information from the tree row this tree belongs to
				if (leafType == null) leafType = LeafType.getValue(parentTreeRow.get().getTags());
				if (leafCycle == null) leafCycle = LeafCycle.getValue(parentTreeRow.get().getTags());
				if (species == null) species = TreeSpecies.getValue(parentTreeRow.get().getTags());
			}

			this.leafType = leafType;
			this.leafCycle = leafCycle;
			this.species = species;
		}

		@Override
		public GroundState getGroundState() {
			return GroundState.ON;
		}

		@Override
		public void renderTo(Target target) {
			if (target instanceof POVRayTarget) {
				renderTreePovrayModel((POVRayTarget)target, node, getBase(), leafType, leafCycle, species);
			} else {
				renderTreeModel(target, node, getBase(), leafType, leafCycle, species);
			}
		}

		@Override
		public void addDeclarationsTo(POVRayTarget target) {
			addTreeDeclarationsTo(target);
		}

	}

	public class TreeRow implements WaySegmentWorldObject, RenderableToPOVRay, LegacyWorldObject {

		private final MapWaySegment segment;

		private final List<EleConnector> treeConnectors;

		private final LeafType leafType;
		private final LeafCycle leafCycle;
		private final TreeSpecies species;

		public TreeRow(MapWaySegment segment) {

			this.segment = segment;

			/* determine details about the trees in the row */

			leafType = LeafType.getValue(segment.getTags());
			leafCycle = LeafCycle.getValue(segment.getTags());
			species = TreeSpecies.getValue(segment.getTags());

			/* place trees along the way this segment belongs to */

			List<VectorXZ> treePositions = new ArrayList<>(GeometryUtil.equallyDistributePointsAlong(
					4 /* TODO: derive from tree count */ ,
					true,
					segment.getWay().getPolylineXZ()));

			/* delete implicit trees if there's already an explicit tree nearby */

			List<VectorXZ> explicitTreePositions = segment.getWay().getNodes().stream()
					.filter(n -> n.getTags().contains("natural", "tree"))
					.map(MapNode::getPos)
					.toList();

			treePositions.removeIf(p -> explicitTreePositions.stream().anyMatch(it -> it.distanceTo(p) < 10));

			/* create a connector for each tree position on the current segment */

			treeConnectors = treePositions.stream()
					.filter(it -> {
						if (getStartPosition().equals(it)) {
							// prevent adding a tree node to two segments if it's exactly on the shared node
							return segment.getWay().getWaySegments().indexOf(segment) == 0;
						} else if (getEndPosition().equals(it)) {
							return true;
						} else {
							return GeometryUtil.isBetween(it, getStartPosition(), getEndPosition());
						}
					})
					.map(it -> new EleConnector(it, null, getGroundState()))
					.toList();

		}

		@Override
		public MapWaySegment getPrimaryMapElement() {
			return segment;
		}

		@Override
		public Iterable<EleConnector> getEleConnectors() {
			return treeConnectors;
		}

		@Override
		public void defineEleConstraints(EleConstraintEnforcer enforcer) {}

		@Override
		public GroundState getGroundState() {
			return GroundState.ON;
		}

		@Override
		public void addDeclarationsTo(POVRayTarget target) {
			addTreeDeclarationsTo(target);
		}

		@Override
		public void renderTo(Target target) {

			for (EleConnector treeConnector : treeConnectors) {

				if (target instanceof POVRayTarget) {
					renderTreePovrayModel((POVRayTarget)target, segment, treeConnector.getPosXYZ(),
							leafType, leafCycle, species);
				} else {
					renderTreeModel(target, segment, treeConnector.getPosXYZ(),
							leafType, leafCycle, species);
				}

				if (target instanceof FaceTarget) {
					((FaceTarget)target).flushReconstructedFaces();
				}

			}

		}

		//TODO: there is significant code duplication with Forest...

	}


	public class Forest implements AreaWorldObject, RenderableToPOVRay, LegacyWorldObject {

		private final MapArea area;
		private final MapData mapData;

		private Collection<EleConnector> treeConnectors = null;

		private final LeafType leafType;
		private final LeafCycle leafCycle;
		private final TreeSpecies species;

		public Forest(MapArea area, MapData mapData) {

			this.area = area;
			this.mapData = mapData;

			leafType = LeafType.getValue(area.getTags());
			leafCycle = LeafCycle.getValue(area.getTags());
			species = TreeSpecies.getValue(area.getTags());

		}

		private void createTreeConnectors(double density) {

			/* collect other objects that the trees should not be placed on */

			Collection<WorldObject> avoidedObjects = new ArrayList<>();

			for (MapOverlap<?, ?> overlap : area.getOverlaps()) {
				for (WorldObject otherRep : overlap.getOther(area).getRepresentations()) {
					if (otherRep.getGroundState() == GroundState.ON) {
						avoidedObjects.add(otherRep);
					}
				}
			}

			/* place the trees */

			List<VectorXZ> treePositions =
				GeometryUtil.distributePointsOn(area.getId(),
						area.getPolygon(), mapData.getBoundary(),
						density, 0.3f);

			filterWorldObjectCollisions(treePositions, avoidedObjects);

			/* create a terrain connector for each tree */

			treeConnectors = treePositions.stream()
					.map(it -> new EleConnector(it, null, getGroundState()))
					.toList();

		}

		@Override
		public MapArea getPrimaryMapElement() {
			return area;
		}

		@Override
		public Iterable<EleConnector> getEleConnectors() {

			if (treeConnectors == null) {
				createTreeConnectors(config.getDouble("treesPerSquareMeter", 0.01f));
			}

			return treeConnectors;

		}

		@Override
		public void defineEleConstraints(EleConstraintEnforcer enforcer) {}

		@Override
		public GroundState getGroundState() {
			return GroundState.ON;
		}

		@Override
		public void addDeclarationsTo(POVRayTarget target) {
			addTreeDeclarationsTo(target);
		}

		@Override
		public void renderTo(Target target) {

			for (EleConnector treeConnector : treeConnectors) {

				if (target instanceof POVRayTarget) {
					renderTreePovrayModel((POVRayTarget)target, area, treeConnector.getPosXYZ(),
							leafType, leafCycle, species);
				} else {
					renderTreeModel(target, area, treeConnector.getPosXYZ(),
							leafType, leafCycle, species);
				}

				if (target instanceof FaceTarget) {
					((FaceTarget)target).flushReconstructedFaces();
				}

			}

		}

	}

}
