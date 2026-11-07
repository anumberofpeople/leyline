package leyline.game.snapshot

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import leyline.bridge.CompanionAction
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.bridge.types.WubrgColorMapping
import leyline.config.KeywordProjection
import leyline.game.annotations.AbilityWordScanner
import leyline.game.annotations.CastAbilityWordScanner
import leyline.game.codes.KeywordGrpIds
import leyline.game.data.BasicLandAbilities
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.grantedKeywordAbilityGrpId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.ZoneIds
import leyline.game.state.AbilityRegistry
import leyline.game.state.GameBridge
import org.jetbrains.annotations.VisibleForTesting
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Produces a [GsmSnapshot] by reading [Game] + [GameBridge].
 *
 * The snapshot is the stable mapper input: seats, zones, objects, bound card
 * data, phase, stack, ability-word entries, and persistent annotation state are
 * captured once so downstream protocol mappers do not keep re-reading live
 * Forge state.
 */
object SnapshotCapture {
    private val log = org.slf4j.LoggerFactory.getLogger(SnapshotCapture::class.java)

    fun run(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        gameStateId: Int,
    ): GsmSnapshot {
        val seats =
            listOf(1, 2).mapNotNull { seatNum ->
                val player = bridge.getPlayer(SeatId(seatNum)) ?: return@mapNotNull null
                SeatSnapshot(
                    seatId = SeatId(seatNum),
                    life = player.life,
                    startingLife = player.startingLife,
                    maxHandSize = player.maxHandSize,
                    speed = player.speed,
                    dungeon = DungeonSnapshot.capture(player, bridge),
                    hasBlessing = player.hasBlessing(),
                    manaPool = ManaSnapshotCapture.capturePool(player, bridge),
                    companion =
                        CompanionAction.chosenCard(player)?.let { source ->
                            val fid = ForgeCardId(source.id)
                            bridge.getOrAllocInstanceId(fid)
                            CompanionSnapshot(
                                card = captureBoundCard(source, game, bridge),
                                originalInstanceId =
                                    bridge
                                        .projectionIdentities()
                                        .instanceIdToForgeId
                                        .filterValues { it == fid }
                                        .keys
                                        .minOf { it.value },
                                available =
                                    player
                                        .getCardsIn(
                                            ForgeZoneType.Command,
                                        ).any { it.id == source.id && isSnapshotVisibleCard(it) },
                            )
                        },
                )
            }
        val zones = captureZones(game, bridge)
        val objects = captureObjects(game, bridge, zones)
        val boundCards = bindCards(objects, bridge)
        val phase = capturePhase(game, bridge)
        val stack = captureStack(game, bridge)
        val abilityWordEntries = computeAbilityWordEntries(game, bridge)
        val pendingTriggers = PendingTriggerCapture.run(game, bridge)
        // Day/Night state. `Game.getDayTime()` is null=neither, false=Day, true=Night.
        // APSC reads from `playerTurn` (turn-owner) since the spell-count tally is
        // owned by that player; priority can shift mid-turn but the tally doesn't.
        val dayTime: Boolean? = game.dayTime
        val activePlayerSpellsCastThisTurn: Int = game.phaseHandler.playerTurn?.spellsCastThisTurn ?: 0
        return GsmSnapshot.forTest(
            matchId = matchId,
            gameStateId = gameStateId,
            seats = seats,
            zones = zones,
            boundCards = boundCards,
            phase = phase,
            stack = stack,
            abilityWordEntries = abilityWordEntries,
            pendingTriggers = pendingTriggers,
            capturedAt =
                CaptureMarker(
                    gsIdBeforeCapture = -1,
                    wallClockMs = System.currentTimeMillis(),
                ),
            dayTime = dayTime,
            activePlayerSpellsCastThisTurn = activePlayerSpellsCastThisTurn,
        )
    }

    /** Capture one transient card that is not yet reachable from a Forge zone. */
    internal fun captureBoundCard(
        card: Card,
        game: Game,
        bridge: GameBridge,
    ): BoundCard {
        val forgeId = ForgeCardId(card.id)
        val snapshot = captureCard(card, game.phaseHandler?.combat, bridge, PreparedLinkage.from(game))
        return bindCards(mapOf(forgeId to snapshot), bridge).getValue(forgeId)
    }

