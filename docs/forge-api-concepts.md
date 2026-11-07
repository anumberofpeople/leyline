---
summary: "Key Forge API concepts for engine work: controller callbacks, SpellAbility chains, actions, costs, events, snapshots, and prompts."
read_when:
  - "modifying engine bridge code that calls into Forge"
  - "adding or debugging a Forge PlayerController override"
  - "deciding whether to use a Forge event, snapshot diff, or prompt bridge"
  - "debugging cast, ability, cost, or targeting behaviour"
---
# Forge API Concepts

This is the stable map for how engine uses Forge APIs. It explains the concepts that show up across many classes; per-class rationale stays in KDoc and wire details stay in protocol docs.

## 1. Boundary Shape

`engine` is an adapter around Forge's synchronous rules engine. The client protocol is asynchronous, but Forge expects blocking answers from a `PlayerController`.

There are three main integration surfaces:

- `PlayerController` callbacks for priority, choices, costs, targeting, combat, and ordering.
- Forge `GameEvent`s for facts that happen during engine execution.
- Snapshot reads of `Game`, `Player`, `Card`, zones, stack, and counters when building protocol state.

Do not duplicate game rules in Kotlin. Ask Forge what is legal or what happened, then translate that result.

## 2. PlayerController Callbacks

Forge dispatches interactive work through virtual methods on the player controller. There is no registration table or composition hook that replaces this surface, so overrides live on `bridge/forge/PlayerController`.

Install runtime adapters as each player's base controller. Forge's timestamped
controller layers belong to temporary control effects and restore the base when
they retire. Controlled-player adapters retain the decision owner's lobby factory
and interaction bridges while keeping the subject player explicit. Resolve zone
ownership from the original lobby identity, independently of the decision owner.

Use this decision rule:

- Priority actions and combat declarations go through `GameActionBridge`.
- Engine-initiated choices go through `InteractivePromptBridge`.
- Mulligan decisions go through `MulliganBridge`.
- Yes/no optional-action style prompts use `OptionalActionGate` when the session must observe a pending prompt outside the normal prompt queue.
- Numeric prompts use `NumericInputGate`.
- Unrestricted card-name callbacks use `CardNameInputGate`. The source-bound mandatory
  string interaction resolves only the submitted face through the lazy catalog.
  Unknown or predicate-disallowed names re-prompt with validation feedback under
  one callback deadline. Timeout terminates the interaction without a default name.
  Forge retains its final card-validity check and exact face semantics. The list
  overload retains the separate dungeon-choice route.
  Reconnect republishes a pending name attempt with fresh response identifiers,
  retaining its original future and deadline. Consumed names are not replayed.

`RearrangeTopOfLibrary` with `MayShuffle` preserves its post-order shuffle
decision through `OptionalActionGate`. Decline retains the chosen library order.
Acceptance lets Forge shuffle before any chained draw. A timed-out decision
declines the optional shuffle.

Finite integer choices retain the complete offered order through named
prompt-parameter choices. Their selected indices map back to the original
integers, including sparse domains after previous choices are excluded.
These mandatory choices reject cancellation and default to the first offered
value on timeout. Singleton domains resolve directly.

Local Commander games configure one free first mulligan in `GameBootstrap`; generic two-player Commander rules do not imply one. Forge's `GameRules` supplies the effective policy to both mulligan execution and the advertised `freeMulliganCount`. `MulliganReq.mulliganCount` reports all redraws taken, while the Forge prompt count determines how many cards to bottom. Each London redraw deals seven cards, and keeping requires one bottom choice per paid mulligan.

The engine thread blocks in these calls. Never block on session-owned state from an override; post a pending request and let the session complete the future.

Named `Choices` votes block in `PlayerController.vote` and retain the exact
option handles through the modal choice lifecycle. The compatibility request
carries `Prompt.parameters[choiceKind] = "vote"`, a `NonLocalizedString`, so
embedding clients can label the decision as a vote. Votes do not offer cast
cancellation or undo, and selecting a vote does not mark a casting mode. Optional votes abstain when the interaction times out.
Forge retains vote order, counting, and effect resolution. Entity votes retain
the existing entity-selection path.

Generic and villainous effect choices use `chooseSpellAbilitiesForEffect` to
retain their named `Choices` subabilities through the same modal bridge.
The selected handles return to Forge for effect resolution. These resolution
choices reject cancellation and do not offer undo.

