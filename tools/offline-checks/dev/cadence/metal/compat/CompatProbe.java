package dev.cadence.metal.compat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** Offline check of CompatLayers' mixin reading: the test mod's hook and Cadence's overwrite on ModelPart.Cube.compile. */
public class CompatProbe {
	public static void main(String[] args) throws Exception {
		List<CompatLayers.Hook> theirs = new ArrayList<>(), ours = new ArrayList<>();
		try (ZipFile z = new ZipFile(args[0])) {
			CompatLayers.hooks("compat_test", "dev.cadence.compattest.mixin.CubeCountMixin",
				z.getInputStream(z.getEntry("dev/cadence/compattest/mixin/CubeCountMixin.class")).readAllBytes(), theirs);
		}
		CompatLayers.hooks("cadence", "dev.cadence.metal.mixin.cpu.ModelCubeWriteMixin",
			Files.readAllBytes(Path.of(args[1], "dev/cadence/metal/mixin/cpu/ModelCubeWriteMixin.class")), ours);
		boolean a = theirs.stream().anyMatch(h -> h.target().equals("net.minecraft.client.model.geom.ModelPart$Cube") && h.method().equals("compile") && !h.replacing());
		boolean b = ours.stream().anyMatch(h -> h.target().equals("net.minecraft.client.model.geom.ModelPart$Cube") && h.method().equals("compile") && h.replacing());
		System.out.println((a ? "OK    " : "FAIL  ") + "compat: the test mod's plain inject on ModelPart$Cube.compile is read");
		System.out.println((b ? "OK    " : "FAIL  ") + "compat: Cadence's overwrite of ModelPart$Cube.compile is read as replacing");
		System.exit(a && b ? 0 : 1);
	}
}