    /**
     * Pair every [CardSnapshot] with its static [leyline.game.data.CardData]
     * plus pre-resolved consumer queries (alt-cost rows, Mobilize/Decayed cleanup,
     * parent linkage, designations) so mappers don't re-call
     * `bridge.cardRepository.*` per consumer site.
     *
     * `data == null` for cards whose [CardSnapshot.grpId] has no DB row
     * (`EFFECT` pieces with grpId=0; tokens or unknown identities that the
     * resolver couldn't bind). [BoundCard.altCosts] is empty for those.
     */
    private fun bindCards(
        objects: Map<ForgeCardId, CardSnapshot>,
        bridge: GameBridge,
    ): Map<ForgeCardId, BoundCard> {
        val repo = bridge.cardRepository
        val out = linkedMapOf<ForgeCardId, BoundCard>()
        for ((fid, snap) in objects) {
            val data = if (snap.grpId > 0) repo.findByGrpId(snap.grpId) else null
            val altCosts = BoundCard.bindAltCosts(data, repo)
            val mobilizeCleanup = BoundCard.bindMobilizeCleanup(data, altCosts, repo)
            val decayedCleanup = BoundCard.bindDecayedCleanup(data, repo)
            val parentLinkage = bindParentLinkage(snap)
            val linkedFaces = BoundCard.bindLinkedFaces(data, repo)
            val designations =
                DesignationSet(
                    prepared = snap.preparedRole,
                    plotted = snap.plottedRole,
                    isSaddled = snap.isSaddled,
                    isSuspected = snap.isSuspected,
                    isSolved = snap.isSolved,
                    foretold = snap.isForetold,
                    isCommander = snap.isCommander,
                    commanderTax = snap.commanderTax,
                    commanderColorIdentity = snap.commanderColorIdentity,
                    isLeftDoorUnlocked = snap.isLeftDoorUnlocked,
                    isRightDoorUnlocked = snap.isRightDoorUnlocked,
                )
            out[fid] = BoundCard(fid, snap, data, altCosts, mobilizeCleanup, decayedCleanup, parentLinkage, designations, linkedFaces)
        }
        return out
    }

    /**
     * Collapse the pair of parent-link instanceIds on [snap] into a single
     * [ParentLinkage] case. Returns null when the card has neither
     * attachment nor prepared-copy linkage. Prepared-copy wins when both
     * are populated — the protocol semantic for "prepared exile copy that's
     * also attached" is unspecified either way; preferring the prepared
     * linkage keeps the cast-from-exile shape intact for the client.
     */
    private fun bindParentLinkage(snap: CardSnapshot): ParentLinkage? {
        if (snap.preparedRole is PreparedRole.Copy) {
            snap.preparedCopySourceInstanceId?.let { return ParentLinkage.PreparedCopy(it) }
        }
        snap.attachedToInstanceId?.let { return ParentLinkage.AttachedTo(it) }
        return null
    }

    /**
     * Resolve the other face's grpId for DFC cards. Returns 0 for non-DFC.
     *
     * Scope: **transform DFCs + meld pairs only** — Forge's `Card.isDoubleFaced`
     * predicate is `isTransformable() || isMeldable()`. MDFC, Adventure, Split,
     * Flip, Saga, Battle, and Room cards do NOT enter this branch; their grpId
     * resolution goes through [GrpIdResolver]'s primary/any-face fallback chain.
     *
     * Back-face cards (Luminous Phantom, Waildrifter, etc.) have `IsPrimaryCard=0`
     * in the Arena DB, so [findGrpIdByName]'s primary-only filter misses them.
     * Fall back to [findGrpIdByNameAnyFace] which lifts that filter.
     *
     * Visible for tests that pin the back-face fallback at the projection
     * boundary. Production callers go through [bindCards].
     */
    @VisibleForTesting
    internal fun resolveOthersideGrpId(
        card: Card,
        cards: CardRepository,
    ): Int {
        if (!card.isDoubleFaced) return 0
        val otherStateName =
            if (card.currentState.stateName == forge.card.CardStateName.Backside) {
                forge.card.CardStateName.Original
            } else {
                forge.card.CardStateName.Backside
            }
        val otherState = card.getState(otherStateName) ?: return 0
        return cards.findGrpIdByName(otherState.name)
            ?: cards.findGrpIdByNameAnyFace(otherState.name)
            ?: 0
    }

    // --- Phase And Stack Capture ---

    /**
     * Snapshot turn/phase/priority state from [game.phaseHandler].
     * [PhaseType] is a Forge enum value; safe to hold as immutable data.
     */
    private fun capturePhase(
        game: Game,
        bridge: GameBridge,
    ): PhaseSnapshot {
        val handler = game.phaseHandler
        return PhaseSnapshot(
            turn = handler.turn.coerceAtLeast(1),
            activePlayer = bridge.seatOf(handler.playerTurn) ?: SeatId(1),
            priorityPlayer = handler.priorityPlayer?.let { bridge.seatOf(it) ?: SeatId(1) },
            phase = handler.phase,
        )
    }

