# Proto3 enum dedup: rename colliding enum values with scope suffixes.
# Upstream riQQ/MtgaProto has C#-scoped enums; proto3 requires global uniqueness.
# Suffix = first 4 hex chars of the parent enum/message name's hash (arbitrary but stable).
#
# Run: sed -f proto/rename-map.sed proto/upstream/messages.proto > proto/src/main/proto/messages.proto

# --- AnnotationType (collides with other enums) ---
s/\tCounter = 14;/\tCounter_803b = 14;/
s/\tReplacementEffect = 62;/\tReplacementEffect_803b = 62;/

# --- ClientMessageType (collides with ServerMessageType) ---
s/\tConnectReq = 1;/\tConnectReq_097b = 1;/
s/\tCancelActionReq = 5;/\tCancelActionReq_097b = 5;/
s/\tConcedeReq = 7;/\tConcedeReq_097b = 7;/
s/\tForceDrawReq = 9;/\tForceDrawReq_097b = 9;/
s/\tGroupResp = 12;/\tGroupResp_097b = 12;/
s/\tMulliganResp = 13;/\tMulliganResp_097b = 13;/
s/\tOrderResp = 14;/\tOrderResp_097b = 14;/
s/\tPerformActionResp = 15;/\tPerformActionResp_097b = 15;/
s/\tControlReq = 17;/\tControlReq_097b = 17;/
s/\tSetSettingsReq = 20;/\tSetSettingsReq_097b = 20;/
s/\tChooseStartingPlayerResp = 24;/\tChooseStartingPlayerResp_097b = 24;/
s/\tDeclareAttackersResp = 30;/\tDeclareAttackersResp_097b = 30;/
s/\tDeclareBlockersResp = 32;/\tDeclareBlockersResp_097b = 32;/
s/\tAssignDamageResp = 35;/\tAssignDamageResp_097b = 35;/
s/\tSelectTargetsResp = 36;/\tSelectTargetsResp_097b = 36;/
s/\tSelectReplacementResp = 39;/\tSelectReplacementResp_097b = 39;/
s/\tDistributionResp = 42;/\tDistributionResp_097b = 42;/
s/\tNumericInputResp = 43;/\tNumericInputResp_097b = 43;/
s/\tSearchResp = 44;/\tSearchResp_097b = 44;/
s/\tEffectCostResp = 45;/\tEffectCostResp_097b = 45;/
s/\tCastingTimeOptionsResp = 46;/\tCastingTimeOptionsResp_097b = 46;/
s/\tSelectFromGroupsResp = 48;/\tSelectFromGroupsResp_097b = 48;/
s/\tSearchFromGroupsResp = 49;/\tSearchFromGroupsResp_097b = 49;/
s/\tGatherResp = 50;/\tGatherResp_097b = 50;/
s/\tSubmitDeckResp = 54;/\tSubmitDeckResp_097b = 54;/
s/\tPerformAutoTapActionsResp = 56;/\tPerformAutoTapActionsResp_097b = 56;/
s/\tStringInputResp = 57;/\tStringInputResp_097b = 57;/
s/\tSelectCountersResp = 58;/\tSelectCountersResp_097b = 58;/
s/\tPredictionReq = 59;/\tPredictionReq_097b = 59;/

# --- ClientToMatchDoorMessageType (collides with MatchDoorToClientMessageType) ---
s/\tClientToMatchDoorConnectRequest = 1;/\tClientToMatchDoorConnectRequest_f487 = 1;/
s/\tAuthenticateRequest = 4;/\tAuthenticateRequest_f487 = 4;/
s/\tCreateMatchGameRoomRequest = 5;/\tCreateMatchGameRoomRequest_f487 = 5;/
s/\tEchoRequest = 8;/\tEchoRequest_f487 = 8;/

