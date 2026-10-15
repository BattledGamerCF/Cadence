// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import dev.cadence.scene.SceneExtractionRequest;
import dev.cadence.scene.SceneGeneration;
import dev.cadence.scene.SceneProvider;
import dev.cadence.scene.SceneSelection;
import dev.cadence.scene.SceneSelectionTicket;
import dev.cadence.scene.SceneViewRequest;
import dev.cadence.scene.SceneViews;
import dev.cadence.scene.VanillaSceneProvider;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.SectionPos;

/**
 * Retains spatial metadata across independently requested views. Mesh readiness is read fresh,
 * including empty-to-solid changes, mining, asynchronous upload completion and transparency resort.
 * Neither camera occlusion nor a previous frame's mesh membership determines shadow membership.
 */
public final class SpatialSceneProvider implements SceneProvider {
  private static final boolean ASYNC_INDEX =
      Boolean.parseBoolean(System.getProperty("cadence.scene.asyncIndex", "true"));
  private final LatestIndexBuild<SpatialSectionIndex<RenderSection>> builder =
      new LatestIndexBuild<>();
  private final SelectionPrefetch<RenderSection> selectionWorker = new SelectionPrefetch<>();
  private long selectionFilterNanos;
  private final VanillaSceneProvider fallback = new VanillaSceneProvider();
  private IndexIdentity requestedIndex;
  private long asyncFallbacks;
  private ViewArea area;
  private SceneGeneration generation;
  private SectionPos center;
  private long positions;
  private SpatialSectionIndex<RenderSection> index;
  private EventSectionMembership<RenderSection> extractionMembership;
  private long queries;
  private long indexBuilds;
  private long coarseTests;
  private long sectionTests;
  private long bulkAcceptedSections;
  private long submitted;
  private long extractionQueries;
  private long extractionCandidates;
  private long extractionSubmitted;
  private long extractionFallbacks;

  @Override
  public String id() {
    return "spatial-sections";
  }

  @Override
  public SceneSelection select(SceneViewRequest request) {
    if (!ensureIndex(request)) {
      asyncFallbacks++;
      return fallback.select(request);
    }
    var query = index.query(request.volume());
    var selected = new ArrayList<RenderSection>();
    int candidates = 0;
    int tested = 0;
    int acceptedInside = 0;
    for (int i = query.candidates().nextSetBit(0);
        i >= 0;
        i = query.candidates().nextSetBit(i + 1)) {
      var entry = index.entry(i);
      RenderSection section = entry.value();
      if (request.requireRenderableLayers() && !section.getSectionMesh().hasRenderableLayers()) {
        continue;
      }
      if (query.fullyInside().get(i)) {
        acceptedInside++;
      } else {
        tested++;
        if (!request.volume().intersects(entry.bounds())) continue;
      }
      candidates++;
      if (request.refinement() == null || request.refinement().intersects(entry.bounds())) {
        selected.add(section);
      }
    }
    queries++;
    coarseTests += query.coarseTests();
    sectionTests += tested;
    bulkAcceptedSections += acceptedInside;
    submitted += selected.size();
    return new SceneSelection(
        request.area(),
        request.generation(),
        request.viewId(),
        selected,
        candidates,
        tested,
        query.coarseTests(),
        id());
  }

  @Override
  public SceneSelectionTicket prefetch(SceneViewRequest request) {
    // Prefetch must not initiate or wait for an index rebuild. Camera recentering keeps the
    // existing synchronous/live fallback until the normal index lifecycle publishes a match.
    SelectionIdentity identity = selectionIdentity(request);
    if (identity == null) return null;
    try {
      var volume = request.volume().workerSnapshot();
      var refinement = request.refinement() == null ? null : request.refinement().workerSnapshot();
      if (volume == null || (request.refinement() != null && refinement == null)) return null;
      return selectionWorker.submit(request, identity, index, volume, refinement);
    } catch (RuntimeException failure) {
      selectionWorker.failedPrefetch();
      return null;
    }
  }

  @Override
  public SceneSelection select(SceneViewRequest request, SceneSelectionTicket ticket) {
    if (ticket == null) return select(request);
    var prepared = selectionWorker.consume(ticket, request, selectionIdentity(request));
    if (prepared == null) return select(request);
    long started = System.nanoTime();
    var filtered = prepared.filter(
        section -> !request.requireRenderableLayers()
            || section.getSectionMesh().hasRenderableLayers());
    queries++;
    coarseTests += filtered.coarseTests();
    sectionTests += filtered.tested();
    bulkAcceptedSections += filtered.inside();
    submitted += filtered.sections().size();
    var result = new SceneSelection(
        request.area(), request.generation(), request.viewId(), filtered.sections(),
        filtered.candidates(), filtered.tested(), filtered.coarseTests(), id());
    selectionFilterNanos += System.nanoTime() - started;
    return result;
  }