    /**
     * Snapshot stack entries from [game.getStack()].
     *
     * Each entry captures the source card ID, owner/controller seats, and a pre-resolved
     * grpId so that [leyline.game.mapping.ZoneMapper.addStackAbilitiesFromSnapshot] never
     * needs a live Forge reference.
     */
    private fun captureStack(
        game: Game,
        bridge: GameBridge,
    ): StackSnapshot {
        val stack = game.getStack()
        if (stack.isEmpty) return StackSnapshot(emptyList())
        val entries = mutableListOf<StackEntry>()
        for (entry in stack) {
            val sourceCard = entry.sourceCard ?: continue
            val fid = ForgeCardId(sourceCard.id)
            val controller = entry.activatingPlayer
            val ownerSeat = bridge.seatOf(sourceCard.owner) ?: SeatId(1)
            val controllerSeat = bridge.seatOf(controller) ?: ownerSeat
            val sourceCardGrpId = resolveStackSourceCardGrpId(sourceCard, bridge)
            val runtimeTriggerId = entry.spellAbility.trigger?.id ?: 0
            val grpId =
                bridge.pendingTriggerCleanupAbilityGrpId(runtimeTriggerId)
                    ?: StackAbilityGrpIdResolver.resolveEntryAbilityGrpId(entry, sourceCard, sourceCardGrpId, bridge)
            val targets = entry.targetChoices?.targetCards?.map { ForgeCardId(it.id) } ?: emptyList()
            entries.add(
                StackEntry(
                    forgeCardId = fid,
                    controller = controllerSeat,
                    owner = ownerSeat,
                    grpId = grpId,
                    sourceCardGrpId = sourceCardGrpId,
                    isSpell = entry.isSpell,
                    isActivatedAbility = entry.spellAbility.isActivatedAbility,
                    targets = targets,
                    forgeAbilityId = entry.spellAbility?.id ?: 0,
                    runtimeTriggerId = runtimeTriggerId,
                    effectSourceForgeCardId = sourceCard.effectSource?.let { ForgeCardId(it.id) },
                ),
            )
        }
        val resolvingAbilityId = stack.takeIf { it.isResolving }?.peekAbility()?.id
        return StackSnapshot(entries, entries.firstOrNull { it.forgeAbilityId == resolvingAbilityId })
    }

    private fun resolveStackSourceCardGrpId(
        sourceCard: Card,
        bridge: GameBridge,
    ): Int =
        bridge.resolveGrpId(sourceCard).takeIf { it != 0 }
            ?: sourceCard.effectSource?.let(bridge::resolveGrpId)?.takeIf { it != 0 }
            ?: 0

    private fun captureZones(
        game: Game,
        bridge: GameBridge,
    ): Map<Int, ZoneSnapshot> {
        val result = linkedMapOf<Int, ZoneSnapshot>()
        for (seatNum in listOf(1, 2)) {
            val player = bridge.getPlayer(SeatId(seatNum)) ?: continue
            capturePlayerZone(player, seatNum, ForgeZoneType.Hand, result)
            capturePlayerZone(player, seatNum, ForgeZoneType.Library, result)
            capturePlayerZone(player, seatNum, ForgeZoneType.Graveyard, result)
            capturePlayerZone(player, seatNum, ForgeZoneType.Sideboard, result)
        }
        captureSharedZone(game, ForgeZoneType.Battlefield, result)
        captureSharedZone(game, ForgeZoneType.Stack, result)
        result[ZoneIds.SUPPRESSED] = MutateSnapshotSupport.captureMergedZone(game, bridge)
        captureSharedZone(game, ForgeZoneType.Exile, result)
        captureSharedZone(game, ForgeZoneType.Command, result)
        for (player in game.players) {
            val companion = CompanionAction.chosenCard(player) ?: continue
            val fid = ForgeCardId(companion.id)
            if (fid !in result[ZoneIds.COMMAND]?.contents.orEmpty()) continue
            val sideboard = ZoneIds.sideboardOf(bridge.seatOf(player)!!.value)
            result[sideboard] = result.getValue(sideboard).let { it.copy(contents = it.contents + fid) }
            result[ZoneIds.COMMAND] = result.getValue(ZoneIds.COMMAND).let { it.copy(contents = it.contents - fid) }
        }
        return result
    }

    private fun capturePlayerZone(
        player: forge.game.player.Player,
        seatNum: Int,
        fz: ForgeZoneType,
        out: MutableMap<Int, ZoneSnapshot>,
    ) {
        val zone = player.getZone(fz) ?: return
        val arenaZoneId = playerZoneId(seatNum, fz) ?: return
        val arenaType = arenaTypeFor(fz)
        val visibility = visibilityFor(fz)
        out[arenaZoneId] =
            ZoneSnapshot(
                id = arenaZoneId,
                type = arenaType,
                owner = SeatId(seatNum),
                visibility = visibility,
                contents = zone.cards.filter(::isSnapshotVisibleCard).map { ForgeCardId(it.id) },
            )
    }

    private fun captureSharedZone(
        game: Game,
        fz: ForgeZoneType,
        out: MutableMap<Int, ZoneSnapshot>,
    ) {
        val arenaZoneId = sharedZoneId(fz) ?: return
        val arenaType = arenaTypeFor(fz)
        out[arenaZoneId] =
            ZoneSnapshot(
                id = arenaZoneId,
                type = arenaType,
                owner = null,
                visibility = Visibility.Public,
                contents = game.getCardsIncludePhasingIn(fz).filter(::isSnapshotVisibleCard).map { ForgeCardId(it.id) },
            )
    }

    /** Forge effect helpers model delayed/resolution machinery, not client-visible cards. */
    internal fun isSnapshotVisibleCard(card: Card): Boolean = card.isEmblem || !card.isImmutable() || card.getEffectSource() == null

    // --- Object Capture ---

