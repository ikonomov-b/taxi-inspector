package com.taxiinspector.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.ui.theme.TaxiInspectorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<RideDetailAction>()

    @Test
    fun detailShowsFinalValuesStatusTimestampAndLockedTariff() {
        render(detailState())

        composeRule.onNodeWithText("Final total").assertIsDisplayed()
        composeRule.onNodeWithText("6.45").assertIsDisplayed()
        composeRule.onNodeWithText("Completed").assertIsDisplayed()
        composeRule.onNodeWithText("Sep 4, 2026, 2:30 PM").assertIsDisplayed()
        composeRule.onNodeWithText("2.50 km").assertIsDisplayed()
        composeRule.onNodeWithText("03:18").assertIsDisplayed()
        composeRule.onNodeWithText("1:04:09").assertIsDisplayed()
        composeRule.onNodeWithText("Locked tariff").assertIsDisplayed()
        composeRule.onNodeWithText("2.4").assertIsDisplayed()
        composeRule.onNodeWithText("1.2").assertIsDisplayed()
        composeRule.onNodeWithText("0.35").assertIsDisplayed()
    }

    @Test
    fun deletionMustBeRequestedAndThenConfirmed() {
        render(detailState())

        composeRule.onNodeWithText("Delete saved ride").performScrollTo().performClick()
        assertEquals(listOf(RideDetailAction.DeleteRequested), actions)
    }

    @Test
    fun confirmationDialogReportsOnlyTheExplicitDestructiveAction() {
        render(detailState().copy(isDeleteConfirmationVisible = true))

        composeRule.onNodeWithText("Delete saved ride?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete ride").performClick()

        assertEquals(listOf(RideDetailAction.DeleteConfirmed), actions)
    }

    @Test
    fun aMissingRecordHasNoDeleteAction() {
        render(RideDetailUiState(isLoading = false))

        composeRule.onNodeWithText("This saved ride is no longer available.").assertIsDisplayed()
        composeRule.onNodeWithText("Delete saved ride").assertDoesNotExist()
    }

    private fun render(state: RideDetailUiState) {
        composeRule.setContent {
            TaxiInspectorTheme {
                RideDetailScreen(state = state, onAction = { actions += it })
            }
        }
    }

    private fun detailState() = RideDetailUiState(
        isLoading = false,
        ride = RideDetailPresentation(
            id = "ride-1",
            endedAt = "Sep 4, 2026, 2:30 PM",
            total = "6.45",
            distanceKilometres = "2.50",
            waitTime = "03:18",
            elapsedTime = "1:04:09",
            initialTax = "2.4",
            perKmRate = "1.2",
            perMinuteStillRate = "0.35",
            status = HistoryRideStatus.Completed,
        ),
    )
}