  private SelectionIdentity selectionIdentity(SceneViewRequest request) {
    long currentPositions = SectionPositionRevision.current();
    if (index == null || area != request.area()
        || !Objects.equals(generation, request.generation())
        || !Objects.equals(center, request.area().getCameraSectionPos())
        || positions != currentPositions) return null;
    return new SelectionIdentity(index, area, generation, center, currentPositions);
  }

  private record SelectionIdentity(
      SpatialSectionIndex<RenderSection> index, ViewArea area,
      SceneGeneration generation, SectionPos center, long positions) {}

  private boolean ensureIndex(SceneViewRequest request) {
    long currentPositions = SectionPositionRevision.current();
    IndexIdentity identity =
        new IndexIdentity(
            request.area(),
            request.generation(),
            request.area().getCameraSectionPos(),
            currentPositions);
    if (index != null
        && area == identity.area()
        && Objects.equals(generation, identity.generation())
        && Objects.equals(center, identity.center())
        && positions == identity.positions()) return true;
    if (!ASYNC_INDEX) {
      rebuild(request, currentPositions);
      return true;
    }
    try {
      if (!identity.equals(requestedIndex)) {
        builder.cancel();
        releaseExtractionMembership();
        requestedIndex = identity;
        // Read live Minecraft state only here, on the caller/render thread. The worker sees
        // immutable AABBs and opaque section references; it never dereferences a section.
        List<SpatialSectionIndex.Entry<RenderSection>> snapshot = snapshot(request.area());
        builder.submit(identity, () -> new SpatialSectionIndex<>(snapshot));
      }
      SpatialSectionIndex<RenderSection> ready = builder.poll(identity);
      if (ready == null) return false;
      index = ready;
      area = identity.area();
      generation = identity.generation();
      center = identity.center();
      positions = identity.positions();
      indexBuilds++;
      return true;
    } catch (RuntimeException failure) {
      // Keep this failed identity instead of resubmitting every frame. Fresh vanilla
      // selection is valid until a new world/position/material identity permits a retry.
      org.slf4j.LoggerFactory.getLogger("cadence_scene_optimizer")
          .warn("Spatial index unavailable; retaining live section selection", failure);
      return false;
    }
  }

  private record IndexIdentity(
      ViewArea area, SceneGeneration generation, SectionPos center, long positions) {}

  private static List<SpatialSectionIndex.Entry<RenderSection>> snapshot(ViewArea area) {
    var entries = new ArrayList<SpatialSectionIndex.Entry<RenderSection>>(area.size());
    for (RenderSection section : SceneViews.sections(area))
      entries.add(new SpatialSectionIndex.Entry<>(section, section.getBoundingBox()));
    return List.copyOf(entries);
  }

