package sonar.fluxnetworks.common.tileentity.energy;

import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.Direction;
import net.minecraftforge.common.util.LazyOptional;
import sonar.fluxnetworks.api.device.IFluxDevice;
import sonar.fluxnetworks.api.misc.FluxCapabilities;
import sonar.fluxnetworks.common.misc.DefaultEnergyWrapper;
import sonar.fluxnetworks.common.tileentity.TileFluxDevice;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public abstract class TileDefaultEnergy extends TileFluxDevice implements IFluxDevice {

    public Map<Direction, DefaultEnergyWrapper> wrappers = new HashMap<>();
    {
        wrappers.put(null, new DefaultEnergyWrapper(this, null));
        for(Direction face : Direction.values()) {
            wrappers.put(face, new DefaultEnergyWrapper(this, face));
        }
    }

    public TileDefaultEnergy(TileEntityType<?> tileEntityTypeIn, String customName, long limit) {
        super(tileEntityTypeIn, customName, limit);
    }

    public DefaultEnergyWrapper getEnergyWrapper(Direction facing) {
        return wrappers.get(facing);
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        if(cap == FluxCapabilities.FN_ENERGY_STORAGE){
            return LazyOptional.of(() -> getEnergyWrapper(side)).cast();
        }
        if(cap == CapabilityEnergy.ENERGY){
            return LazyOptional.of(() -> getEnergyWrapper(side)).cast();
        }
        return super.getCapability(cap, side);
    }
}
