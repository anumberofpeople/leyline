package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.annotations.AnnotationContext
import leyline.game.annotations.AnnotationPipeline
import leyline.game.annotations.CombatAnnotationResult
import leyline.game.annotations.ConvokeContributor
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferResult
import leyline.game.annotations.ZoneTransferAdapter
import leyline.game.bundle.GsmFrame
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.snapshot.EarthbendProjection
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.AnnotationProjectionState
import leyline.game.state.DelayedTriggerAffecteesKind
import leyline.game.state.EarthbendTracker
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.EffectTracker
import leyline.game.state.FrameContext
import leyline.game.state.HolderBatch
import leyline.game.state.HolderRecord
import leyline.game.state.InstanceIdRegistry
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.OpponentKnowledgeTracker
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.ProjectionOutput
import leyline.game.state.ProjectionState
import leyline.game.state.PromptFactConsumption
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.RevealStarted
import leyline.game.state.SyntheticEffectProjection
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Orchestrates the GsmSnapshot → proto state-mapping pipeline.
 *
 * Builds a Full or Diff [GameStateMessage] draft from immutable frame values.
 * [StateProjectionCompiler] owns the public finalized transition boundary.
 *
 * Lifecycle GSM factories (deal-hand, mulligan, transitions) live in [leyline.game.bundle.GsmBuilder].
 * Interactive request builders (targeting, combat) live in [leyline.game.bundle.RequestBuilder].
 * Value-to-proto projection helpers live in the `mapping/` package.
 *
 * ## Tentative compute boundary
 *
 * The compiler supplies one private [ProjectionState.Editor]. This mapper adds
 * base frame state and annotations without freezing or exposing the editor.
 *
 * [StateFrameInput] carries snapshot, event, [PromptProjectionFacts],
 * [EffectProjectionFacts], [MechanicSourceFacts], [AbilityExhaustionFacts], and
 * [PersistentFeedFacts] values for scoped projection inputs.
 *
 * [StateMapperValueBoundaryTest] exercises the direct no-bridge contract across
 * two frames. [PureDiffReplayTest] covers broader shell-materialized replay.
 * Both assert byte-equal messages and equal next state for the same input.
 *
 * All history-dependent operations use one private [ProjectionState.Editor].
 * Stable card metadata and protocol configuration come from the match-scoped
 * [StateProjectionEnvironment]. No shared projection value changes until the
 * shell validates and installs the returned transition.
 */
@Suppress("LargeClass") // pipeline orchestrator; stages already delegated to mapper/* and helper objects
object StateMapper {
    private val log = LoggerFactory.getLogger(StateMapper::class.java)
    private val disturbBackPlayerZoneIds =
        setOf(
            ZoneIds.P1_HAND,
            ZoneIds.P2_HAND,
            ZoneIds.P1_GRAVEYARD,
            ZoneIds.P2_GRAVEYARD,
        )

    internal data class Draft(
        val gsm: GameStateMessage,
        /** Engine snapshot used as the next viewer diff baseline. */
        val projectionSnapshot: GsmSnapshot,
        /** Compute-only metadata needed to assemble the wire result. */
        val output: ProjectionOutput,
        val firstAnnotationId: Int,
        val idResolver: FrameIdResolver,
        /** Existing objects that must be re-emitted for state projected outside [CardSnapshot]. */
        val objectRefreshInstanceIds: Set<Int>,
    )

    private data class ShuffleIdentityPlan(
        val eventIndex: Int,
        val cardIds: List<ForgeCardId>,
        val reallocations: Map<ForgeCardId, InstanceIdRegistry.IdReallocation>,
    )

    private fun planShuffleIdentities(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        prev: GsmSnapshot?,
        editor: ProjectionState.Editor,
    ): List<ShuffleIdentityPlan> =
        events.mapIndexedNotNull { index, event ->
            if (event !is GameEvent.LibraryShuffled) return@mapIndexedNotNull null
            val zoneId = ZoneIds.libraryOf(event.seatId.value)
            val cardIds = snap.zones[zoneId]?.contents.orEmpty()
            val priorCardIds =
                prev
                    ?.zones
                    ?.get(zoneId)
                    ?.contents
                    .orEmpty()
                    .toSet()
            ShuffleIdentityPlan(
                eventIndex = index,
                cardIds = cardIds,
                reallocations = cardIds.filter { prev == null || it in priorCardIds }.associateWith(editor.identities::realloc),
            )
        }

