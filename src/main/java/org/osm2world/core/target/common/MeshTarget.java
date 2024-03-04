package org.osm2world.core.target.common;

import static java.awt.Color.WHITE;
import static java.lang.Math.min;
import static java.util.Arrays.stream;
import static java.util.Collections.nCopies;
import static java.util.stream.Collectors.toList;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.osm2world.core.math.TriangleXYZ;
import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.math.VectorXZ;
import org.osm2world.core.math.shapes.SimpleClosedShapeXZ;
import org.osm2world.core.target.Target;
import org.osm2world.core.target.common.MeshStore.MeshMetadata;
import org.osm2world.core.target.common.MeshStore.MeshProcessingStep;
import org.osm2world.core.target.common.MeshStore.MeshWithMetadata;
import org.osm2world.core.target.common.material.*;
import org.osm2world.core.target.common.material.TextureLayer.TextureType;
import org.osm2world.core.target.common.mesh.*;
import org.osm2world.core.util.color.LColor;
import org.osm2world.core.world.data.WorldObject;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

/**
 * a {@link Target} that collects everything that is being drawn as {@link Mesh}es
 */
public class MeshTarget extends AbstractTarget {

	protected final MeshStore meshStore = new MeshStore();

	protected WorldObject currentWorldObject = null;

	@Override
	public void beginObject(WorldObject object) {
		this.currentWorldObject = object;
	}

	@Override
	public void drawMesh(Mesh mesh) {

		MeshMetadata metadata = (currentWorldObject != null)
				? new MeshMetadata(currentWorldObject.getPrimaryMapElement().getElementWithId(),
						currentWorldObject.getClass())
				: new MeshMetadata(null, null);

		meshStore.addMesh(mesh, metadata);

	}

	public List<Mesh> getMeshes() {
		return meshStore.meshes();
	}

	public static class FilterLod implements MeshProcessingStep {

		private final LevelOfDetail targetLod;

		public FilterLod(LevelOfDetail targetLod) {
			this.targetLod = targetLod;
		}

		@Override
		public MeshStore apply(MeshStore meshStore) {
			return new MeshStore(meshStore.meshesWithMetadata().stream()
					.filter(m -> m.mesh().lodRangeContains(targetLod))
					.collect(toList()));
		}

	}

	public static class MergeMeshes implements MeshProcessingStep {

		/** options that alter the behavior away from the default */
		public enum MergeOption {

			/**
			 * whether meshes should be merged across distinct OSM elements.
			 * If this is enabled, the result will not have any {@link MeshMetadata}.
			 */
			MERGE_ELEMENTS,

			/** whether meshes should be kept separate if they have different {@link Material#getInterpolation()} */
			SEPARATE_NORMAL_MODES,

			/** whether meshes should be kept separate if they have different {@link Material#getColor()} */
			SINGLE_COLOR_MESHES,

			/**
			 * whether specialized {@link Geometry} types (such as {@link ExtrusionGeometry} and {@link ShapeGeometry})
			 * should be preserved in separate meshes instead of being converted to {@link TriangleGeometry}
			 */
			PRESERVE_GEOMETRY_TYPES

		}

		private final Set<MergeOption> options;

		public MergeMeshes(Set<MergeOption> options) {
			this.options = options;
		}

		/** checks if two meshes should be merged according to the MergeOptions */
		public boolean shouldBeMerged(MeshWithMetadata m1, MeshWithMetadata m2) {

			if (m1.mesh().lodRangeMin != m2.mesh().lodRangeMin
					|| m1.mesh().lodRangeMax != m2.mesh().lodRangeMax) {
				return false;
			}

			if (!options.contains(MergeOption.MERGE_ELEMENTS)
					&& !Objects.equals(m1.metadata(), m2.metadata())) {
				return false;
			}

			if (options.contains(MergeOption.PRESERVE_GEOMETRY_TYPES)
					&& (!Objects.equals(m1.mesh().geometry.getClass(), TriangleGeometry.class)
							|| !Objects.equals(m2.mesh().geometry.getClass(), TriangleGeometry.class))) {
				return false;
			}

			return m1.mesh().material.equals(m2.mesh().material,
					!options.contains(MergeOption.SEPARATE_NORMAL_MODES),
					!options.contains(MergeOption.SINGLE_COLOR_MESHES));

		}

