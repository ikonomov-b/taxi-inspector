package com.taxiinspector.ui.meter

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.ui.TariffSummary
import com.taxiinspector.ui.companies.CompanySummary
import com.taxiinspector.ui.theme.TaxiInspectorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the meter screen as a pure function of [MeterUiState], so the rendered controls,
 * totals, confirmations, and semantics are verified without a service or a database.
 */
@RunWith(AndroidJUnit4::class)
class MeterScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<MeterAction>()

    @Test
    fun readyStateStartsARideAndOffersResetButNeverDiscard() {
        render(readyState())

        composeRule.onNodeWithText("METER READY").assertIsDisplayed()
        composeRule.onNodeWithText("0.00").assertIsDisplayed()
        composeRule.onNodeWithText("Reset").assertIsDisplayed()
        composeRule.onNodeWithText("Discard ride").assertDoesNotExist()

        composeRule.onNodeWithText("Start ride").assertIsEnabled().performClick()
        assertEquals(listOf(MeterAction.StartRide), actions)
    }

    @Test
    fun startIsUnavailableUntilACompanyIsSelected() {
        render(
            readyState().copy(
                canStart = false,
                company = null,
                companies = emptyList(),
                selectedCompanyId = null,
                status = MeterStatus.CompanyNeeded,
            ),
        )

        composeRule.onNodeWithText("Start ride").assertIsNotEnabled()
        composeRule.onNodeWithText("Select a taxi company to start").assertIsDisplayed()
        composeRule.onNodeWithText("No company selected").performScrollTo().assertIsDisplayed()
        // With nothing to choose between, the selector itself stays unavailable.
        composeRule.onNodeWithText("Change").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun runningStateShowsTheTotalWithPauseStopAndDiscard() {
        render(runningState())

        composeRule.onNodeWithText("METER RUNNING").assertIsDisplayed()
        composeRule.onNodeWithText("12.85").assertIsDisplayed()
        composeRule.onNodeWithText("6.42 km", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("03:18", useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithText("Pause").performClick()
        composeRule.onNodeWithText("Stop & save").performClick()
        composeRule.onNodeWithText("Discard ride").performScrollTo().performClick()
        assertEquals(
            listOf(MeterAction.PauseRide, MeterAction.StopAndSave, MeterAction.DiscardRequested),
            actions,
        )
        composeRule.onNodeWithText("Resume").assertDoesNotExist()
    }

    @Test
    fun pausedStateResumesFromTheVisibleScreen() {
        render(runningState().copy(
            presentation = runningState().presentation.copy(phase = MeterPhaseLabel.Paused),
            status = MeterStatus.Paused,
        ))

        composeRule.onNodeWithText("METER PAUSED").assertIsDisplayed()
        composeRule.onNodeWithText("Paused — fare frozen").assertIsDisplayed()
        composeRule.onNodeWithText("Resume").performClick()
        composeRule.onNodeWithText("Stop & save").assertIsDisplayed()
        composeRule.onNodeWithText("Discard ride").performScrollTo().assertIsDisplayed()
        assertEquals(listOf(MeterAction.ResumeRide), actions)
    }

    @Test
    fun interruptedStateOffersOnlySaveAsInterruptedOrDiscard() {
        render(runningState().copy(
            presentation = runningState().presentation.copy(phase = MeterPhaseLabel.Interrupted),
            status = MeterStatus.PendingInterrupted,
        ))

        composeRule.onNodeWithText("METER INTERRUPTED").assertIsDisplayed()
        composeRule.onNodeWithText("Pause").assertDoesNotExist()
        composeRule.onNodeWithText("Resume").assertDoesNotExist()
        composeRule.onNodeWithText("Save as interrupted").performClick()
        composeRule.onNodeWithText("Discard ride").performScrollTo().assertIsDisplayed()
        assertEquals(listOf(MeterAction.StopAndSave), actions)
    }

    @Test
    fun discardIsConfirmedBeforeItIsReported() {
        render(runningState().copy(isDiscardConfirmationVisible = true))

        composeRule.onNodeWithText("Discard ride?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Discard without saving").performClick()
        assertEquals(listOf(MeterAction.DiscardConfirmed), actions)
    }

    @Test
    fun companySelectionIsLockedAndExplainedWhileARideIsActive() {
        render(runningState())

        composeRule.onNodeWithText("Change").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Manage companies").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("The ride keeps the company and rates it locked at Start.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aRideLockedBeforeCompaniesExistedIsLabelledRatherThanNamed() {
        render(runningState().copy(company = MeterCompany(null, TariffSummary("2.4", "1.2", "0.35"))))

        composeRule.onNodeWithText("Company not recorded").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Initial 2.4 · 1.2/km · 0.35/min")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theSelectedCompanyStaysVisibleAndManagementOpensItsOwnDestination() {
        render(readyState())

        composeRule.onNodeWithText("City Taxi").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Initial 2.4 · 1.2/km · 0.35/min")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Manage companies")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf(MeterAction.ManageCompanies), actions)
    }

    @Test
    fun theSelectorListsEachProfileWithItsRatesAndReportsTheChoice() {
        render(
            readyState().copy(
                isCompanySelectorVisible = true,
                companies = listOf(
                    CompanySummary("city", "City Taxi", TariffSummary("2.4", "1.2", "0.35")),
                    CompanySummary("night", "Night Cabs", TariffSummary("5", "2", "0.5")),
                ),
                selectedCompanyId = "city",
            ),
        )

        // Enough tariff detail to tell two companies apart, merged into one option.
        composeRule
            .onNode(hasText("Night Cabs") and hasText("Initial 5 · 2/km · 0.5/min"))
            .assertExists()
            .performClick()

        assertEquals(listOf(MeterAction.CompanySelected("night")), actions)
    }

    @Test
    fun historyOpensFromTheMeter() {
        render(readyState())

        composeRule.onNodeWithText("History").performScrollTo().performClick()

        assertEquals(listOf(MeterAction.ViewHistory), actions)
    }

    @Test
    fun everyMeterValueAndStatusCarriesAScreenReaderLabel() {
        render(runningState())

        composeRule.onNodeWithContentDescription("Estimated total 12.85 tariff units").assertExists()
        composeRule.onNodeWithContentDescription("Distance 6.42 kilometres").assertExists()
        composeRule.onNodeWithContentDescription("Wait time 3 minutes 18 seconds").assertExists()
        composeRule.onNodeWithContentDescription("GPS status: GPS good").assertExists()
    }

    @Test
    fun aDisabledGpsProviderOffersItsOwnRecoveryAction() {
        render(readyState().copy(status = MeterStatus.GpsDisabled, recovery = MeterRecovery.EnableGps))

        composeRule.onNodeWithText("GPS turned off").assertIsDisplayed()
        composeRule.onNodeWithText("Turn on GPS in location settings").performScrollTo().performClick()
        assertTrue(actions.contains(MeterAction.RecoveryRequested))
    }

    @Test
    fun aMissingPermissionOffersTheSettingsRecoveryAction() {
        render(
            readyState().copy(
                status = MeterStatus.PermissionNeeded,
                recovery = MeterRecovery.GrantPreciseLocation,
            ),
        )

        composeRule.onNodeWithText("Location permission needed — fare frozen").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings to allow precise location")
            .performScrollTo()
            .performClick()
        assertTrue(actions.contains(MeterAction.RecoveryRequested))
    }

    private fun render(state: MeterUiState) {
        composeRule.setContent {
            TaxiInspectorTheme {
                MeterScreen(state = state, onAction = { actions += it })
            }
        }
    }

    private fun readyState() = MeterUiState(
        company = MeterCompany("City Taxi", TariffSummary("2.4", "1.2", "0.35")),
        companies = listOf(
            CompanySummary("city", "City Taxi", TariffSummary("2.4", "1.2", "0.35")),
        ),
        selectedCompanyId = "city",
        status = MeterStatus.ReadyToStart,
        canStart = true,
    )

    private fun runningState() = readyState().copy(
        presentation = MeterPresentation(
            total = "12.85",
            distance = "6.42",
            waitTime = "03:18",
            waitMinutes = 3,
            waitSeconds = 18,
            phase = MeterPhaseLabel.Running,
        ),
        status = MeterStatus.Good,
        canStart = false,
        canManageCompanies = false,
        companies = emptyList(),
        selectedCompanyId = null,
    )
}
