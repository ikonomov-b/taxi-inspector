package com.taxiinspector.ui.companies

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.ui.TariffSummary
import com.taxiinspector.ui.theme.TaxiInspectorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives both company screens as pure functions of their state. */
@RunWith(AndroidJUnit4::class)
class CompanyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val listActions = mutableListOf<CompanyListAction>()
    private val editorActions = mutableListOf<CompanyEditorAction>()

    @Test
    fun anEmptyListExplainsItselfAndOffersOnlyAdd() {
        renderList(CompanyListUiState(isLoading = false))

        composeRule.onNodeWithText("No taxi companies saved").assertIsDisplayed()
        composeRule
            .onNodeWithText("Save a company and its rates before starting a ride.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Add company").assertIsEnabled().performClick()

        assertEquals(listOf(CompanyListAction.AddCompany), listActions)
    }

    @Test
    fun eachRowSelectsEditsOrDeletesItsOwnCompany() {
        renderList(listState())

        composeRule.onNodeWithText("Night Cabs").performScrollTo().performClick()
        composeRule.onAllNodesWithText("Edit")[0].performScrollTo().performClick()
        composeRule.onAllNodesWithText("Delete")[0].performScrollTo().performClick()

        assertEquals(
            listOf(
                CompanyListAction.SelectCompany("night"),
                CompanyListAction.EditCompany("city"),
                CompanyListAction.DeleteRequested("city"),
            ),
            listActions,
        )
    }

    @Test
    fun everyRowIsOneSelectableOptionCarryingItsNameAndRates() {
        renderList(listState())

        // The selectable row merges its children, so each option is announced as a whole.
        composeRule.onNode(hasText("City Taxi") and hasText("Initial 2.4 · 1.2/km · 0.35/min · wait below 8 km/h"))
            .assertExists()
        composeRule.onNode(hasText("Night Cabs") and hasText("Initial 5 · 2/km · 0.5/min · wait below 5 km/h"))
            .assertExists()
    }

    @Test
    fun theTenCompanyLimitExplainsItselfAndDisablesAdd() {
        renderList(listState().copy(isAtLimit = true))

        composeRule
            .onNodeWithText("Ten companies are saved. Delete one before adding another.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Add company").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun anActiveRideDisablesEveryManagementControl() {
        renderList(listState().copy(canManage = false))

        composeRule
            .onNodeWithText("Companies cannot be changed while a ride is active.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Add company").performScrollTo().assertIsNotEnabled()
        composeRule.onAllNodesWithText("Edit")[0].assertIsNotEnabled()
        composeRule.onAllNodesWithText("Delete")[0].assertIsNotEnabled()
    }

    @Test
    fun deletionNamesTheCompanyAndSaysWhatSurvives() {
        renderList(listState().copy(pendingDeletion = listState().companies.first()))

        composeRule.onNodeWithText("Delete City Taxi?").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "This company and its rates are deleted immediately. Saved rides keep the " +
                    "name and rates they recorded. This cannot be undone.",
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("Delete company").performClick()

        assertEquals(listOf(CompanyListAction.DeleteConfirmed), listActions)
    }

    @Test
    fun theEditorReportsTheNameAndEachRateEvenWhileTheKeyboardMovesTheColumn() {
        renderStatefulEditor(CompanyEditorUiState())

        // Focusing a field opens the IME, and the inset-padded column scrolls underneath the
        // test, so every interaction re-resolves its own node.
        composeRule.onNodeWithText("Company name").performScrollTo().performTextInput("City Taxi")
        composeRule.onNodeWithText("Initial tax").performScrollTo().performTextInput("2.40")
        composeRule.onNodeWithText("Per km rate").performScrollTo().performTextInput("1.20")
        composeRule.onNodeWithText("Per minute car-still rate")
            .performScrollTo()
            .performTextInput("0,35")
        composeRule.onNodeWithText("Save company").performScrollTo().performClick()

        assertEquals(
            listOf(
                CompanyEditorAction.NameChanged("City Taxi"),
                CompanyEditorAction.RateChanged(CompanyRateField.InitialTax, "2.40"),
                CompanyEditorAction.RateChanged(CompanyRateField.PerKmRate, "1.20"),
                CompanyEditorAction.RateChanged(CompanyRateField.PerMinuteStillRate, "0,35"),
                CompanyEditorAction.Save,
            ),
            editorActions,
        )
    }

    @Test
    fun aFirstRunOffersNoWayOutOfTheEditor() {
        renderEditor(CompanyEditorUiState(), onCancel = null)

        composeRule
            .onNodeWithText("Name the taxi company and enter its rates before starting a ride.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun eachInvalidFieldExplainsItselfWhereItWasTyped() {
        renderEditor(
            CompanyEditorUiState(
                form = CompanyFormState(
                    name = "",
                    initialTax = "2.40",
                    perKmRate = "1,2,3",
                    perMinuteStillRate = "0.35",
                    nameError = CompanyNameError.Blank,
                    invalidRates = setOf(CompanyRateField.PerKmRate),
                    isPristine = false,
                ),
            ),
        )

        composeRule.onNodeWithText("Enter a company name.").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Enter a non-negative number with at most one decimal separator and six decimals.",
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aDuplicateNameIsReportedWithoutBlamingTheRates() {
        renderEditor(CompanyEditorUiState(error = CompanyEditorError.DuplicateName))

        composeRule
            .onNodeWithText("A company with this name is already saved.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun anActiveRideLocksTheEditorAndSaysWhy() {
        renderEditor(CompanyEditorUiState(isLocked = true))

        composeRule.onNodeWithText("Save company").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText("Companies cannot be changed while a ride is active.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun renderList(state: CompanyListUiState) {
        composeRule.setContent {
            TaxiInspectorTheme {
                CompanyListScreen(state = state, onAction = { listActions += it })
            }
        }
    }

    private fun renderEditor(
        state: CompanyEditorUiState,
        onCancel: (() -> Unit)? = {},
    ) {
        composeRule.setContent {
            TaxiInspectorTheme {
                CompanyEditorScreen(
                    state = state,
                    onAction = { editorActions += it },
                    onCancel = onCancel,
                )
            }
        }
    }

    private fun renderStatefulEditor(initial: CompanyEditorUiState) {
        composeRule.setContent {
            var state by remember { mutableStateOf(initial) }
            TaxiInspectorTheme {
                CompanyEditorScreen(
                    state = state,
                    onAction = { action ->
                        editorActions += action
                        state = when (action) {
                            is CompanyEditorAction.NameChanged ->
                                state.copy(form = state.form.copy(name = action.value))
                            is CompanyEditorAction.RateChanged ->
                                state.copy(form = state.form.withValue(action.field, action.value))
                            else -> state
                        }
                    },
                    onCancel = null,
                )
            }
        }
    }

    private fun listState() = CompanyListUiState(
        isLoading = false,
        companies = listOf(
            CompanySummary("city", "City Taxi", TariffSummary("2.4", "1.2", "0.35", "8")),
            CompanySummary("night", "Night Cabs", TariffSummary("5", "2", "0.5", "5")),
        ),
        selectedCompanyId = "city",
    )
}