		@Override
		public MeshStore apply(MeshStore meshStore) {

			/* form sets of meshes that should be merged with each other.
			 * To improve performance, sets are indexed by the hash code of the mesh material's texture layer list. */

			Multimap<Integer, List<MeshWithMetadata>> meshSetsByHashCode = HashMultimap.create();

			meshLoop:
			for (MeshWithMetadata mesh : meshStore.meshesWithMetadata()) {

				int hashCode = mesh.mesh().material.getTextureLayers().hashCode();
				if (!options.contains(MergeOption.MERGE_ELEMENTS)) {
					hashCode ^= mesh.metadata().hashCode();
				}

				for (List<MeshWithMetadata> set : meshSetsByHashCode.get(hashCode)) {
					if (shouldBeMerged(mesh, set.get(0))) {
						set.add(mesh);
						continue meshLoop;
					}
				}

				ArrayList<MeshWithMetadata> newSet = new ArrayList<>();
				newSet.add(mesh);
				meshSetsByHashCode.put(hashCode, newSet);

			}

			/* merge meshes in the same set to produce the result */

			List<MeshWithMetadata> result = new ArrayList<>();

			for (List<MeshWithMetadata> meshSet : meshSetsByHashCode.values()) {
				result.add(MeshWithMetadata.merge(meshSet));
			}

			return new MeshStore(result);

		}

	}

	/** replaces meshes that have multiple layers of textures with multiple meshes, each of which have only one layer */
	public static class EmulateTextureLayers implements MeshProcessingStep {

		private static final double OFFSET_PER_LAYER = 1e-3;

		/** maximum number of layers for which geometry is created, additional ones are omitted */
		private final int maxLayers;

		public EmulateTextureLayers(int maxLayers) {
			this.maxLayers = maxLayers;
		}

		public EmulateTextureLayers() {
			this.maxLayers = Integer.MAX_VALUE;
		}

		@Override
		public MeshStore apply(MeshStore meshStore) {

			/* replace any multi-layer meshes with multiple meshes */

			List<MeshWithMetadata> result = new ArrayList<>();

			for (MeshWithMetadata meshWithMetadata : meshStore.meshesWithMetadata()) {

				Mesh mesh = meshWithMetadata.mesh();

				if (mesh.material.getNumTextureLayers() <= 1) {
					result.add(meshWithMetadata);
				} else {

					TriangleGeometry tg = mesh.geometry.asTriangles();

					for (int layer = 0; layer < min(maxLayers, mesh.material.getNumTextureLayers()); layer++) {

						double offset = layer * OFFSET_PER_LAYER;

						TriangleGeometry.Builder builder = new TriangleGeometry.Builder(1, null, null);
						List<TriangleXYZ> offsetTriangles = tg.triangles.stream()
								.map(t -> t.shift(t.getNormal().mult(offset)))
								.collect(toList());
						List<List<VectorXZ>> texCoords = List.of(tg.texCoords.get(layer));
						builder.addTriangles(offsetTriangles, texCoords, tg.colors, tg.normalData.normals());
						TriangleGeometry newGeometry = builder.build();

						Material singleLayerMaterial = mesh.material
								.withTransparency(layer > 0 ? Material.Transparency.BINARY : null)
								.withLayers(List.of(mesh.material.getTextureLayers().get(layer)));

						Mesh newMesh = new Mesh(newGeometry, singleLayerMaterial, mesh.lodRangeMin, mesh.lodRangeMax);

						result.add(new MeshWithMetadata(newMesh, meshWithMetadata.metadata()));

					}

				}
			}

			return new MeshStore(result);

		}

	}

	/** adds the {@link Material}'s colors directly to the {@link Mesh} as vertex colors */
	public static class MoveColorsToVertices implements MeshProcessingStep {

