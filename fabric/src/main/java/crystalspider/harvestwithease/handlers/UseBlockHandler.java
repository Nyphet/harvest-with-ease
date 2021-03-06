package crystalspider.harvestwithease.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import crystalspider.harvestwithease.HarvestWithEaseLoader;
import crystalspider.harvestwithease.config.HarvestWithEaseConfig;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

/**
 * {@link UseBlockCallback} event handler.
 * Handles the {@link UseBlockCallback} event to right-click harvest when possible.
 * See {@link #handle(PlayerEntity, World, Hand, BlockHitResult)} for more details.
 */
public class UseBlockHandler {
  /**
   * List of additional in-game IDs for crops that need to be supported but do not extend {@link CropBlock}.
   */
	private final ArrayList<String> crops = new ArrayList<String>(List.of(getKey(Blocks.NETHER_WART), getKey(Blocks.COCOA)));
  /**
   * Whether holding a hoe (either hands) is required.
   */
  private final Boolean requireHoe;
  /**
   * Amount of damage to deal on a hoe when it is used to right-click harvest.
   * Effective only if greater than 0 and {@link #requireHoe} is true.
   */
	private final Integer damageOnHarvest;
  /**
   * Amount of experience to grant on harvest.
   * Effective only if greater than 0.
   */
  private final Integer grantedExp;
  /**
   * Whether to play a sound when harvesting a crop.
   */
  private final Boolean playSound;

	public UseBlockHandler() {
		crops.addAll(HarvestWithEaseConfig.getCrops());
		this.requireHoe = HarvestWithEaseConfig.getRequireHoe();
    this.damageOnHarvest = HarvestWithEaseConfig.getDamageOnHarvest();
    this.grantedExp = HarvestWithEaseConfig.getGrantedExp();
    this.playSound = HarvestWithEaseConfig.getPlaySound();
	}

  /**
   * Handles the event {@link UseBlockCallback}.
   * Will cancel further event processing only if the {@link PlayerEntity player}
   * is not in spectator mode,
   * is not crouching,
   * is holding the correct item (depends on {@link #requireHoe})
   * and the interaction involves a fully grown {@link #isCrop crop}.
   * 
   * @param player - {@link PlayerEntity player} executing the action.
   * @param world - {@link World world} where the event is happening.
   * @param hand - {@link Hand hand} player's hand.
   * @param result - {@link BlockHitResult} result of hitting the block.
   * @return - {@link ActionResult} result of the action.
   */
  public ActionResult handle(PlayerEntity player, World world, Hand hand, BlockHitResult result) {
    ActionResult actionResult = ActionResult.PASS;
    if (!player.isSpectator()) {
      BlockPos blockPos = result.getBlockPos();
      BlockState blockState = world.getBlockState(blockPos);
      if (isCrop(blockState.getBlock()) && getInteractionHand(player) == hand && canHarvest(player.getStackInHand(hand), blockState)) {
        try {
          IntProperty age = getAge(blockState);
          if (blockState.getOrEmpty(age).orElse(0) >= Collections.max(age.getValues())) {
            actionResult = ActionResult.SUCCESS;
            if (!world.isClient()) {
              grantExp(player);
              damageHoe(player, hand);
              dropResources(world.getServer().getWorld(world.getRegistryKey()), blockState, result.getSide(), blockPos, player, hand);
              world.setBlockState(blockPos, blockState.with(age, 0));
              playSound(world, blockState, blockPos);
            }
          }
        } catch (NullPointerException | NoSuchElementException | ClassCastException e) {
          HarvestWithEaseLoader.LOGGER.debug("Exception generated by block at [" + blockPos.getX() + ", " + blockPos.getY() + ", " + blockPos.getZ() + "]");
          HarvestWithEaseLoader.LOGGER.debug("This is a non blocking error, but can result in incorrect behavior for mod " + HarvestWithEaseLoader.MODID);
          HarvestWithEaseLoader.LOGGER.debug("Most probably the cause of this issue was that a non-crop ID was added in the configuration and its age property could not be retrieved, see stack trace for more details", e);
        }
      }
    }
    return actionResult;
  }

  /**
   * Grants the given player the configured amount of experience, if any.
   * 
   * @param player - {@link PlayerEntity player} to grant the experience to.
   */
  private void grantExp(PlayerEntity player) {
    if (grantedExp >= 0) {
      player.addExperience(grantedExp);
    }
  }

  /**
   * If needed and possible, damages the hoe of the given {@link #damageOnHarvest damage}.
   * 
   * @param player - {@link PlayerEntity player} holding the hoe. 
   * @param interactionHand - {@link Hand hand} holding the hoe.
   */
  private void damageHoe(PlayerEntity player, Hand interactionHand) {
    if (requireHoe && damageOnHarvest > 0 && !player.isCreative()) {
      player.getStackInHand(interactionHand).damage(damageOnHarvest, player, playerEntity -> playerEntity.sendToolBreakStatus(interactionHand));
    }
  }