    /**
     * Build [CardSnapshot] for every card referenced by any captured zone.
     *
     * Cards appearing in multiple zones (shouldn't happen in practice but safe to handle)
     * are captured once — first zone wins.
     */
    private fun captureObjects(
        game: Game,
        bridge: GameBridge,
        zones: Map<Int, ZoneSnapshot>,
    ): Map<ForgeCardId, CardSnapshot> {
        val combat = game.phaseHandler?.combat
        val liveZoneCards = liveZoneCardsByZoneAndId(game, bridge)
        // Pre-pass: walk the live battlefield once, build the prepared linkage in
        // both directions:
        //   sourceToCopy: source ForgeCardId → copy ForgeCardId
        //   copyToSource: copy ForgeCardId  → source ForgeCardId
        // Concentrates the source/copy lookup at one site so per-card snapshotting
        // doesn't re-read `Card.prepared.firstRemembered` and risk diverging from
        // this map. Stays identity-stable across Forge's `Card.id` reallocation on
        // cast (the source's `firstRemembered` always points at Forge's current
        // copy Card object, which matches whatever the Copy snapshot will see).
        val linkage = PreparedLinkage.from(game)
        val seen = linkedMapOf<ForgeCardId, CardSnapshot>()
        for (zone in zones.values) {
            for (fid in zone.contents) {
                if (fid in seen) continue
                val card = bridge.findCard(fid) ?: liveZoneCards[zone.id to fid] ?: continue
                seen[fid] = captureCard(card, combat, bridge, linkage)
            }
        }
        return seen
    }

    private fun liveZoneCardsByZoneAndId(
        game: Game,
        bridge: GameBridge,
    ): Map<Pair<Int, ForgeCardId>, Card> {
        val cards = linkedMapOf<Pair<Int, ForgeCardId>, Card>()
        for (seatNum in listOf(1, 2)) {
            val player = bridge.getPlayer(SeatId(seatNum)) ?: continue
            for (zoneType in listOf(ForgeZoneType.Hand, ForgeZoneType.Library, ForgeZoneType.Graveyard, ForgeZoneType.Sideboard)) {
                val zoneId = playerZoneId(seatNum, zoneType) ?: continue
                player.getZone(zoneType)?.cards?.forEach { cards[zoneId to ForgeCardId(it.id)] = it }
            }
        }
        cards.putAll(MutateSnapshotSupport.liveCardsByZoneId(game, bridge))
        val sharedZoneTypes =
            listOf(
                ForgeZoneType.Battlefield,
                ForgeZoneType.Stack,
                ForgeZoneType.Exile,
                ForgeZoneType.Command,
            )
        for (zoneType in sharedZoneTypes) {
            val zoneId = sharedZoneId(zoneType) ?: continue
            game.getCardsIncludePhasingIn(zoneType).forEach { cards[zoneId to ForgeCardId(it.id)] = it }
        }
        return cards
    }

    private fun captureActivationOccurrences(
        card: Card,
        bridge: GameBridge,
    ): Map<SeatId, List<ActivatedAbilityOccurrence>> {
        if (card.isFaceDown || card.spellAbilities.none { it.isActivatedAbility && !it.isManaAbility() }) return emptyMap()
        val originalPlayers = card.allSpellAbilities.associateWith { it.activatingPlayer }
        val registries = mutableMapOf<Int, AbilityRegistry>()
        val dataLookup: (Card) -> CardData? = { source ->
            bridge.cardRepository.findByGrpId(bridge.resolveGrpId(source, bridge.instanceId(source)))
        }
        val registryLookup: (Card, CardData?) -> AbilityRegistry? = { source, data ->
            data?.let {
                registries.getOrPut(source.id) {
                    AbilityRegistry.build(source, it, bridge.cardRepository::grantedKeywordAbilityGrpId)
                }
            }
        }
        try {
            return card.game.players
                .mapNotNull { player ->
                    val seat = bridge.seatOf(player) ?: return@mapNotNull null
                    if (card.isInZone(ForgeZoneType.Library) &&
                        card.game.players.none { card in it.getZone(ForgeZoneType.Library).getCardsPlayerCanActivate(player) }
                    ) {
                        return@mapNotNull null
                    }
                    val abilities = getNonManaActivatedAbilities(card, player)
                    if (abilities.none { !it.isBasicSpell }) return@mapNotNull null
                    val seen = mutableSetOf<Pair<Int, Int?>>()
                    val occurrences =
                        abilities.mapIndexed { index, ability ->
                            val identity = AbilityRegistry.resolveActivated(card, ability, dataLookup, registryLookup)
                            val first = seen.add(identity.abilityGrpId to identity.uniqueAbilityId)
                            ActivatedAbilityOccurrence(
                                index,
                                identity.definition.definitionId,
                                identity.abilityGrpId,
                                identity.uniqueAbilityId,
                                isAlternative = !ability.isBasicSpell && !first,
                            )
                        }
                    if (occurrences.none { it.isAlternative }) null else seat to occurrences
                }.toMap()
        } finally {
            originalPlayers.forEach { (ability, player) -> ability.setActivatingPlayer(player) }
        }
    }

