package leyline.testkit

import forge.game.Game
import forge.game.card.Card
import forge.game.phase.PhaseType
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.advanceToMain1
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.ActionMapper
import leyline.game.seedDiffBaseline
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

/**
 * Board-tier test context: the bridge, game, and message counter produced by
 * a `start*` setup call, plus the state-needing helpers that act on them.
 *
 * Destructures as `(bridge, game, counter)` so every existing
 * `val (b, game, counter) = start*` call site keeps compiling unchanged.
 */
class Board(
    val bridge: GameBridge,
    val game: Game,
    val counter: LogicalSequencePlanner,
) {
    operator fun component1(): GameBridge = bridge

    operator fun component2(): Game = game

    operator fun component3(): LogicalSequencePlanner = counter

    /** The human (non-AI) player. */
    val human: Player get() = game.humanPlayer

    /** The AI player. */
    val ai: Player get() = game.aiPlayer

    // ----- Snapshot + diff -----

    /** Create a [BundleBuilder] with standard test constants. */
    fun bundleBuilder(): BundleBuilder = BundleBuilder(bridge, TEST_MATCH_ID, SEAT_ID)

    /** Build a stateOnlyDiff and return the GSM. Fails if no GSM produced. */
    fun stateOnlyDiff(): GameStateMessage =
        BundleBuilderTestSupport.stateOnly(bundleBuilder(), bridge, game, counter).gsmOrNull
            ?: error("stateOnlyDiff returned no GSM")

    /**
     * Seed the diff baseline, run [action], build a stateOnlyDiff, return the GSM.
     * If [checkSba] is true, triggers state-based actions after the action.
     */
    fun snapshotDiff(
        checkSba: Boolean = false,
        action: () -> Unit,
    ): GameStateMessage {
        bridge.seedDiffBaseline(game, counter.currentGsId())
        action()
        if (checkSba) game.action.checkStateEffects(true)
        return stateOnlyDiff()
    }

    /** Build a postAction bundle with standard test constants. */
    fun postAction(): BundleBuilder.BundleResult {
        val playbackMessages =
            bridge.playback
                ?.drainQueue()
                .orEmpty()
                .flatten()
        val result = BundleBuilderTestSupport.postAction(bundleBuilder(), bridge, game, counter)
        if (playbackMessages.isEmpty()) return result
        return BundleBuilder.BundleResult(playbackMessages + result.messages)
    }

    /** Build a gameStart bundle (phaseTransitionDiff) with standard test constants. */
    fun gameStart(): BundleBuilder.BundleResult = BundleBuilderTestSupport.phaseTransition(bundleBuilder(), bridge, game, counter)

    // ----- Board actions -----

    /**
     * Build the human player's hand and battlefield actions via the production
     * snapshot projection path ([ActionMapper.buildFromSnapshot]).
     */
    fun actions(): ActionsAvailableReq =
        ActionMapper.buildFromSnapshot(
            SEAT_ID,
            leyline.game.snapshot.SnapshotCapture
                .run(game, bridge, TEST_MATCH_ID, 0),
            bridge,
        )

    /** Add a card to a player's zone, registered so the bridge can resolve its instanceId. */
    fun addCard(
        name: String,
        player: Player,
        zone: ZoneType = ZoneType.Battlefield,
    ): Card {
        val card = createCard(name, player, zone)
        TestCardRegistry.ensureCardRegistered(card.name)
        bridge.instance(card)
        return card
    }

    /** Play first land from hand via Forge's full path. Fires GameEventLandPlayed. */
    fun playLandFromHand(): GameStateMessage {
        val land = human.getZone(ZoneType.Hand).cards.first { it.isLand }
        return snapshotDiff { human.playLand(land, null) }
    }

    /** Find card by name, perform action, assert realloc + Limbo, return (gsm, newInstanceId). */
    fun transferCard(
        cardName: String,
        checkSba: Boolean = false,
        action: (Card, Game) -> Unit,
    ): Pair<GameStateMessage, Int> {
        val card =
            listOf(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile)
                .firstNotNullOf { zone -> human.getZone(zone).cards.firstOrNull { it.name == cardName } }
        val origId = instanceId(card.id)
        val cardId = card.id

        val gsm = snapshotDiff(checkSba = checkSba) { action(card, game) }
        val newId = instanceId(cardId)

        // Every zone transfer reallocates instanceId and retires the old one to Limbo
        check(origId != newId) { "instanceId should change on zone transfer ($cardName): $origId" }
        assertLimboContains(gsm, origId)

        return gsm to newId
    }

    /** Resolve a Forge card.id to its current proto instanceId. */
    fun instanceId(cardId: Int): Int = bridge.getOrAllocInstanceId(ForgeCardId(cardId)).value

    companion object {
        const val TEST_MATCH_ID = "test-match"
        const val SEAT_ID = 1

        // Guards Forge's static MyRandom during seed → shuffle → initial-draw.
        // See note in startGameAtMain1 for why.
        private val RNG_LOCK = Any()

        /**
         * Create + place a card in a zone. No bridge registration — used during
         * [startWithBoard] setup, before the board's card-registration pass runs.
         */
        internal fun createCard(
            name: String,
            player: Player,
            zone: ZoneType,
        ): Card {
            val paperCard =
                forge.model.FModel
                    .getMagicDb()
                    .commonCards
                    .getCard(name)
                    ?: run {
                        forge.StaticData.instance().attemptToLoadCard(name)
                        forge.model.FModel
                            .getMagicDb()
                            .commonCards
                            .getCard(name)
                    }
                    ?: error("Card not found: $name")
            val card = Card.fromPaperCard(paperCard, player)
            card.setGameTimestamp(player.game.nextTimestamp)
            player.getZone(zone).add(card)
            if (zone == ZoneType.Battlefield) {
                card.setSickness(false)
            }
            return card
        }

        /**
         * Start a game with cards placed directly into zones — no threads, no loop.
         *
         * Uses the upstream `AITest` pattern: empty-deck game, `devModeSet(MAIN1)`,
         * cards added via `Zone.add()`. Fully synchronous — Forge events fire inline
         * when you call `game.action.*`. ~0.01s per test (vs 0.5s for startGameAtMain1).
         *
         * @param board lambda that receives (game, human, ai) to set up zones
         */
        fun startWithBoard(board: (game: Game, human: Player, ai: Player) -> Unit): Board = startWithBoard(EngineSettings(), board)

        fun startWithBoard(
            settings: EngineSettings,
            board: (game: Game, human: Player, ai: Player) -> Unit,
        ): Board {
            val counter = LogicalSequencePlanner(initialGsId = 20, initialMsgId = 0)
            val b = GameBridge(initialSequence = counter.snapshot(), cardRepository = TestCardRegistry.repo, engineSettings = settings)

            val game = GameBootstrap.createGame()
            b.wrapGame(game)

            board(game, game.humanPlayer, game.aiPlayer)

            // Register all cards on the board in CardRepository + InstanceIdRegistry
            for (player in game.players) {
                for (zone in listOf(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library)) {
                    for (card in player.getZone(zone).cards) {
                        TestCardRegistry.ensureCardRegistered(card.name)
                        b.instance(card)
                    }
                }
            }

            b.seedDiffBaseline(game, counter.currentGsId())
            return Board(b, game, counter)
        }

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
        ): Board {
            // Auto-register CardData for all cards in the deck list
            if (deckList != null) {
                TestCardRegistry.ensureDeckRegistered(deckList)
            }
            val counter = LogicalSequencePlanner(initialGsId = 20, initialMsgId = 0)
            val b = GameBridge(initialSequence = counter.snapshot(), cardRepository = TestCardRegistry.repo)
            // Forge's MyRandom is a static Random. b.start(seed) replaces it via
            // MyRandom.setRandom(Random(seed)), so two concurrent Kotest specs
            // calling this race — one overwrites the other's RNG mid-shuffle and
            // non-deterministic hands result ("No land in hand at seed 42" flake
            // at kotest.framework.parallelism=8).
            //
            // Serialize the seed → shuffle → hand-draw window. After advanceToMain1
            // the library is fixed and further MyRandom writes from other specs
            // don't affect assertion outcomes in tests that don't trigger random
            // mid-game effects.
            val game =
                synchronized(RNG_LOCK) {
                    b.start(seed = seed, deckList = deckList, variant = variant)
                    b.submitKeep(SeatId(1))
                    advanceToMain1(b)
                    b.getGame()!!
                }
            check(game.phaseHandler.phase == PhaseType.MAIN1) {
                "Game should be at Main1 after advanceToMain1 (actual: ${game.phaseHandler.phase})"
            }
            b.seedDiffBaseline(game, counter.currentGsId())
            return Board(b, game, counter)
        }

        /**
         * Start a game from an inline puzzle definition — no mulligan, no turn advancement.
         *
         * Much faster than [startGameAtMain1] (~0.3s vs ~1.5s) because it skips:
         * deck shuffle, mulligan keep, and priority-passing through upkeep/draw.
         *
         * @param puzzleText inline `.pzl` content (see `data/puzzles/` for format)
         */
        fun startPuzzleAtMain1(
            puzzleText: String,
            engineSettings: EngineSettings = EngineSettings(),
        ): Board = startPuzzleAtMain1(PuzzleSource.loadFromText(puzzleText), engineSettings)

        /** Convenience: load a puzzle from a shared identity path or test-private resource. */
        fun startPuzzleAtMain1FromResource(
            resourcePath: String,
            engineSettings: EngineSettings = EngineSettings(),
        ): Board = startPuzzleAtMain1(PuzzleSource.loadFromResource(resourcePath), engineSettings)

        private fun startPuzzleAtMain1(
            puzzle: forge.gamemodes.puzzle.Puzzle,
            engineSettings: EngineSettings,
        ): Board {
            val counter = LogicalSequencePlanner(initialGsId = 20, initialMsgId = 0)
            val b =
                GameBridge(engineSettings = engineSettings, initialSequence = counter.snapshot(), cardRepository = TestCardRegistry.repo)

            // Board fixtures hold their requested phase for direct inspection and manual prompt publication.
            b.priorityPolicy.submit(SettingsMessage.newBuilder().setAutoPassOption(AutoPassOption.FullControl).build())
            b.startPuzzle(puzzle)

            val game = b.getGame()!!
            TestCardRegistry.registerPuzzleCards(game)
            check(game.phaseHandler.phase == PhaseType.MAIN1) {
                "Puzzle game should be at Main1 (actual: ${game.phaseHandler.phase})"
            }
            b.seedDiffBaseline(game, counter.currentGsId())
            return Board(b, game, counter)
        }
    }
}

/** The human (non-AI) player. Use after any start* method. */
val Game.humanPlayer: Player
    get() = players.first { it.lobbyPlayer !is forge.ai.LobbyPlayerAi }

/** The AI player. Use after any start* method. */
val Game.aiPlayer: Player
    get() = players.first { it.lobbyPlayer is forge.ai.LobbyPlayerAi }
