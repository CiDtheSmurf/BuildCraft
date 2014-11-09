/**
 * Copyright (c) 2011-2014, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.energy;

import buildcraft.api.power.IEngine;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ICrafting;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.ForgeDirection;
import cofh.api.energy.IEnergyHandler;
import buildcraft.api.core.NetworkData;
import buildcraft.api.transport.IPipeConnection;
import buildcraft.api.transport.IPipeTile;
import buildcraft.api.transport.IPipeTile.PipeType;
import buildcraft.core.DefaultProps;
import buildcraft.core.TileBuffer;
import buildcraft.core.TileBuildCraft;
import buildcraft.energy.gui.ContainerEngine;

public abstract class TileEngine extends TileBuildCraft implements IPipeConnection, IEnergyHandler, IEngine {
	// Index corresponds to metadata
	public static final ResourceLocation[] BASE_TEXTURES = new ResourceLocation[]{
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/base_wood.png"),
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/base_stone.png"),
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/base_iron.png"),
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/base_creative.png")
	};

	public static final ResourceLocation[] CHAMBER_TEXTURES = new ResourceLocation[]{
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/chamber_wood.png"),
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/chamber_stone.png"),
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/chamber_iron.png"),
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/chamber_creative.png")
	};

	// THESE ARE ONLY BLUE TRUNKS. OTHER HEAT STAGES ARE HANDLED PER TILE
	public static final ResourceLocation[] TRUNK_TEXTURES = new ResourceLocation[]{
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/trunk_wood.png"),
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/trunk_stone.png"),
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/trunk_iron.png"),
			new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/trunk_creative.png")
	};

	// TEMP
	public static final ResourceLocation TRUNK_BLUE_TEXTURE = new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/trunk_blue.png");
	public static final ResourceLocation TRUNK_GREEN_TEXTURE = new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/trunk_green.png");
	public static final ResourceLocation TRUNK_YELLOW_TEXTURE = new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/trunk_yellow.png");
	public static final ResourceLocation TRUNK_RED_TEXTURE = new ResourceLocation(DefaultProps.TEXTURE_PATH_BLOCKS + "/trunk_red.png");

	public enum EnergyStage {
		BLUE, GREEN, YELLOW, RED, OVERHEAT;
		public static final EnergyStage[] VALUES = values();
	}

	public static final float MIN_HEAT = 20;
	public static final float IDEAL_HEAT = 100;
	public static final float MAX_HEAT = 250;
	public int currentOutput = 0;
	public boolean isRedstonePowered = false;
	public float progress;
	public int energy;
	public float heat = MIN_HEAT;
	@NetworkData
	public EnergyStage energyStage = EnergyStage.BLUE;
	@NetworkData
	public ForgeDirection orientation = ForgeDirection.UP;

	protected int progressPart = 0;
	protected boolean lastPower = false;

	private boolean checkOrientation = false;

	@NetworkData
	private boolean isPumping = false; // Used for SMP synch

	public TileEngine() {
	}

	@Override
	public void initialize() {
		if (!worldObj.isRemote) {
			checkRedstonePower();
		}
	}

	public abstract ResourceLocation getBaseTexture();

	public abstract ResourceLocation getChamberTexture();

	public ResourceLocation getTrunkTexture(EnergyStage stage) {
		switch (stage) {
			case BLUE:
				return TRUNK_BLUE_TEXTURE;
			case GREEN:
				return TRUNK_GREEN_TEXTURE;
			case YELLOW:
				return TRUNK_YELLOW_TEXTURE;
			case RED:
				return TRUNK_RED_TEXTURE;
			default:
				return TRUNK_RED_TEXTURE;
		}
	}

	public boolean onBlockActivated(EntityPlayer player, ForgeDirection side) {
		return false;
	}

	public double getEnergyLevel() {
		return ((double) energy) / getMaxEnergy();
	}

	protected EnergyStage computeEnergyStage() {
		float energyLevel = getHeatLevel();
		if (energyLevel < 0.25f) {
			return EnergyStage.BLUE;
		} else if (energyLevel < 0.5f) {
			return EnergyStage.GREEN;
		} else if (energyLevel < 0.75f) {
			return EnergyStage.YELLOW;
		} else if (energyLevel < 1f) {
			return EnergyStage.RED;
		} else {
			return EnergyStage.OVERHEAT;
		}
	}

	public final EnergyStage getEnergyStage() {
		if (!worldObj.isRemote) {
			if (energyStage == EnergyStage.OVERHEAT) {
				return energyStage;
			}

			EnergyStage newStage = computeEnergyStage();

			if (energyStage != newStage) {
				energyStage = newStage;
				sendNetworkUpdate();
			}
		}

		return energyStage;
	}

	public void updateHeatLevel() {
		heat = (float) ((MAX_HEAT - MIN_HEAT) * getEnergyLevel()) + MIN_HEAT;
	}

	public float getHeatLevel() {
		return (heat - MIN_HEAT) / (MAX_HEAT - MIN_HEAT);
	}

	public float getIdealHeatLevel() {
		return heat / IDEAL_HEAT;
	}

	public float getHeat() {
		return heat;
	}

	public float getPistonSpeed() {
		if (!worldObj.isRemote) {
			return Math.max(0.16f * getHeatLevel(), 0.01f);
		}

		switch (getEnergyStage()) {
			case BLUE:
				return 0.02F;
			case GREEN:
				return 0.04F;
			case YELLOW:
				return 0.08F;
			case RED:
				return 0.16F;
			default:
				return 0;
		}
	}

	@Override
	public void updateEntity() {
		super.updateEntity();

		if (worldObj.isRemote) {
			if (progressPart != 0) {
				progress += getPistonSpeed();

				if (progress > 1) {
					progressPart = 0;
					progress = 0;
				}
			} else if (this.isPumping) {
				progressPart = 1;
			}

			return;
		}

		if (checkOrientation) {
			checkOrientation = false;

			if (!isOrientationValid()) {
				switchOrientation(true);
			} else {
				TileEntity tile = getTile(orientation);
			}
		}

		updateHeatLevel();
		getEnergyStage();
		engineUpdate();

		TileEntity tile = getTile(orientation);

		if (progressPart != 0) {
			progress += getPistonSpeed();

			if (progress > 0.5 && progressPart == 1) {
				progressPart = 2;
			} else if (progress >= 1) {
				progress = 0;
				progressPart = 0;
			}
		} else if (isRedstonePowered && isActive()) {
			if (isPoweredTile(tile, orientation)) {
				progressPart = 1;
				setPumping(true);
				if (getPowerToExtract() > 0) {
					progressPart = 1;
					setPumping(true);
				} else {
					setPumping(false);
				}
			} else {
				setPumping(false);
			}
		} else {
			setPumping(false);
		}

		burn();
		
		if (!isRedstonePowered) {
			currentOutput = 0;
		} else if (isRedstonePowered && isActive()) {
			sendPower();
		}
	}

	private int getPowerToExtract() {
		TileEntity tile = getTile(orientation);

        if (tile instanceof IEngine) {
            IEngine engine = (IEngine) tile;

            int maxEnergy = engine.receiveEnergyFromEngine(
                    orientation.getOpposite(),
                    this.energy, true);
            return extractEnergy(maxEnergy, false);
        } else if (tile instanceof IEnergyHandler) {
			IEnergyHandler handler = (IEnergyHandler) tile;

			int maxEnergy = handler.receiveEnergy(
					orientation.getOpposite(),
					this.energy, true);
			return extractEnergy(maxEnergy, false);
		} else {
			return 0;
		}
	}

	protected void sendPower() {
		TileEntity tile = getTile(orientation);
		if (isPoweredTile(tile, orientation)) {
			int extracted = getPowerToExtract();
			if (extracted <= 0) {
				setPumping(false);
                return;
			}

            setPumping(true);

			if (tile instanceof IEngine) {
                IEngine engine = (IEngine) tile;
                int neededRF = engine.receiveEnergyFromEngine(
                        orientation.getOpposite(),
                        extracted, false);

                extractEnergy(neededRF, true);
            } else if (tile instanceof IEnergyHandler) {
                IEnergyHandler handler = (IEnergyHandler) tile;
                int neededRF = handler.receiveEnergy(
                        orientation.getOpposite(),
                        extracted, false);

                extractEnergy(neededRF, true);
            }
		}
	}

	// Uncomment out for constant power
//	public float getActualOutput() {
//		float heatLevel = getIdealHeatLevel();
//		return getCurrentOutput() * heatLevel;
//	}
	protected void burn() {
	}

	protected void engineUpdate() {
		if (!isRedstonePowered) {
			if (energy >= 10) {
				energy -= 10;
			} else if (energy < 10) {
				energy = 0;
			}
		}
	}

	public boolean isActive() {
		return true;
	}

	protected final void setPumping(boolean isActive) {
		if (this.isPumping == isActive) {
			return;
		}

		this.isPumping = isActive;
		sendNetworkUpdate();
	}

	public boolean isOrientationValid() {
		TileEntity tile = getTile(orientation);

		return isPoweredTile(tile, orientation);
	}

	public boolean switchOrientation(boolean preferPipe) {
		if (preferPipe && switchOrientationDo(true)) {
			return true;
		} else {
			return switchOrientationDo(false);
		}
	}

	private boolean switchOrientationDo(boolean pipesOnly) {
		for (int i = orientation.ordinal() + 1; i <= orientation.ordinal() + 6; ++i) {
			ForgeDirection o = ForgeDirection.VALID_DIRECTIONS[i % 6];

			TileEntity tile = getTile(o);

			if ((!pipesOnly || tile instanceof IPipeTile) && isPoweredTile(tile, o)) {
				orientation = o;
				worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
				worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, worldObj.getBlock(xCoord, yCoord, zCoord));

				return true;
			}
		}

		return false;
	}

	@Override
	public void invalidate() {
		super.invalidate();
		checkOrientation = true;
	}

	@Override
	public void validate() {
		super.validate();
		checkOrientation = true;
	}

	@Override
	public void readFromNBT(NBTTagCompound data) {
		super.readFromNBT(data);

		orientation = ForgeDirection.getOrientation(data.getByte("orientation"));
		progress = data.getFloat("progress");
		energy = data.getInteger("energy");
		heat = data.getFloat("heat");
	}

	@Override
	public void writeToNBT(NBTTagCompound data) {
		super.writeToNBT(data);

		data.setByte("orientation", (byte) orientation.ordinal());
		data.setFloat("progress", progress);
		data.setInteger("energy", energy);
		data.setFloat("heat", heat);
	}

	public void getGUINetworkData(int id, int value) {
		switch (id) {
			case 0:
				int iEnergy = Math.round(energy);
				iEnergy = (iEnergy & 0xffff0000) | (value & 0xffff);
				energy = iEnergy;
				break;
			case 1:
				iEnergy = Math.round(energy);
				iEnergy = (iEnergy & 0xffff) | ((value & 0xffff) << 16);
				energy = iEnergy;
				break;
			case 2:
				currentOutput = value;
				break;
			case 3:
				heat = value / 100F;
				break;
		}
	}

	public void sendGUINetworkData(ContainerEngine containerEngine, ICrafting iCrafting) {
		iCrafting.sendProgressBarUpdate(containerEngine, 0, Math.round(energy) & 0xffff);
		iCrafting.sendProgressBarUpdate(containerEngine, 1, (Math.round(energy) & 0xffff0000) >> 16);
		iCrafting.sendProgressBarUpdate(containerEngine, 2, Math.round(currentOutput));
		iCrafting.sendProgressBarUpdate(containerEngine, 3, Math.round(heat * 100));
	}

	/* STATE INFORMATION */
	public abstract boolean isBurning();

	public void addEnergy(int addition) {
		energy += addition;

		if (getEnergyStage() == EnergyStage.OVERHEAT) {
			worldObj.createExplosion(null, xCoord, yCoord, zCoord, explosionRange(), true);
			worldObj.setBlockToAir(xCoord, yCoord, zCoord);
		}

		if (energy > getMaxEnergy()) {
			energy = getMaxEnergy();
		}
	}

	public int extractEnergy(int energyMax, boolean doExtract) {
		int max = Math.min(energyMax, maxEnergyExtracted());

		int extracted;

		if (energy >= max) {
			extracted = max;

			if (doExtract) {
				energy -= max;
			}
		} else {
			extracted = energy;

			if (doExtract) {
				energy = 0;
			}
		}

		return extracted;
	}

	public boolean isPoweredTile(TileEntity tile, ForgeDirection side) {
		if (tile == null) {
            return false;
        } else if (tile instanceof IEngine) {
            return ((IEngine) tile).canReceiveFromEngine(side.getOpposite());
		} else if (tile instanceof IEnergyHandler) {
            return ((IEnergyHandler) tile).canConnectEnergy(side.getOpposite());
        } else {
			return false;
		}
	}

	public abstract int getMaxEnergy();

	public int minEnergyReceived() {
		return 20;
	}

	public abstract int maxEnergyReceived();

	public abstract int maxEnergyExtracted();

	public abstract float explosionRange();

	public int getEnergyStored() {
		return energy;
	}

	public abstract int calculateCurrentOutput();

	@Override
	public ConnectOverride overridePipeConnection(PipeType type, ForgeDirection with) {
		if (type == PipeType.POWER) {
			return ConnectOverride.DEFAULT;
		} else if (with == orientation) {
			return ConnectOverride.DISCONNECT;
		} else {
			return ConnectOverride.DEFAULT;
		}
	}

	public void checkRedstonePower() {
		isRedstonePowered = worldObj.isBlockIndirectlyGettingPowered(xCoord, yCoord, zCoord);
	}

	public void onNeighborUpdate() {
		checkRedstonePower();
		checkOrientation = true;
	}
	// RF support

	@Override
	public int receiveEnergy(ForgeDirection from, int maxReceive,
			boolean simulate) {
		return 0;
	}

	@Override
	public int extractEnergy(ForgeDirection from, int maxExtract,
			boolean simulate) {
		return 0;
	}

	@Override
	public int getEnergyStored(ForgeDirection from) {
		if (!(from == orientation)) {
			return 0;
		}

		return energy;
	}

	@Override
	public int getMaxEnergyStored(ForgeDirection from) {
		return this.getMaxEnergy();
	}

	@Override
	public boolean canConnectEnergy(ForgeDirection from) {
		return from == orientation;
	}

    // IEngine

    @Override
    public boolean canReceiveFromEngine(ForgeDirection side) {
        return side == orientation.getOpposite();
    }

    @Override
    public int receiveEnergyFromEngine(ForgeDirection side, int amount, boolean simulate) {
        if (canReceiveFromEngine(side)) {
            int targetEnergy = Math.min(this.getMaxEnergy() - this.energy, amount);
            if (!simulate) {
                energy += targetEnergy;
            }
            return targetEnergy;
        } else {
            return 0;
        }
    }
}