Catalog modal discovery follows `ReplaceWith` references as well as trigger and
execution references. Draw replacements such as Abundance retain their named
Land and Nonland choices through the same resolution-time bridge.
Only named modal APIs contribute option identities; `Choices` validity filters
on effects such as `ChoosePlayer` do not replace a card's modal metadata.

Optional replacement effects retain Forge's selected decision controller through
`confirmReplacementEffect`. The general callback publishes consent through
`OptionalActionGate`, identifying the replacement's source card rather than the
affected player or card. Decline and timeout return false to Forge before the
replacement ability runs. Acceptance continues to any replacement-mode choice.
Commander return and optional battlefield-copy replacements retain their dedicated
source selection and presentation.

`chooseSomeType` retains the offered card-type strings and order in an explicit
CardTypes subset. If any offered card type has no enum mapping, the whole choice
keeps its default policy instead of publishing a partial subset. Optional
unsupported card-type choices return no value. Creature and land subtype
choices retain the SubTypes domain.

Attacker alternatives are committed with the combat declaration. Exert and
Enlist callbacks consume their own selections on the engine thread before
Forge pays optional attack costs. Selecting a normal attack declines those
costs without opening another choice.

Multiple-card effect and reveal-from-hand callbacks use the resolution card-selection route when every candidate has a chooser-visible card identity. Visible resolution choices use a neutral selection instruction. Hidden-library look-and-pick choices keep their dedicated instruction. Choices return the selected original Forge handles. Mandatory singleton choices resolve directly, while incomplete or hidden candidate domains retain their fallback. Complete player-only choices use the compatibility selection runtime with exact player handles.

Flat library searches retain same-owner graveyard cards when Forge offers both
zones. The full library remains private to the chooser, while the graveyard
retains its public visibility. A reveal-backed choice may similarly retain
public graveyard candidates alongside the exact revealed hand. Neither route
admits unrevealed hand cards or invents additional legal candidates.

### Combat damage assignment

The blocking interaction validator accepts any nonnegative split of an assigner's
total damage among its published creature recipients. Recipient order is an
identity/response contract, not a requirement to assign lethal damage before
another creature receives damage. A published defender slot may receive damage
only after every blocking creature receives its lethal threshold. The emitted
`minDamage` thresholds retain deathtouch semantics for that trample check; total
damage, recipient identities, and positive slot maxima remain authoritative.

## 3. SpellAbility Is A Chain

A spell or ability is often an SA chain, not one `SpellAbility`. Wrapper APIs such as `Charm`, `Effect`, `Repeat`, and `RepeatEach` can put meaningful work in sub-abilities that run after choices are made.

Implication: predicates over only the outer SA are suspect:

- `sa.api`
- `sa.hasParam(...)`
- `sa.usesTargeting()`
- direct checks on only `sa.hostCard` or only the first sub-ability

Prefer Forge helpers that walk or resolve the chain:

- `PlaySpellAbility.playAbility(..., mayChooseTargets = true, ...)` for normal spell play.
- `setupTargets()` through Forge's play path for targets that are not already set.
- `PlaySpellAbility.playSpellAbilityNoStack(...)` for no-stack effects that can carry costs; it preserves payment and resolves the sub-ability chain.
- `getRootAbility()` or recursive target checks where Forge exposes them.

When the client supplied targets before the Forge play path starts, `sa.targets.isEmpty()` is the stable gate: if targets are already present, do not ask Forge to choose them again.

## 4. Castable Abilities

Card spells, alternative costs, and zone-cast options should flow through the shared cast helper, not direct `card.getSpells()` scans.

Use `getAllCastableAbilities(card, player)` when you need Forge's castable SA list. It expands additional and alternative costs with `GameActionUtil`, handles special cast states, sets the activating player, and filters by Forge legality.

Library candidates come from Forge's `PlayerZone.getCardsPlayerCanActivate(player)`. That query limits inspection to the current top card and preserves the permission's player identity. The ordinary priority catalog and zone-cast projection retain the supplied ability, including life payment, while Forge still owns land-play limits and cost affordability.

Use `chooseCastAbility(card, player)` when you only need the best current cast candidate.

Use `CastRails` when an action needs protocol fields for a named cast rail such as plot, foretell, disturb, escape, warp, or sneak. The rail table is the shared source for action emission and action submission.