# --- ServerMessageType (collides with ClientMessageType) ---
s/\tGameStateMessage = 1;/\tGameStateMessage_695e = 1;/
s/\tActionsAvailableReq = 2;/\tActionsAvailableReq_695e = 2;/
s/\tChooseStartingPlayerReq = 6;/\tChooseStartingPlayerReq_695e = 6;/
s/\tConnectResp = 7;/\tConnectResp_695e = 7;/
s/\tGetSettingsResp = 9;/\tGetSettingsResp_695e = 9;/
s/\tSetSettingsResp = 10;/\tSetSettingsResp_695e = 10;/
s/\tGroupReq = 11;/\tGroupReq_695e = 11;/
s/\tOrderReq = 17;/\tOrderReq_695e = 17;/
s/\tBinaryGameState = 25;/\tBinaryGameState_695e = 25;/
s/\tDeclareAttackersReq = 26;/\tDeclareAttackersReq_695e = 26;/
s/\tSubmitAttackersResp = 27;/\tSubmitAttackersResp_695e = 27;/
s/\tDeclareBlockersReq = 28;/\tDeclareBlockersReq_695e = 28;/
s/\tSubmitBlockersResp = 29;/\tSubmitBlockersResp_695e = 29;/
s/\tAssignDamageReq = 30;/\tAssignDamageReq_695e = 30;/
s/\tAssignDamageConfirmation = 31;/\tAssignDamageConfirmation_695e = 31;/
s/\tSelectTargetsReq = 34;/\tSelectTargetsReq_695e = 34;/
s/\tSubmitTargetsResp = 35;/\tSubmitTargetsResp_695e = 35;/
s/\tPayCostsReq = 36;/\tPayCostsReq_695e = 36;/
s/\tIntermissionReq = 37;/\tIntermissionReq_695e = 37;/
s/\tDieRollResultsResp = 38;/\tDieRollResultsResp_695e = 38;/
s/\tSelectReplacementReq = 39;/\tSelectReplacementReq_695e = 39;/
s/\tDistributionReq = 42;/\tDistributionReq_695e = 42;/
s/\tNumericInputReq = 43;/\tNumericInputReq_695e = 43;/
s/\tSearchReq = 44;/\tSearchReq_695e = 44;/
s/\tOptionalActionMessage = 45;/\tOptionalActionMessage_695e = 45;/
s/\tCastingTimeOptionsReq = 46;/\tCastingTimeOptionsReq_695e = 46;/
s/\tSelectFromGroupsReq = 48;/\tSelectFromGroupsReq_695e = 48;/
s/\tSearchFromGroupsReq = 49;/\tSearchFromGroupsReq_695e = 49;/
s/\tGatherReq = 50;/\tGatherReq_695e = 50;/
s/\tSubmitDeckReq = 53;/\tSubmitDeckReq_695e = 53;/
s/\tEdictalMessage = 54;/\tEdictalMessage_695e = 54;/
s/\tTimeoutMessage = 55;/\tTimeoutMessage_695e = 55;/
s/\tTimerStateMessage = 56;/\tTimerStateMessage_695e = 56;/
s/\tStringInputReq = 58;/\tStringInputReq_695e = 58;/
s/\tSelectCountersReq = 59;/\tSelectCountersReq_695e = 59;/
s/\tPredictionResp = 60;/\tPredictionResp_695e = 60;/

# --- MatchServiceMessageType (collides with SettingScope) ---
s/\tReference = 3;/\tReference_a14a = 3;/

# --- SettingScope (collides with various) ---
s/\tManaRequirement = 4;/\tManaRequirement_a187 = 4;/
s/\tAutoTapStopsSetting = 7;/\tAutoTapStopsSetting_a187 = 7;/
s/\tManaPaymentStrategyType = 17;/\tManaPaymentStrategyType_a187 = 17;/
s/\tResultSpec = 23;/\tResultSpec_a187 = 23;/
s/\tResultReason = 24;/\tResultReason_a187 = 24;/
s/\tSuperFormat = 25;/\tSuperFormat_a187 = 25;/
s/\tMulliganType = 27;/\tMulliganType_a187 = 27;/
s/\tAutoTapSolution = 33;/\tAutoTapSolution_a187 = 33;/

# --- SelectionValidationType / ResultCode collision ---
s/\tCombatRestrictionViolated = 16;/\tRestrictionViolated_a63a = 16;/
s/\tCombatRequirementViolated = 17;/\tRequirementViolated_a63a = 17;/

# --- ResultCode (collides with SelectionValidationType) — delete duplicates ---
/\tInvalidDamageSource_a63a = 62;/d
/\tNonlethalDamageAssignment = 63;/d
/\tInvalidDamageAssignment = 64;/d
/\tTooManyTargets_a63a = 65;/d
/\tNotEnoughTargets_a63a = 66;/d
/\tIllegalTarget_a63a = 67;/d
/\tTargetRestrictionViolated = 68;/d

# --- SelectionValidationType (rename to un-suffixed since ResultCode ones are deleted) ---
s/\tInvalidDamageSource_a500 = 9;/\tInvalidDamageSource = 9;/
s/\tTooManyTargets_a500 = 12;/\tTooManyTargets = 12;/
s/\tNotEnoughTargets_a500 = 13;/\tNotEnoughTargets = 13;/
s/\tIllegalTarget_a500 = 14;/\tIllegalTarget = 14;/
s/\tRestrictionViolated = 15;/\tRestrictionViolated_a500 = 15;/
s/\tRequirementViolated = 16;/\tRequirementViolated_a500 = 16;/

# --- AbilityType collision ---
s/\tTriggeredAbility = 4;/\tTriggeredAbility_c799 = 4;/

# --- SubType placeholders (upstream has names we don't recognize) ---
s/\tMutagen = 469;/\tPlaceholderSubType469 = 469;/
s/\tUtrom = 470;/\tPlaceholderSubType470 = 470;/

# --- Keyword ability collision ---
s/\tSneak = 55;/\tPlaceholder55 = 55;/

# --- Delete entries not in our version ---
/PhaseOrStepTransitionSkipNext = 57;/d
/MultiplayerNumericAid = 114;/d
/Phct212 = 212;/d
/PlaceholderSubType482 = 482;/d
/PlaceholderSubType483 = 483;/d
/PlaceholderSubType484 = 484;/d
/PlaceholderSubType485 = 485;/d
/PlaceholderSubType486 = 486;/d
/PlaceholderSubType487 = 487;/d
/PlaceholderSubType488 = 488;/d

# --- TurnStep collision ---
s/\tStop = 7;/\tStop_2117 = 7;/

# Local embedding extension: high tag avoids upstream low-tag field collisions.
# Presence distinguishes unavailable/legacy data from an authoritative empty type line.
/^message GameObjectInfo {/a\
\toptional string currentTypeLine = 50001;
