package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.data.CardData
import leyline.game.data.CardProtoBuilder
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.FaceDownKind
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.SeatSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.ProjectionState
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class StateProjectionCompilerVisibilityTest :
    FunSpec({
        tags(UnitTag)

        test("hand inspection revocation clears previously visible current type text in Diff") {
            val cardId = ForgeCardId(10)
            val visible =
                CardSnapshot(
                    cardId,
                    "Private card",
                    101,
                    SeatId(1),
                    SeatId(1),
                    mayLookSeatIds = setOf(SeatId(2)),
                    currentTypeLine = "Creature - Secret",
                )

            fun snapshot(
                id: Int,
                card: CardSnapshot,
            ) = GsmSnapshot.forTest(
                matchId = "type-visibility",
                gameStateId = id,
                seats = listOf(SeatSnapshot(SeatId(1), 20, 20, 7), SeatSnapshot(SeatId(2), 20, 20, 7)),
                objects = mapOf(cardId to card),
                zones =
                    mapOf(
                        ZoneIds.P1_HAND to
                            ZoneSnapshot(
                                ZoneIds.P1_HAND,
                                ZoneType.Hand,
                                SeatId(1),
                                Visibility.Private,
                                listOf(cardId),
                            ),
                    ),
            )
            val before = snapshot(1, visible)
            val shown =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(before).copy(viewingSeatId = 2),
                    ProjectionState.initial(),
                )
            val iid =
                shown.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(cardId)
                    .value
            shown.gsm.gameObjectsList
                .single { it.instanceId == iid }
                .currentTypeLine shouldBe "Creature - Secret"
            val after = snapshot(2, visible.copy(mayLookSeatIds = emptySet()))
            val concealed =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(after, before).copy(viewingSeatId = 2),
                    shown.transition.nextState,
                )
            val hidden = concealed.gsm.gameObjectsList.single { it.instanceId == iid }
            concealed.gsm.type shouldBe GameStateType.Diff
            hidden.visibility shouldBe Visibility.Hidden
            hidden.grpId shouldBe 0
            hidden.hasCurrentTypeLine() shouldBe false
        }

        test("prompt source identity survives admission without a synthetic stack object") {
            val source = ForgeCardId(42)
            val beforeEntry = GsmSnapshot.forTest()
            val pending =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(beforeEntry),
                    ProjectionState.initial(),
                    ViewerProjectionIntent.of(privateCardPrompt = PrivateCardPromptProjection.of(emptyList(), source)),
                )
            val sourceId =
                pending.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(source)
            assertSoftly {
                pending.projectionSnapshot.stack.entries shouldBe emptyList()
                pending.gsm.gameObjectsList.any { it.instanceId == sourceId.value } shouldBe false
            }
            val entered =
                GsmSnapshot.forTest(
                    objects = mapOf(source to CardSnapshot(source, "Entering Token", 9001, SeatId(1), SeatId(1))),
                    zones =
                        mapOf(
                            ZoneIds.BATTLEFIELD to
                                ZoneSnapshot(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, null, Visibility.Public, listOf(source)),
                        ),
                )
            val admitted =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(entered, beforeEntry),
                    pending.transition.nextState,
                )
            assertSoftly {
                admitted.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(source) shouldBe sourceId
                admitted.gsm.gameObjectsList
                    .single { it.instanceId == sourceId.value }
                    .zoneId shouldBe ZoneIds.BATTLEFIELD
                admitted.projectionSnapshot.stack.entries shouldBe emptyList()
            }
        }

        test("foretold exile keeps its owner identity and exposes only a public card back to the opponent") {
            val foretoldId = ForgeCardId(10)
            val publicId = ForgeCardId(20)
            val handId = ForgeCardId(30)
            val snapshot =
                GsmSnapshot.forTest(
                    matchId = "foretell",
                    gameStateId = 1,
                    seats = listOf(SeatSnapshot(SeatId(1), 20, 20, 7), SeatSnapshot(SeatId(2), 20, 20, 7)),
                    objects =
                        mapOf(
                            foretoldId to
                                CardSnapshot(
                                    foretoldId,
                                    "Foretold card",
                                    101,
                                    SeatId(1),
                                    SeatId(2),
                                    isForetold = true,
                                    currentTypeLine = "Sorcery - Secret",
                                ),
                            publicId to CardSnapshot(publicId, "Public exile card", 202, SeatId(2), SeatId(2)),
                            handId to CardSnapshot(handId, "Private hand card", 303, SeatId(1), SeatId(1)),
                        ),
                    zones =
                        mapOf(
                            ZoneIds.EXILE to
                                ZoneSnapshot(
                                    ZoneIds.EXILE,
                                    ZoneType.Exile,
                                    null,
                                    Visibility.Public,
                                    listOf(foretoldId, publicId),
                                ),
                            ZoneIds.P1_HAND to ZoneSnapshot(ZoneIds.P1_HAND, ZoneType.Hand, SeatId(1), Visibility.Private, listOf(handId)),
                        ),
                )
            val projected =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    ProjectionState.initial(),
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(snapshot).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(compilerInput(snapshot).copy(viewingSeatId = 2)),
                    ),
                )
            val foretoldIid =
                projected.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(foretoldId)
                    .value
            val owner = projected.viewers[0].result.gsm
            val opponent = projected.viewers[1].result.gsm
            val card = owner.gameObjectsList.single { it.instanceId == foretoldIid }
            assertSoftly {
                card.currentTypeLine shouldBe "Sorcery - Secret"
                card.grpId shouldBe 101
                card.overlayGrpId shouldBe 3
                card.isFacedown shouldBe true
                card.visibility shouldBe Visibility.Private
                card.viewersList shouldBe listOf(1)
                card.ownerSeatId shouldBe 1
                card.controllerSeatId shouldBe 2
                val opponentCard = opponent.gameObjectsList.single { it.instanceId == foretoldIid }
                opponentCard.hasCurrentTypeLine() shouldBe false
                opponentCard.grpId shouldBe 3
                opponentCard.overlayGrpId shouldBe 3
                opponentCard.isFacedown shouldBe true
                opponentCard.visibility shouldBe Visibility.Public
                opponentCard.ownerSeatId shouldBe 1
                opponentCard.controllerSeatId shouldBe 2
                opponentCard.viewersList shouldBe listOf(1)
                opponentCard.name shouldBe 0
                opponentCard.cardTypesList shouldBe emptyList()
                opponentCard.uniqueAbilitiesList shouldBe emptyList()
                opponentCard.objectSourceGrpId shouldBe 0
                opponent.gameObjectsList.map { it.grpId } shouldContainExactly listOf(3, 202)
                owner.gameObjectsList.single { it.grpId == 303 }.visibility shouldBe Visibility.Private
                owner.zonesList.single { it.zoneId == ZoneIds.EXILE }.objectInstanceIdsList shouldBe
                    opponent.zonesList.single { it.zoneId == ZoneIds.EXILE }.objectInstanceIdsList
                owner.gameObjectsList.single { it.grpId == 202 }.visibility shouldBe Visibility.Public
                snapshot.objects.getValue(foretoldId).grpId shouldBe 101
            }
            val handSnapshot =
                GsmSnapshot.forTest(
                    matchId = snapshot.matchId,
                    gameStateId = 1,
                    seats = snapshot.seats,
                    objects = snapshot.objects + (foretoldId to snapshot.objects.getValue(foretoldId).copy(isForetold = false)),
                    zones =
                        snapshot.zones +
                            mapOf(
                                ZoneIds.EXILE to snapshot.zones.getValue(ZoneIds.EXILE).copy(contents = listOf(publicId)),
                                ZoneIds.P1_HAND to snapshot.zones.getValue(ZoneIds.P1_HAND).copy(contents = listOf(foretoldId, handId)),
                            ),
                )
            val hand =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    ProjectionState.initial(),
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(handSnapshot).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(compilerInput(handSnapshot).copy(viewingSeatId = 2)),
                    ),
                )
            val preparedSnapshot = snapshot.withGameStateId(2)
            val actions = ActionsAvailableReq.newBuilder().addActions(Action.newBuilder().setActionType(ActionType.Pass)).build()
            val prepared =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    hand.transition.nextState,
                    listOf(
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(preparedSnapshot, handSnapshot).copy(viewingSeatId = 1),
                            actions = actions,
                        ),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(preparedSnapshot, handSnapshot).copy(viewingSeatId = 2),
                            actions = actions,
                        ),
                    ),
                )
            val preparedIid =
                prepared.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(foretoldId)
                    .value
            for (index in prepared.viewers.indices) {
                val diff = prepared.viewers[index].result.gsm
                val expected =
                    projected.viewers[index]
                        .result.gsm.gameObjectsList
                        .single { it.instanceId == foretoldIid }
                assertSoftly {
                    diff.type shouldBe GameStateType.Diff
                    diff.gameObjectsList
                        .single { it.instanceId == preparedIid }
                        .toBuilder()
                        .clearInstanceId()
                        .build() shouldBe
                        expected.toBuilder().clearInstanceId().build()
                    diff.actionsList
                        .single()
                        .action.actionType shouldBe ActionType.Pass
                    diff.actionsList.single().seatId shouldBe index + 1
                }
            }
            val castSnapshot =
                GsmSnapshot.forTest(
                    matchId = snapshot.matchId,
                    gameStateId = 3,
                    seats = snapshot.seats,
                    objects = snapshot.objects + (foretoldId to snapshot.objects.getValue(foretoldId).copy(isForetold = false)),
                    zones =
                        snapshot.zones +
                            mapOf(
                                ZoneIds.EXILE to snapshot.zones.getValue(ZoneIds.EXILE).copy(contents = listOf(publicId)),
                                ZoneIds.STACK to ZoneSnapshot(ZoneIds.STACK, ZoneType.Stack, null, Visibility.Public, listOf(foretoldId)),
                            ),
                )
            val cast =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    prepared.transition.nextState,
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(castSnapshot, preparedSnapshot).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(compilerInput(castSnapshot, preparedSnapshot).copy(viewingSeatId = 2)),
                    ),
                )
            val stackIid =
                cast.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(foretoldId)
                    .value
            for (viewer in cast.viewers) {
                val stackCard =
                    viewer.result.gsm.gameObjectsList
                        .single { it.instanceId == stackIid }
                assertSoftly {
                    viewer.result.gsm.type shouldBe GameStateType.Diff
                    stackCard.zoneId shouldBe ZoneIds.STACK
                    stackCard.grpId shouldBe 101
                    stackCard.overlayGrpId shouldBe 101
                    stackCard.visibility shouldBe Visibility.Public
                    stackCard.isFacedown shouldBe false
                    stackCard.viewersList shouldBe emptyList()
                    viewer.result.gsm.zonesList
                        .single { it.zoneId == ZoneIds.EXILE }
                        .objectInstanceIdsList shouldBe
                        listOf(
                            projected.transition.nextState.identities.forgeIdToInstanceId
                                .getValue(publicId)
                                .value,
                        )
                }
            }
        }

        for (kind in FaceDownKind.entries) {
            val zones = if (kind == FaceDownKind.Disguise) listOf(ZoneType.Stack, ZoneType.Battlefield) else listOf(ZoneType.Battlefield)
            for (zoneType in zones) {
                test("$kind in $zoneType preserves its public face-down object through Full and Diff") {
                    val cards = InMemoryCardRepository()
                    cards.registerData(
                        CardData(
                            grpId = 101,
                            titleId = 101,
                            power = "5",
                            toughness = "5",
                            colors = emptyList(),
                            types = listOf(CardType.Creature.number),
                            subtypes = emptyList(),
                            supertypes = emptyList(),
                            abilityIds = listOf(9001 to 0),
                            manaCost = emptyList(),
                        ),
                        "Hidden creature",
                    )
                    val environment =
                        compilerEnvironment().copy(
                            cardProto = CardProtoBuilder(cards),
                            cardReferences = ProjectionCardReferences(cards),
                        )
                    val cardId = ForgeCardId(10)
                    val zoneId = if (zoneType == ZoneType.Stack) ZoneIds.STACK else ZoneIds.BATTLEFIELD
                    val printed =
                        CardSnapshot(
                            cardId,
                            "Hidden creature",
                            101,
                            SeatId(1),
                            SeatId(2),
                            currentTypeLine = "Creature - Secret",
                            isOnBattlefield =
                                zoneType == ZoneType.Battlefield,
                        )

                    fun snapshot(
                        id: Int,
                        card: CardSnapshot,
                    ) = GsmSnapshot.forTest(
                        matchId = "face-down",
                        gameStateId = id,
                        seats = listOf(SeatSnapshot(SeatId(1), 20, 20, 7), SeatSnapshot(SeatId(2), 20, 20, 7)),
                        objects = mapOf(cardId to card),
                        zones = mapOf(zoneId to ZoneSnapshot(zoneId, zoneType, null, Visibility.Public, listOf(cardId))),
                    )

                    fun project(
                        current: GsmSnapshot,
                        prior: ProjectionState,
                        previous: GsmSnapshot? = null,
                    ) = StateProjectionCompiler.compileViewers(
                        environment,
                        prior,
                        (1..2).map { seat ->
                            StateProjectionCompiler.ViewerInput(compilerInput(current, previous).copy(viewingSeatId = seat))
                        },
                    )

                    val up = snapshot(1, printed)
                    val faceUp = project(up, ProjectionState.initial())
                    val down = snapshot(2, printed.copy(faceDownKind = kind))
                    val faceDown = project(down, faceUp.transition.nextState, up)
                    val fullDown = project(down, ProjectionState.initial())
                    for (result in listOf(faceDown, fullDown)) {
                        val iid =
                            result.transition.nextState.identities.forgeIdToInstanceId
                                .getValue(cardId)
                                .value
                        for (viewer in result.viewers) {
                            val obj =
                                viewer.result.gsm.gameObjectsList
                                    .single { it.instanceId == iid }
                            assertSoftly {
                                obj.grpId shouldBe if (viewer.seatId == SeatId(2)) 101 else 3
                                obj.visibility shouldBe if (viewer.seatId == SeatId(2)) Visibility.Private else Visibility.Public
                                obj.viewersList shouldBe listOf(2)
                                obj.ownerSeatId shouldBe 1
                                obj.controllerSeatId shouldBe 2
                                obj.zoneId shouldBe zoneId
                                obj.overlayGrpId shouldBe 3
                                obj.isFacedown shouldBe true
                                obj.power.value shouldBe 2
                                obj.toughness.value shouldBe 2
                                obj.hasCurrentTypeLine() shouldBe false
                                obj.name shouldBe 0
                                obj.subtypesList shouldBe emptyList()
                                obj.colorList shouldBe emptyList()
                                obj.uniqueAbilitiesList.map { it.grpId } shouldBe
                                    if (kind == FaceDownKind.ManifestDread) emptyList() else listOf(KeywordAbilityIds.WARD_TWO)
                            }
                        }
                    }
                    val restored = snapshot(3, printed)
                    val faceUpAgain = project(restored, faceDown.transition.nextState, down)
                    val iid =
                        faceDown.transition.nextState.identities.forgeIdToInstanceId
                            .getValue(cardId)
                            .value
                    for (viewer in faceUpAgain.viewers) {
                        val obj =
                            viewer.result.gsm.gameObjectsList
                                .single { it.instanceId == iid }
                        assertSoftly {
                            viewer.result.gsm.type shouldBe GameStateType.Diff
                            obj.grpId shouldBe 101
                            obj.overlayGrpId shouldBe 101
                            obj.currentTypeLine shouldBe "Creature - Secret"
                            obj.isFacedown shouldBe false
                            obj.visibility shouldBe Visibility.Public
                            obj.viewersList shouldBe emptyList()
                            obj.uniqueAbilitiesList.map { it.grpId } shouldBe listOf(9001)
                        }
                    }
                }
            }
        }
    })