Granted alternate costs retain their Forge keyword definition and granting
static ability. Resolve their protocol identity through
`CardRepository.grantedKeywordAbilityGrpId`: native metadata uses the granting
card's hidden keyword row with the same cost, while the Forge catalog uses a
stable generated keyword-definition identity. The offer, chosen-cast marker,
and sacrifice trigger use that same identity even after the hand-only grant
stops applying.

Continuous keyword grants use known keyword identities first, then the Forge
catalog's generated definition identity. The source card's
`grantedKeywordAbilityIds` supplies names and reminder text to embedding clients.
Keyword grants keep a separate persistent row for each recipient so
recipient and source removal retire the corresponding grant.

Forge catalog localization reads unbound keyword definitions, not live recipient
keywords. Recipient-dependent `ManaCost` grants keep a symbolic reminder and
keyword name, including any miracle cost reduction. They do not advertise a
concrete mana cost. Forge resolves and pays the effective cost only after the
keyword is bound to its recipient during gameplay.

Calculated `N` keyword amounts retain their catalog identity and localize as
symbolic `X` amounts. Forge applies `CalcKeywordN` to the live recipient before
constructing its effective keyword.

Use `getNonManaActivatedAbilities(card, player)` and `getPlayableManaAbilities(card, player)` for ability lookup. Both set the activating player before legality-sensitive checks.

Non-mana activation lookup retains each base ability followed by Forge's
alternative-cost variants. Those variants keep their own complete payment costs
and restrictions, including discard and per-turn conditions. Offers retain the
selected Forge ability rather than reconstructing it from a catalog identity.

Generated activation alternatives share their original catalog definition but
receive distinct per-source `uniqueAbilityId` handles. Snapshots retain
the unfiltered occurrence order for each seat. Shared state projection assigns
the handles after ordinary and granted object rows exist, and passes the same
plan through `ProjectionOutput` to action publication. Each alternative has a
matching source `uniqueAbilities` row. Responses identify the retained ability
without using displayed costs to distinguish siblings.
For a granted variant family without a printed occurrence handle, the same plan
adds backed base and alternative handles using the resolved catalog definition.
Embedded GSM activation actions retain supplied nonzero occurrence handles while
still omitting execution-only fields. This compatibility shape does not allocate
handles for actions that have none.

Activated abilities come from the card's current state. An ability on another
transforming face is neither active nor inactive on the current face. Explicit
special actions such as turning a face-down card face up retain their own lookup.
Intrinsic keyword slots follow the active face's declared keyword order, including
the declaration source of copied states. Repeated intrinsic keywords bind one live
occurrence per declaration slot. Exact definitions bind before any unambiguous
keyword-family fallback, so one declaration cannot consume a sibling's identity.
Repeated catalog rows retain their declaration-local `uniqueAbilityId` only while
the resolved row still matches that declaration. Derived action overrides use
their own catalog lookup rather than aliasing the declaration's object row. The
selected action still executes its retained Forge ability.
Generated triggers without a direct
definition binding use cleanup and pending identities before falling back to
their spawning ability's identity during targeting and stack resolution.

Abilities copied by continuous effects retain `SpellAbility.originalAbility`.
`AbilityRegistry.identitySource` selects that definition's card catalog, while
`forSpellAbility` resolves its original definition identity. Actions and object
ability rows use the same recipient-local unique identity for each live copy.
The copying static remains the source of the grant, and the retained live
ability remains the execution handle.

Spells that can target both stack objects and permanents use Forge’s list-choice
callback. The bridge keeps both candidate kinds explicit and excludes zone
headings from selectable options; the generic choice default must not choose
a target. Cancellation returns through the ordinary spell rollback path.

## 5. Legality Versus Affordability

`SpellAbility.canPlay()` answers legality, not mana affordability.

It checks timing, zone restrictions, activator restrictions, activation limits, phase restrictions, and similar rule gates. A legal ability can still be unpayable. Opponent-turn priority stops therefore require payable mana for non-mana activations as well as casts. See the `priority-cycling` acceptance suite for paired available and unavailable response windows.

For mana affordability, call:

```kotlin
ActionManaCosts.canPayManaCost(sa, player)
```

The bridge helper wraps Forge's mana planner and restores payment-probe state.
Some exotic costs can throw; an ordinary probe exception means "not currently
payable", while strict prompt refusals still propagate.

Normal action building pattern:

1. Set `activatingPlayer`.
2. Check `canPlay()`.
3. Check `ActionManaCosts.canPayManaCost(...)`.
4. Emit active or inactive action with the right cost fields.

