// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.chunk;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.LongStream;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

/**
 * C4 (-Dcadence.chunk.parse=true|verify): chunk-section palettes decoded without DFU. Vanilla's codecs
 * (PalettedContainer.codecRW(BlockState.CODEC) / codecRO(biomes.holderByNameCodec())) read {"palette": [entries], "data": long[]}
 * into PackedData and call PalettedContainer.unpack. For clean input their element decoding is a pure function, reproduced here:
 * <ul>
 * <li>block entry "ns:path" (a string): the block's default state (Codec.either tries the registry name first);</li>
 * <li>block entry {id: "ns:path", properties: {...}}: the default state, then for every property of the block, a string value the
 *     property accepts replaces the default (missing, non-string or unknown values keep it: orElseGet), unknown keys are ignored,
 *     a singleton block ignores properties; no "properties" keeps the default (lenientOptionalFieldOf);</li>
 * <li>biome entry "ns:path": the registry's reference holder.</li>
 * </ul>
 * The entries go, in list order, into the same PackedData and the same PalettedContainer.unpack, so the container (palette order,
 * storage words) is the one vanilla builds. Anything else (an unknown or malformed name, properties that aren't a compound, data
 * that isn't a long array, an unpack error) returns null and the vanilla codec decodes it, errors and logging included.
 */
public final class FastPalette {
	private FastPalette() { }

	/** A codec that tries {@code fast} on NBT compounds and otherwise (or when it declines) is {@code vanilla}. */
	public static <C> Codec<C> wrap(Codec<C> vanilla, Function<CompoundTag, C> fast, String what) {
		return wrap(vanilla, fast, null, what);
	}

	/**
	 * Decode as above; with {@code fastEncode} (serialize=true|verify) encoding into a fresh NBT value (encodeStart) goes through it
	 * too. verify encodes both ways, keeps vanilla's tag and compares the two by their serialized bytes (key order included).
	 */
	public static <C> Codec<C> wrap(Codec<C> vanilla, Function<CompoundTag, C> fast, Function<C, Tag> fastEncode, String what) {
		return new Codec<>() {
			@Override
			public <O> DataResult<Pair<C, O>> decode(DynamicOps<O> ops, O input) {
				if (ops == NbtOps.INSTANCE && input instanceof CompoundTag tag) {
					C c = fast.apply(tag);
					if (ChunkOpt.PARSE_VERIFY) {
						DataResult<Pair<C, O>> ref = vanilla.decode(ops, input);
						verify(what, c, ref.result().map(Pair::getFirst).orElse(null), ref.error().isPresent());
						return ref;
					}
					if (c != null) {
						if (ChunkOpt.STATS) ChunkOpt.count("parse." + what + ".fast");
						return DataResult.success(Pair.of(c, ops.empty()));
					}
					if (ChunkOpt.STATS) ChunkOpt.count("parse." + what + ".vanilla");
				}
				return vanilla.decode(ops, input);
			}

			@Override
			@SuppressWarnings("unchecked")
			public <O> DataResult<O> encode(C input, DynamicOps<O> ops, O prefix) {
				if (fastEncode != null && ops == NbtOps.INSTANCE && prefix == NbtOps.INSTANCE.empty()) {
					Tag tag = fastEncode.apply(input);
					if (ChunkOpt.SERIALIZE_VERIFY) {
						DataResult<O> ref = vanilla.encode(input, ops, prefix);
						Tag v = (Tag) ref.result().orElse(null);
						ChunkOpt.count("verifyEncode." + what + (tag == null ? ".declined" : v != null && Arrays.equals(tagBytes(tag), tagBytes(v)) ? ".same" : ".MISMATCH"));
						return ref;
					}
					if (tag != null) {
						if (ChunkOpt.STATS) ChunkOpt.count("encode." + what + ".fast");
						return DataResult.success((O) tag);
					}
				}
				return vanilla.encode(input, ops, prefix);
			}

			@Override
			public String toString() {
				return "cadence.FastPalette[" + vanilla + "]";
			}
		};
	}

	private static void verify(String what, Object fast, Object ref, boolean refError) {
		if (fast == null) {
			ChunkOpt.count("verify." + what + (refError ? ".declinedError" : ".declined"));
			return;
		}
		if (refError || ref == null) {
			ChunkOpt.count("verify." + what + ".MISMATCH_vanillaFailed");
			return;
		}
		boolean same = Arrays.equals(bytes((PalettedContainerRO<?>) fast), bytes((PalettedContainerRO<?>) ref));
		ChunkOpt.count("verify." + what + (same ? ".same" : ".MISMATCH"));
	}