  /**
   * Drop the resources resulting from harvesting a crop in the given servelLevl and blockState, making them pop from the given face and using the item held in the given player hand.
   * Also removes 1 seed from the drops, if any seed is found in the {@link #getDrops drops list}.
   * A seed is here defined as the item needed to plant the crop.
   * 
   * @param serverWorld - {@link ServerWorld server level} of the {@link World world} the drops should come from.
   * @param blockState - {@link BlockState state} of the crop being harvested.
   * @param direction - {@link Direction face} clicked of the crop.
   * @param blockPos - crop {@link BlockPos position}.
   * @param player - {@link PlayerEntity player} harvesting the crop.
   * @param interactionHand - {@link InteractionHand hand} used to harvest the crop.
   */
  private void dropResources(ServerWorld serverWorld, BlockState blockState, Direction direction, BlockPos blockPos, PlayerEntity player, Hand interactionHand) {
    List<ItemStack> drops = getDrops(serverWorld, blockState, blockPos, player, interactionHand);
    boolean seedRemoved = false;
    for (ItemStack stack : drops) {
      if (!seedRemoved && stack.isItemEqual(blockState.getBlock().getPickStack(serverWorld, blockPos, blockState))) {
        stack.decrement(1);
        seedRemoved = true;
      }
      Block.dropStack(serverWorld, blockPos, direction, stack);
    }
  }

  /**
   * Returns the list of drops calculated from the parameters.
   * 
   * @param serverWorld - {@link ServerWorld server world} of the {@link World world} the drops should come from.
   * @param blockState - {@link BlockState state} of the block breaking.
   * @param blockPos - {@link BlockPos position} of the block breaking.
   * @param player - {@link PlayerEntity player} breaking the block.
   * @param interactionHand - {@link Hand hand} the player is using to break the block.
   * @return
   */
  private List<ItemStack> getDrops(ServerWorld serverWorld, BlockState blockState, BlockPos blockPos, PlayerEntity player, Hand interactionHand) {
    return blockState.getDroppedStacks(
      new LootContext.Builder(serverWorld)
        .parameter(LootContextParameters.ORIGIN, new Vec3d(blockPos.getX(), blockPos.getY(), blockPos.getZ()))
        .parameter(LootContextParameters.BLOCK_STATE, blockState)
        .parameter(LootContextParameters.THIS_ENTITY, player)
        .parameter(LootContextParameters.TOOL, player.getStackInHand(interactionHand))
    );
  }

  /**
   * If {@link #playSound} is true, plays the block breaking sound.
   * 
   * @param world - {@link World} to play the sound.
   * @param blockState - {@link BlockState state} of the block emitting the sound.
   * @param blockPos - {@link BlockPos position} of the block emitting the sound.
   */
  private void playSound(World world, BlockState blockState, BlockPos blockPos) {
    if (playSound) {
      BlockSoundGroup soundGroup = blockState.getBlock().getSoundGroup(blockState);
      world.playSound(null, blockPos, soundGroup.getBreakSound(), SoundCategory.BLOCKS, soundGroup.getVolume(), soundGroup.getPitch());
    }
  }

  /**
   * Returns the age integer property from the given blockState.
   * 
   * @param blockState - {@link BlockState state} to take the age property from.
   * @return the age property from the given blockState.
   * @throws NullPointerException - if the age property was null.
   * @throws NoSuchElementException - if no value for the age property is present.
   * @throws ClassCastException - if the age property is not an {@link IntProperty}.
   */
  private IntProperty getAge(BlockState blockState) throws NullPointerException, NoSuchElementException, ClassCastException {
    return (IntProperty) blockState.getProperties().stream().filter(property -> property.getName().equals("age")).findFirst().orElseThrow();
  }

  /**
   * Returns the most suitable interaction hand from the player.
   * Returns null if there was no suitable interaction hand.
   * 
   * @param player
   * @return most suitable interaction hand.
   */
  @Nullable
  private Hand getInteractionHand(PlayerEntity player) {
    if (!player.isSneaking()) {
      if (isHoe(player.getStackInHand(Hand.MAIN_HAND))) {
        return Hand.MAIN_HAND;
      }
      if (isHoe(player.getStackInHand(Hand.OFF_HAND))) {
        return Hand.OFF_HAND;
      }
      if (!requireHoe) {
        return Hand.MAIN_HAND;
      }
    }
    return null;
  }

  /**
   * Checks whether or not the given itemStack is an Item that extends {@link HoeItem}.
   * 
   * @param handItem
   * @return whether the given itemStack is a hoe tool.
   */
  private boolean isHoe(ItemStack handItem) {
    return handItem.getItem() instanceof HoeItem;
  }

  /**
   * Checks whether or not the block passed as parameter is a crop that can be harvested using this mod.
   * 
   * @param block
   * @return whether the given block it's a valid crop.
   */
  private boolean isCrop(Block block) {
		return block instanceof CropBlock || crops.contains(getKey(block));
	}

  /**
   * Checks whether a tool is required to harvest the crop and, in case, if the tool in hand satisfies the requirement.
   * 
   * @param itemStack - Tool held in hand.
   * @param blockState - {@link BlockState} of the crop to harvest.
   * @return whether the given tool can harvest the given crop.
   */
  private boolean canHarvest(ItemStack itemStack, BlockState blockState) {
    return !blockState.isToolRequired() || itemStack.isSuitableFor(blockState);
  }

  /**
   * Returns the in-game ID of the block passed as parameter.
   * 
   * @param block
   * @return in-game ID of the given block.
   */
  private String getKey(Block block) {
    return Registry.BLOCK.getKey(block).get().getValue().toString();
  }
}
