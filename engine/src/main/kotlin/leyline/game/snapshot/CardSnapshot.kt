package leyline.game.snapshot

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

/** One position in the full, unfiltered activation domain for a particular seat. */
data class ActivatedAbilityOccurrence(
    val abilityIndex: Int,
    val definitionId: Int,
    val abilityGrpId: Int,
    val uniqueAbilityId: Int?,
    val isAlternative: Boolean,
)

/**
 * Immutable snapshot of one card's observable state. Fields grow as mappers migrate
 * — identity, zone, object attributes (p/t, tapped, counters, combat state), action
 * flags (abilities, cost materials), and persistent-annotation inputs
 * (`isOnAdventure`, `endOfTurnLeavePlay`).
 */
data class CardSnapshot(
    val forgeCardId: ForgeCardId,
    val name: String,
    val grpId: Int,
    val owner: SeatId,
    val controller: SeatId,
    /** Seats that Forge currently allows to inspect this card in a hidden zone. */
    val mayLookSeatIds: Set<SeatId> = emptySet(),
    /** True when this engine object is a client-visible card, token, or emblem. */
    val isProjectable: Boolean = true,
    /** Public Command-zone emblem identity, separate from internal effect helpers. */
    val emblem: EmblemSnapshot? = null,
    /** Implicit client mana-ability grpId for a basic land; 0 otherwise. */
    val basicLandManaAbilityGrpId: Int = 0,
    /** Forge identity of the effect source observed at snapshot capture. */
    val effectSourceForgeCardId: ForgeCardId? = null,
    /** Exact live Paradigm keyword membership observed at snapshot capture. */
    val hasParadigmKeyword: Boolean = false,
    /** Cards currently imprinted by this battlefield permanent. */
    val imprintedCardIds: List<ForgeCardId> = emptyList(),
    // --- ActionMapper shape flags ---
    /** True when Forge considers this card a land (type.isLand). */
    val isLand: Boolean = false,
    /** True when this is an adventure card (has a Secondary state with its own spell ability). */
    val isAdventureCard: Boolean = false,
    /** True when this is an Omen card (Secondary state with subtype "Omen"). */
    val isOmenCard: Boolean = false,
    /** True when the card has the `Room` subtype (split-room enchantment with two doors). */
    val isRoom: Boolean = false,
    val isSaga: Boolean = false,
    /** True when the card has at least one mana ability (used for ActivateMana action shape). */
    val hasManaAbilities: Boolean = false,
    /** ManaColor enum numbers this battlefield source can produce. */
    val manaProductionColors: List<Int> = emptyList(),
    /** Chosen creature/card type string from Forge, when a battlefield permanent stores one. */
    val chosenType: String? = null,
    /** Chosen color ids in Arena's static color domain. */
    val chosenColorIds: List<Int> = emptyList(),
    /**
     * True when the card has at least one non-mana activated ability.
     * Needed to know whether to iterate spellAbilities during action enumeration.
     */
    val hasNonManaActivatedAbilities: Boolean = false,
    val activationOccurrences: Map<SeatId, List<ActivatedAbilityOccurrence>> = emptyMap(),
    // --- ObjectMapper live state ---
    /** True when the card is in the Battlefield zone. */
    val isOnBattlefield: Boolean = false,
    /** Null means this host or identity does not supply effective Flying membership. */
    val effectiveFlying: Boolean? = null,
    /** Typed intrinsic/component rows and the explicit grant identity covered by that observation. */
    val flyingAbilityGrpIds: Set<Int> = emptySet(),
    /** Phasing status preserves battlefield membership and object identity. */
    val isPhasedOut: Boolean = false,
    /** Live net power from Forge (continuous effects/counters). Non-null only for creatures. */
    val netPower: Int? = null,
    /** Live net toughness from Forge (continuous effects/counters). Non-null only for creatures. */
    val netToughness: Int? = null,
    /** Whether the permanent is tapped (battlefield only; false off-battlefield). */
    val tapped: Boolean = false,
    /** Whether the creature has summoning sickness (battlefield creatures only). */
    val hasSickness: Boolean = false,
    /** Combat damage marked on this creature. */
    val damage: Int = 0,
    /** Current loyalty counter value for planeswalkers. */
    val currentLoyalty: Int = 0,
    /** True when Forge considers this a token. Used for GameObjectType selection. */
    val isToken: Boolean = false,
    /** True when this is a copy token or copied spell (Forge copy identity). */
    val isCopyToken: Boolean = false,
    /** Source-card grpId for tokens created by a stack ability. */
    val tokenSourceCardGrpId: Int = 0,
    /** Stack ability iid that created this token. */
    val tokenParentAbilityInstanceId: Int = 0,
    /**
     * Pre-resolved client instanceId of the Aura/Equipment carrier permanent
     * this card is attached to, populated at snapshot time. Null when not
     * attached. Surfaces on [BoundCard.parentLinkage] as
     * [ParentLinkage.AttachedTo]; `ObjectMapper` reads from there.
     */
    val attachedToInstanceId: Int? = null,
    /** Protector of a battle currently on the battlefield. */
    val battleProtectorSeatId: SeatId? = null,
    /**
     * For [PreparedRole.Copy], the pre-resolved client instanceId of the live
     * battlefield Source. Null when no source is linked (mid-cast or unprepared).
     * Lets [leyline.game.mapping.ObjectMapper.buildFromSnapshot] set the cast-from-
     * exile parent linkage without bridge access.
     */
    val preparedCopySourceInstanceId: Int? = null,
    /**
     * Live core card types from Forge (continuous effects can add/remove types).
     * Stored as proto [CardType] ordinal integers for easy comparison.
     */
    val liveCardTypeNumbers: List<Int> = emptyList(),
    /** Full effective Forge type text; null is unavailable, empty is authoritative typeless. */
    val currentTypeLine: String? = null,
    /** True when the card is double-faced (has an Backside/Original alternate state). */
    val isDoubleFaced: Boolean = false,
    /**
     * Other face grpId for DFC cards; 0 for non-DFC.
     * Pre-resolved so [ObjectMapper.buildFromSnapshot] doesn't need CardRepository.
     */
    val othersideGrpId: Int = 0,
    /** Forge CardStateName of the current face, used to resolve the correct face in DFC logic. */
    val currentStateNameIsBackside: Boolean = false,
    /**
     * Combat role for battlefield creatures; null for non-creatures or non-combat cards.
     */
    val combatRole: CombatRole? = null,
    /**
     * True when this card is currently in Exile and was exiled "on Adventure"
     * (Forge's `card.isOnAdventure`). Drives Qualification pAnn for the
     * cast-from-exile eligibility marker.
     */
    val isOnAdventure: Boolean = false,
    /**
     * True when the card is a token with the `EndOfTurnLeavePlay` SVar set
     * (Forge's `card.isToken && card.hasSVar("EndOfTurnLeavePlay")`). Drives
     * TemporaryPermanent pAnn so the client renders EOT-sacrifice tokens.
     */
    val endOfTurnLeavePlay: Boolean = false,
    /** True while a spell or permanent retains a paid Evoke cast. */
    val evokePaid: Boolean = false,
    /** Ability identity retained by the paid keyword grant after its source-zone restriction ends. */
    val grantedCastAbilityGrpId: Int? = null,
    /**
     * Role this card plays in the Prepared mechanic. [PreparedRole.None] for the
     * vast majority of cards. [PreparedRole.Source] for a battlefield creature
     * with an active prepared-spell exile copy. [PreparedRole.Copy] for the copy
     * itself. Drives the `Prepared` Designation pAnn, exile-copy projection, and
     * the by-name grpId fallback that bypasses the engine-spawned-token path.
     */
    val preparedRole: PreparedRole = PreparedRole.None,
    /**
     * Role this card plays in the Plot mechanic. [PlottedRole.None] for the vast
     * majority of cards. [PlottedRole.Plotted] for a card sitting in exile face-up
     * with the plotted state, awaiting a sorcery-speed cast on a later turn.
     * Drives the `Plotted` Designation pAnn (DesignationType=18). Plot has no
     * Source/Copy split — the card itself is in exile, not a copy.
     */
    val plottedRole: PlottedRole = PlottedRole.None,
    /**
     * True when this is a battlefield mount with Forge's saddled state active
     * for the turn. Drives the `Saddled` Designation pAnn (DesignationType=17).
     */
    val isSaddled: Boolean = false,
    /** True when this battlefield permanent is suspected. Drives DesignationType=16. */
    val isSuspected: Boolean = false,
    /** True when a Case permanent is solved. Drives DesignationType=15. */
    val isSolved: Boolean = false,
    /**
     * True when the card is currently in Exile with the foretold state.
     * Drives the face-down exile rendering (FaceDown +
     * SuppressedPowerAndToughness annotations + visibility=Private). Foretell
     * is single-state — None or Foretold — so a Boolean is enough; no Role
     * hierarchy needed (compare PlottedRole / PreparedRole which carry
     * additional structural variants).
     */
    val isForetold: Boolean = false,
    /** Supported face-down battlefield/stack mechanic, when present. */
    val faceDownKind: FaceDownKind? = null,
    /** True when this card is one of its owner's commanders. */
    val isCommander: Boolean = false,
    /** Commander tax currently exposed to the client, in generic mana. */
    val commanderTax: Int = 0,
    /** Commander color identity as proto ManaColor enum numbers. */
    val commanderColorIdentity: List<Int> = emptyList(),
    /**
     * True when this is a battlefield Room card with the LeftSplit door
     * unlocked. Drives the persistent `Designation{type=19}` (LeftUnlocked)
     * pAnn and the transient gain/lose pair as the door state changes.
     *
     * Filtered to `isOnBattlefield` at construction (Forge keeps the
     * `unlockedRooms` set on retired stack/limbo card states alongside the
     * live battlefield permanent — same trap as Prepared / Plotted).
     */
    val isLeftDoorUnlocked: Boolean = false,
    /**
     * True when this is a battlefield Room card with the RightSplit door
     * unlocked. See [isLeftDoorUnlocked] for the lifecycle invariant.
     */
    val isRightDoorUnlocked: Boolean = false,
    /** Client iid of the battlefield permanent this component is merged into. */
    val mergedToInstanceId: Int? = null,
    /** Ability rows contributed by non-top merged components to this visible permanent. */
    val mergedComponentAbilityGrpIds: List<Int> = emptyList(),
    /** Origin card grpIds parallel to [mergedComponentAbilityGrpIds]. */
    val mergedComponentAbilityOriginalCardGrpIds: List<Int> = emptyList(),
    /** True for the visible battlefield object representing a merged permanent. */
    val isMergedPermanent: Boolean = false,
    /** True when this merged component is currently the top component. */
    val isTopMergedComponent: Boolean = false,
)

