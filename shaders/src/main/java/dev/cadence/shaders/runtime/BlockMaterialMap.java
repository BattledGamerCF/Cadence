// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import dev.cadence.shaders.pack.MaterialMappings;
import dev.cadence.shaders.pack.ShaderPackException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Resolves pack selectors against the frozen game registries once. Chunk workers then perform an
 * immutable identity lookup, with no property serialization, selector matching, or allocations.
 */
public final class BlockMaterialMap implements ToIntFunction<BlockState> {
  private final Map<BlockState, Integer> blocks;
  // Cadence: the chunk workers' per-block lookup without boxing or the unmodifiable wrapper (1.6% of CPU while loading).
  private final it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<BlockState> blockIds;
  private final Map<Item, Integer> items;
  private final Map<EntityType<?>, Integer> entities;

  public BlockMaterialMap(MaterialMappings mappings) throws ShaderPackException {
    Map<BlockState, Integer> states = new IdentityHashMap<>();
    for (Block block : BuiltInRegistries.BLOCK) {
      String identifier = BuiltInRegistries.BLOCK.getKey(block).toString();
      for (BlockState state : block.getStateDefinition().getPossibleStates()) {
        Map<String, String> properties = new HashMap<>();
        for (Property<?> property : state.getProperties()) {
          properties.put(property.getName(), serialize(state, property));
        }
        states.put(state, mappings.blockId(identifier, properties));
      }
    }
    Map<Item, Integer> itemIds = new IdentityHashMap<>();
    for (Item item : BuiltInRegistries.ITEM) {
      itemIds.put(item, mappings.itemId(BuiltInRegistries.ITEM.getKey(item).toString()));
    }
    Map<EntityType<?>, Integer> entityIds = new IdentityHashMap<>();
    for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
      entityIds.put(type, mappings.entityId(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()));
    }
    blocks = Collections.unmodifiableMap(states);
    blockIds = new it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<>(states);
    blockIds.defaultReturnValue(-1);
    blockIds.trim();
    items = Collections.unmodifiableMap(itemIds);
    entities = Collections.unmodifiableMap(entityIds);
  }

  private static <T extends Comparable<T>> String serialize(
      BlockState state, Property<T> property) {
    return property.getName(state.getValue(property));
  }

  @Override
  public int applyAsInt(BlockState state) {
    return blockIds.getInt(state);
  }

  /**
   * Cadence: how many block states map to a different pack id in {@code other} (0: terrain built with either map is
   * identical). Compares the ints: IdentityHashMap.equals would compare the boxed values by identity.
   */
  public int differingBlockIds(BlockMaterialMap other) {
    int n = 0;
    for (var e : blocks.entrySet()) if (other.blockIds.getInt(e.getKey()) != e.getValue()) n++;
    return n + Math.max(0, other.blocks.size() - blocks.size());
  }

  public int stateCount() {
    return blocks.size();
  }

  /** Includes the complete frozen registry; any future unmapped state resolves to -1. */
  public boolean allBlockIdsFitSigned16() {
    for (int id : blocks.values()) {
      if (id < Short.MIN_VALUE || id > Short.MAX_VALUE) return false;
    }
    return true;
  }

  public int itemId(ItemStack stack) {
    return stack.isEmpty() ? -1 : itemId(stack.getItem());
  }

  public int itemId(Item item) {
    return items.getOrDefault(item, -1);
  }

  public int entityId(Entity entity) {
    return entityTypeId(entity.getType());
  }

  public int entityTypeId(EntityType<?> type) {
    return entities.getOrDefault(type, -1);
  }
}