    /**
     * Read all live Forge [Card] fields and pack them into an immutable [CardSnapshot].
     *
     * This is the single point where Forge Card reads occur for the ObjectMapper path.
     * [bridge] is needed to resolve instance IDs for combat targets/blockers.
     * [bridge] resolves Forge players to protocol seats for combat target player IDs.
     */
    @Suppress(
        // Branches once per card-state attribute the snapshot tracks (foretold
        // face-down identity, prepared linkage, copy/token, attachment, combat,
        // designations). Each new mechanic adds one branch. Inherent.
        "CyclomaticComplexMethod",
        "LongMethod",
    )
    private fun captureCard(
        card: Card,
        combat: forge.game.combat.Combat?,
        bridge: GameBridge,
        preparedLinkage: PreparedLinkage,
    ): CardSnapshot {
        val onBf = card.isInZone(ForgeZoneType.Battlefield)
        // Foretold cards are face-down — `card.type` reads from the FaceDown state
        // (Creature 2/2). Owner-perspective output must show the Original state
        // (Instant for Demon Bolt, etc.) so MTGA renders the real card and offers
        // the foretell-cast UX. (Forge's FaceDown defaults are appropriate for
        // morph / disguise on the battlefield, not for face-down-in-exile.)
        val isForetoldCard = Foretell.isForetold(card)
        val originalState =
            if (isForetoldCard) card.getOriginalState(forge.card.CardStateName.Original) else null
        val type = originalState?.type ?: card.type
        val resolvedName = originalState?.name?.takeIf { it.isNotEmpty() } ?: card.name

        // Live card types as proto CardType ordinal ints (mirrors overlayCardTypes logic)
        val liveTypeNumbers =
            type.coreTypes
                .mapNotNull { coreTypeToProto[it] }
                .sortedBy { it.number }
                .map { it.number }

        // Combat role — only for battlefield creatures
        val combatRole: CombatRole? =
            if (combat != null && onBf && type.isCreature) {
                resolveCombatRole(card, combat, bridge)
            } else {
                null
            }

        // Attachment — pre-resolve the parent instanceId here so ObjectMapper
        // doesn't need bridge access at projection time.
        val attachedToInstanceId =
            card.attachedTo?.let(bridge::instanceId)
        val mergedState = MutateSnapshotSupport.mergedState(card, bridge, bridge.cardRepository)

        val ownForgeId = ForgeCardId(card.id)
        val preparedRole = resolvePreparedRole(card, onBf, ownForgeId, preparedLinkage)
        val preparedCopySourceInstanceId =
            (preparedRole as? PreparedRole.Copy)?.sourceForgeCardId?.let {
                bridge.getOrAllocInstanceId(it).value
            }

        // DFC fields — pre-resolve the other face's grpId here, so the
        // projection step ([ObjectMapper.buildFromSnapshot]) reads from
        // [CardSnapshot.othersideGrpId] without bridge access.
        val othersideGrpId = resolveOthersideGrpId(card, bridge.cardRepository)
        val currentStateNameIsBackside =
            card.currentState?.stateName == forge.card.CardStateName.Backside

        // grpId — single resolution path. [GrpIdResolver] handles every case
        // (EFFECT → 0, tokens via registry/preparedCopy/copyPermanent/standard,
        // foretold via original-state name, regular via primary/any-face).
        val instanceId = bridge.getOrAllocInstanceId(ownForgeId).value
        val grpId =
            GrpIdResolver.resolve(
                card,
                bridge.cardRepository,
                instanceId = instanceId,
                tokenRegistry = bridge.tokenRegistry,
            )
        val isEngineToken = card.isToken && preparedRole !is PreparedRole.Copy
        val tokenAbility = card.tokenSpawningAbility
        val tokenSourceCard = tokenAbility?.hostCard?.takeIf { isEngineToken && tokenAbility.isAbility }
        val tokenSourceCardGrpId =
            tokenSourceCard?.let { source ->
                val sourceIid = bridge.instanceId(source)
                bridge.resolveGrpId(source, sourceIid)
            } ?: 0
        val tokenParentAbilityInstanceId =
            tokenAbility
                ?.takeIf { card.isToken && it.isAbility && it.id != 0 }
                ?.takeIf { isEngineToken }
                ?.let { bridge.getOrAllocInstanceId(FrameIdResolver.triggerStackAbilityForgeId(it.id)).value }
                ?: 0

        val ownerSeat = bridge.seatOf(card.owner) ?: SeatId(1)
        val controllerSeat = bridge.seatOf(card.controller) ?: ownerSeat
        val mayLookSeatIds =
            listOf(SeatId(1), SeatId(2)).filterTo(linkedSetOf()) { seatId ->
                bridge.getPlayer(seatId)?.let(card::mayPlayerLook) == true
            }

        // ActionMapper shape flags — read once here, not in the mapper.
        val isLand = type.isLand
        val isAdventureCard = card.isAdventureCard
        val isOmenCard =
            card.hasState(forge.card.CardStateName.Secondary) &&
                card.getState(forge.card.CardStateName.Secondary).type.hasSubtype("Omen")
        val isRoom = card.isRoom
        val hasManaAbilities = card.manaAbilities.isNotEmpty()
        val manaProductionColors = ManaSnapshotCapture.captureProductionColors(card, onBf)
        val chosenType = card.chosenType.takeIf { onBf && it.isNotBlank() }
        val chosenColorIds =
            if (onBf && card.hasChosenColor()) {
                card.chosenColors.mapNotNull { StaticChoiceIds.colorIdForName(it) }
            } else {
                emptyList()
            }
        val hasNonManaActivatedAbilities =
            card.spellAbilities.any { sa ->
                sa.isActivatedAbility && !sa.isManaAbility()
            }

        val emblem = EmblemSnapshot.capture(card, bridge)
        val observeFlying =
            bridge.engineSettings.keywordProjection == KeywordProjection.WebEffectiveFlying &&
                onBf &&
                !card.isFaceDown
        val flyingAbilityGrpIds =
            if (observeFlying) {
                (
                    bridge.cardRepository
                        .findByGrpId(grpId)
                        ?.abilityIds
                        .orEmpty()
                        .map { it.first } +
                        mergedState.componentAbilityGrpIds
                ).filterTo(mutableSetOf()) { bridge.cardRepository.findAbilityLocalization(it)?.keyword == "Flying" }
                    .apply { add(checkNotNull(KeywordGrpIds.forKeyword("Flying"))) }
            } else {
                emptySet()
            }
        return CardSnapshot(
            forgeCardId = ForgeCardId(card.id),
            name = resolvedName,
            activationOccurrences = captureActivationOccurrences(card, bridge),
            grpId = grpId,
            owner = ownerSeat,
            controller = controllerSeat,
            battleProtectorSeatId = if (onBf && card.isBattle) card.protectingPlayer?.let(bridge::seatOf) else null,
            mayLookSeatIds = mayLookSeatIds,
            isProjectable =
                emblem != null ||
                    card.gamePieceType == forge.card.GamePieceType.DUNGEON ||
                    card.gamePieceType == forge.card.GamePieceType.CARD ||
                    card.gamePieceType == forge.card.GamePieceType.COPIED_SPELL ||
                    card.isToken,
            emblem = emblem,
            basicLandManaAbilityGrpId = BasicLandAbilities.byForgeSubtypeNames(type.subtypes) ?: 0,
            effectSourceForgeCardId = card.effectSource?.let { ForgeCardId(it.id) },
            hasParadigmKeyword = card.hasKeyword("Paradigm"),
            imprintedCardIds = if (onBf) card.imprintedCards.map { ForgeCardId(it.id) } else emptyList(),
            isLand = isLand,
            isAdventureCard = isAdventureCard,
            isOmenCard = isOmenCard,
            isRoom = isRoom,
            isSaga = card.isSaga,
            hasManaAbilities = hasManaAbilities,
            manaProductionColors = manaProductionColors,
            chosenType = chosenType,
            chosenColorIds = chosenColorIds,
            hasNonManaActivatedAbilities = hasNonManaActivatedAbilities,
            isOnBattlefield = onBf,
            effectiveFlying = if (observeFlying) card.hasKeyword("Flying") else null,
            flyingAbilityGrpIds = flyingAbilityGrpIds,
            isPhasedOut = card.isPhasedOut,
            // P/T captured for all creatures so off-battlefield object shape stays stable.
            netPower = if (type.isCreature) card.netPower else null,
            netToughness = if (type.isCreature) card.netToughness else null,
            tapped = if (onBf) card.isTapped else false,
            hasSickness = onBf && type.isCreature && card.hasSickness(),
            damage = if (onBf && type.isCreature) card.damage else 0,
            currentLoyalty = if (onBf && type.isPlaneswalker) card.currentLoyalty else 0,
            isOnAdventure = card.isOnAdventure,
            endOfTurnLeavePlay = card.isToken && card.hasSVar("EndOfTurnLeavePlay"),
            grantedCastAbilityGrpId = card.castSA?.let { bridge.cardRepository.grantedKeywordAbilityGrpId(it) },
            evokePaid =
                (onBf || card.isInZone(ForgeZoneType.Stack)) &&
                    card.castSA?.isEvoke == true,
            isToken = card.isToken,
            isCopyToken = card.gamePieceType == forge.card.GamePieceType.COPIED_SPELL || (card.isToken && card.copiedPermanent != null),
            tokenSourceCardGrpId = tokenSourceCardGrpId,
            tokenParentAbilityInstanceId = tokenParentAbilityInstanceId,
            attachedToInstanceId = attachedToInstanceId,
            preparedCopySourceInstanceId = preparedCopySourceInstanceId,
            liveCardTypeNumbers = liveTypeNumbers,
            currentTypeLine = card.type.toString(),
            isDoubleFaced = card.isDoubleFaced,
            othersideGrpId = othersideGrpId,
            currentStateNameIsBackside = currentStateNameIsBackside,
            combatRole = combatRole,
            preparedRole = preparedRole,
            plottedRole = if (Plotted.isPlotted(card)) PlottedRole.Plotted else PlottedRole.None,
            isSaddled = onBf && card.isSaddled,
            isSuspected = onBf && card.isSuspected,
            isSolved = onBf && card.isSolved,
            isForetold = Foretell.isForetold(card),
            faceDownKind = FaceDown.kind(card),
            isCommander = card.isCommander,
            commanderTax = commanderTax(card),
            commanderColorIdentity = commanderColorIdentity(card),
            // Door state is meaningful only on battlefield rooms — Forge keeps
            // `unlockedRooms` populated on retired stack/limbo card states the
            // same way it keeps `isPrepared` / `isPlotted` lingering. Filter to
            // `onBf && card.isRoom` to anchor the Designation pAnn on the live
            // battlefield permanent.
            isLeftDoorUnlocked =
                onBf &&
                    card.isRoom &&
                    forge.card.CardStateName.LeftSplit in card.unlockedRooms,
            isRightDoorUnlocked =
                onBf &&
                    card.isRoom &&
                    forge.card.CardStateName.RightSplit in card.unlockedRooms,
            mergedToInstanceId = mergedState.targetInstanceId,
            mergedComponentAbilityGrpIds = mergedState.componentAbilityGrpIds,
            mergedComponentAbilityOriginalCardGrpIds = mergedState.componentAbilityOriginalCardGrpIds,
            isMergedPermanent = mergedState.isMergedPermanent,
            isTopMergedComponent = mergedState.isTopComponent,
        )
    }