Shared priority visibility and player controls are described in
[Priority flow](priority-flow.md). Inactive actions remain presentation data;
they do not require a visible priority decision.

## 6. Mana And Costs

For cast actions, use the effective Forge cost, not printed card data, whenever a live `SpellAbility` exists. Effective cost applies raises and reductions through Forge's `CostAdjustment` pipeline.

Stack-only keyword grants must be present for both displayed cost and affordability.
`ActionManaCosts` evaluates a prospective stack copy with the selected spell ability
and cast-from/player context, then restores the real static state. This lets Forge
apply granted affinity without changing the original hand card or consuming a
payment choice. The view-tracker scope preserves changes already queued by its
caller. Actual casting and payment remain Forge-owned.

For activated abilities, use `SpellAbility.payCosts.totalMana` unless the mechanic has a specific reason to use the cast-cost pipeline.

Phyrexian symbols and `PayLifeInsteadOf:B` permit a player choice for each symbol.
`ComputerUtilMana` normally prefers mana and uses life as a fallback. An explicit
payment plan removes selected life shards before automatic mana payment and
temporarily sets `AIPhyrexianPayment=Never` to preserve selected mana choices.
Life is committed only after the remaining mana succeeds. The original policy
is restored in `finally`; failed payment uses Forge's existing mana rollback.
Commander tax remains generic mana in the effective cost.

For mana color translation, use `ManaColorMapping`. Forge's color bitmasks and the client mana ordinals are not the same domain.

Protection choices retain Forge's offered quality strings and order. Color-only
choices use the static color-choice runtime with the exact allowed CardColors
subset, including colorless value zero when offered. Mixed qualities use named
prompt-parameter choices with exact labels and one-based selection identities;
the selected original string returns to Forge without color-enum conversion.

For land color production:

- Check `manaPart.isComboMana` first.
- Combo sources use `manaPart.getComboColors(sa)`.
- Single-color sources use `manaPart.origProduced`.
- Split produced tokens on spaces, not characters.

Manual mana activation remains a player decision when its source has non-mana
costs. Pay those costs through the active controller's
`getCostDecisionMaker(...)` and `CostPayment.payCost(...)` so sacrifice and
other choices reach the frontend. Resolve the original root with `AbilityUtils.resolve(...)` after payment so deferred mana effects and cleanup run together. Deferred output has no predictive mana selection and cannot be batched. Reserve `AiCostDecision` and
`payComputerCosts(...)` for automatic AI payment paths.

`Special` mana output is also deferred: offer the playable root ability without
predictive color choices and let Forge compute all produced colors at resolution.

## 7. Cost Payment Decisions

Forge cost payment uses visitor-style cost parts. `CostDecision` is the bridge point for interactive non-mana cost decisions.

Use the existing cost-decision path when the engine is paying a cost and asks for cards or permanents. Do not invent a parallel resolver from protocol input to game mutation. The bridge should collect a choice, return Forge objects to the cost visitor, and let Forge perform the payment.

Resolution-time card costs also use Forge's `PlaySpellAbility.payCostPart(...)`
through `payCostToPreventEffect(...)`. An empty or incomplete selection leaves a
positive cost unpaid. Forge then applies `UnlessCost` and `UnlessSwitched` to
determine resolution. A calculated zero-card cost can be paid with an empty
selection. Forge owns that payment result rather than inferring consent from a
non-null selection.

Optional additional costs should be selected before `PlaySpellAbility.playAbility(...)` runs, then fed back into Forge through `GameActionUtil.addOptionalCosts(...)`.
When no casting-time choice is stored, decline optional additional costs. This
also applies to free casts offered while another ability resolves.

## 8. Events Versus Snapshots

Forge events are best for "what caused this?" facts:

- cast, resolve, fizzle, mana payment
- card changed zones and why
- damage source
- attachment lifecycle
- token creation
- controller change
- shuffle, scry, surveil

Snapshots are best for "what is true now?" facts:

- zones and object visibility
- live power/toughness and counters
- continuous effects
- keyword grants
- designations
- persistent annotation baselines

Continuous hand inspection follows `Card.mayPlayerLook` through
`CardSnapshot.mayLookSeatIds`. Hand zones retain their private owner-only default,
while permitted card objects list their owner and current viewers. When permission
ends, the viewer's committed full-state inventory conceals the hand identity
while preserving its slot and retires inaccessible linked faces from Diff updates. Temporary public hand reveals retain
their existing precedence.