		@Override
		public MeshStore apply(MeshStore meshStore) {

			List<MeshWithMetadata> result = new ArrayList<>();

			for (MeshWithMetadata meshWithMetadata : meshStore.meshesWithMetadata()) {

				if (meshWithMetadata.mesh().material.getTextureLayers().stream().noneMatch(it -> it.colorable)) {
					result.add(meshWithMetadata);
					continue;
				}

				Mesh mesh = meshWithMetadata.mesh();
				Material newMaterial = mesh.material.withColor(WHITE);
				Geometry newGeometry;

				if (mesh.geometry instanceof TriangleGeometry tg) {

					List<Color> colors = (tg.colors != null) ? tg.colors
							: new ArrayList<>(nCopies(tg.vertices().size(), WHITE));
					for (int i = 0; i < colors.size(); i++) {
						colors.set(i, LColor.fromAWT(colors.get(i)).multiply(mesh.material.getLColor()).toAWT());
					}

					TriangleGeometry.Builder builder = new TriangleGeometry.Builder(tg.texCoords.size(), null, null);
					builder.addTriangles(tg.triangles, tg.texCoords, colors, tg.normalData.normals());
					newGeometry = builder.build();

				} else if (mesh.geometry instanceof ShapeGeometry sg) {

					LColor existingColor = sg.color == null ? LColor.WHITE : LColor.fromAWT(sg.color);
					LColor newColor = existingColor.multiply(mesh.material.getLColor());

					newGeometry = new ShapeGeometry(sg.shape, sg.point, sg.frontVector, sg.upVector, sg.scaleFactor,
							newColor.toAWT(), sg.normalMode, sg.textureDimensions);

				} else if (mesh.geometry instanceof ExtrusionGeometry eg) {

					LColor existingColor = eg.color == null ? LColor.WHITE : LColor.fromAWT(eg.color);
					LColor newColor = existingColor.multiply(LColor.fromAWT(mesh.material.getColor()));

					newGeometry = new ExtrusionGeometry(eg.shape, eg.path, eg.upVectors, eg.scaleFactors,
							newColor.toAWT(), eg.options, eg.textureDimensions);

				} else {
					throw new Error("unsupported geometry type: " + mesh.geometry.getClass());
				}

				result.add(new MeshWithMetadata(new Mesh(newGeometry, newMaterial), meshWithMetadata.metadata()));

			}

			return new MeshStore(result);

		}

	}

	public static class ReplaceTexturesWithAtlas implements MeshProcessingStep {

		/** a group of {@link TextureAtlas}es, one for each texture type in a {@link TextureLayer} */
		public static class TextureAtlasGroup {

			public final TextureAtlas baseColorAtlas, normalAtlas, ormAtlas, displacementAtlas;

			private TextureAtlasGroup(Set<TextureLayer> textureLayers) {

				Map<TextureType, List<TextureData>> map = new HashMap<>();

				for (TextureType type : TextureType.values()) {
					map.put(type, textureLayers.stream()
							.map(l -> l.getTexture(type))
							.map(t -> t != null ? t : BlankTexture.INSTANCE)
							.collect(toList()));
				}

				this.baseColorAtlas = new TextureAtlas(map.get(TextureType.BASE_COLOR));
				this.normalAtlas = new TextureAtlas(map.get(TextureType.NORMAL));
				this.ormAtlas = new TextureAtlas(map.get(TextureType.ORM));
				this.displacementAtlas = new TextureAtlas(map.get(TextureType.DISPLACEMENT));

			}

			public TextureAtlas getTextureAtlas(TextureType type) {
				switch (type) {
				case BASE_COLOR: return baseColorAtlas;
				case NORMAL: return normalAtlas;
				case ORM: return ormAtlas;
				case DISPLACEMENT: return displacementAtlas;
				default: throw new Error();
				}
			}

			public boolean canReplaceLayer(TextureLayer layer) {

				int index = baseColorAtlas.textures.indexOf(layer.baseColorTexture);

				return index >= 0 && stream(TextureType.values()).allMatch(type -> layer.getTexture(type) == null
						|| index == getTextureAtlas(type).textures.indexOf(layer.getTexture(type)));

			}

		}

		public final @Nullable TextureAtlasGroup textureAtlasGroup;
		public final Predicate<TextureData> excludeFromAtlas;

		/**
		 * @param textureAtlasGroup  a pre-existing group of texture atlases that will be used.
		 *   If none is provided, such a group will be generated separately for each {@link MeshStore}
		 */
		public ReplaceTexturesWithAtlas(@Nullable TextureAtlasGroup textureAtlasGroup) {
			this.textureAtlasGroup = textureAtlasGroup;
			this.excludeFromAtlas = x -> false;
		}

		/**
		 * @param excludeFromAtlas  identifies texture layers which should never be included in a texture atlas
		 */
		public ReplaceTexturesWithAtlas(Predicate<TextureData> excludeFromAtlas) {
			this.textureAtlasGroup = null;
			this.excludeFromAtlas = excludeFromAtlas;
		}