  @Override
  public List<RenderSection> selectExtraction(SceneExtractionRequest request) {
    if (Boolean.getBoolean("cadence.scene.disableSparseExtraction")
        || (request.purpose() == SceneExtractionRequest.Purpose.DIRTY_SECTIONS
            && (request.updateTracker().getClass() != SectionUpdateTracker.class
                || !(request.updateTracker() instanceof TrackedDirtySections)))) {
      extractionFallbacks++;
      return SceneProvider.super.selectExtraction(request);
    }
    if (!ensureIndex(request.view())) {
      extractionFallbacks++;
      return SceneProvider.super.selectExtraction(request);
    }
    if (extractionMembership == null) {
      var sections = new ArrayList<RenderSection>(index.size());
      for (int i = 0; i < index.size(); i++) sections.add(index.entry(i).value());
      extractionMembership = new EventSectionMembership<>(sections);
      SectionMeshChanges.listen(extractionMembership);
      extractionMembership.initialize(SpatialSceneProvider::mayHaveBlockEntities);
    }
    BitSet candidates;
    if (request.purpose() == SceneExtractionRequest.Purpose.BLOCK_ENTITIES) {
      candidates = extractionMembership.snapshot(SpatialSceneProvider::mayHaveBlockEntities);
    } else {
      var tracked = (TrackedDirtySections) request.updateTracker();
      var exclude = request.excludeExtra();
      var dirtySet = tracked.scene$dirtySections();
      // Cadence: the dirty nodes' ordinals only change when the dirty set, the exclusion or the index does; most frames
      // reuse the last candidate set (the volume test below still runs every frame).
      if (dirtySet == cachedDirtySet && dirtySet.version() == cachedDirtyVersion && exclude == cachedExclude
          && extractionMembership == cachedMembership && cachedCandidates != null) {
        candidates = cachedCandidates;
        if (VERIFY_EVERY > 0 && ++verifyReuses % VERIFY_EVERY == 0) {
          verifyChecks++;
          if (!candidates.equals(dirtyCandidates(request, dirtySet, exclude))) verifyMismatches++;
          if (System.nanoTime() - verifyLogged > 30_000_000_000L) {
            verifyLogged = System.nanoTime();
            org.slf4j.LoggerFactory.getLogger("cadence")
                .info("Extraction candidate cache: {} reuses checked, {} differed from a fresh scan", verifyChecks, verifyMismatches);
          }
        }
      } else {
        candidates = dirtyCandidates(request, dirtySet, exclude);
        cachedDirtySet = dirtySet;
        cachedDirtyVersion = dirtySet.version();
        cachedExclude = exclude;
        cachedMembership = extractionMembership;
        cachedCandidates = candidates;
      }
    }
    var extra = new ArrayList<RenderSection>();
    int tested = 0;
    for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
      var entry = index.entry(i);
      tested++;
      if (request.view().volume().intersects(entry.bounds())
          && (request.view().refinement() == null
              || request.view().refinement().intersects(entry.bounds()))) {
        extra.add(entry.value());
      }
    }
    var result = request.merge(extra);
    extractionQueries++;
    extractionCandidates += tested;
    extractionSubmitted += result.size();
    return result;
  }

  /** Ordinals of the dirty sections the caller doesn't exclude. */
  private BitSet dirtyCandidates(
      SceneExtractionRequest request, DirtySectionMembership dirtySet, java.util.function.LongPredicate exclude) {
    BitSet candidates = new BitSet(index.size());
    // Cadence: primitive iteration (the boxed for-each allocated a Long per dirty node per frame) and the caller's
    // exclusion first: the shadow pass's far and unneeded casters stay dirty forever and were re-tested every frame.
    for (var nodes = dirtySet.nodes().iterator(); nodes.hasNext(); ) {
      long node = nodes.nextLong();
      if (exclude != null && exclude.test(node)) continue;
      RenderSection section = SceneViews.section(request.view().area(), node);
      int ordinal = extractionMembership.ordinal(section);
      if (ordinal >= 0) candidates.set(ordinal);
    }
    return candidates;
  }

  // -Dcadence.verifyExtractionCache=true: every reuse is checked against a fresh scan; self-tests check every 16th
  private static final int VERIFY_EVERY = Boolean.getBoolean("cadence.verifyExtractionCache") ? 1
      : System.getProperty("cadence.selftest") != null ? 16 : 0;
  private long verifyReuses, verifyChecks, verifyMismatches, verifyLogged;
  private DirtySectionMembership cachedDirtySet;
  private long cachedDirtyVersion;
  private java.util.function.LongPredicate cachedExclude;
  private EventSectionMembership<RenderSection> cachedMembership;
  private BitSet cachedCandidates;

  private static boolean mayHaveBlockEntities(RenderSection section) {
    // Custom mutable meshes remain candidates, even if their current feature list is empty.
    if (section.getClass() != RenderSection.class) return true;
    var mesh = section.getSectionMesh();
    if (mesh == CompiledSectionMesh.EMPTY || mesh == CompiledSectionMesh.UNCOMPILED) return false;
    return mesh.getClass() != CompiledSectionMesh.class
        || !mesh.getRenderableBlockEntities().isEmpty();
  }

  private void rebuild(SceneViewRequest request, long currentPositions) {
    releaseExtractionMembership();
    index = new SpatialSectionIndex<>(snapshot(request.area()));
    area = request.area();
    generation = request.generation();
    center = area.getCameraSectionPos();
    positions = currentPositions;
    indexBuilds++;
  }

  @Override
  public void invalidate() {
    builder.cancel();
    selectionWorker.cancel();
    requestedIndex = null;
    releaseExtractionMembership();
    // Cadence: the extraction candidate cache holds the old view's sections
    cachedDirtySet = null;
    cachedExclude = null;
    cachedMembership = null;
    cachedCandidates = null;
    index = null;
    area = null;
    generation = null;
    center = null;
  }

  private void releaseExtractionMembership() {
    if (extractionMembership != null) SectionMeshChanges.stop(extractionMembership);
    extractionMembership = null;
  }

  public Stats stats() {
    var worker = builder.stats();
    var selection = selectionWorker.stats();
    return new Stats(
        queries,
        indexBuilds,
        coarseTests,
        sectionTests,
        bulkAcceptedSections,
        submitted,
        index == null ? 0 : index.size(),
        extractionQueries,
        extractionCandidates,
        extractionSubmitted,
        extractionFallbacks,
        ASYNC_INDEX,
        worker.submitted(),
        worker.completed(),
        worker.cancelled(),
        worker.pending(),
        asyncFallbacks,
        selection.submitted(), selection.ready(), selection.missed(), selection.stale(),
        selection.failed(), selection.workerNanos(), selectionFilterNanos);
  }

  public record Stats(
      long queries,
      long indexBuilds,
      long coarseTests,
      long sectionTests,
      long bulkAcceptedSections,
      long submittedSections,
      int indexedSections,
      long extractionQueries,
      long extractionCandidates,
      long extractionSubmitted,
      long extractionFallbacks,
      boolean asyncIndex,
      long indexBuildsSubmitted,
      long indexBuildsCompleted,
      long indexBuildsCancelled,
      boolean indexBuildPending,
      long asyncSelectionFallbacks,
      long selectionPrefetchSubmitted,
      long selectionPrefetchReady,
      long selectionPrefetchMissed,
      long selectionPrefetchStale,
      long selectionPrefetchFailed,
      long selectionWorkerNanos,
      long selectionFilterNanos) {}
}