Live card type text is captured from Forge's effective `CardType.toString()`
in `CardSnapshot.currentTypeLine` only when the embedding host opts in with
`EngineSettings.includeCurrentTypeLine`. This transient setting defaults to
false, preserving native protocol bytes. Visible active card objects publish it as
the optional `GameObjectInfo.currentTypeLine` string (local extension tag 50001,
outside upstream's low-tag field range). This preserves types absent from GRE
enums without inventing enum values. Absent means unavailable/legacy; present
empty means authoritative typeless. Linked inactive faces retain printed data.
Face-down creature facades and concealed identities omit the field. Viewer Diff
objects replace whole prior objects, so concealment retires any previous text.

Do not infer a cause from snapshots when a Forge event can carry it. Do not store a parallel mutable truth when a snapshot can read the current Forge state.

When an upstream Forge event lacks the payload needed for protocol translation, prefer a small fork-local event enrichment over correlating unrelated events after the fact. The event should carry the Forge object IDs needed by the bridge, not protocol instance IDs.

Imprint display is persistent state, so the snapshot reads the source's current
`imprintedCards` membership after resolution. Projection includes only battlefield
sources and cards still in exile. Rebuilding this feed retires a display when the
relationship ends, without treating every exile within an Imprint operation as
an imprinted card. Imprint rows omit the temporary-return marker.

### Atomic imported places

Puzzle-based state import has one protected pre-settle hook immediately before
its final `checkStateEffects`. Specialized importers may complete current-state
restoration there while the stack is resolving and triggers are suppressed.
Keep the default hook empty so ordinary puzzles retain their existing behavior.

Restore relationships only after object identities and visible types exist.
Restore survival abilities and current characteristics before marked damage,
then combat and phase, and allow one final settled-state check. Do not call
state-based actions or start an advisor from inside a partially imported place.
Verification must be object-aware: creature power/toughness fields do not define
planeswalker or other noncreature characteristics.

## 9. Prompt Semantics

`PromptSemantic` is the planner-facing contract between an engine callback and
a protocol prompt shape. A producer resolves it through `PromptRouteResolver`
when constructing `PromptRequest`; the request stores one immutable
`ResolvedPromptRoute` for the full pending interaction. Its `semantic`
accessor is diagnostic data derived from that route.

Use an explicit semantic when the Forge callback does not uniquely imply the wire type. Avoid relying on fallback classification by message text or by "candidate refs exist" unless the prompt is truly generic targeting.

Adding a semantic means updating the enum and the resolver's exhaustive route
catalog, then the mapping docs that describe the Forge callback to protocol
prompt relation. Classifiers, builders, re-prompts, and response handlers
consume the bound route rather than maintaining semantic lookup tables.

## 10. Identity

Forge card IDs are engine identity. Client instance IDs are protocol identity. Keep them separate.

Snapshot identity reads use the active projection workspace during tentative
compilation. A chosen companion can receive its first instance ID in the initial
publication, before that allocation is committed. Its designation retains the
oldest instance ID, including after later reallocations; discarded compilation
must not publish the tentative allocation.

Event-layer types should carry Forge IDs. Resolve Forge IDs to instance IDs at mapping time, when the current frame has the right allocation and zone-transfer context.

When a prompt records target or source identity while a spell is still on the stack, freeze the instance ID if later resolution would move the source and change the normal lookup result.

## 11. Tests

Use the smallest test surface that exercises the concept:

- Pure mapper or annotation tests for deterministic translation logic.
- Match harness tests when the Forge callback and session bridge both matter.
- Integration tests when phase progression, priority, or multi-step engine state matters.

For board setup, pick the Forge API that matches the test intent:

- `player.playLand(land, true, null)` for testing land play itself.
- `game.action.moveToPlay(...)` for a raw move that should not fire land-play events.
- Harness setup helpers for pre-existing board state where no event should fire.

## Ascend state

Forge owns Ascend acquisition and the lasting `Player.hasBlessing()` flag.
Seat snapshots project that flag as a player designation, independent of the
source permanent. The Ascend badge groups battlefield sources by controller,
reports the current permanent count, and retains its identity across blessing
acquisition. Source references resolve after zone identity changes. The
`ascend` acceptance suite covers threshold acquisition, retention below ten,
and conditional Saproling stats.