data class EarthbendProjection(
    val sourceCardGrpId: Int,
    val hasteAbilityGrpId: Int,
    val uniqueAbilityId: Int,
)

/**
 * Role of a card in the Prepared mechanic — None, Source, or Copy.
 *
 * ## Why a sealed Role hierarchy and not 4 nullable fields
 *
 * The first wiring iteration spread Prepared state across four optional
 * fields on `CardSnapshot`: `isPrepared`, `preparedCopyForgeCardId`,
 * `preparedSourceForgeCardId`, `isPreparedCopy`. Consumers had to mentally
 * AND them to figure out what was true: `isPrepared && copyId != null && !isPreparedCopy`
 * meant Source-with-live-copy. That pattern smelled — the four fields had
 * structural relationships (a Source has a non-null copyId, a Copy has at
 * most a sourceId, None has neither) that a sealed type can express directly.
 *
 * Replacing them with `PreparedRole = None | Source | Copy` collapses the
 * conjunctions into a `when` over the type, makes invalid combinations
 * unrepresentable, and keeps the partner's [ForgeCardId] inline so consumers
 * never need to re-read Forge state to recover the linkage.
 *
 * ## Generalization
 *
 * Card-state designations follow this shape — Saddled, Plotted, Day/Night,
 * Door states, and Commander all have a "card has state X" question with
 * structural variants (e.g. Saddled-by-whom). When implementing the next
 * one, prefer a sealed Role hierarchy over a bag of booleans on
 * `CardSnapshot`.
 */