    private fun commanderTax(card: Card): Int {
        if (!card.isCommander) return 0
        return card.owner.getCommanderCast(card.realCommander ?: card) * 2
    }

    private fun commanderColorIdentity(card: Card): List<Int> {
        if (!card.isCommander) return emptyList()
        val mask =
            card.rules.colorIdentity.color
                .toInt()
        return WubrgColorMapping.manaColorNumbersFromMagicMask(mask)
    }

    /**
     * Resolve the [PreparedRole] for [card]. Both Source and Copy directions read
     * from the same [linkage] map, so a card never disagrees with itself across the
     * Source/Copy boundary.
     */
    private fun resolvePreparedRole(
        card: Card,
        onBattlefield: Boolean,
        ownForgeId: ForgeCardId,
        linkage: PreparedLinkage,
    ): PreparedRole =
        when {
            onBattlefield && card.isPrepared -> {
                val copy = linkage.copyOf(ownForgeId)
                if (copy == null) {
                    // `setPrepared(eff)` is the last step of the AlterAttribute Prepared
                    // path in Forge, after `eff.addRemembered(prepared)`. An observable
                    // snapshot with `isPrepared==true` should always have
                    // `firstRemembered != null`. If we hit the null branch the engine
                    // was observed mid-effect — log and degrade to None so we don't
                    // anchor a Designation pAnn on a Source we can't link.
                    log.warn(
                        "isPrepared=true with firstRemembered=null for forgeId={} ({}); skipping Source role",
                        card.id,
                        card.name,
                    )
                    PreparedRole.None
                } else {
                    PreparedRole.Source(copy)
                }
            }
            PreparedSpell.isCopy(card) ->
                PreparedRole.Copy(linkage.sourceOf(ownForgeId))
            else -> PreparedRole.None
        }