		public ReplaceTexturesWithAtlas() {
			this((TextureAtlasGroup) null);
		}

		@Override
		public MeshStore apply(MeshStore meshStore) {

			TextureAtlasGroup atlasGroup = textureAtlasGroup != null
					? textureAtlasGroup
					: generateTextureAtlasGroup(List.of(meshStore), excludeFromAtlas);

			if (atlasGroup == null) {
				return meshStore;
			} else {
				return replaceTexturesWithAtlas(meshStore, atlasGroup);
			}

		}

		/**
		 * finds suitable textures in one or more {@link MeshStore}s and creates a {@link TextureAtlasGroup} for them.
		 * @param excludeFromAtlas  identifies texture layers which should never be included in a texture atlas
		 * @return  the {@link TextureAtlasGroup} with all suitable textures, or null if no suitable textures exist
		 */
		public static @Nullable TextureAtlasGroup generateTextureAtlasGroup(
				Iterable<MeshStore> meshStores, Predicate<TextureData> excludeFromAtlas) {

			Set<TextureLayer> textureLayersForAtlas = new HashSet<>();
			for (MeshStore meshStore : meshStores) {
				for (Mesh mesh : meshStore.meshes()) {
					for (TextureLayer textureLayer : mesh.material.getTextureLayers()) {
						if (textureLayer.textures().stream().noneMatch(excludeFromAtlas)) {
							textureLayersForAtlas.add(textureLayer);
						}
					}
				}
			}

			for (MeshStore meshStore : meshStores) {
				for (Mesh mesh : meshStore.meshes()) {
					List<List<VectorXZ>> texCoordLists = mesh.geometry.asTriangles().texCoords;
					for (int layer = 0; layer < mesh.material.getNumTextureLayers(); layer++) {
						if (texCoordLists.get(layer).stream().anyMatch(t -> t.x < 0 || t.x > 1 || t.z < 0 || t.z > 1)) {
							// texture is accessed at a tex coordinate outside the [0;1] range, it should not be in an atlas
							textureLayersForAtlas.remove(mesh.material.getTextureLayers().get(layer));
						}
					}
				}
			}

			if (textureLayersForAtlas.isEmpty()) {
				return null;
			} else {
				return new TextureAtlasGroup(textureLayersForAtlas);
			}

		}

		/** replaces textures with the atlas texture and translate texture coordinates */
		private static MeshStore replaceTexturesWithAtlas(MeshStore meshStore, TextureAtlasGroup atlasGroup) {

			List<MeshWithMetadata> result = new ArrayList<>();

			for (MeshWithMetadata meshWithMetadata : meshStore.meshesWithMetadata()) {

				Mesh mesh = meshWithMetadata.mesh();

				if (!mesh.material.getTextureLayers().stream().anyMatch(atlasGroup::canReplaceLayer)) {
					result.add(meshWithMetadata);
				} else {

					TriangleGeometry tg = mesh.geometry.asTriangles();

					List<TextureLayer> newTextureLayers = new ArrayList<>(mesh.material.getTextureLayers());
					List<List<VectorXZ>> newTexCoords = new ArrayList<>(tg.texCoords);

					for (int layer = 0; layer < newTextureLayers.size(); layer ++) {

						TextureLayer oldLayer = newTextureLayers.get(layer);

						if (atlasGroup.canReplaceLayer(oldLayer)) {
							TextureLayer newLayer = new TextureLayer(atlasGroup.baseColorAtlas,
									oldLayer.normalTexture == null ? null : atlasGroup.normalAtlas,
									oldLayer.ormTexture == null ? null : atlasGroup.ormAtlas,
									oldLayer.displacementTexture == null ? null : atlasGroup.displacementAtlas,
									true);
							newTextureLayers.set(layer, newLayer);
							newTexCoords.set(layer, atlasGroup.baseColorAtlas.mapTexCoords(
									oldLayer.baseColorTexture, newTexCoords.get(layer)));
						}

					}

					TriangleGeometry.Builder builder = new TriangleGeometry.Builder(tg.texCoords.size(), null, null);
					builder.addTriangles(tg.triangles, newTexCoords, tg.colors, tg.normalData.normals());

					Material newMaterial = mesh.material.withLayers(newTextureLayers);
					Mesh newMesh = new Mesh(builder.build(), newMaterial, mesh.lodRangeMin, mesh.lodRangeMax);

					result.add(new MeshWithMetadata(newMesh, meshWithMetadata.metadata()));

				}
			}

			return new MeshStore(result);

		}

	}

