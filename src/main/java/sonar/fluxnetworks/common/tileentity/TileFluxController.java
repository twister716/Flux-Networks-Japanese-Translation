package sonar.fluxnetworks.common.tileentity;

import sonar.fluxnetworks.api.network.FluxDeviceType;
import sonar.fluxnetworks.api.network.ITransferHandler;
import sonar.fluxnetworks.api.device.IFluxController;
import sonar.fluxnetworks.api.device.IFluxEnergy;
import sonar.fluxnetworks.common.connection.handler.FluxControllerHandler;
import sonar.fluxnetworks.common.registry.RegistryBlocks;

public class TileFluxController extends TileFluxDevice implements IFluxController, IFluxEnergy {

    public final FluxControllerHandler handler = new FluxControllerHandler(this);

    public TileFluxController() {
        super(RegistryBlocks.FLUX_CONTROLLER_TILE, , );
        customName = "Flux Controller";
    }

    @Override
    public FluxDeviceType getDeviceType() {
        return FluxDeviceType.CONTROLLER;
    }

    @Override
    public ITransferHandler getTransferHandler() {
        return handler;
    }

    @Override
    public long addEnergy(long amount, boolean simulate) {
        return 0;
    }

    @Override
    public long removeEnergy(long amount, boolean simulate) {
        return 0;
    }

    @Override
    public long getEnergy() {
        return 0;
    }
}
