package com.taxiinspector.ui.tariff

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.ui.theme.TaxiInspectorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TariffScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<TariffAction>()

    @Test
    fun typingEachRateReportsItEvenWhileTheKeyboardMovesTheColumn() {
        renderStateful(TariffUiState())

        // Focusing the first field opens the IME, and the column is inset-padded and
        // scrollable, so its contents shift underneath the test. Every interaction
        // re-resolves its own node instead of reusing bounds captured before that.
        composeRule.onNodeWithText("Initial tax").performScrollTo().performTextInput("2.40")
        composeRule.onNodeWithText("Per km rate").performScrollTo().performTextInput("1.20")
        composeRule.onNodeWithText("Per minute car-still rate")
            .performScrollTo()
            .performTextInput("0,35")
        composeRule.onNodeWithText("Save tariff").performScrollTo().performClick()

        assertEquals(
            listOf(
                TariffAction.FieldChanged(TariffField.InitialTax, "2.40"),
                TariffAction.FieldChanged(TariffField.PerKmRate, "1.20"),
                TariffAction.FieldChanged(TariffField.PerMinuteStillRate, "0,35"),
                TariffAction.Save,
            ),
            actions,
        )
    }

    @Test
    fun anInvalidRateExplainsItselfInlineOnItsOwnField() {
        render(
            TariffUiState(
                form = TariffFormState(
                    initialTax = "2.40",
                    perKmRate = "1,2,3",
                    perMinuteStillRate = "0.35",
                    invalidFields = setOf(TariffField.PerKmRate),
                    isPristine = false,
                ),
            ),
        )

        composeRule
            .onNodeWithText(
                "Enter a non-negative number with at most one decimal separator and six decimals.",
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theScreenStatesThatNoCurrencyIsUsedOrConverted() {
        render(TariffUiState())

        composeRule
            .onNodeWithText(
                "Enter all three values in the same unit the taxi uses. Taxi Inspector stores no currency and never converts between currencies.",
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun everyFieldAndSaveIsDisabledWhileARideHoldsALockedTariff() {
        render(
            TariffUiState(
                savedTariff = TariffSummary("2.4", "1.2", "0.35"),
                isLocked = true,
            ),
        )

        composeRule.onNodeWithText("Initial tax").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Per km rate").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Save tariff").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Tariff editing is locked while a ride is active.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theFirstRunHasNoWayToLeaveWithoutSavingARate() {
        render(TariffUiState(), onCancel = null)

        composeRule.onNodeWithText("Enter the taxi's three rates before starting a ride.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun editingAnExistingTariffCanBeCancelled() {
        var cancelled = false
        render(
            TariffUiState(savedTariff = TariffSummary("2.4", "1.2", "0.35")),
            onCancel = { cancelled = true },
        )

        composeRule.onNodeWithText("Rates may be changed only between rides.").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(true, cancelled)
    }

    private fun render(state: TariffUiState, onCancel: (() -> Unit)? = {}) {
        composeRule.setContent {
            TaxiInspectorTheme {
                TariffScreen(state = state, onAction = { actions += it }, onCancel = onCancel)
            }
        }
    }

    /** Feeds edits back into the rendered state, as the real ViewModel does. */
    private fun renderStateful(initial: TariffUiState) {
        composeRule.setContent {
            var state by remember { mutableStateOf(initial) }
            TaxiInspectorTheme {
                TariffScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        if (action is TariffAction.FieldChanged) {
                            state = state.copy(
                                form = state.form.withValue(action.field, action.value),
                            )
                        }
                    },
                    onCancel = null,
                )
            }
        }
    }
}
