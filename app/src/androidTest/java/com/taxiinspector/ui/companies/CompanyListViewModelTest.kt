package com.taxiinspector.ui.companies

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.data.rides.TaxiInspectorDatabase
import com.taxiinspector.ride.Tariff
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies selection, confirmed deletion, the limit signal, and the active-ride lock. */
@RunWith(AndroidJUnit4::class)
class CompanyListViewModelTest {
    private lateinit var database: TaxiInspectorDatabase
    private lateinit var repository: RoomRideRepository
    private lateinit var viewModel: CompanyListViewModel

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, TaxiInspectorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomRideRepository(database.rideDao())
        viewModel = CompanyListViewModel(repository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun selectingACompanyDurablyReplacesTheWholeProfile() = runBlocking {
        repository.createCompany("City Taxi", tariff("2.40", "1.20", "0.35"))
        repository.createCompany("Night Cabs", tariff("5", "2", "0.5"))
        val night = awaitState { it.companies.size == 2 }.companies.single { it.name == "Night Cabs" }

        viewModel.onAction(CompanyListAction.SelectCompany(night.id))

        val state = awaitState { it.selectedCompanyId == night.id }
        assertEquals(night.id, state.selectedCompanyId)
        assertEquals(tariff("5", "2", "0.5"), repository.selectedCompany()?.tariff)
    }

    @Test
    fun deletionOnlyHappensAfterItIsConfirmed() = runBlocking {
        repository.createCompany("City Taxi", tariff("2.40", "1.20", "0.35"))
        val city = awaitState { it.companies.isNotEmpty() }.companies.single()

        viewModel.onAction(CompanyListAction.DeleteRequested(city.id))
        assertEquals(city, awaitState { it.pendingDeletion != null }.pendingDeletion)

        viewModel.onAction(CompanyListAction.DeleteDismissed)
        awaitState { it.pendingDeletion == null }
        assertEquals(1, repository.observeCompanies().first().size)

        viewModel.onAction(CompanyListAction.DeleteRequested(city.id))
        viewModel.onAction(CompanyListAction.DeleteConfirmed)

        val state = awaitState { it.companies.isEmpty() }
        assertNull(state.pendingDeletion)
        assertTrue(repository.observeCompanies().first().isEmpty())
    }

    @Test
    fun deletingTheSelectedCompanyLeavesNoSelection() = runBlocking {
        repository.createCompany("City Taxi", tariff("2.40", "1.20", "0.35"))
        repository.createCompany("Night Cabs", tariff("5", "2", "0.5"))
        val selected = awaitState { it.selectedCompanyId != null }.selectedCompanyId

        viewModel.onAction(CompanyListAction.DeleteRequested(requireNotNull(selected)))
        viewModel.onAction(CompanyListAction.DeleteConfirmed)

        // Deleting touches both the company table and the selection, so the two flows may
        // settle in either order; this waits for the state they converge on.
        val state = awaitState { it.selectedCompanyId == null && it.companies.size == 1 }
        // The remaining company is not chosen for the user; Start stays unavailable.
        assertEquals(1, state.companies.size)
        assertNull(repository.selectedCompany())
    }

    @Test
    fun tenCompaniesReportTheLimitWithoutEvictingAnything() = runBlocking {
        repeat(10) { index -> repository.createCompany("Company $index", tariff("1", "1", "1")) }

        val state = awaitState { it.companies.size == 10 }
        assertTrue(state.isAtLimit)
        assertEquals(10, state.companies.size)
    }

    @Test
    fun anActiveRideWithdrawsManagementAndRefusesBothWrites() = runBlocking {
        repository.createCompany("City Taxi", tariff("2.40", "1.20", "0.35"))
        repository.createCompany("Night Cabs", tariff("5", "2", "0.5"))
        val companies = awaitState { it.companies.size == 2 }.companies
        val city = companies.single { it.name == "City Taxi" }
        val night = companies.single { it.name == "Night Cabs" }
        repository.startRide("ride-lock", 1_000)
        awaitState { !it.canManage }

        viewModel.onAction(CompanyListAction.SelectCompany(night.id))
        viewModel.onAction(CompanyListAction.DeleteRequested(city.id))
        viewModel.onAction(CompanyListAction.DeleteConfirmed)

        // Both are refused by the repository, so the ride keeps its locked company.
        val state = awaitState { !it.canManage }
        assertFalse(state.canManage)
        assertEquals(city.id, state.selectedCompanyId)
        assertEquals(2, repository.observeCompanies().first().size)
        assertEquals("City Taxi", repository.currentActiveRide()?.companyName)
    }

    private suspend fun awaitState(predicate: (CompanyListUiState) -> Boolean): CompanyListUiState =
        withTimeout(TIMEOUT_MILLIS) { viewModel.state.first(predicate) }

    private fun tariff(initialTax: String, perKm: String, perMinute: String) = Tariff(
        initialTax = requireNotNull(DecimalAmount.parse(initialTax)),
        perKmRate = requireNotNull(DecimalAmount.parse(perKm)),
        perMinuteStillRate = requireNotNull(DecimalAmount.parse(perMinute)),
        waitingCrossoverKilometersPerHour = requireNotNull(DecimalAmount.parse("8")),
    )

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
