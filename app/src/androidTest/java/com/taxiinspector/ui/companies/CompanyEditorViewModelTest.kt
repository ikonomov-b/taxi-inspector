package com.taxiinspector.ui.companies

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.data.rides.TaxiInspectorDatabase
import com.taxiinspector.ride.Tariff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies name and rate validation, the duplicate and limit rules, and the ride lock. */
@RunWith(AndroidJUnit4::class)
class CompanyEditorViewModelTest {
    private lateinit var database: TaxiInspectorDatabase
    private lateinit var repository: RoomRideRepository

    private val savedEvents = Channel<Unit>(Channel.UNLIMITED)
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, TaxiInspectorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomRideRepository(database.rideDao())
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        database.close()
    }

    @Test
    fun aValidCompanyIsStoredExactlyAndBecomesTheFirstSelection() = runBlocking {
        val viewModel = editor(companyId = null)
        enter(viewModel, "City Taxi", "2.40", "1.20", "0.35")
        viewModel.onAction(CompanyEditorAction.Save)

        assertEquals(Unit, withTimeout(TIMEOUT_MILLIS) { savedEvents.receive() })
        val saved = repository.observeCompanies().first().single()
        assertEquals("City Taxi", saved.name)
        assertEquals(tariff("2.40", "1.20", "0.35"), saved.tariff)
        // The first saved company selects itself, so the meter becomes usable at once.
        assertEquals(saved.id, repository.selectedCompany()?.id)
    }

    @Test
    fun aBlankNameAndAnInvalidRateAreRejectedOnTheirOwnFields() = runBlocking {
        val viewModel = editor(companyId = null)
        enter(viewModel, "   ", "2.40", "1 200", "0.35")
        viewModel.onAction(CompanyEditorAction.Save)

        val state = awaitState(viewModel) { it.form.nameError != null }
        assertEquals(CompanyNameError.Blank, state.form.nameError)
        assertEquals(setOf(CompanyRateField.PerKmRate), state.form.invalidRates)
        assertTrue(repository.observeCompanies().first().isEmpty())
        assertNull(withTimeoutOrNull(QUIET_MILLIS) { savedEvents.receive() })
    }

    @Test
    fun aNameLongerThanEightyCharactersIsRejected() = runBlocking {
        val viewModel = editor(companyId = null)
        enter(viewModel, "n".repeat(81), "2.40", "1.20", "0.35")
        viewModel.onAction(CompanyEditorAction.Save)

        assertEquals(
            CompanyNameError.TooLong,
            awaitState(viewModel) { it.form.nameError != null }.form.nameError,
        )
        assertTrue(repository.observeCompanies().first().isEmpty())
    }

    @Test
    fun aNameIsTrimmedAndADuplicateIsRejectedRegardlessOfCase() = runBlocking {
        repository.createCompany("City Taxi", tariff("2.40", "1.20", "0.35"))

        val viewModel = editor(companyId = null)
        enter(viewModel, "  CITY taxi  ", "9", "9", "9")
        viewModel.onAction(CompanyEditorAction.Save)

        assertEquals(
            CompanyEditorError.DuplicateName,
            awaitState(viewModel) { it.error != null }.error,
        )
        assertEquals(1, repository.observeCompanies().first().size)

        // A distinct name saves, and it is stored trimmed.
        viewModel.onAction(CompanyEditorAction.NameChanged("  Night Cabs  "))
        viewModel.onAction(CompanyEditorAction.Save)
        withTimeout(TIMEOUT_MILLIS) { savedEvents.receive() }
        assertTrue(repository.observeCompanies().first().any { it.name == "Night Cabs" })
    }

    @Test
    fun anEleventhCompanyIsRejectedWithoutEvictingAnyOfTheTen() = runBlocking {
        repeat(10) { index ->
            repository.createCompany("Company $index", tariff("1", "1", "1"))
        }

        val viewModel = editor(companyId = null)
        enter(viewModel, "Eleventh", "2", "2", "2")
        viewModel.onAction(CompanyEditorAction.Save)

        assertEquals(
            CompanyEditorError.LimitReached,
            awaitState(viewModel) { it.error != null }.error,
        )
        val companies = repository.observeCompanies().first()
        assertEquals(10, companies.size)
        assertTrue(companies.none { it.name == "Eleventh" })
    }

    @Test
    fun anEditLoadsTheSavedValuesAndRewritesOnlyThatCompany() = runBlocking {
        repository.createCompany("City Taxi", tariff("2.40", "1.20", "0.35"))
        repository.createCompany("Night Cabs", tariff("5", "2", "0.5"))
        val city = repository.observeCompanies().first().single { it.name == "City Taxi" }

        val viewModel = editor(companyId = city.id)
        val loaded = awaitState(viewModel) { it.form.name.isNotEmpty() }
        assertEquals(CompanyEditorMode.Edit, loaded.mode)
        assertEquals("City Taxi", loaded.form.name)
        assertEquals("2.4", loaded.form.initialTax)

        viewModel.onAction(CompanyEditorAction.NameChanged("City Cabs"))
        viewModel.onAction(CompanyEditorAction.RateChanged(CompanyRateField.PerKmRate, "1,45"))
        viewModel.onAction(CompanyEditorAction.Save)
        withTimeout(TIMEOUT_MILLIS) { savedEvents.receive() }

        val companies = repository.observeCompanies().first()
        val edited = companies.single { it.id == city.id }
        assertEquals("City Cabs", edited.name)
        // An untouched rate is re-saved from its displayed form, which omits trailing zeroes.
        assertEquals(tariff("2.4", "1.45", "0.35"), edited.tariff)
        assertEquals(tariff("5", "2", "0.5"), companies.single { it.name == "Night Cabs" }.tariff)
    }

    @Test
    fun anActiveRideLocksTheEditorAndRefusesTheSave() = runBlocking {
        repository.createCompany("City Taxi", tariff("2.40", "1.20", "0.35"))
        repository.startRide("ride-lock", 1_000)

        val viewModel = editor(companyId = null)
        awaitState(viewModel) { it.isLocked }
        enter(viewModel, "Night Cabs", "5", "2", "0.5")
        viewModel.onAction(CompanyEditorAction.Save)

        assertNull(withTimeoutOrNull(QUIET_MILLIS) { savedEvents.receive() })
        assertEquals(1, repository.observeCompanies().first().size)
    }

    private fun editor(companyId: String?): CompanyEditorViewModel {
        val viewModel = CompanyEditorViewModel(repository, companyId)
        collectorScope.launch { viewModel.saved.collect { savedEvents.send(Unit) } }
        return viewModel
    }

    private fun enter(
        viewModel: CompanyEditorViewModel,
        name: String,
        initialTax: String,
        perKm: String,
        perMinute: String,
    ) {
        viewModel.onAction(CompanyEditorAction.NameChanged(name))
        viewModel.onAction(CompanyEditorAction.RateChanged(CompanyRateField.InitialTax, initialTax))
        viewModel.onAction(CompanyEditorAction.RateChanged(CompanyRateField.PerKmRate, perKm))
        viewModel.onAction(
            CompanyEditorAction.RateChanged(CompanyRateField.PerMinuteStillRate, perMinute),
        )
    }

    private suspend fun awaitState(
        viewModel: CompanyEditorViewModel,
        predicate: (CompanyEditorUiState) -> Boolean,
    ): CompanyEditorUiState = withTimeout(TIMEOUT_MILLIS) { viewModel.state.first(predicate) }

    private fun tariff(initialTax: String, perKm: String, perMinute: String) = Tariff(
        initialTax = requireNotNull(DecimalAmount.parse(initialTax)),
        perKmRate = requireNotNull(DecimalAmount.parse(perKm)),
        perMinuteStillRate = requireNotNull(DecimalAmount.parse(perMinute)),
        waitingCrossoverKilometersPerHour = requireNotNull(DecimalAmount.parse("8")),
    )

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val QUIET_MILLIS = 400L
    }
}