sealed interface PreparedRole {
    /** Card is not involved in the Prepared mechanic. */
    data object None : PreparedRole

    /**
     * Card is a battlefield permanent with an active prepared-spell exile copy.
     *
     * Set only on cards observed `isOnBattlefield && isPrepared`. Forge keeps
     * `isPrepared==true` on retired stack/limbo card states even after the
     * battlefield permanent inherits the flag — without the battlefield filter
     * we'd anchor a Designation pAnn on a stale iid and the wire would
     * reference a card that doesn't exist. The role's construction site in
     * [SnapshotCapture] enforces the filter.
     *
     * @property copyForgeCardId Forge id of the spell-face copy in exile.
     */
    data class Source(
        val copyForgeCardId: ForgeCardId,
    ) : PreparedRole

    /**
     * Card is a prepared-spell exile copy spawned by a battlefield Source.
     *
     * Detected by face state (`gamePieceType == TOKEN && currentState ==
     * PreparedSpell`) — see `PreparedSpell.isCopy`. State-based detection
     * survives the Forge `Card.id` reallocation that happens when the copy
     * moves Exile → Stack on cast.
     *
     * @property sourceForgeCardId Forge id of the live battlefield Source. Null
     *   when the copy is mid-cast (Forge has reallocated its `Card.id` and the
     *   Source's `prepared.firstRemembered` no longer points at this Card object)
     *   or the Source has already been unprepared. The copy still projects as a
     *   `GameObjectType_Card` either way; only `parentId` is omitted when null.
     */
    data class Copy(
        val sourceForgeCardId: ForgeCardId?,
    ) : PreparedRole
}