	/** removes all geometry outside a bounding shape */
	public static class ClipToBounds implements MeshProcessingStep {

		private final SimpleClosedShapeXZ bounds;

		public ClipToBounds(SimpleClosedShapeXZ bounds) {
			this.bounds = bounds;
		}

		@Override
		public MeshStore apply(MeshStore meshStore) {

			List<MeshWithMetadata> result = new ArrayList<>();

			for (MeshWithMetadata meshWithMetadata : meshStore.meshesWithMetadata()) {

				Mesh mesh = meshWithMetadata.mesh();
				TriangleGeometry tg = mesh.geometry.asTriangles();

				/* mark triangles outside the bounds for removal */

				Set<TriangleXYZ> trianglesToRemove = new HashSet<>();

				for (TriangleXYZ t : tg.triangles) {
					if (!bounds.contains(t.getCenter().xz())) {
						trianglesToRemove.add(t);
					}
				}

				/* build a new mesh without the triangles outside the bounds */

				if (trianglesToRemove.size() == tg.triangles.size()) {
					// entire mesh has been removed
				} else if (trianglesToRemove.isEmpty()) {
					result.add(meshWithMetadata);
				} else {

					List<VectorXYZ> normals = tg.normalData.normals();

					List<TriangleXYZ> newTriangles = new ArrayList<>();
					List<Color> newColors = tg.colors == null ? null : new ArrayList<>();
					List<VectorXYZ> newNormals = new ArrayList<>();
					List<List<VectorXZ>> newTexCoords = new ArrayList<>(tg.texCoords.size());

					for (int layer = 0; layer < tg.texCoords.size(); layer++) {
						newTexCoords.add(new ArrayList<>());
					}

					for (int i = 0; i < tg.triangles.size(); i++) {
						if (!trianglesToRemove.contains(tg.triangles.get(i))) {

							newTriangles.add(tg.triangles.get(i));

							for (int j = 0; j <= 2; j++) {

								if (newColors != null) {
									newColors.add(tg.colors.get(3 * i + j));
								}

								newNormals.add(normals.get(3 * i + j));

								for (int layer = 0; layer < tg.texCoords.size(); layer ++) {
									newTexCoords.get(layer).add(tg.texCoords.get(layer).get(3 * i + j));
								}

							}

						}
					}

					TriangleGeometry.Builder builder = new TriangleGeometry.Builder(newTexCoords.size(), null, null);
					builder.addTriangles(newTriangles, newTexCoords, newColors, newNormals);
					result.add(new MeshWithMetadata(new Mesh(builder.build(), mesh.material), meshWithMetadata.metadata()));

				}

			}

			return new MeshStore(result);

		}

	}

	/** replaces meshes' {@link Material}s with equivalents that omit certain texture types */
	public record RemoveTextures(EnumSet<TextureType> textureTypesToRemove) implements MeshProcessingStep {

		public RemoveTextures {
			if (textureTypesToRemove.contains(TextureType.BASE_COLOR)) {
				throw new IllegalArgumentException("Base color textures cannot be removed");
			}
		}

		@Override
		public MeshStore apply(MeshStore meshStore) {

			List<MeshWithMetadata> result = new ArrayList<>();

			for (MeshWithMetadata m : meshStore.meshesWithMetadata()) {

				Material oldMaterial = m.mesh().material;

				Material newMaterial = oldMaterial.withLayers(oldMaterial.getTextureLayers().stream().map(
						l -> new TextureLayer(
								l.baseColorTexture,
								textureTypesToRemove.contains(TextureType.NORMAL) ? null : l.normalTexture,
								textureTypesToRemove.contains(TextureType.ORM) ? null : l.ormTexture,
								textureTypesToRemove.contains(TextureType.DISPLACEMENT) ? null : l.displacementTexture,
								l.colorable
						)
				).toList());

				result.add(new MeshWithMetadata(new Mesh(m.mesh().geometry, newMaterial), m.metadata()));

			}

			return new MeshStore(result);

		}

	}

	// TODO: implement additional processing steps
	// * EmulateDoubleSidedMaterials
	// * ReplaceAlmostBlankTextures(threshold)
	// * BakeDisplacement

}