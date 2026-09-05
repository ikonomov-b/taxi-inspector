package com.taxiinspector.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.ui.theme.TaxiInspectorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<HistoryAction>()

    @Test
    fun emptyHistoryExplainsWhichRidesWillAppear() {
        render(HistoryUiState(isLoading = false))

        composeRule.onNodeWithText("No saved rides").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Completed and explicitly saved interrupted rides appear here.",
        ).assertIsDisplayed()
    }

    @Test
    fun newestFirstRowsShowRequiredValuesAndOpenTheSelectedRide() {
        render(
            HistoryUiState(
                isLoading = false,
                rides = listOf(
                    ride("newest", "Sep 4, 2026, 2:30 PM", "6.45", "2.50"),
                    ride(
                        id = "older",
                        endedAt = "Sep 3, 2026, 9:15 AM",
                        total = "3.20",
                        distance = "0.75",
                        status = HistoryRideStatus.Interrupted,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Sep 4, 2026, 2:30 PM", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Total 6.45", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("2.50 km", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Interrupted", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Ride ended Sep 4, 2026, 2:30 PM. Completed. " +
                "Total 6.45 tariff units. Distance 2.50 kilometres.",
        ).performClick()

        assertEquals(listOf(HistoryAction.RideSelected("newest")), actions)
    }

    @Test
    fun backIsReportedAsAnAction() {
        render(HistoryUiState(isLoading = false))

        composeRule.onNodeWithText("Back").performClick()

        assertEquals(listOf(HistoryAction.Back), actions)
    }

    private fun render(state: HistoryUiState) {
        composeRule.setContent {
            TaxiInspectorTheme {
                HistoryScreen(state = state, onAction = { actions += it })
            }
        }
    }

    private fun ride(
        id: String,
        endedAt: String,
        total: String,
        distance: String,
        status: HistoryRideStatus = HistoryRideStatus.Completed,
    ) = HistoryRideItem(id, endedAt, total, distance, status)
}
