package leyline.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Engine behavior settings: match timing, new-match defaults, draft policy,
 * and diagnostics. The :app composition root resolves these values once from
 * `leyline.toml` plus `LEYLINE_*` overrides and passes them explicitly to the
 * engine bridge and match session.
 */
@Serializable
data class EngineSettings(
    /** Priority/action window timeout (ms). Null = wait indefinitely for human input. */
    @SerialName("bridge_timeout_ms")
    val bridgeTimeoutMs: Long? = null,
    /** Client-visible prompt fail-safe (ms). Null = no prompt timeout. */
    @SerialName("prompt_failsafe_ms")
    val promptFailsafeMs: Long? = 45_000L,
    /** How long the engine waits for the AI turn before suppressing action prompts (ms). */
    @SerialName("ai_turn_wait_ms")
    val aiTurnWaitMs: Long = 30_000L,
    /** How long session entry waits for the engine to publish a mulligan phase (ms). */
    @SerialName("mulligan_wait_ms")
    val mulliganWaitMs: Long = 45_000L,
    /** RNG seed for deterministic shuffles. Null = random each game. */
    val seed: Long? = null,
    /** Seat that wins the die roll and goes first (1 = human, 2 = AI). Null = random. */
    @SerialName("die_roll_winner")
    val dieRollWinner: Int? = null,
    /** Skip the mulligan phase — auto-keep the opening hand. */
    @SerialName("skip_mulligan")
    val skipMulligan: Boolean = false,
    /** Send the decision timer (rope) on priority grant. */
    val timer: Boolean = false,
    /** AI opponent deck name (looked up in player.db). "random" picks a random deck. Null = mirror match. */
    @SerialName("ai_deck")
    val aiDeck: String? = null,
    /** Run both seats under Forge AI; attach the client as a read-only viewer. */
    @SerialName("spectator_mode")
    val spectatorMode: Boolean = false,
    /** AI animation speed (0 = instant, 1 = default pacing). */
    @SerialName("ai_speed")
    val aiSpeed: Double = 1.0,
    /** Booster-draft picker policy. */
    val draft: DraftSettings = DraftSettings(),
    /** Development-time diagnostics knobs. */
    val dev: DevSettings = DevSettings(),
    /** Alternate additional-cost GRE labels. All connections to a match share this setting. */
    @SerialName("cost_choice_presentation")
    val costChoicePresentation: CostChoicePresentation = CostChoicePresentation.Native,
    /** Embedding-host opt-in; native configuration and wire defaults stay unchanged. */
    @Transient
    val keywordProjection: KeywordProjection = KeywordProjection.Native,
    /** Embedding-host opt-in for the local live type-text extension; native bytes stay unchanged. */
    @Transient
    val includeCurrentTypeLine: Boolean = false,
) {
    /**
     * AI delay multiplier derived from [aiSpeed].
     * speed=2 means 2x faster → delays halved (multiplier=0.5).
     * speed=0 means instant (multiplier=0).
     */
    val aiDelayMultiplier: Double get() = if (aiSpeed == 0.0) 0.0 else 1.0 / aiSpeed

    /**
     * Session pacing delay derived from [aiSpeed], applied between auto-pass
     * and combat steps so clients can animate. speed=0 disables pacing entirely.
     */
    val paceDelayMs: Long get() = (200L * aiDelayMultiplier).toLong()

    fun validate() {
        bridgeTimeoutMs?.let { require(it > 0) { "engine.bridge_timeout_ms must be positive when set, got $it" } }
        promptFailsafeMs?.let { require(it > 0) { "engine.prompt_failsafe_ms must be positive when set, got $it" } }
        require(aiTurnWaitMs > 0) { "engine.ai_turn_wait_ms must be positive, got $aiTurnWaitMs" }
        require(mulliganWaitMs > 0) { "engine.mulligan_wait_ms must be positive, got $mulliganWaitMs" }
        seed?.let { require(it >= 0) { "engine.seed must be non-negative, got $it" } }
        dieRollWinner?.let { require(it in 1..2) { "engine.die_roll_winner must be 1 or 2, got $it" } }
        require(aiSpeed >= 0.0) { "engine.ai_speed must be non-negative, got $aiSpeed" }
        draft.validate()
        dev.validate()
    }
}

/** Booster-draft picker policy. */
@Serializable
data class DraftSettings(
    /** Pick strategy for computer seats during booster drafts. */
    val picker: String = "forge",
    /** Directory containing one subdirectory per set with weights.json(.gz) and card_meta.json. */
    @SerialName("model_dir")
    val modelDir: String = "data/draft-models",
) {
    fun validate() {
        require(picker in setOf("forge", "model")) { "engine.draft.picker must be 'forge' or 'model', got $picker" }
        require(modelDir.isNotBlank()) { "engine.draft.model_dir must not be blank" }
    }
}

/** Development-time diagnostics knobs. */
@Serializable
data class DevSettings(
    /** Crash on unexpected nulls/fallbacks. Off = warn + fallback (production behavior). */
    val strict: Boolean = false,
    /** Crash when auto-pass fires from missing data. Off = pass priority silently. */
    @SerialName("strict_pass")
    val strictPass: Boolean = false,
    /** Enable automatic local prompt handling. */
    @SerialName("copilot_autopush")
    val copilotAutopush: Boolean = false,
    /** Endpoint for local prompt handling. */
    @SerialName("copilot_bridge_url")
    val copilotBridgeUrl: String = "http://127.0.0.1:8092",
) {
    fun validate() {
        require(copilotBridgeUrl.isNotBlank()) { "engine.dev.copilot_bridge_url must not be blank" }
    }
}
