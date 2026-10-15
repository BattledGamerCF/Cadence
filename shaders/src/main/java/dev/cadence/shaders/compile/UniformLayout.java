// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.compile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The shared std140 layout supplied to both stages of one shader-pack program. */
public record UniformLayout(List<Field> fields, int byteSize, Map<String, float[]> defaults) {
  public static final String BLOCK_NAME = "PackUniforms";

  public UniformLayout {
    fields = List.copyOf(fields);
    defaults = Map.copyOf(defaults);
  }

  public UniformLayout(List<Field> fields, int byteSize) {
    this(fields, byteSize, Map.of());
  }

  public record Field(
      String name,
      String type,
      int arrayLength,
      int offset,
      int byteSize,
      int arrayStride,
      int matrixStride) {}

  static UniformLayout of(Map<String, Declaration> declarations) {
    return of(declarations, Map.of());
  }

  /** defaults: Cadence: the pack's own initial value for a uniform the game may not supply (component order). */
  static UniformLayout of(Map<String, Declaration> declarations, Map<String, float[]> defaults) {
    List<Field> fields = new ArrayList<>();
    int offset = 0;
    for (var entry : declarations.entrySet()) {
      Declaration declaration = entry.getValue();
      Type type = type(declaration.type());
      int alignment =
          declaration.arrayLength() > 0 ? align(type.alignment(), 16) : type.alignment();
      int stride = declaration.arrayLength() > 0 ? align(type.size(), 16) : 0;
      int size = declaration.arrayLength() > 0 ? stride * declaration.arrayLength() : type.size();
      offset = align(offset, alignment);
      fields.add(
          new Field(
              entry.getKey(),
              declaration.type(),
              declaration.arrayLength(),
              offset,
              size,
              stride,
              type.matrixStride()));
      offset += size;
    }
    return new UniformLayout(fields, align(offset, 16), defaults);
  }

  public Field field(String name) {
    return fields.stream()
        .filter(field -> field.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown pack uniform: " + name));
  }

  public ByteBuffer allocate() {
    return ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder());
  }

  /** Absolute writes preserve buffer position and make missing/wrongly typed values explicit. */
  public void putFloats(ByteBuffer target, String name, float... values) {
    Field field = field(name);
    if (!(field.type().equals("float")
        || field.type().startsWith("vec")
        || field.type().startsWith("mat")))
      throw new IllegalArgumentException("Uniform is not floating point: " + name);
    write(target, field, values.length, (offset, index) -> target.putFloat(offset, values[index]));
  }

  public void putInts(ByteBuffer target, String name, int... values) {
    Field field = field(name);
    if (!(field.type().equals("int")
        || field.type().equals("uint")
        || field.type().equals("bool")
        || field.type().matches("[ibu]vec[234]")))
      throw new IllegalArgumentException("Uniform is not integer/bool: " + name);
    write(target, field, values.length, (offset, index) -> target.putInt(offset, values[index]));
  }

  private static void write(ByteBuffer target, Field field, int count, Writer writer) {
    Type type = type(field.type());
    int elements = Math.max(field.arrayLength(), 1);
    if (count != elements * type.components())
      throw new IllegalArgumentException(
          "Uniform "
              + field.name()
              + " expects "
              + elements * type.components()
              + " values, received "
              + count);
    if (target.limit() < field.offset() + field.byteSize())
      throw new IllegalArgumentException("Uniform buffer is too small for " + field.name());
    for (int element = 0; element < elements; element++) {
      int start = field.offset() + element * field.arrayStride();
      for (int component = 0; component < type.components(); component++) {
        int byteOffset =
            type.matrixStride() == 0
                ? component * 4
                : component / type.rows() * type.matrixStride() + component % type.rows() * 4;
        writer.write(start + byteOffset, element * type.components() + component);
      }
    }
  }

  String declaration() {
    return declaration(java.util.Set.of());
  }

  /** hidden: Cadence: members named sl_other_NAME in this stage's source (declared by the other stage only). */
  String declaration(java.util.Set<String> hidden) {
    if (fields.isEmpty()) return "";
    StringBuilder source = new StringBuilder("layout(std140) uniform " + BLOCK_NAME + " {\n");
    for (Field field : fields) {
      source.append("  ").append(field.type()).append(' ')
          .append(hidden.contains(field.name()) ? "sl_other_" + field.name() : field.name());
      if (field.arrayLength() > 0) source.append('[').append(field.arrayLength()).append(']');
      source.append(";\n");
    }
    return source.append("};\n").toString();
  }

  private static Type type(String type) {
    if (type.matches("float|int|uint|bool")) return new Type(4, 4, 1, 1, 0);
    if (type.matches("[ibu]?vec[234]")) {
      int size = type.charAt(type.length() - 1) - '0';
      return new Type(size == 2 ? 8 : 16, size * 4, size, size, 0);
    }
    if (type.matches("mat[234](x[234])?")) {
      int columns = type.charAt(3) - '0';
      int rows = type.length() == 4 ? columns : type.charAt(5) - '0';
      return new Type(16, columns * 16, columns * rows, rows, 16);
    }
    throw new UnsupportedOperationException("Unsupported pack uniform type: " + type);
  }

  private static int align(int value, int alignment) {
    return (value + alignment - 1) / alignment * alignment;
  }

  record Declaration(String type, int arrayLength) {}

  private record Type(int alignment, int size, int components, int rows, int matrixStride) {}

  private interface Writer {
    void write(int offset, int index);
  }
}
