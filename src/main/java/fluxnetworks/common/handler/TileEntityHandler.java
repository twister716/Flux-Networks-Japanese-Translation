package fluxnetworks.common.handler;

import com.google.common.collect.Lists;
import fluxnetworks.FluxNetworks;
import fluxnetworks.api.energy.ITileEnergyHandler;
import fluxnetworks.api.tileentity.IFluxConnector;
import fluxnetworks.common.handler.energy.ForgeEnergyHandler;
import fluxnetworks.common.handler.energy.GTEnergyHandler;
import fluxnetworks.common.handler.energy.ICEnergyHandler;
import fluxnetworks.common.handler.energy.RedstoneFluxHandler;
import fluxnetworks.common.tileentity.TileFluxController;
import fluxnetworks.common.tileentity.TileFluxPlug;
import fluxnetworks.common.tileentity.TileFluxPoint;
import fluxnetworks.common.tileentity.TileFluxStorage;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.GameRegistry;

import javax.annotation.Nullable;
import java.util.List;

public class TileEntityHandler {

    public static List<ITileEnergyHandler> tileEnergyHandlers = Lists.newArrayList();

    public static void registerTileEntity() {
        GameRegistry.registerTileEntity(TileFluxController.class, new ResourceLocation(FluxNetworks.MODID, "FluxController"));
        GameRegistry.registerTileEntity(TileFluxPoint.class, new ResourceLocation(FluxNetworks.MODID, "FluxPoint"));
        GameRegistry.registerTileEntity(TileFluxPlug.class, new ResourceLocation(FluxNetworks.MODID, "FluxPlug"));
        GameRegistry.registerTileEntity(TileFluxStorage.class, new ResourceLocation(FluxNetworks.MODID, "FluxStorage"));
        GameRegistry.registerTileEntity(TileFluxStorage.Herculean.class, new ResourceLocation(FluxNetworks.MODID, "HerculeanFluxStorage"));
        GameRegistry.registerTileEntity(TileFluxStorage.Gargantuan.class, new ResourceLocation(FluxNetworks.MODID, "GargantuanFluxStorage"));
    }

    public static void registerEnergyHandler() {
        tileEnergyHandlers.add(new ForgeEnergyHandler());
        if(Loader.isModLoaded("gregtech")) {
            tileEnergyHandlers.add(new GTEnergyHandler());
        }
        if(Loader.isModLoaded("redstoneflux")) {
            tileEnergyHandlers.add(new RedstoneFluxHandler());
        }
        if(Loader.isModLoaded("ic2")) {
            tileEnergyHandlers.add(new ICEnergyHandler());
        }
    }

    @Nullable
    public static ITileEnergyHandler getEnergyHandler(TileEntity tile, EnumFacing side) {
        if(tile instanceof IFluxConnector) {
            return null;
        }
        for(ITileEnergyHandler handler : tileEnergyHandlers) {
            if(handler.canRenderConnection(tile, side)) {
                return handler;
            }
        }
        return null;
    }

    public static boolean canRenderConnection(TileEntity tile, EnumFacing side) {
        if(tile == null) {
            return false;
        }
        if(tile instanceof IFluxConnector) {
            return false;
        }
        ITileEnergyHandler handler = getEnergyHandler(tile, side);
        if(handler != null) {
            return true;
        }
        return false;
    }
}
