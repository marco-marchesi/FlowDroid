package com.flowdroid.ui.flows

import com.flowdroid.common.flow.InstalledPackage
import com.flowdroid.common.flow.InstalledPackagesRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PackagePickerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: InstalledPackagesRepository

    @BeforeEach fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mockk(relaxed = false)
        io.mockk.every { repo.observe() } returns emptyFlow()
    }

    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private val sample = listOf(
        InstalledPackage(packageName = "com.zoo", label = "Zoo", isLaunchable = true),
        InstalledPackage(packageName = "com.alpha", label = "Alpha", isLaunchable = true),
        InstalledPackage(packageName = "com.beta", label = null, isLaunchable = true),
        InstalledPackage(packageName = "com.sys", label = "System Tool", isLaunchable = false),
        InstalledPackage(packageName = "com.example.app", label = "Echo", isLaunchable = true),
    )

    @Test fun `Ready state lists launchable+labelled apps first sorted by label`() = runTest(dispatcher) {
        coEvery { repo.snapshot() } returns sample
        val vm = PackagePickerViewModel(repo)
        advanceUntilIdle()
        val ready = vm.state.value as PackagePickerUiState.Ready
        // Launchable-and-labelled: Alpha, Echo, Zoo (alphabetical).
        // Then non-launchable / no-label: com.beta, com.sys (alphabetical by package).
        assertThat(ready.items.map { it.packageName }).containsExactly(
            "com.alpha", "com.example.app", "com.zoo", "com.beta", "com.sys",
        ).inOrder()
    }

    @Test fun `setQuery filters case-insensitively across label and package`() = runTest(dispatcher) {
        coEvery { repo.snapshot() } returns sample
        val vm = PackagePickerViewModel(repo)
        advanceUntilIdle()

        vm.setQuery("FUB")
        val r1 = vm.state.value as PackagePickerUiState.Ready
        assertThat(r1.items.map { it.packageName }).containsExactly("com.example.app")

        vm.setQuery("com.b")
        val r2 = vm.state.value as PackagePickerUiState.Ready
        assertThat(r2.items.map { it.packageName }).containsExactly("com.beta")
    }

    @Test fun `setQuery with no matches yields empty items list`() = runTest(dispatcher) {
        coEvery { repo.snapshot() } returns sample
        val vm = PackagePickerViewModel(repo)
        advanceUntilIdle()
        vm.setQuery("nothingmatches")
        val r = vm.state.value as PackagePickerUiState.Ready
        assertThat(r.items).isEmpty()
        assertThat(r.query).isEqualTo("nothingmatches")
    }

    @Test fun `empty snapshot still resolves to Ready`() = runTest(dispatcher) {
        coEvery { repo.snapshot() } returns emptyList()
        val vm = PackagePickerViewModel(repo)
        advanceUntilIdle()
        val r = vm.state.value as PackagePickerUiState.Ready
        assertThat(r.items).isEmpty()
    }

    @Test fun `snapshot exception falls back to empty Ready`() = runTest(dispatcher) {
        coEvery { repo.snapshot() } throws IllegalStateException("pkg manager dead")
        val vm = PackagePickerViewModel(repo)
        advanceUntilIdle()
        val r = vm.state.value as PackagePickerUiState.Ready
        assertThat(r.items).isEmpty()
    }
}