    private fun applyShuffleIdentityPlans(
        events: MutableList<GameEvent>,
        plans: List<ShuffleIdentityPlan>,
        transferResult: TransferResult,
    ) {
        val transfersByCard = transferResult.transfers.mapNotNull { transfer -> transfer.forgeCardId?.let { it to transfer } }.toMap()
        plans.forEach { plan ->
            val pairs =
                plan.cardIds.mapNotNull { cardId ->
                    plan.reallocations[cardId]?.let { it.old.value to it.new.value }
                        ?: transfersByCard[cardId]?.let { it.origId to it.newId }
                }
            val event = events[plan.eventIndex] as GameEvent.LibraryShuffled
            events[plan.eventIndex] = event.copy(oldIds = pairs.map { it.first }, newIds = pairs.map { it.second })
        }
    }

    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
    private fun buildFromSnapshotInternal(
        rawSnap: GsmSnapshot,
        gameStateId: Int,
        matchId: String,
        environment: StateProjectionEnvironment,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        prev: GsmSnapshot? = null,
        events: FrameEventLog,
        promptFacts: PromptProjectionFacts,
        persistentFeedFacts: PersistentFeedFacts,
        effectFacts: EffectProjectionFacts,
        mechanicSourceFacts: MechanicSourceFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
        editor: ProjectionState.Editor,
    ): Draft {
        val annotationJournal = editor.annotations
        val effectPlanner = editor.effects
        val earthbendResolutions = effectFacts.pendingEarthbendResolutions
        val earthbendSignatures = effectFacts.battlefieldEarthbendSignatures
        for (resolution in earthbendResolutions) {
            if (rawSnap.boundCards[resolution.sourceCardId] == null) continue
            val sourceIid = editor.identities.getOrAlloc(resolution.sourceCardId)
            val resolvingIid =
                if (resolution.abilityForgeId != 0) {
                    editor.identities.getOrAlloc(FrameIdResolver.triggerStackAbilityForgeId(resolution.abilityForgeId))
                } else {
                    sourceIid
                }
            effectPlanner.earthbend.recordResolution(
                resolution = resolution,
                sourceCardGrpId = rawSnap.boundCards[resolution.sourceCardId]?.snapshot?.grpId ?: 0,
                sourceInstanceId = sourceIid.value,
                resolvingInstanceId = resolvingIid.value,
                battlefieldSignatures = earthbendSignatures,
                targetInstanceId = { forgeCardId -> editor.identities.getOrAlloc(forgeCardId).value },
                nextEffectId = effectPlanner.effects::nextEffectId,
            )
        }
        val snap = rawSnap
        snap.objects.forEach { (cardId, card) ->
            card.emblem?.let { editor.emblemLineage.putIfAbsent(cardId, it) }
        }
        val earthbendProjection: (ForgeCardId) -> EarthbendProjection? = { forgeCardId ->
            effectPlanner.earthbend.projectionFor(
                forgeCardId,
                earthbendSignatures.firstOrNull { it.forgeCardId == forgeCardId }?.signature,
            )
        }
        val frame = GsmFrame.Companion.from(snap)

        // ═══ GATHER: snapshot mutable state (events arrive from caller) ═══
        // applyRevealProxies may append RevealProxiesDeleted on reveal end; keep local mutable copy.
        val eventsMutable = events.events.toMutableList()
        val shuffleIdentityPlans = planShuffleIdentities(eventsMutable, snap, prev, editor)
        val initEffectDiff = effectPlanner.effects.emitInitEffectsOnce()
        val boostSnapshot = boostEntries(effectFacts, editor.identities)
        val effectDiff = effectPlanner.effects.diffBoosts(boostSnapshot)
        val keywordSnapshot = keywordEntries(effectFacts, effectPlanner.earthbend, editor.identities)
        val keywordDiff = effectPlanner.effects.diffKeywords(keywordSnapshot)
        val grantedAbilitySnapshot = grantedAbilityEntries(effectFacts, editor.identities)
        val grantedAbilityDiff = effectPlanner.effects.diffGrantedAbilities(grantedAbilitySnapshot)
        val activeGrantedAbilities = effectPlanner.effects.activeGrantedAbilities().groupBy { it.cardInstanceId }
        // Persistent annotation history comes from the tentative projection editor.
        // computeBatch is pure over this value and the current feed set.
        val persistentState = editor.persistentAnnotations
        val persistSnapshot = persistentState.activeAnnotations
        val startPersistentId = persistentState.nextPersistentId
        val startAnnotationId = persistentState.nextAnnotationId

        // ═══ MAP: engine state → proto objects ═══
        val gameInfo = StateZoneProjection.buildGameInfo(matchId, environment.matchConfig)

        val player1 = PlayerMapper.buildFromSnapshot(snap, 1)
        val player2 = PlayerMapper.buildFromSnapshot(snap, 2)

        val team1 =
            TeamInfo
                .newBuilder()
                .setId(1)
                .addPlayerIds(1)
                .setStatus(TeamStatus.InGame_a458)
        val team2 =
            TeamInfo
                .newBuilder()
                .setId(2)
                .addPlayerIds(2)
                .setStatus(TeamStatus.InGame_a458)

        val zones = mutableListOf<ZoneInfo>()
        val gameObjects = mutableListOf<GameObjectInfo>()

        // Standard zone layout (17 zones, IDs 18-38) — must send all for Full state
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P1, ZoneType.Revealed, 1, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P2, ZoneType.Revealed, 2, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.SUPPRESSED, ZoneType.Suppressed, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.PENDING, ZoneType.Pending, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.COMMAND, ZoneType.Command, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.STACK, ZoneType.Stack, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.EXILE, ZoneType.Exile, 0, Visibility.Public))
        // Limbo zone: include all previously accumulated retired instanceIds.
        // TriggerHolder iids are spliced in after the holder-tracker diff runs
        // below — see the splice that wraps `transferResultWithHolders`. They
        // must reflect post-diff state (holder added → in zone, holder removed
        // → out of zone) so the deletion GSM's Limbo listing doesn't disagree
        // with `diffDeletedInstanceIds`.
        val limboZone =
            ZoneInfo
                .newBuilder()
                .setZoneId(ZoneIds.LIMBO)
                .setType(ZoneType.Limbo)
                .setVisibility(Visibility.Public)
        for (id in editor.limboInstanceIds) {
            limboZone.addObjectInstanceIds(id)
        }
        zones.add(limboZone.build())

        // Classify a stale reveal from materialized prompt facts. Its compare-and-clear
        // intent returns with this draft and runs only after an accepted commit.
        val (activeReveal, staleReveals) = detectActiveReveal(promptFacts, editor.revealProxies.isEmpty())
        val revealedHandSeat = activeReveal?.ownerSeatId?.value

        // Player 1 zones
        if (StateZoneProjection.hasSeat(snap, SeatId(1))) {
            ZoneMapper.addPlayerZonesFromSnapshot(
                SeatId(1),
                snap,
                environment,
                editor.identities::getOrAlloc,
                zones,
                gameObjects,
                ZoneIds.P1_HAND,
                ZoneIds.P1_LIBRARY,
                ZoneIds.P1_GRAVEYARD,
                ZoneIds.P1_SIDEBOARD,
                viewingSeatId,
                revealForSeat,
                revealHand = revealedHandSeat == 1,
                lookHand = activeReveal?.lookOnly == true,
                previousSnapshot = prev,
            )
        }

        // Player 2 zones
        if (StateZoneProjection.hasSeat(snap, SeatId(2))) {
            ZoneMapper.addPlayerZonesFromSnapshot(
                SeatId(2),
                snap,
                environment,
                editor.identities::getOrAlloc,
                zones,
                gameObjects,
                ZoneIds.P2_HAND,
                ZoneIds.P2_LIBRARY,
                ZoneIds.P2_GRAVEYARD,
                ZoneIds.P2_SIDEBOARD,
                viewingSeatId,
                revealForSeat,
                revealHand = revealedHandSeat == 2,
                lookHand = activeReveal?.lookOnly == true,
                previousSnapshot = prev,
            )
        }

        // Populate shared zones with cards.
        projectSharedZone(
            snap,
            ZoneIds.BATTLEFIELD,
            environment,
            editor,
            zones,
            gameObjects,
            keywordSnapshot,
            earthbendProjection,
            activeGrantedAbilities,
        )
        projectSharedZone(snap, ZoneIds.STACK, environment, editor, zones, gameObjects)
        projectSharedZone(snap, ZoneIds.SUPPRESSED, environment, editor, zones, gameObjects)
        projectSharedZone(snap, ZoneIds.EXILE, environment, editor, zones, gameObjects)
        projectSharedZone(snap, ZoneIds.COMMAND, environment, editor, zones, gameObjects)

        // Stack abilities (triggers, activated abilities not represented as zone cards)
        val stateZoneFacts = StateZoneProjection.zoneTransferFacts(snap)
        ZoneMapper.addStackAbilitiesFromSnapshot(
            snap = snap,
            environment = environment,
            instanceIdLookup = editor.identities::getOrAlloc,
            paradigmSourceStackIidLookup = { forgeCardId ->
                StateZoneProjection.paradigmSourceStackIid(
                    facts = stateZoneFacts,
                    forgeCardId = forgeCardId,
                    stackIidLookup = annotationJournal::paradigmSourceStackIidFor,
                )
            },
            zones = zones,
            gameObjects = gameObjects,
        )

        // RevealedCard view synthesis / cleanup (may append RevealProxiesDeleted to eventsMutable)
        applyRevealProxies(activeReveal, snap, editor, environment, zones, gameObjects, eventsMutable)
        addSpeedTriggerHolders(snap, zones, gameObjects)

        log.trace(
            "buildFromSnapshot: phase={} turn={} hand={} objects={} zones={}",
            snap.phase.phase,
            snap.phase.turn,
            snap.zones[ZoneIds.P1_HAND]?.contents?.size ?: 0,
            gameObjects.size,
            zones.size,
        )

        // ═══ COMPUTE: annotation pipeline (stages 1-5) ═══
        var transferResult =
            ZoneTransferAdapter.detectZoneTransfers(
                gameObjects,
                zones,
                editor,
                snap,
                eventsMutable,
                prev,
                mechanicSourceFacts,
                zoneMoves = events.zoneMoves,
            )
        applyShuffleIdentityPlans(eventsMutable, shuffleIdentityPlans, transferResult)
        recordParadigmSourceStackIids(transferResult, snap, annotationJournal)
        // Frame-scoped id resolver — uses the planned-realloc map so any consumer
        // asking "what iid will the client see for this card?" gets the
        // post-realloc answer before the transition installs.
        val frameIds =
            FrameIdResolver(
                editor.identities,
                FrameIdResolver.postReallocIids(transferResult),
            )
        val tokenParents =
            eventsMutable
                .filterIsInstance<GameEvent.TokenCreated>()
                .mapNotNull { event ->
                    val sourceCardId = event.sourceCardId ?: return@mapNotNull null
                    if (event.sourceAbilityForgeId == 0) return@mapNotNull null
                    frameIds.cardIid(event.cardId).value to
                        AnnotationContext.stackAbilityIid(event.sourceAbilityForgeId, sourceCardId, frameIds)
                }.toMap()
        if (tokenParents.isNotEmpty()) {
            transferResult =
                transferResult.copy(
                    patchedObjects =
                        transferResult.patchedObjects.map { obj ->
                            tokenParents[obj.instanceId]?.let { obj.toBuilder().setParentId(it).build() } ?: obj
                        },
                )
        }
        val (opponentKnowledge, nextOpponentKnowledge) =
            OpponentKnowledgeTracker.plan(editor.opponentKnowledge, snap, frameIds, eventsMutable)
        editor.opponentKnowledge = nextOpponentKnowledge
        val resolvedStackAbilityIids =
            eventsMutable
                .filterIsInstance<GameEvent.SpellResolved>()
                .filter { it.isTrigger || it.isAbility }
                .mapTo(linkedSetOf()) { AnnotationContext.stackAbilityIid(it.abilityForgeId, it.cardId, frameIds) }
        transferResult = transferResult.withoutStackAbilities(resolvedStackAbilityIids)
        transferResult =
            transferResult.withDecayedCleanupAffectors(
                eventsMutable,
                snap,
                environment.cardReferences,
                frameIds,
            )
        val actingSeat = snap.phase.priorityPlayer?.value ?: 2
        val annotationContext =
            AnnotationContext(
                editor = editor,
                environment = environment,
                snap = snap,
                frameIds = frameIds,
                events = eventsMutable,
                frameEntryStep = prev?.let { GsmFrame.from(it).step },
                promptFacts = promptFacts,
                effectFacts = effectFacts,
                opponentKnowledge = opponentKnowledge,
                transferResult = transferResult,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
            )
        val (annotations, transferPersistent, combatResult) =
            AnnotationPipeline.computeAnnotations(
                ctx = annotationContext,
                transferResult = transferResult,
                actingSeat = actingSeat,
                annotationJournal = annotationJournal,
            )

        val convokePaymentsBySource = annotationContext.activeConvokePaymentsBySource()
        val convokePlan = ConvokeContributor.plan(annotationContext)
        annotations.addAll(convokePlan.transient)

        val decayedCleanupSourcesThisGsm =
            updateDecayedCleanupSources(
                eventsMutable,
                snap,
                environment.cardReferences,
                transferResult,
                annotationJournal,
            )

        val persistentFeedResult =
            PersistentFeedBuilder.build(
                events = eventsMutable,
                snap = snap,
                prev = prev,
                frameIds = frameIds,
                decayedCleanupSourcesThisGsm = decayedCleanupSourcesThisGsm,
                transferResult = transferResult,
                promptFacts = promptFacts,
                persistentFeedFacts = persistentFeedFacts,
                references = environment.cardReferences,
            )
        val activeHolderRecords = editor.delayedTriggerHolders.toMap()
        val carriedHolders =
            delayedTriggerHoldersAwaitingLiveAbility(
                activeHolders = activeHolderRecords.values.toList(),
                currentHolders = persistentFeedResult.currentHolders,
                activeAnnotations = persistentState.activeAnnotations.values,
                snap = snap,
                identities = editor.identities,
            )
        val currentHolders = persistentFeedResult.currentHolders + carriedHolders
        val currentHoldersByIid = currentHolders.associateBy { it.iid }
        val holderBatch =
            HolderBatch(
                added = currentHolders.filter { it.iid !in activeHolderRecords },
                removed = (activeHolderRecords.keys - currentHoldersByIid.keys).toList(),
            )
        val removedHolderRecords = holderBatch.removed.mapNotNull(activeHolderRecords::get)
        val delayedTriggerAffectorReplacements =
            delayedTriggerAffectorReplacements(removedHolderRecords, snap, frameIds)
        val postDiffActiveIids =
            (activeHolderRecords.keys + holderBatch.added.map { it.iid }) -
                holderBatch.removed.toSet()
        transferResult = transferResult.withDelayedTriggerHolders(holderBatch, postDiffActiveIids, editor.effects)
        // Stack contents (cards) plus stack-resident Ability gameObjects — both
        // can own persistent trigger relations.
        val stackIids: Set<Int> = frameIds.stackInstanceIds(snap)
        val resolvingStackIids: Set<Int> =
            (
                transferResult.transfers
                    .filter { it.srcZoneId == ZoneIds.STACK }
                    .map { it.origId } +
                    eventsMutable
                        .filterIsInstance<GameEvent.SpellResolved>()
                        .filter { it.isTrigger || it.isAbility }
                        .map { AnnotationContext.stackAbilityIid(it.abilityForgeId, it.cardId, frameIds) }
            ).toSet()
        val persistentFeeds =
            PersistentFeedBuilder.remapDelayedTriggerAffectees(
                feeds =
                    PersistentFeedBuilder.retainDelayedTriggerAffectees(
                        feeds = persistentFeedResult.feeds,
                        activeAnnotations = persistentState.activeAnnotations.values,
                        holderIids = carriedHolders.map { it.iid }.toSet() + (stackIids - resolvingStackIids),
                    ),
                activeAnnotations = persistentState.activeAnnotations.values,
                affectorReplacements = delayedTriggerAffectorReplacements,
            )
        // Transient gain/lose Designation annotations — diff prev vs cur on the
        // `Source on battlefield with isPrepared` set. Gains insert before the
        // Stack→Battlefield Resolve ZoneTransfer for the same source iid to match
        // the protocol's bracket order (annotation 848 before 849 in the spec).
        // Loses append at the end (cast acceptance has no co-located ZT for the
        // source — the ZT is on the copy moving Exile→Stack). Skipped on full
        // snapshot rebuild (prev == null) — the persistent Designation pAnn alone
        // re-syncs client state on rebuild.
        if (prev != null) {
            val resolvingAbilityIid =
                eventsMutable
                    .filterIsInstance<GameEvent.SpellResolved>()
                    .filter { it.isTrigger || it.isAbility }
                    .map { resolved ->
                        InstanceId(AnnotationContext.stackAbilityIid(resolved.abilityForgeId, resolved.cardId, frameIds))
                    }.singleOrNull()
            insertStateDesignationTransients(
                annotations = annotations,
                prev = prev,
                cur = snap,
                resolveInstanceId = editor.identities::getOrAlloc,
                resolveAffectorId = { spec, _ ->
                    if (spec.kind == DesignationKind.SUSPECTED) resolvingAbilityIid else null
                },
            )
            insertDayNightDesignationTransients(annotations, prev.dayTime, snap.dayTime)
            insertCitysBlessingDesignationTransients(annotations, prev.seats, snap.seats)
        }

        // Stages 4-5 + persistent computation
        val battlefieldIids: Set<Int> = frameIds.battlefieldInstanceIds(snap)
        val controllerOf: Map<Int, SeatId> =
            snap.boundCards.values.associate { bound ->
                editor.identities.getOrAlloc(bound.forgeCardId).value to bound.snapshot.controller
            }
        val frameContext =
            FrameContext(
                phase = snap.phase.phase,
                activePlayerSeat = snap.phase.activePlayer,
                battlefieldIids = battlefieldIids,
                controllerOf = controllerOf,
                stackIids = stackIids,
                resolvingStackIids = resolvingStackIids,
                displayCardAffectors = battlefieldIids + postDiffActiveIids + (stackIids - resolvingStackIids),
                delayedTriggerAffectorReplacements = delayedTriggerAffectorReplacements,
            )
        // Stage-4-5 contributors may need the frame's pre-reallocation identities.
        val annCtx =
            AnnotationContext(
                editor = editor,
                environment = environment,
                snap = snap,
                frameIds = frameIds,
                events = eventsMutable,
                frameEntryStep = prev?.let { GsmFrame.from(it).step },
                promptFacts = promptFacts,
                effectFacts = effectFacts,
                opponentKnowledge = opponentKnowledge,
                transferResult = transferResult,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
            )
        val remaining =
            AnnotationPipeline.computeRemainingAnnotations(
                annCtx,
                annotations,
                transferPersistent,
                initEffectDiff,
                effectDiff,
                persistSnapshot,
                startPersistentId,
                frameContext = frameContext,
                keywordDiff = keywordDiff,
                grantedAbilityDiff = grantedAbilityDiff,
                combatResult = combatResult,
                persistentFeeds = persistentFeeds,
                convokePaymentsBySource = convokePaymentsBySource,
                transferResult = transferResult,
                annotationJournal = annotationJournal,
            )

        transferResult = LinkedFaceCompanionProjector.append(transferResult, snap, editor, environment, frameIds)
        for (seat in snap.seats) {
            val companion = seat.companion ?: continue
            if (companion.available) continue
            val iid = companion.originalInstanceId
            val ghost =
                ObjectMapper.buildFromSnapshot(
                    companion.card.snapshot,
                    iid,
                    ZoneIds.LIMBO,
                    seat.seatId.value,
                    environment.cardProto,
                    Visibility.Public,
                )
            transferResult =
                transferResult.copy(
                    patchedObjects = transferResult.patchedObjects.filter { it.instanceId != iid } + ghost,
                    patchedZones =
                        transferResult.patchedZones.map { zone ->
                            if (zone.zoneId == ZoneIds.LIMBO && iid !in zone.objectInstanceIdsList) {
                                zone.toBuilder().addObjectInstanceIds(iid).build()
                            } else {
                                zone
                            }
                        },
                )
        }

        val activationProjection =
            ObjectMapper.planActivationOccurrences(
                transferResult.patchedObjects,
                snap.objects.values.associateBy { editor.identities.getOrAlloc(it.forgeCardId).value },
            )
        transferResult = transferResult.copy(patchedObjects = activationProjection.objects)

        // ═══ ASSEMBLE: build the GSM proto ═══
        val built =
            assembleGsm(
                gameStateId,
                gameInfo,
                frame,
                transferResult,
                remaining,
                combatResult,
                team1.build(),
                team2.build(),
                player1,
                player2,
                updateType,
                actions,
                actingSeat,
                prev?.gameStateId,
            )

        holderBatch.removed.forEach(editor.delayedTriggerHolders::remove)
        holderBatch.added.forEach { editor.delayedTriggerHolders[it.iid] = it }
        transferResult.lifecycle.applyTo(editor)
        editor.persistentAnnotations =
            editor.persistentAnnotations.copy(
                activeAnnotations = remaining.batch.allAnnotations.associateBy { it.id },
                nextPersistentId = remaining.batch.nextPersistentId,
            )
        editor.transientLinkedFaceFamilyIds =
            transferResult.transientHiddenFamilyIds.mapTo(mutableSetOf(), ::InstanceId)

        // ═══ COLLECT assembly metadata (always) ═══
        val output =
            ProjectionOutput(
                idReallocations = transferResult.idReallocations,
                persistentBatch = remaining.batch,
                promptFactConsumption =
                    PromptFactConsumption(
                        choiceResults = promptFacts.choiceResults.map { it.key },
                        staleReveals = staleReveals.map { it.key },
                        convokePayments = convokePlan.consumedPayments.map { it.key },
                        collectEvidenceCosts =
                            promptFacts.collectEvidenceCosts
                                .filter { fact ->
                                    eventsMutable.any { it is GameEvent.SpellCast && it.cardId == fact.context.sourceForgeCardId }
                                }.map { it.key },
                        targetSpecs = remaining.consumedTargetSpecs.map { it.key },
                    ),
                holderBatch = holderBatch,
                diffDeletedInstanceIds =
                    (stackTransferDeletedIds(transferResult) + resolvedStackAbilityIids)
                        .distinct()
                        .map { InstanceId(it) },
                openingHandAbilityDeletedInstanceIds =
                    transferResult.transfers
                        .map { it.openingHandAbilityInstanceId }
                        .filter { it != 0 }
                        .distinct()
                        .map(::InstanceId),
                consumedEarthbendResolutionVersions = earthbendResolutions.mapTo(mutableSetOf()) { it.version },
                priorPersistentAnnotations = persistSnapshot,
                activationOccurrences = activationProjection.occurrences,
            )

        return Draft(
            gsm = built,
            projectionSnapshot = snap,
            output = output,
            firstAnnotationId = startAnnotationId,
            idResolver = frameIds,
            objectRefreshInstanceIds =
                (
                    snap.objects.values
                        .filter {
                            it.activationOccurrences.isNotEmpty() ||
                                prev
                                    ?.objects
                                    ?.get(it.forgeCardId)
                                    ?.activationOccurrences
                                    ?.isNotEmpty() == true
                        }.map { editor.identities.getOrAlloc(it.forgeCardId).value } +
                        keywordDiff.created.map { it.cardInstanceId } +
                        keywordDiff.destroyed.map { it.cardInstanceId } +
                        grantedAbilityDiff.created.map { it.cardInstanceId } +
                        grantedAbilityDiff.destroyed.map { it.cardInstanceId }
                ).toSet(),
        )
    }

    private fun recordParadigmSourceStackIids(
        transferResult: TransferResult,
        snap: GsmSnapshot,
        annotationJournal: AnnotationProjectionState.Planner,
    ) {
        for (transfer in transferResult.transfers) {
            val forgeCardId = transfer.forgeCardId ?: continue
            if (!StateZoneProjection.isParadigm(snap, forgeCardId)) continue
            val isOriginalCast =
                transfer.category == TransferCategory.CastSpell &&
                    (transfer.srcZoneId == ZoneIds.P1_HAND || transfer.srcZoneId == ZoneIds.P2_HAND) &&
                    transfer.destZoneId == ZoneIds.STACK
            val isStackSelfExile =
                transfer.category == TransferCategory.Exile &&
                    transfer.srcZoneId == ZoneIds.STACK &&
                    transfer.destZoneId == ZoneIds.EXILE
            if (isOriginalCast) {
                annotationJournal.recordParadigmSourceStackIid(forgeCardId, transfer.newId)
            } else if (isStackSelfExile) {
                annotationJournal.recordParadigmSourceStackIidIfAbsent(forgeCardId, transfer.origId)
            }
        }
    }

    private fun projectSharedZone(
        snap: GsmSnapshot,
        arenaZoneId: Int,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        keywordSnapshot: Map<Int, List<EffectTracker.KeywordEntry>> = emptyMap(),
        earthbendProjection: (ForgeCardId) -> EarthbendProjection? = { null },
        grantedAbilitySnapshot: Map<Int, List<EffectTracker.TrackedGrantedAbility>> = emptyMap(),
    ) {
        ZoneMapper.addSharedZoneCardsFromSnapshot(
            snap = snap,
            arenaZoneId = arenaZoneId,
            environment = environment,
            instanceIdLookup = editor.identities::getOrAlloc,
            zones = zones,
            gameObjects = gameObjects,
            keywordSnapshot = keywordSnapshot,
            earthbendProjection = earthbendProjection,
            grantedAbilitySnapshot = grantedAbilitySnapshot,
        )
    }

    private fun addSpeedTriggerHolders(
        snap: GsmSnapshot,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
    ) {
        val activeSeats = snap.seats.filter { it.speed > 0 }.map { it.seatId }
        if (activeSeats.isEmpty()) return
        val limbo = zones.firstOrNull { it.zoneId == ZoneIds.LIMBO } ?: return
        val limboIids = limbo.objectInstanceIdsList.toMutableSet()
        for (seat in activeSeats) {
            val iid = FrameIdResolver.speedTriggerHolderIid(seat)
            limboIids.add(iid.value)
            if (gameObjects.none { it.instanceId == iid.value }) {
                gameObjects += ObjectMapper.buildTriggerHolderObject(iid.value, seat.value)
            }
        }
        zones.removeIf { it.zoneId == ZoneIds.LIMBO }
        zones +=
            limbo
                .toBuilder()
                .clearObjectInstanceIds()
                .addAllObjectInstanceIds(limboIids)
                .build()
    }

    private fun boostEntries(
        facts: EffectProjectionFacts,
        identities: leyline.game.state.InstanceIdRegistry.Planner,
    ): Map<Int, List<EffectTracker.BoostEntry>> {
        val instanceIds = linkedMapOf<ForgeCardId, Int>()
        val entriesByInstance = linkedMapOf<Int, MutableList<EffectTracker.BoostEntry>>()
        for (entry in facts.boostEntries) {
            val instanceId = instanceIds.getOrPut(entry.forgeCardId) { identities.getOrAlloc(entry.forgeCardId).value }
            entriesByInstance
                .getOrPut(instanceId) { mutableListOf() }
                .add(
                    EffectTracker.BoostEntry(
                        timestamp = entry.timestamp,
                        staticId = entry.staticId,
                        power = entry.power,
                        toughness = entry.toughness,
                        sourceAbilityGrpId = entry.sourceAbilityGrpId,
                        sourceForgeCardId = entry.sourceForgeCardId,
                    ),
                )
        }
        return entriesByInstance.mapValues { (_, entries) -> entries.toList() }
    }

    private fun keywordEntries(
        facts: EffectProjectionFacts,
        earthbend: EarthbendTracker,
        identities: leyline.game.state.InstanceIdRegistry.Planner,
    ): Map<Int, List<EffectTracker.KeywordEntry>> {
        val instanceIds = linkedMapOf<ForgeCardId, Int>()
        val entriesByInstance = linkedMapOf<Int, MutableList<EffectTracker.KeywordEntry>>()
        for (entry in facts.keywordEntries) {
            val instanceId = instanceIds.getOrPut(entry.forgeCardId) { identities.getOrAlloc(entry.forgeCardId).value }
            if (
                entry.keyword == "Haste" &&
                earthbend.isEarthbendHasteKeyword(
                    entry.forgeCardId,
                    entry.timestamp,
                    entry.staticId,
                )
            ) {
                continue
            }
            entriesByInstance
                .getOrPut(instanceId) { mutableListOf() }
                .add(
                    EffectTracker.KeywordEntry(
                        timestamp = entry.timestamp,
                        staticId = entry.staticId,
                        keyword = entry.keyword,
                        affectorForgeCardId = entry.affectorForgeCardId,
                        abilityGrpId = entry.abilityGrpId,
                    ),
                )
        }
        return entriesByInstance.mapValues { (_, entries) -> entries.toList() }
    }

    private fun grantedAbilityEntries(
        facts: EffectProjectionFacts,
        identities: leyline.game.state.InstanceIdRegistry.Planner,
    ): Map<Int, List<EffectTracker.GrantedAbilityEntry>> {
        val entriesByInstance = linkedMapOf<Int, MutableList<EffectTracker.GrantedAbilityEntry>>()
        for (entry in facts.grantedAbilityEntries) {
            val instanceId = identities.getOrAlloc(entry.forgeCardId).value
            entriesByInstance
                .getOrPut(instanceId) { mutableListOf() }
                .add(
                    EffectTracker.GrantedAbilityEntry(
                        timestamp = entry.timestamp,
                        staticId = entry.staticId,
                        abilityGrpId = entry.abilityGrpId,
                        uniqueAbilityId = entry.uniqueAbilityId,
                        sourceForgeCardId = entry.sourceForgeCardId,
                    ),
                )
        }
        return entriesByInstance.mapValues { (_, entries) -> entries.toList() }
    }

    private fun stackTransferDeletedIds(transferResult: TransferResult): List<Int> =
        (
            transferResult.transfers
                .filter { it.srcZoneId == ZoneIds.STACK && it.destZoneId != ZoneIds.BATTLEFIELD }
                .filter { transfer ->
                    transfer.origId != transfer.newId ||
                        transferResult.patchedObjects.none {
                            it.instanceId == transfer.newId && it.zoneId == transfer.destZoneId
                        }
                }.map { it.origId }
        )

    /**
     * Build a Diff [GameStateMessage] by snap-vs-snap field comparison.
     *
     * Ordering-sensitive history comes from the compiler-supplied prior state.
     * The result remains private to that tentative compilation.
     *
     * `prev == null` → returns the Full GSM built from `cur` (first bundle, post-handshake).
     * Otherwise emits only zones/objects whose CardSnapshot/ZoneSnapshot field-equality
     * differs between `prev` and `cur`. Player/turn/annotation/persistent-annotation
     * lists are taken from the freshly-built current full GSM (current-bundle events
     * already applied).
     */

    /** Internal mapper stage owned by [StateProjectionCompiler]. */
    internal fun buildDraft(
        input: StateFrameInput,
        environment: StateProjectionEnvironment,
        priorProjection: ProjectionState,
        editor: ProjectionState.Editor,
        actions: ActionsAvailableReq? = null,
    ): Draft =
        buildDiffInternal(
            prev = input.previousSnapshot,
            cur = input.snapshot,
            events = input.events,
            promptFacts = input.promptFacts,
            effectFacts = input.effectFacts,
            mechanicSourceFacts = input.mechanicSourceFacts,
            abilityExhaustionFacts = input.abilityExhaustionFacts,
            persistentFeedFacts = input.persistentFeedFacts,
            gameStateId = input.gameStateId,
            matchId = input.snapshot.matchId,
            environment = environment,
            actions = actions,
            updateType = input.updateType,
            viewingSeatId = input.viewingSeatId,
            revealForSeat = input.revealForSeat,
            editor = editor,
            priorProjection = priorProjection,
        )

    /** Plans causal projection lifecycle once without applying a viewer visibility baseline. */
    internal fun planSharedDraft(
        input: StateFrameInput,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): Draft =
        buildFromSnapshotInternal(
            rawSnap = input.snapshot,
            gameStateId = input.gameStateId,
            matchId = input.snapshot.matchId,
            environment = environment,
            updateType = input.updateType,
            viewingSeatId = 0,
            revealForSeat = input.revealForSeat,
            prev = input.previousSnapshot,
            events = input.events,
            promptFacts = input.promptFacts,
            persistentFeedFacts = input.persistentFeedFacts,
            effectFacts = input.effectFacts,
            mechanicSourceFacts = input.mechanicSourceFacts,
            abilityExhaustionFacts = input.abilityExhaustionFacts,
            editor = editor,
        )

    /** Renders one viewer from an already planned shared lifecycle draft. */
    internal fun renderViewerDraft(
        shared: Draft,
        input: StateFrameInput,
        environment: StateProjectionEnvironment,
        priorProjection: ProjectionState,
        editor: ProjectionState.Editor,
        actions: ActionsAvailableReq? = null,
        includePrivateObjects: Boolean = true,
    ): Draft =
        buildDiffInternal(
            prev = input.previousSnapshot,
            cur = input.snapshot,
            events = input.events,
            promptFacts = input.promptFacts,
            effectFacts = input.effectFacts,
            mechanicSourceFacts = input.mechanicSourceFacts,
            abilityExhaustionFacts = input.abilityExhaustionFacts,
            persistentFeedFacts = input.persistentFeedFacts,
            gameStateId = input.gameStateId,
            matchId = input.snapshot.matchId,
            environment = environment,
            actions = actions,
            updateType = input.updateType,
            viewingSeatId = input.viewingSeatId,
            revealForSeat = input.revealForSeat,
            editor = editor,
            priorProjection = priorProjection,
            sharedDraft = shared,
            includePrivateObjects = includePrivateObjects,
        )

    internal fun renderViewerFullState(
        shared: Draft,
        viewingSeatId: Int,
        actions: ActionsAvailableReq?,
        includePrivateObjects: Boolean,
    ): GameStateMessage = shared.forViewer(viewingSeatId, includePrivateObjects, actions).gsm

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition", "LongParameterList")
    private fun buildDiffInternal(
        prev: GsmSnapshot?,
        cur: GsmSnapshot,
        events: FrameEventLog,
        promptFacts: PromptProjectionFacts,
        effectFacts: EffectProjectionFacts,
        mechanicSourceFacts: MechanicSourceFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
        persistentFeedFacts: PersistentFeedFacts,
        gameStateId: Int,
        matchId: String,
        environment: StateProjectionEnvironment,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        editor: ProjectionState.Editor,
        priorProjection: ProjectionState,
        sharedDraft: Draft? = null,
        includePrivateObjects: Boolean = true,
    ): Draft {
        if (prev == null) {
            // First bundle — Full GSM with one complete transition.
            return sharedDraft?.forViewer(viewingSeatId, includePrivateObjects, actions) ?: buildFromSnapshotInternal(
                rawSnap = cur,
                gameStateId = gameStateId,
                matchId = matchId,
                environment = environment,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                editor = editor,
                prev = null,
                events = events,
                promptFacts = promptFacts,
                persistentFeedFacts = persistentFeedFacts,
                effectFacts = effectFacts,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
            )
        }

        // Library reveal forces a Full GSM. Library cards don't move zones or
        // change CardSnapshot fields when a search reveals them, so the diff
        // filter (snap-vs-snap object delta + zone-moved fids) discards them
        // and the picker can't render face-up. Sending Full re-emits every
        // game object including the library, with per-object visibility set
        // to Private + viewers=[searchingSeat] so only the searcher sees the
        // contents (matches the protocol shape Arena uses for cycling /
        // tutor searches).
        if (revealForSeat != null) {
            return sharedDraft?.forViewer(viewingSeatId, includePrivateObjects, actions) ?: buildFromSnapshotInternal(
                rawSnap = cur,
                gameStateId = gameStateId,
                matchId = matchId,
                environment = environment,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                editor = editor,
                prev = prev,
                events = events,
                promptFacts = promptFacts,
                persistentFeedFacts = persistentFeedFacts,
                effectFacts = effectFacts,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
            )
        }

        // Build current full GSM (viewingSeatId=0 to include all objects for accurate diff).
        val fullResult =
            sharedDraft ?: buildFromSnapshotInternal(
                rawSnap = cur,
                gameStateId = gameStateId,
                matchId = matchId,
                environment = environment,
                revealForSeat = revealForSeat,
                editor = editor,
                prev = prev,
                events = events,
                promptFacts = promptFacts,
                persistentFeedFacts = persistentFeedFacts,
                effectFacts = effectFacts,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
            )
        val current = fullResult.gsm
        val projectedCur = fullResult.projectionSnapshot
        val currentCompanionIds =
            current.gameObjectsList
                .filter { LinkedFaceCompanionProjector.isCompanionType(it.type) }
                .mapTo(mutableSetOf()) { it.instanceId }
        val previousCompanionIds =
            LinkedFaceCompanionProjector.instanceIds(
                prev,
                editor,
                viewingSeatId,
                parentIidLookup = priorProjection.identities.forgeIdToInstanceId::get,
            )
        val previousTransientHiddenFamilyIds = priorProjection.transientLinkedFaceFamilyIds.map { it.value }

        // Snap-vs-snap zone delta: any zone whose snapshot field-equality differs.
        val changedZoneIds =
            projectedCur.zones.keys
                .asSequence()
                .filter { id -> prev.zones[id] != projectedCur.zones[id] }
                .toSet()
        val opponentHandZoneId = ZoneMapper.opponentHandZone(viewingSeatId)
        val opponentSideboardZoneId = ZoneMapper.opponentSideboardZone(viewingSeatId)
        val activeReveal = promptFacts.activeReveal
        val hasActiveReveal = activeReveal != null
        // Protocol-only zones not tracked in GsmSnapshot must always be included when non-empty:
        //   - Limbo (id=30): grows monotonically; always send when it has content.
        //   - REVEALED_P1/P2 (id=18/19): synthesized by applyRevealProxies during active reveal.
        //   - Hand zone of revealed seat: visibility flipped to Public by buildFromSnapshot but
        //     ZoneSnapshot still records Private, so snap equality check misses the change.
        val opponentRevealedHandZoneId: Int? =
            when {
                hasActiveReveal ->
                    activeReveal.reveal.ownerSeatId.value
                        .let { ZoneIds.handOf(it) }
                else -> null
            }
        val hasStackRetirement = fullResult.output.diffDeletedInstanceIds.isNotEmpty()
        // Ability membership is projected from stack entries, outside ZoneSnapshot.contents.
        val hasStackChanges = prev.stack != projectedCur.stack || hasStackRetirement
        val changedZones =
            current.zonesList
                .filter { zone ->
                    zone.zoneId in changedZoneIds ||
                        (zone.zoneId == ZoneIds.STACK && hasStackChanges) ||
                        (
                            zone.zoneId == ZoneIds.LIMBO &&
                                (
                                    zone.objectInstanceIdsCount > 0 ||
                                        fullResult.output.holderBatch.removed
                                            .isNotEmpty()
                                )
                        ) ||
                        (zone.zoneId == ZoneIds.REVEALED_P1 || zone.zoneId == ZoneIds.REVEALED_P2) ||
                        (opponentRevealedHandZoneId != null && zone.zoneId == opponentRevealedHandZoneId)
                }.map { zone ->
                    redactOpponentSideboardZone(zone, opponentSideboardZoneId)
                }

        // Snap-vs-snap object delta: any card whose CardSnapshot field-equality differs.
        // Plus opponent-hand filter + active-reveal exception preserved.
        val cardSnapshotChangedFids =
            projectedCur.objects.keys
                .asSequence()
                .filter { fid -> prev.objects[fid] != projectedCur.objects[fid] }
                .toSet()

        // Cards whose zone changed (CardSnapshot doesn't carry zoneId; ZoneSnapshot.contents does).
        val prevZoneOf: Map<ForgeCardId, Int> =
            prev.zones.values
                .flatMap { z -> z.contents.map { it to z.id } }
                .toMap()
        val curZoneOf: Map<ForgeCardId, Int> =
            projectedCur.zones.values
                .flatMap { z -> z.contents.map { it to z.id } }
                .toMap()
        val zoneMovedFids =
            (prevZoneOf.keys + curZoneOf.keys)
                .asSequence()
                .filter { fid -> prevZoneOf[fid] != curZoneOf[fid] }
                .toSet()

        val prevDisturbBackSourceFids = projectedDisturbBackSourceFids(prev)
        val curDisturbBackSourceFids = projectedDisturbBackSourceFids(projectedCur)
        val changedFids = cardSnapshotChangedFids + zoneMovedFids
        val currentParentIds =
            changedFids.mapTo(mutableSetOf()) { fid ->
                fullResult.idResolver.cardIid(fid).value
            }
        val changedCompanionIds =
            current.gameObjectsList
                .filter { LinkedFaceCompanionProjector.isCompanionType(it.type) && it.parentId in currentParentIds }
                .mapTo(mutableSetOf()) { it.instanceId }
        val changedDisturbBackIds =
            disturbBackInstanceIds(
                changedFids.filter { it in prevDisturbBackSourceFids || it in curDisturbBackSourceFids },
                editor.identities,
            )
        val changedInstanceIds =
            changedFids.map { editor.identities.getOrAlloc(it).value }.toSet() +
                changedDisturbBackIds +
                changedCompanionIds +
                fullResult.objectRefreshInstanceIds
        // instanceIds tracked in the prev snapshot (to detect truly new objects like RevealedCard proxies)
        val prevInstanceIds =
            prev.objects.keys
                .map { editor.identities.getOrAlloc(it).value }
                .toSet() +
                disturbBackInstanceIds(prevDisturbBackSourceFids, editor.identities) +
                previousCompanionIds
        val changedObjects =
            current.gameObjectsList.filter { obj ->
                // Always include new objects absent from prev (e.g. RevealedCard proxies synthesized mid-diff).
                if (obj.instanceId !in prevInstanceIds) {
                    // Opponent hand objects require a current viewer permission or active public reveal.
                    if (opponentHandZoneId != 0 && obj.zoneId == opponentHandZoneId) {
                        return@filter obj.type == GameObjectType.RevealedCard ||
                            (hasActiveReveal && obj.visibility == Visibility.Public) ||
                            (obj.visibility == Visibility.Private && viewingSeatId in obj.viewersList)
                    }
                    if (opponentSideboardZoneId != 0 && obj.zoneId == opponentSideboardZoneId) return@filter false
                    return@filter true
                }
                if (obj.instanceId !in changedInstanceIds) {
                    // During active reveal, always include opponent hand cards (visibility changed outside CardSnapshot)
                    if (hasActiveReveal &&
                        opponentHandZoneId != 0 &&
                        obj.zoneId == opponentHandZoneId &&
                        (
                            obj.type == GameObjectType.RevealedCard ||
                                obj.visibility == Visibility.Public ||
                                (obj.visibility == Visibility.Private && viewingSeatId in obj.viewersList)
                        )
                    ) {
                        return@filter true
                    }
                    return@filter false
                }
                if (opponentHandZoneId != 0 && obj.zoneId == opponentHandZoneId) {
                    if (obj.type == GameObjectType.RevealedCard ||
                        (hasActiveReveal && obj.visibility == Visibility.Public) ||
                        (obj.visibility == Visibility.Private && viewingSeatId in obj.viewersList)
                    ) {
                        // fall through
                    } else {
                        return@filter false
                    }
                }
                if (opponentSideboardZoneId != 0 && obj.zoneId == opponentSideboardZoneId) return@filter false
                true
            }

        // Deleted IDs: in prev.objects but not in cur.objects, minus IDs still tracked
        // in cur zone listings (limbo-retired IDs that still appear in zone contents).
        val currentObjIds = current.gameObjectsList.map { it.instanceId }.toSet()
        val currentZoneTrackedIds = current.zonesList.flatMap { it.objectInstanceIdsList }.toSet()
        val deletedDisturbBackIds =
            disturbBackInstanceIds(prevDisturbBackSourceFids - curDisturbBackSourceFids, editor.identities)
        val deletedCompanionIds = previousCompanionIds - currentCompanionIds
        val deletedIds =
            (
                (prev.objects.keys - projectedCur.objects.keys).map { editor.identities.getOrAlloc(it).value } +
                    deletedDisturbBackIds +
                    deletedCompanionIds
            ).filter { it !in currentObjIds && it !in currentZoneTrackedIds } +
                previousTransientHiddenFamilyIds

        val previousTurnInfo = GsmFrame.Companion.from(prev).turnInfo()
        val includeTurnInfo =
            current.turnInfo != previousTurnInfo ||
                current.annotationsList.any { ann ->
                    AnnotationType.PhaseOrStepModified in ann.typeList ||
                        AnnotationType.ResolutionStart in ann.typeList ||
                        AnnotationType.ResolutionComplete in ann.typeList
                }
        val previousPlayers = listOf(PlayerMapper.buildFromSnapshot(prev, 1), PlayerMapper.buildFromSnapshot(prev, 2))
        val playerPayloadNeeded =
            events.events.any { it is GameEvent.ManaAbilityActivated } ||
                current.annotationsList.any { ann ->
                    AnnotationType.ModifiedLife in ann.typeList || AnnotationType.LossOfGame_af5a in ann.typeList
                }
        val includePlayers =
            current.playersList != previousPlayers || playerPayloadNeeded

        val builder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gameStateId)
                .addAllZones(changedZones.sortedBy { it.zoneId })
                .addAllGameObjects(changedObjects)
                .addAllAnnotations(current.annotationsList)
                // Emit new and in-place-updated persistent annotations. The client
                // accumulates unchanged rows by id and removes them through the
                // deletion list. Baseline is cur's state at frame entry.
                .addAllPersistentAnnotations(
                    current.persistentAnnotationsList.filter { annotation ->
                        fullResult.output.priorPersistentAnnotations[annotation.id] != annotation
                    },
                )
                // Drain THIS frame's deletions directly from the just-computed batch.
                // Use this frame's batch so deletion cannot lag the value transition.
                .addAllDiffDeletedPersistentAnnotationIds(fullResult.output.persistentBatch.deletedIds)
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(updateType)
                .setPrevGameStateId(prev.gameStateId)

        if (includeTurnInfo) builder.setTurnInfo(current.turnInfo)
        if (includePlayers) builder.addAllPlayers(current.playersList)

        // Fold TriggerHolder gameObjects retired this GSM into the delete list.
        // The batch is compute metadata; the transition already contains its next state.
        val holderDeletions = fullResult.output.holderBatch.removed
        val allDeletedIds =
            (
                deletedIds +
                    holderDeletions +
                    fullResult.output.diffDeletedInstanceIds.map { it.value } +
                    fullResult.output.openingHandAbilityDeletedInstanceIds.map { it.value }
            ).distinct()
        if (allDeletedIds.isNotEmpty()) {
            builder.addAllDiffDeletedInstanceIds(allDeletedIds)
        }

        // Embed stripped actions + set pendingMessageCount when AAR follows
        if (actions != null) {
            builder.setPendingMessageCount(1)
            val activeSeat = current.turnInfo.priorityPlayer
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(activeSeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }

        val built = builder.build()
        if (built.gameStateId != 0 && built.gameStateId == built.prevGameStateId) {
            log.error(
                "SELF-REF gsId={} prev.gsId={} param={} caller={}",
                built.gameStateId,
                prev.gameStateId,
                gameStateId,
                Thread.currentThread().stackTrace[2].let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" },
            )
        }
        val draft =
            Draft(
                gsm = built,
                projectionSnapshot = projectedCur,
                output = fullResult.output,
                firstAnnotationId = fullResult.firstAnnotationId,
                idResolver = fullResult.idResolver,
                objectRefreshInstanceIds = fullResult.objectRefreshInstanceIds,
            )
        return draft.forViewer(viewingSeatId, includePrivateObjects, actions)
    }

    private fun Draft.forViewer(
        viewingSeatId: Int,
        includePrivateObjects: Boolean,
        actions: ActionsAvailableReq? = null,
    ): Draft {
        val projectedBuilder =
            if (viewingSeatId == 0 && includePrivateObjects) {
                gsm.toBuilder().clearActions()
            } else {
                val opponentSideboardZoneId = ZoneMapper.opponentSideboardZone(viewingSeatId)
                val visibleObjects =
                    gsm.gameObjectsList
                        .map { obj ->
                            if (obj.visibility == Visibility.Private &&
                                obj.isFacedown &&
                                (!includePrivateObjects || viewingSeatId !in obj.viewersList)
                            ) {
                                val facade =
                                    if (obj.zoneId == ZoneIds.EXILE) {
                                        ZoneMapper
                                            .hiddenCardObject(obj.instanceId, obj.zoneId, SeatId(obj.ownerSeatId))
                                            .toBuilder()
                                            .setControllerSeatId(obj.controllerSeatId)
                                            .addAllViewers(obj.viewersList)
                                    } else {
                                        obj.toBuilder()
                                    }
                                facade
                                    .setVisibility(Visibility.Public)
                                    .clearCurrentTypeLine()
                                    .setGrpId(3)
                                    .setOverlayGrpId(3)
                                    .setIsFacedown(true)
                                    .build()
                            } else {
                                obj
                            }
                        }.filter { obj ->
                            obj.visibility != Visibility.Private || includePrivateObjects && viewingSeatId in obj.viewersList
                        }
                gsm
                    .toBuilder()
                    .clearZones()
                    .addAllZones(
                        gsm.zonesList.map { zone ->
                            if (!includePrivateObjects && zone.visibility == Visibility.Private) {
                                zone.toBuilder().clearObjectInstanceIds().build()
                            } else {
                                if (zone.type == ZoneType.Revealed) {
                                    val hiddenIds =
                                        gsm.gameObjectsList
                                            .filter {
                                                it.visibility == Visibility.Private &&
                                                    (!includePrivateObjects || viewingSeatId !in it.viewersList)
                                            }.mapTo(mutableSetOf()) { it.instanceId }
                                    zone
                                        .toBuilder()
                                        .clearObjectInstanceIds()
                                        .addAllObjectInstanceIds(zone.objectInstanceIdsList.filterNot { it in hiddenIds })
                                        .build()
                                } else {
                                    redactOpponentSideboardZone(zone, opponentSideboardZoneId)
                                }
                            }
                        },
                    ).clearGameObjects()
                    .addAllGameObjects(visibleObjects)
                    .clearActions()
            }
        if (actions != null) {
            val actionSeat = viewingSeatId.takeIf { it != 0 } ?: gsm.turnInfo.priorityPlayer
            actions.actionsList.forEach { action ->
                projectedBuilder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(actionSeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }
        return copy(gsm = projectedBuilder.build())
    }

    private fun redactOpponentSideboardZone(
        zone: ZoneInfo,
        opponentSideboardZoneId: Int,
    ): ZoneInfo {
        if (opponentSideboardZoneId == 0 || zone.zoneId != opponentSideboardZoneId) return zone
        return zone.toBuilder().clearObjectInstanceIds().build()
    }

    /**
     * Resolve the correct updateType for a game state message.
     * - SendAndRecord: state change the client must persist (zone transfers, actions)
     * - SendHiFi: transient update (phase echoes, state refreshes)
     *
     * Note: protocol uses SendAndRecord for ALL zone-transfer diffs, regardless
     * of whose turn it is. This heuristic (acting == viewing) is an approximation
     * used by postAction. Playback frames start as SendHiFi; BundleBuilder promotes
     * persistent type changes to SendAndRecord so clients refresh battlefield layout.
     */
    fun resolveUpdateType(
        snap: GsmSnapshot,
        viewingSeatId: Int,
    ): GameStateUpdate {
        val actingSeat = snap.phase.priorityPlayer?.value ?: snap.phase.activePlayer.value
        return if (actingSeat == viewingSeatId) {
            GameStateUpdate.SendAndRecord
        } else {
            GameStateUpdate.SendHiFi
        }
    }

    /** Assemble the final GameStateMessage proto from computed components. */
    @Suppress("LongParameterList")
    private fun assembleGsm(
        gameStateId: Int,
        gameInfo: GameInfo,
        frame: GsmFrame,
        transferResult: TransferResult,
        remaining: AnnotationPipeline.RemainingAnnotationsResult,
        combatResult: CombatAnnotationResult,
        team1: TeamInfo,
        team2: TeamInfo,
        player1: PlayerInfo,
        player2: PlayerInfo,
        updateType: GameStateUpdate,
        actions: ActionsAvailableReq?,
        prioritySeat: Int,
        prevGsId: Int?,
    ): GameStateMessage {
        val effectiveTurnInfo =
            if (combatResult.hasCombatDamage) {
                frame
                    .turnInfo()
                    .toBuilder()
                    .setPhase(Phase.Combat_a549)
                    .setStep(combatResult.damageStep)
            } else {
                frame.turnInfo().toBuilder()
            }

        val builder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(gameStateId)
                .setGameInfo(gameInfo)
                .addAllTeams(listOf(team1, team2))
                .setTurnInfo(effectiveTurnInfo)
                .addAllPlayers(listOf(player1, player2))
                .addAllZones(transferResult.patchedZones.sortedBy { it.zoneId }.map(ZoneMapper::clientOrderedZone))
                .addAllGameObjects(transferResult.patchedObjects)
                .addAllAnnotations(remaining.transient)
                .addAllPersistentAnnotations(remaining.persistent)
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(updateType)
        if (prevGsId != null && prevGsId > 0) {
            builder.setPrevGameStateId(prevGsId)
        }

        if (actions != null) {
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(prioritySeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }
        return builder.build()
    }

    private fun projectedDisturbBackSourceFids(snap: GsmSnapshot): Set<ForgeCardId> {
        val playerZoneFids =
            disturbBackPlayerZoneIds
                .asSequence()
                .flatMap { zoneId ->
                    snap.zones[zoneId]
                        ?.contents
                        .orEmpty()
                        .asSequence()
                }

        return playerZoneFids
            .filter { fid ->
                val cardSnap = snap.objects[fid] ?: return@filter false
                cardSnap.othersideGrpId != 0 &&
                    snap.boundCards[fid]?.altCost(KeywordAbilityIds.DISTURB) != null
            }.toSet()
    }

    private fun disturbBackInstanceIds(
        sourceFids: Iterable<ForgeCardId>,
        identities: leyline.game.state.InstanceIdRegistry.Planner,
    ): Set<Int> =
        sourceFids
            .mapTo(mutableSetOf()) { fid ->
                identities.getOrAlloc(FrameIdResolver.disturbBackForgeId(fid)).value
            }

    private fun TransferResult.withDecayedCleanupAffectors(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        references: ProjectionCardReferences,
        frameIds: FrameIdResolver,
    ): TransferResult {
        val cleanupAbilityIids =
            events
                .filterIsInstance<GameEvent.SpellResolved>()
                .filter { it.isTrigger && it.abilityGrpId != 0 }
                .mapNotNull { ev ->
                    val cleanupGrpId =
                        PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, references, this)
                            ?: return@mapNotNull null
                    if (ev.abilityGrpId != cleanupGrpId) return@mapNotNull null
                    ev.cardId to AnnotationContext.stackAbilityIid(ev.abilityForgeId, ev.cardId, frameIds)
                }.toMap()
        if (cleanupAbilityIids.isEmpty()) return this
        val patchedTransfers =
            transfers.map { transfer ->
                val affector = transfer.forgeCardId?.let { cleanupAbilityIids[it] } ?: return@map transfer
                if (transfer.affectorId != 0) return@map transfer
                val category =
                    if (transfer.srcZoneId == ZoneIds.BATTLEFIELD && transfer.destZoneId in graveyardZoneIds) {
                        TransferCategory.Sacrifice
                    } else {
                        transfer.category
                    }
                transfer.copy(category = category, affectorId = affector)
            }
        return copy(transfers = patchedTransfers)
    }

    /**
     * Resolution events are authoritative when Forge's stack snapshot still
     * carries the resolved ability for the current frame. Remove those stale
     * projections before diffing; [ProjectionOutput.diffDeletedInstanceIds]
     * retires their client identity without placing abilities in Limbo.
     */
    private fun TransferResult.withoutStackAbilities(resolvedIids: Set<Int>): TransferResult {
        if (resolvedIids.isEmpty()) return this
        val updatedObjects = patchedObjects.filterNot { it.instanceId in resolvedIids }
        val updatedZones =
            patchedZones.map { zone ->
                if (zone.zoneId != ZoneIds.STACK && zone.zoneId != ZoneIds.LIMBO) {
                    zone
                } else {
                    zone
                        .toBuilder()
                        .clearObjectInstanceIds()
                        .addAllObjectInstanceIds(zone.objectInstanceIdsList.filterNot { it in resolvedIids })
                        .build()
                }
            }
        return copy(
            patchedObjects = updatedObjects,
            patchedZones = updatedZones,
            lifecycle = lifecycle.copy(retiredIds = lifecycle.retiredIds.filterNot { it in resolvedIids }),
        )
    }

    private fun TransferResult.withDelayedTriggerHolders(
        holderBatch: HolderBatch,
        postDiffActiveIids: Set<Int>,
        effects: SyntheticEffectProjection.Planner,
    ): TransferResult {
        if (holderBatch.added.isEmpty() && holderBatch.removed.isEmpty() && postDiffActiveIids.isEmpty()) return this

        val patchedZones = this.patchedZones.toMutableList()
        val patchedObjects = this.patchedObjects.toMutableList()
        val existingLimbo = patchedZones.find { it.zoneId == ZoneIds.LIMBO }
        val limboBuilder =
            existingLimbo?.toBuilder() ?: ZoneInfo.newBuilder().setZoneId(ZoneIds.LIMBO).setType(ZoneType.Limbo)
        if (existingLimbo != null) patchedZones.removeIf { it.zoneId == ZoneIds.LIMBO }

        val limboIids = limboBuilder.objectInstanceIdsList.toMutableSet()
        limboIids.removeAll(holderBatch.removed.toSet())
        limboIids.addAll(postDiffActiveIids)
        limboBuilder.clearObjectInstanceIds()
        for (iid in limboIids) limboBuilder.addObjectInstanceIds(iid)

        for (holder in holderBatch.added) {
            patchedObjects.add(
                ObjectMapper.buildTriggerHolderObject(
                    instanceId = holder.iid,
                    ownerSeatId = holder.ownerSeat,
                    objectSourceGrpId = holder.objectSourceGrpId,
                    parentInstanceId = holder.parentIid,
                    uniqueAbilityGrpId = holder.cleanupGrpId,
                    uniqueAbilityId = effects.effects.nextEffectId(),
                ),
            )
        }
        patchedZones.add(limboBuilder.build())
        return copy(patchedZones = patchedZones, patchedObjects = patchedObjects)
    }

    private val graveyardZoneIds = setOf(ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD)

    private fun updateDecayedCleanupSources(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        references: ProjectionCardReferences,
        transferResult: TransferResult,
        annotationJournal: AnnotationProjectionState.Planner,
    ): Set<ForgeCardId> {
        val visibleThisGsm = annotationJournal.activeDecayedCleanupSources().toMutableSet()
        val addedThisGsm = linkedSetOf<ForgeCardId>()
        for (ev in events) {
            if (ev is GameEvent.SpellResolved) {
                val cleanupGrpId =
                    PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, references, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: (snap.boundCards[ev.cardId]?.snapshot?.grpId ?: 0)
                if (ev.isTrigger && cleanupGrpId != null && abilityGrpId == KeywordAbilityIds.DECAYED) {
                    annotationJournal.recordDecayedCleanupSource(ev.cardId)
                    visibleThisGsm.add(ev.cardId)
                    addedThisGsm.add(ev.cardId)
                }
            } else if (ev is GameEvent.SpellCast) {
                val cleanupGrpId =
                    PersistentFeedBuilder.decayedCleanupGrpIdForSource(ev.cardId, snap, references, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: (snap.boundCards[ev.cardId]?.snapshot?.grpId ?: 0)
                if (ev.isTrigger && cleanupGrpId != null && abilityGrpId == cleanupGrpId) {
                    annotationJournal.clearDecayedCleanupSource(ev.cardId)
                    if (ev.cardId !in addedThisGsm) visibleThisGsm.remove(ev.cardId)
                }
            } else if (ev is GameEvent.CardSacrificed) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
            } else if (ev is GameEvent.CardDestroyed) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
            } else if (ev is GameEvent.CardBounced) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
            } else if (ev is GameEvent.CardExiled) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
            } else if (ev is GameEvent.ZoneChanged) {
                if (ev.from == leyline.game.event.Zone.Battlefield && ev.to != leyline.game.event.Zone.Battlefield) {
                    clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, annotationJournal)
                }
            }
        }
        return visibleThisGsm
    }

    private fun clearDecayedCleanupSource(
        sourceForgeId: ForgeCardId,
        addedThisGsm: Set<ForgeCardId>,
        visibleThisGsm: MutableSet<ForgeCardId>,
        annotationJournal: AnnotationProjectionState.Planner,
    ) {
        annotationJournal.clearDecayedCleanupSource(sourceForgeId)
        if (sourceForgeId !in addedThisGsm) visibleThisGsm.remove(sourceForgeId)
    }

    private fun delayedTriggerAffectorReplacements(
        removedHolders: List<HolderRecord>,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): Map<Int, Int> {
        val available =
            snap.stack.entries
                .filterNot { it.isSpell }
                .toMutableList()
        return buildMap {
            for (holder in removedHolders) {
                val matchIndex =
                    available.indexOfFirst { entry ->
                        if (holder.runtimeTriggerId != null) {
                            entry.runtimeTriggerId == holder.runtimeTriggerId
                        } else {
                            holder.sourceForgeCardId != null &&
                                entry.forgeCardId == holder.sourceForgeCardId &&
                                entry.grpId == holder.cleanupGrpId
                        }
                    }
                if (matchIndex < 0) continue
                val entry = available.removeAt(matchIndex)
                val abilityIid =
                    if (entry.forgeAbilityId != 0) {
                        frameIds.triggerStackAbilityIid(entry.forgeAbilityId)
                    } else {
                        frameIds.stackAbilityIid(entry.forgeCardId)
                    }
                put(holder.iid, abilityIid.value)
            }
        }
    }

    private fun delayedTriggerHoldersAwaitingLiveAbility(
        activeHolders: List<HolderRecord>,
        currentHolders: List<HolderRecord>,
        activeAnnotations: Collection<AnnotationInfo>,
        snap: GsmSnapshot,
        identities: leyline.game.state.InstanceIdRegistry.Planner,
    ): List<HolderRecord> {
        val currentIids = currentHolders.map { it.iid }.toSet()
        val battlefieldCards =
            snap.zones[ZoneIds.BATTLEFIELD]
                ?.contents
                .orEmpty()
                .toSet()
        return activeHolders.filter { holder ->
            if (holder.iid in currentIids || holder.runtimeTriggerId == null) return@filter false
            val liveAbilityPresent = snap.stack.entries.any { it.runtimeTriggerId == holder.runtimeTriggerId }
            if (liveAbilityPresent) return@filter false
            activeAnnotations
                .firstOrNull {
                    DelayedTriggerAffecteesKind.matches(it) && it.affectorId == holder.iid
                }?.affectedIdsList
                ?.mapNotNull { iid -> identities.getForgeCardId(InstanceId(iid)) }
                ?.any { it !in battlefieldCards } == true
        }
    }

    /** Classify stale reveal state without touching the prompt journal. */
    private fun detectActiveReveal(
        promptFacts: PromptProjectionFacts,
        revealProxiesEmpty: Boolean,
    ): Pair<RevealStarted?, List<PromptProjectionFacts.RevealFact>> {
        val reveal = promptFacts.activeReveal ?: return null to emptyList()
        return if (!revealProxiesEmpty && !reveal.hasPendingPrompt) {
            null to listOf(reveal)
        } else {
            reveal.reveal to emptyList()
        }
    }

    /** Keep known companion hand views and prompt-bound reveal views visible to the opponent. */
    // Nullable `activeReveal` is intentional: the function has two branches —
    // synthesize proxies when non-null, cleanup-and-clear when null.
    @Suppress("CanBeNonNullable")
    private fun privateLookCards(
        activeReveal: RevealStarted?,
        events: List<GameEvent.CardsRevealed>,
    ): Set<ForgeCardId> {
        val looked = events.filter { it.lookOnly }.flatMap { it.cardIds }.toMutableSet()
        if (activeReveal?.lookOnly == true) looked.addAll(activeReveal.allHandCardIds)
        return looked - events.filterNot { it.lookOnly }.flatMap { it.cardIds }.toSet()
    }

    private fun applyRevealProxies(
        activeReveal: RevealStarted?,
        snap: GsmSnapshot,
        editor: ProjectionState.Editor,
        environment: StateProjectionEnvironment,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        events: MutableList<GameEvent>,
    ) {
        val eventReveals =
            events.filterIsInstance<GameEvent.CardsRevealed>().filter { it.viewerSeatId != it.ownerSeatId }
        val privateLookCards =
            privateLookCards(activeReveal, eventReveals) - snap.seats.mapNotNull { it.companion?.card?.forgeCardId }.toSet()
        val revealFacts =
            buildList {
                snap.seats.forEach { seat ->
                    val companion = seat.companion ?: return@forEach
                    val handZone = ZoneIds.handOf(seat.seatId)
                    if (companion.card.forgeCardId in snap.zones[handZone]?.contents.orEmpty()) {
                        add(Triple(companion.card.forgeCardId, seat.seatId.value, handZone))
                    }
                }
                eventReveals.forEach { reveal ->
                    reveal.cardIds.forEach {
                        add(
                            Triple(
                                it,
                                reveal.ownerSeatId.value,
                                reveal.sourceZone?.let { zone ->
                                    ZoneIds.revealZone(zone, reveal.ownerSeatId)
                                },
                            ),
                        )
                    }
                }
                events.filterIsInstance<GameEvent.CardBounced>().forEach { returned ->
                    val card = snap.boundCards[returned.cardId]?.snapshot ?: return@forEach
                    val handZone = ZoneIds.handOf(card.owner)
                    if (returned.cardId in
                        snap.zones[handZone]?.contents.orEmpty()
                    ) {
                        add(Triple(returned.cardId, card.owner.value, handZone))
                    }
                }
                activeReveal?.allHandCardIds?.forEach { cardId ->
                    add(Triple(cardId, activeReveal.ownerSeatId.value, ZoneIds.handOf(activeReveal.ownerSeatId)))
                }
            }.distinctBy { it.first }

        if (revealFacts.isNotEmpty()) {
            val retiredViews = editor.revealProxies.retain(revealFacts.mapTo(mutableSetOf()) { it.first })
            if (retiredViews.isNotEmpty()) events.add(GameEvent.RevealProxiesDeleted(retiredViews))
            for ((ownerSeat, ownerFacts) in revealFacts.groupBy { it.second }) {
                val viewerSeat = SeatId(ownerSeat).opponent.value
                val revealedZoneId = ZoneIds.revealedOf(ownerSeat)
                val revealedZoneIdx = zones.indexOfFirst { it.zoneId == revealedZoneId }
                val revealedZoneBuilder =
                    if (revealedZoneIdx >= 0) {
                        zones.removeAt(revealedZoneIdx).toBuilder()
                    } else {
                        ZoneMapper.makeZone(revealedZoneId, ZoneType.Revealed, ownerSeat, Visibility.Public).toBuilder()
                    }

                for ((forgeCardId, _, sourceZoneId) in ownerFacts) {
                    val cardSnap = snap.objects[forgeCardId] ?: continue
                    val proxyId =
                        editor.revealProxies.lookup(forgeCardId) ?: run {
                            val id = editor.identities.reserve()
                            editor.revealProxies.allocate(forgeCardId, id)
                            id
                        }
                    revealedZoneBuilder.addObjectInstanceIds(proxyId.value)
                    gameObjects.add(
                        ObjectMapper.buildRevealedCardProxy(
                            cardSnap,
                            proxyId.value,
                            sourceZoneId
                                ?: snap.zones.values
                                    .firstOrNull { forgeCardId in it.contents }
                                    ?.id
                                ?: ZoneIds.handOf(ownerSeat),
                            ownerSeat,
                            viewerSeat,
                            environment.cardProto,
                            parentLinkage = snap.boundCards[forgeCardId]?.parentLinkage,
                            lookOnly = forgeCardId in privateLookCards,
                        ),
                    )
                }
                zones.add(revealedZoneBuilder.build())
            }
        } else if (!editor.revealProxies.isEmpty()) {
            // Reveal ended — emit cleanup annotations and clear tracking.
            // Diff naturally detects missing view objects via snapshot-compare.
            val deletedProxies = editor.revealProxies.drain()
            events.add(GameEvent.RevealProxiesDeleted(deletedProxies))
        }
    }
}
