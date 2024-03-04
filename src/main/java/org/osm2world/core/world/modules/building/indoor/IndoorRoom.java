package org.osm2world.core.world.modules.building.indoor;

import static java.util.Collections.emptyList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.osm2world.core.map_data.data.MapArea;
import org.osm2world.core.map_elevation.data.EleConnector;
import org.osm2world.core.target.Target;
import org.osm2world.core.world.attachment.AttachmentSurface;
import org.osm2world.core.world.data.AreaWorldObject;
import org.osm2world.core.world.data.LegacyWorldObject;
import org.osm2world.core.world.data.WorldObject;
import org.osm2world.core.world.modules.building.BuildingDefaults;

public class IndoorRoom implements AreaWorldObject, LegacyWorldObject {

    private final IndoorWall wall;
    private final IndoorFloor floor;
    private final Ceiling ceiling;

    private final IndoorObjectData data;

    IndoorRoom(IndoorObjectData data){

    	((MapArea) data.getMapElement()).addRepresentation(this);

        this.data = data;

        this.wall = new IndoorWall(data.getBuildingPart(), data.getMapElement());

        floor = new IndoorFloor(data.getBuildingPart(),
                data.getSurface(),
                data.getPolygon(),
                data.getLevelHeightAboveBase(),
                data.getRenderableLevels().contains(data.getMinLevel()), data.getMinLevel());

        ceiling = new Ceiling(data.getBuildingPart(),
                data.getMaterial(BuildingDefaults.getDefaultsFor(data.getBuildingPart().getTags()).materialWall),
                data.getPolygon(),
                data.getTopOfTopLevelHeightAboveBase(),
                data.getRenderableLevels().contains(data.getMaxLevel()), data.getMaxLevel());

        if (data.getMapElement() instanceof MapArea) {
            data.getLevels().forEach(l ->  data.getBuildingPart().getBuilding().addListWindowNodes(((MapArea) data.getMapElement()).getBoundaryNodes(), l));
        }

    }

    public Collection<AttachmentSurface> getAttachmentSurfaces() {

        Collection<AttachmentSurface> floorSurfaces = floor.getAttachmentSurfaces();
        Collection<AttachmentSurface> ceilingSurfaces = ceiling.getAttachmentSurfaces();
        Collection<AttachmentSurface> wallSurfaces = wall.getAttachmentSurfaces();

        List<AttachmentSurface> surfaces = new ArrayList<>();

        surfaces.addAll(floorSurfaces);
        surfaces.addAll(ceilingSurfaces);
        surfaces.addAll(wallSurfaces);

        return surfaces;

    }

    @Override
    public void renderTo(Target target) {

        wall.renderTo(target);

        floor.renderTo(target);

        ceiling.renderTo(target);

    }

	@Override
	public MapArea getPrimaryMapElement() {
		return (MapArea) data.getMapElement();
	}

	@Override
	public WorldObject getParent() {
		return data.getBuildingPart();
	}

	@Override
	public Iterable<EleConnector> getEleConnectors() {
		return emptyList();
	}

}