    private fun resolveCombatRole(
        card: Card,
        combat: forge.game.combat.Combat,
        bridge: GameBridge,
    ): CombatRole? {
        if (combat.isAttacking(card)) {
            val targetInstanceId: Int =
                run {
                    val defender = combat.getDefenderByAttacker(card)
                    when {
                        defender == null -> 0
                        defender is Player -> bridge.seatOf(defender)?.value ?: 0
                        defender is Card -> bridge.instanceId(defender)
                        else -> 0
                    }
                }
            val isBlocked: Boolean? = combat.getBandOfAttacker(card)?.isBlocked()
            return CombatRole.Attacker(
                targetInstanceId = targetInstanceId,
                isBlocked = isBlocked,
            )
        }
        if (combat.isBlocking(card)) {
            val attackerIds =
                combat.getAttackersBlockedBy(card).map { atk ->
                    bridge.instanceId(atk)
                }
            return CombatRole.Blocker(attackerInstanceIds = attackerIds)
        }
        return null
    }

    // Delegate to the canonical mapping in ObjectMapper — single source of truth.
    private val coreTypeToProto get() = leyline.game.mapping.ObjectMapper.coreTypeToProto

    // --- Zone ID helpers (unchanged from before) ---

    private fun playerZoneId(
        seat: Int,
        fz: ForgeZoneType,
    ): Int? =
        when (fz) {
            ForgeZoneType.Hand -> ZoneIds.handOf(seat)
            ForgeZoneType.Library -> ZoneIds.libraryOf(seat)
            ForgeZoneType.Graveyard -> ZoneIds.graveyardOf(seat)
            ForgeZoneType.Sideboard -> ZoneIds.sideboardOf(seat)
            ForgeZoneType.Battlefield,
            ForgeZoneType.Exile,
            ForgeZoneType.Flashback,
            ForgeZoneType.Command,
            ForgeZoneType.Stack,
            ForgeZoneType.Ante,
            ForgeZoneType.Merged,
            ForgeZoneType.SchemeDeck,
            ForgeZoneType.PlanarDeck,
            ForgeZoneType.AttractionDeck,
            ForgeZoneType.Junkyard,
            ForgeZoneType.ContraptionDeck,
            ForgeZoneType.StickerSheets,
            ForgeZoneType.Subgame,
            ForgeZoneType.ExtraHand,
            ForgeZoneType.None,
            -> null
        }