	/** The container's network form: bits, palette in internal order, storage words: equal bytes, equal container state. */
	private static byte[] bytes(PalettedContainerRO<?> c) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		c.write(buf);
		byte[] out = new byte[buf.readableBytes()];
		buf.readBytes(out);
		buf.release();
		return out;
	}

	private static byte[] tagBytes(Tag tag) {
		try {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			net.minecraft.nbt.NbtIo.writeAnyTag(tag, new java.io.DataOutputStream(bytes));
			return bytes.toByteArray();
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * serialize=true: encode a block-state container as codecRW(BlockState.CODEC) does: vanilla's own pack() (canonical palette,
	 * storage words), then {"palette": [entries], "data": long[] if any}; a default state is its block's id string, any other
	 * state {"id": block id, "properties": {name: value} for every property in name order}.
	 */
	public static Tag encodeBlockStates(PalettedContainer<BlockState> container, Strategy<BlockState> strategy) {
		PalettedContainerRO.PackedData<BlockState> packed = container.pack(strategy);
		ListTag palette = new ListTag();
		for (BlockState state : packed.paletteEntries()) palette.add(encodeState(state));
		return containerTag(palette, packed);
	}

	/** serialize=true: a biome container as codecRO(holderByNameCodec) encodes it; null (vanilla) for a non-reference holder. */
	public static Tag encodeBiomes(PalettedContainerRO<Holder<Biome>> container, Strategy<Holder<Biome>> strategy) {
		PalettedContainerRO.PackedData<Holder<Biome>> packed = container.pack(strategy);
		ListTag palette = new ListTag();
		for (Holder<Biome> biome : packed.paletteEntries()) {
			if (!(biome instanceof Holder.Reference<Biome> ref)) return null;
			palette.add(StringTag.valueOf(ref.key().identifier().toString()));
		}
		return containerTag(palette, packed);
	}

	private static CompoundTag containerTag(ListTag palette, PalettedContainerRO.PackedData<?> packed) {
		CompoundTag tag = new CompoundTag();
		tag.put("palette", palette);
		packed.storage().ifPresent(longs -> tag.put("data", new LongArrayTag(longs.toArray())));
		return tag;
	}

	private static Tag encodeState(BlockState state) {
		Block block = state.getBlock();
		String id = BuiltInRegistries.BLOCK.getKey(block).toString();
		if (state == block.defaultBlockState()) return StringTag.valueOf(id);
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		CompoundTag properties = new CompoundTag();
		for (Property<?> property : block.getStateDefinition().getProperties()) properties.putString(property.getName(), valueName(state, property));
		tag.put("properties", properties);
		return tag;
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	public static PalettedContainer<BlockState> blockStates(CompoundTag container, Strategy<BlockState> strategy) {
		if (!(container.get("palette") instanceof ListTag list)) return null;
		List<BlockState> palette = new ArrayList<>(list.size());
		for (Tag entry : list) {
			BlockState state = blockState(entry);
			if (state == null) return null;
			palette.add(state);
		}
		return unpack(container, strategy, palette);
	}

	public static PalettedContainerRO<Holder<Biome>> biomes(CompoundTag container, Strategy<Holder<Biome>> strategy, Registry<Biome> registry) {
		if (!(container.get("palette") instanceof ListTag list)) return null;
		List<Holder<Biome>> palette = new ArrayList<>(list.size());
		for (Tag entry : list) {
			if (!(entry instanceof StringTag(String name))) return null;
			Identifier id = identifier(name);
			if (id == null) return null;
			Optional<Holder.Reference<Biome>> holder = registry.get(id);
			if (holder.isEmpty()) return null;
			palette.add(holder.get());
		}
		return unpack(container, strategy, palette);
	}

	private static <T> PalettedContainer<T> unpack(CompoundTag container, Strategy<T> strategy, List<T> palette) {
		Tag data = container.get("data");
		Optional<LongStream> storage;
		if (data == null) storage = Optional.empty();
		else if (data instanceof LongArrayTag longs) storage = Optional.of(Arrays.stream(longs.getAsLongArray()));
		else return null;
		return PalettedContainer.unpack(strategy, new PalettedContainerRO.PackedData<>(palette, storage)).result().orElse(null);
	}

	private static BlockState blockState(Tag entry) {
		if (entry instanceof StringTag(String name)) {
			Block block = block(name);
			return block == null ? null : block.defaultBlockState();
		}
		if (!(entry instanceof CompoundTag tag) || !(tag.get("id") instanceof StringTag(String name))) return null;
		Block block = block(name);
		if (block == null) return null;
		StateDefinition<Block, BlockState> definition = block.getStateDefinition();
		BlockState state = block.defaultBlockState();
		if (definition.isSingletonState()) return state;
		Tag properties = tag.get("properties");
		if (properties == null) return state;
		if (!(properties instanceof CompoundTag values)) return null;
		for (Property<?> property : definition.getProperties()) {
			if (values.get(property.getName()) instanceof StringTag(String value)) state = with(state, property, value);
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
		Optional<T> v = property.getValue(value);
		return v.isPresent() ? state.setValue(property, v.get()) : state;
	}

	private static Block block(String name) {
		Identifier id = identifier(name);
		if (id == null) return null;
		Optional<Holder.Reference<Block>> holder = BuiltInRegistries.BLOCK.get(id);
		return holder.map(Holder.Reference::value).orElse(null);
	}

	private static Identifier identifier(String name) {
		return Identifier.read(name).result().orElse(null);
	}
}
