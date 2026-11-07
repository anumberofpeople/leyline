package leyline.testkit

import forge.game.Game
import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import leyline.BoardTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.awaitFreshPending
import leyline.game.bundle.AbilityExhaustionFactsCapture
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.bundle.PersistentFeedFactsCapture
import leyline.game.seedDiffBaseline
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.tooling.headless.cardIn
import leyline.tooling.headless.iidVia
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import leyline.testkit.StateMapperShell as StateMapper

/**
 * Base class for board-tier tests (land/mana, combat, stack, etc.).
 *
 * Extends FunSpec — no `val base =` boilerplate. Wires initCardDatabase/tearDown
 * automatically; owns one shared bridge/counter pair per spec.
 *
 * ```
 * class LandManaTest : BoardTest({
 *     test("Forest — ColorProduction [5]") {
 *         val board = startWithBoard { _, human, _ ->
 *             addCard("Forest", human, ZoneType.Hand)
 *         }
 *         ...
 *     }
 * })
 * ```
 *
 * Tests that need an isolated bridge/counter pair outside this shared instance
 * (e.g. driving two independent bridges within a single test body) should use
 * [IsolatedBoardLifecycle] instead of constructing another `BoardTest`.
 */
// `abstract` keeps Kotest's auto-discovery from trying to instantiate the base
// class directly (no zero-arg constructor — only the `body` lambda variant).
@Suppress("UnnecessaryAbstractClass")
abstract class BoardTest(
    body: BoardTest.() -> Unit,
) : FunSpec() {
    private var bridge: GameBridge? = null

    /** Shared counter for the current test. Reset per test via [startGameAtMain1] et al. */
    private var testCounter: LogicalSequencePlanner = LogicalSequencePlanner()

    init {
        tags(BoardTag)
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }
        afterEach {
            bridge?.shutdown()
            bridge = null
            testCounter = LogicalSequencePlanner()
        }
        body()
    }

    // --- Setup ---

    /**
     * Start a game with cards placed directly into zones — no threads, no loop.
     * ~0.01s per test (vs 0.5s for [startGameAtMain1]).
     *
     * @param board lambda that receives (game, human, ai) to set up zones
     */
    fun startWithBoard(board: (game: Game, human: Player, ai: Player) -> Unit): Board = trackResult(Board.startWithBoard(board))

    fun startWithBoard(
        settings: EngineSettings,
        board: (game: Game, human: Player, ai: Player) -> Unit,
    ): Board = trackResult(Board.startWithBoard(settings, board))

    /**
     * Start a deterministic game, keep hand, advance to Main1.
     *
     * @param seed RNG seed for deterministic shuffles
     * @param deckList custom deck list (e.g. "30 Plains\n30 Forest"); null uses default mono-green
     * @param variant game variant (e.g. "brawl" for commander tax + command zone); null = Constructed
     */
    fun startGameAtMain1(
        seed: Long = 42L,
        deckList: String? = null,
        variant: String? = null,
    ): Board = trackResult(Board.startGameAtMain1(seed, deckList, variant))

    /**
     * Start a game from an inline puzzle definition — no mulligan, no turn advancement.
     *
     * @param puzzleText inline `.pzl` content (see `data/puzzles/` for format)
     */
    fun startPuzzleAtMain1(
        puzzleText: String,
        engineSettings: EngineSettings = EngineSettings(),
    ): Board = trackResult(Board.startPuzzleAtMain1(puzzleText, engineSettings))

    /** Convenience: load a puzzle from a shared identity path or test-private resource. */
    fun startPuzzleAtMain1FromResource(resourcePath: String): Board = trackResult(Board.startPuzzleAtMain1FromResource(resourcePath))

    private fun trackResult(result: Board): Board {
        bridge = result.bridge
        testCounter = result.counter
        return result
    }

    /** Register a manually constructed [GameBridge] so teardown shuts it down after the test. */
    fun useBridge(b: GameBridge) {
        bridge = b
    }

    fun addCard(
        name: String,
        player: Player,
        zone: ZoneType = ZoneType.Battlefield,
    ): Card = Board.createCard(name, player, zone)

    // --- Board actions ---

    /** Move card to battlefield — raw zone move, no events, no triggers. For setup. */
    fun moveToBattlefield(
        card: Card,
        game: Game,
    ) {
        game.action.moveToPlay(card, null, AbilityKey.newMap())
    }

    // --- ID helpers ---

    /** Resolve a Forge card.id to its current proto instanceId. */
    fun GameBridge.instanceId(cardId: Int): Int = getOrAllocInstanceId(ForgeCardId(cardId)).value

    // --- Game action wrappers (hide Forge internals) ---

    fun destroy(
        card: Card,
        game: Game,
    ) {
        game.action.destroy(card, null, false, AbilityKey.newMap())
    }

    fun exile(
        card: Card,
        game: Game,
    ) {
        game.action.exile(card, null, AbilityKey.newMap())
    }

    // --- Instance probe DSL ---

    /** Battlefield zone of [this] player as a probe handle. */
    val Player.battlefield: PlayerZone get() = PlayerZone(this, ZoneType.Battlefield)

    /** Hand zone of [this] player as a probe handle. */
    val Player.hand: PlayerZone get() = PlayerZone(this, ZoneType.Hand)

    /** Graveyard zone of [this] player as a probe handle. */
    val Player.graveyard: PlayerZone get() = PlayerZone(this, ZoneType.Graveyard)

    /** Exile zone of [this] player as a probe handle. */
    val Player.exile: PlayerZone get() = PlayerZone(this, ZoneType.Exile)

    /** Library zone of [this] player as a probe handle. */
    val Player.library: PlayerZone get() = PlayerZone(this, ZoneType.Library)

    /**
     * Resolve a card by name within this (player, zone) handle to its
     * instanceId — call sites read like a path: `human.battlefield.iid("Grizzly Bears")`.
     * Resolves through the most recent `start*` board's bridge.
     */
    fun PlayerZone.iid(cardName: String): Int = iidVia(currentBridge(), cardName)

    /** Resolve multiple cards by name in one go — `human.battlefield.iids("A", "B")`. */
    fun PlayerZone.iids(vararg cardNames: String): List<Int> = cardNames.map { iid(it) }

    /** Find a card in the zone by name. */
    fun PlayerZone.card(name: String): Card = cardIn(player, zone, name)

    private fun currentBridge(): GameBridge = bridge ?: error("Call a start*() method before using the probe DSL")

    // --- Bundle / action-loop helpers ---

    /** Create a [BundleBuilder] with standard test constants. */
    fun bundleBuilder(b: GameBridge): BundleBuilder = BundleBuilder(b, Board.TEST_MATCH_ID, Board.SEAT_ID)

    /**
     * Build a Full state GSM simulating the handshake baseline.
     * Accumulator-based tests need this before processing thin Diffs from gameStart.
     */
    fun handshakeFull(
        game: Game,
        b: GameBridge,
        gsId: Int,
    ): GameStateMessage {
        val snap = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, gsId)
        val promptFacts = b.materializePromptProjectionFacts()
        return StateMapper
            .buildFromSnapshot(
                snap,
                gsId,
                Board.TEST_MATCH_ID,
                b,
                viewingSeatId = Board.SEAT_ID,
                promptFacts = promptFacts,
                persistentFeedFacts =
                    PersistentFeedFactsCapture.capture(snap, promptFacts, b, b.stateProjectionEnvironment),
                effectFacts = b.materializeEffectProjectionFacts(),
                abilityExhaustionFacts = AbilityExhaustionFactsCapture.capture(snap, b),
            ).gsm
    }

    fun playLand(b: GameBridge): PlayerAction.PlayLand? {
        val player = b.getPlayer(SeatId(1)) ?: return null
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return null
        val pending = awaitFreshPending(b, null) ?: return null
        val action = PlayerAction.PlayLand(ForgeCardId(land.id))
        b.submitTestAction(pending.actionId, action)
        awaitFreshPending(b, pending.actionId)
        return action
    }

    fun castCreature(b: GameBridge): PlayerAction.CastSpell? {
        val player = b.getPlayer(SeatId(1)) ?: return null
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return null
        val pending = awaitFreshPending(b, null) ?: return null
        val action = PlayerAction.CastSpell(ForgeCardId(creature.id))
        b.submitTestAction(pending.actionId, action)
        awaitFreshPending(b, pending.actionId)
        return action
    }

    fun passPriority(b: GameBridge) {
        val pending = awaitFreshPending(b, null) ?: return
        b.submitTestAction(pending.actionId, PlayerAction.PassPriority)
        awaitFreshPending(b, pending.actionId)
    }

    // --- Cast/resolve convenience captures ---

    /** Play a land and capture the resulting GSM. */
    fun playLandAndCapture(): GameStateMessage? {
        val board = startGameAtMain1()
        playLand(board.bridge) ?: return null
        return board.postAction().gsmOrNull
    }

    /**
     * Cast a creature spell and capture the full bundle.
     * Plays a land first for mana. With CastSpell split, this returns the
     * QueuedGSM triplet + ActionsAvailableReq.
     */
    fun castSpellBundle(): BundleBuilder.BundleResult? {
        val board = startGameAtMain1()
        playLand(board.bridge) ?: return null
        board.bridge.playback?.drainQueue()
        board.bridge.seedDiffBaseline(board.game)
        castCreature(board.bridge) ?: return null
        return board.postAction()
    }

    /**
     * Cast a creature spell and capture the on-stack GSM.
     * Plays a land first for mana. Returns merged GSM with all annotations
     * from the QueuedGSM triplet combined.
     */
    fun castSpellAndCapture(): GameStateMessage? = castSpellBundle()?.mergedGsm

    /**
     * Cast a creature and capture GSM + pre/post instanceIds.
     * Returns (gsm, origInstanceId, newInstanceId).
     */
    fun castSpellAndCaptureWithIds(): Triple<GameStateMessage, Int, Int>? {
        val board = startGameAtMain1()
        playLand(board.bridge) ?: return null
        board.bridge.playback?.drainQueue()
        board.bridge.seedDiffBaseline(board.game)

        val action = castCreature(board.bridge) ?: return null
        // Use mergedGsm to combine QueuedGSM triplet annotations into one GSM
        val gsm = board.postAction().mergedGsm
        val origInstanceId = gsm.annotation(AnnotationType.ObjectIdChanged).detailInt("orig_id")
        val newInstanceId = board.bridge.getOrAllocInstanceId(action.cardId).value

        return Triple(gsm, origInstanceId, newInstanceId)
    }

    /**
     * Full cast+resolve cycle: play land -> cast creature -> pass priority.
     * Returns the GSM from the resolution step.
     */
    fun resolveAndCapture(): GameStateMessage? {
        val board = startGameAtMain1()
        playLand(board.bridge) ?: return null
        board.bridge.playback?.drainQueue()
        board.bridge.seedDiffBaseline(board.game)

        castCreature(board.bridge) ?: return null
        board.postAction() // capture cast result (advances counter)
        board.bridge.seedDiffBaseline(board.game)

        passPriority(board.bridge)
        return board.postAction().gsmOrNull
    }

    companion object {
        const val SEAT_ID = Board.SEAT_ID
    }
}