/**
 * Role of a card in the Plot mechanic — None or Plotted.
 *
 * Single-state (`None | Plotted`) because Plot has no copy: the plotted card
 * itself sits in exile and is later cast from there. Compare [PreparedRole]
 * which is two-state (`Source | Copy`) because Prepared spawns an exile copy
 * paired with a battlefield source.
 *
 * The role is populated only when [Plotted.isPlotted] returns true (`isPlotted &&
 * isInZone(Exile)`), so consumers don't need to re-check the zone or the flag.
 */
sealed interface PlottedRole {
    /** Card is not plotted. */
    data object None : PlottedRole

    /** Card sits in exile with the plotted state, castable at sorcery speed on a later turn. */
    data object Plotted : PlottedRole
}

/**
 * Per-card combat role, populated from Forge [forge.game.combat.Combat].
 * Sealed so ObjectMapper can exhaustively pattern-match without a default branch.
 */
sealed interface CombatRole {
    /**
     * This card is declared as an attacker.
     *
     * @param targetInstanceId Arena instance ID of the defending player or planeswalker/battle.
     *   0 means the target couldn't be resolved (should be treated as "no info").
     * @param isBlocked Whether the attacking band is blocked (true), unblocked (false), or unknown (null).
     */
    data class Attacker(
        val targetInstanceId: Int,
        val isBlocked: Boolean?,
    ) : CombatRole

    /**
     * This card is declared as a blocker.
     *
     * @param attackerInstanceIds Arena instance IDs of the attackers this card is blocking.
     */
    data class Blocker(
        val attackerInstanceIds: List<Int>,
    ) : CombatRole
}
