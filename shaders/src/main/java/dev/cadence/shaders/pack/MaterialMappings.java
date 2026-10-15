// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry-name mappings. Resolve block states once during pack activation, not for every vertex.
 */
public final class MaterialMappings {
  public record BlockRule(int id, Map<String, List<String>> properties) {
    public BlockRule {
      Map<String, List<String>> copy = new TreeMap<>();
      properties.forEach((name, values) -> copy.put(name, List.copyOf(values)));
      properties = Collections.unmodifiableMap(copy);
    }

    boolean matches(Map<String, String> state) {
      return properties.entrySet().stream()
          .allMatch(
              entry -> {
                // Cadence: a selector naming a property this block doesn't have doesn't match (Complementary's
                // block.properties: NullPointerException before)
                String value = state.get(entry.getKey());
                return value != null && entry.getValue().contains(value);
              });
    }
  }

  private final Map<String, List<BlockRule>> blocks;
  private final Map<String, Integer> items;
  private final Map<String, Integer> entities;

  private MaterialMappings(
      Map<String, List<BlockRule>> blocks,
      Map<String, Integer> items,
      Map<String, Integer> entities) {
    Map<String, List<BlockRule>> copy = new TreeMap<>();
    blocks.forEach((name, rules) -> copy.put(name, List.copyOf(rules)));
    this.blocks = Collections.unmodifiableMap(copy);
    this.items = Collections.unmodifiableMap(new TreeMap<>(items));
    this.entities = Collections.unmodifiableMap(new TreeMap<>(entities));
  }

  public static MaterialMappings load(
      ShaderPack pack, Map<String, String> options, Map<String, String> environment)
      throws ShaderPackException {
    Map<String, String> definitions = pack.definitions(options, environment);
    return parse(
        pack.contains("block.properties") ? pack.text("block.properties") : "",
        pack.contains("item.properties") ? pack.text("item.properties") : "",
        pack.contains("entity.properties") ? pack.text("entity.properties") : "",
        definitions);
  }

  public static MaterialMappings parse(
      String blockSource, String itemSource, String entitySource, Map<String, String> definitions)
      throws ShaderPackException {
    Map<String, List<BlockRule>> blocks = new TreeMap<>();
    for (var entry : ShaderProperties.parse(blockSource, definitions).values().entrySet()) {
      if (!entry.getKey().startsWith("block.")) continue;
      int id = materialId(entry.getKey(), "block.");
      tokens:
      for (String token : entry.getValue().split("\\s+")) {
        if (token.isEmpty()) continue;
        String[] parts = token.split(":", -1);
        int propertyStart = parts.length > 1 && !parts[1].contains("=") ? 2 : 1;
        String registryName = lenientId(propertyStart == 2 ? parts[0] + ":" + parts[1] : parts[0], token);
        if (registryName == null) continue;
        Map<String, List<String>> properties = new TreeMap<>();
        for (int index = propertyStart; index < parts.length; index++) {
          String[] property = parts[index].split("=", -1);
          // Cadence: an entry Cadence can't read is skipped, as Iris skips it, instead of refusing the pack
          if (property.length != 2
              || !property[0].matches("[a-z0-9_]+")
              || !property[1].matches("[a-z0-9_,-]+")
              || properties.putIfAbsent(property[0], List.of(property[1].split(",", -1))) != null) {
            skipped(token);
            continue tokens;
          }
        }
        blocks
            .computeIfAbsent(registryName, ignored -> new ArrayList<>())
            .add(new BlockRule(id, properties));
      }
    }
    for (List<BlockRule> rules : blocks.values()) {
      rules.sort(Comparator.comparingInt((BlockRule rule) -> rule.properties().size()).reversed());
    }
    return new MaterialMappings(
        blocks,
        parseSimple(itemSource, "item.", definitions),
        parseSimple(entitySource, "entity.", definitions));
  }

  private static Map<String, Integer> parseSimple(
      String source, String prefix, Map<String, String> definitions) throws ShaderPackException {
    Map<String, Integer> result = new TreeMap<>();
    for (var entry : ShaderProperties.parse(source, definitions).values().entrySet()) {
      if (!entry.getKey().startsWith(prefix)) continue;
      int id = materialId(entry.getKey(), prefix);
      for (String token : entry.getValue().split("\\s+")) {
        if (token.isEmpty()) continue;
        String name = lenientId(token, token);
        if (name == null) continue;
        result.putIfAbsent(name, id); // Cadence: a name listed under two IDs keeps the first
      }
    }
    return result;
  }

  private static int materialId(String key, String prefix) throws ShaderPackException {
    try {
      int id = Integer.parseInt(key.substring(prefix.length()));
      if (id < 0) throw new NumberFormatException();
      return id;
    } catch (NumberFormatException e) {
      throw new ShaderPackException("Invalid material mapping key: " + key);
    }
  }

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("cadence_shaders");

  /** The registry id, or null (logged) for one Cadence can't read, such as Bliss's "conquest:conquest:iron_candelabrum_1". */
  private static String lenientId(String name, String token) {
    try {
      return registryId(name);
    } catch (ShaderPackException e) {
      skipped(token);
      return null;
    }
  }

  private static void skipped(String token) {
    LOGGER.debug("Shader pack material entry skipped (not a block/item/entity name Cadence can read): {}", token);
  }

  private static String registryId(String name) throws ShaderPackException {
    String qualified = name.contains(":") ? name : "minecraft:" + name;
    if (!qualified.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
      throw new ShaderPackException("Unsupported material identifier: " + name);
    }
    return qualified;
  }

  public Map<String, List<BlockRule>> blocks() {
    return blocks;
  }

  public int blockId(String registryId, Map<String, String> properties) throws ShaderPackException {
    int result = -1;
    int specificity = -1;
    for (BlockRule rule : blocks.getOrDefault(registryId(registryId), List.of())) {
      if (!rule.matches(properties)) continue;
      if (specificity > rule.properties().size()) break;
      if (result != -1 && result != rule.id()) break; // Cadence: equally specific rules disagree: the first listed wins
      result = rule.id();
      specificity = rule.properties().size();
    }
    return result;
  }

  public int itemId(String registryId) {
    return items.getOrDefault(
        registryId.contains(":") ? registryId : "minecraft:" + registryId, -1);
  }

  public int entityId(String registryId) {
    return entities.getOrDefault(
        registryId.contains(":") ? registryId : "minecraft:" + registryId, -1);
  }
}