    private fun sharedZoneId(fz: ForgeZoneType): Int? =
        when (fz) {
            ForgeZoneType.Battlefield -> ZoneIds.BATTLEFIELD
            ForgeZoneType.Stack -> ZoneIds.STACK
            ForgeZoneType.Merged -> ZoneIds.SUPPRESSED
            ForgeZoneType.Exile -> ZoneIds.EXILE
            ForgeZoneType.Command -> ZoneIds.COMMAND
            ForgeZoneType.Hand,
            ForgeZoneType.Library,
            ForgeZoneType.Graveyard,
            ForgeZoneType.Flashback,
            ForgeZoneType.Sideboard,
            ForgeZoneType.Ante,
            ForgeZoneType.SchemeDeck,
            ForgeZoneType.PlanarDeck,
            ForgeZoneType.AttractionDeck,
            ForgeZoneType.Junkyard,
            ForgeZoneType.ContraptionDeck,
            ForgeZoneType.StickerSheets,
            ForgeZoneType.Subgame,
            ForgeZoneType.ExtraHand,
            ForgeZoneType.None,
            -> null
        }

    private fun arenaTypeFor(fz: ForgeZoneType): ZoneType =
        when (fz) {
            ForgeZoneType.Hand -> ZoneType.Hand
            ForgeZoneType.Library -> ZoneType.Library
            ForgeZoneType.Graveyard -> ZoneType.Graveyard
            ForgeZoneType.Sideboard -> ZoneType.Sideboard
            ForgeZoneType.Command -> ZoneType.Command
            ForgeZoneType.Battlefield -> ZoneType.Battlefield
            ForgeZoneType.Stack -> ZoneType.Stack
            ForgeZoneType.Merged -> ZoneType.Suppressed
            ForgeZoneType.Exile -> ZoneType.Exile
            ForgeZoneType.Flashback,
            ForgeZoneType.Ante,
            ForgeZoneType.SchemeDeck,
            ForgeZoneType.PlanarDeck,
            ForgeZoneType.AttractionDeck,
            ForgeZoneType.Junkyard,
            ForgeZoneType.ContraptionDeck,
            ForgeZoneType.StickerSheets,
            ForgeZoneType.Subgame,
            ForgeZoneType.ExtraHand,
            ForgeZoneType.None,
            -> ZoneType.UNRECOGNIZED
        }

    private fun visibilityFor(fz: ForgeZoneType): Visibility =
        when (fz) {
            ForgeZoneType.Hand,
            ForgeZoneType.Library,
            ForgeZoneType.Sideboard,
            -> Visibility.Private
            ForgeZoneType.Battlefield,
            ForgeZoneType.Exile,
            ForgeZoneType.Flashback,
            ForgeZoneType.Command,
            ForgeZoneType.Stack,
            ForgeZoneType.Graveyard,
            ForgeZoneType.Ante,
            ForgeZoneType.Merged,
            ForgeZoneType.SchemeDeck,
            ForgeZoneType.PlanarDeck,
            ForgeZoneType.AttractionDeck,
            ForgeZoneType.Junkyard,
            ForgeZoneType.ContraptionDeck,
            ForgeZoneType.StickerSheets,
            ForgeZoneType.Subgame,
            ForgeZoneType.ExtraHand,
            ForgeZoneType.None,
            -> Visibility.Public
        }

    /**
     * Pre-run [leyline.game.annotations.AbilityWordScanner] at capture time so the diff
     * pipeline reads from snap instead of `game.registeredPlayers`.
     */
    private fun computeAbilityWordEntries(
        game: Game,
        bridge: GameBridge,
    ): List<AbilityWordScanner.AbilityWordEntry> {
        val bfCards =
            game.registeredPlayers.flatMap {
                it.getZone(ForgeZoneType.Battlefield).cards.toList()
            }
        val handCards =
            game.registeredPlayers.flatMap {
                it.getZone(ForgeZoneType.Hand).cards.toList()
            }
        return AbilityWordScanner.scan(
            battlefieldCards = bfCards,
            handCards = handCards,
            instanceIdResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
            registryResolver = { card ->
                val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                val cardData = bridge.cardRepository.findByGrpId(grpId)
                bridge.abilityRegistryFor(card, cardData)
            },
        ) + CastAbilityWordScanner.scan(game, bridge)
    }
}
