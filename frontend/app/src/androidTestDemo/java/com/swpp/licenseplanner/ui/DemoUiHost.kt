package com.swpp.licenseplanner.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.AtomicFilePlanStore
import com.swpp.licenseplanner.data.DemoAdjustmentRepository
import com.swpp.licenseplanner.data.FlavorRepositories
import com.swpp.licenseplanner.data.LocalPlanStore
import com.swpp.licenseplanner.data.PlanSnapshot
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanStorageScope
import com.swpp.licenseplanner.data.PlanStoreLoad
import com.swpp.licenseplanner.data.PlanningRepository
import com.swpp.licenseplanner.data.ReloadOutcome
import com.swpp.licenseplanner.data.UnavailableCalendarExport
import com.swpp.licenseplanner.data.UnavailableCalendarRead
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.ui.theme.LicensePlannerTheme
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

internal typealias UiRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** Owns only the test's store and ViewModels; the activity hosts the real navigation. */
internal class DemoUiHost(private val rule: UiRule, private val testName: String) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val directory = File(instrumentation.targetContext.cacheDir, "compose-plans-$testName-${System.nanoTime()}")
        .apply { check(mkdirs()) }
    private val storeContext = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }
    private val oldLocale = Locale.getDefault()
    private val oldTimeZone = TimeZone.getDefault()
    private val oldConfiguration = Configuration(rule.activity.resources.configuration)
    private val diagnostics = File(instrumentation.targetContext.filesDir, "compose-ui-diagnostics/$testName")
    private val current = mutableStateOf<PlanFormViewModel?>(null)
    private var owner: ViewModelStoreOwner? = null
    val adjustmentCalls = AtomicInteger()
    val planningCalls = AtomicInteger()
    val questionCalls = AtomicInteger()
    val reloadCalls = AtomicInteger()
    val saveCalls = AtomicInteger()
    var storageScope = PlanStorageScope.DEMO
    var reloadOutcome: ReloadOutcome? = null
    var returnedTopic: String? = null
    /** While set, every save is refused before any byte is written, as a failed device write. */
    val failSaves = AtomicBoolean(false)
    /** While set, every save waits for it to complete before writing or being refused. */
    val holdSaves = AtomicReference<CompletableDeferred<Unit>?>(null)
    val viewModel: PlanFormViewModel get() = checkNotNull(current.value)

    @Suppress("DEPRECATION")
    fun start() {
        check(diagnostics.deleteRecursively())
        rule.runOnUiThread {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            val configuration = Configuration(oldConfiguration).apply { setLocale(Locale.US) }
            rule.activity.resources.updateConfiguration(configuration, rule.activity.resources.displayMetrics)
        }
        freshState()
        rule.setContent {
            LicensePlannerTheme {
                current.value?.let { model -> key(model) { AppNavigation(model) } }
            }
        }
        awaitSaved()
    }

    fun recreate() {
        disposeState()
        freshState()
        awaitSaved()
    }

    private fun freshState() {
        val nextOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val today = LocalDate.of(2026, 11, 16)
        val adjustment = DemoAdjustmentRepository { today.atStartOfDay() }
        val countedAdjustment = object : AdjustmentRepository by adjustment {
            override suspend fun adjust(input: AdjustmentInput) = adjustment.adjust(input).also {
                adjustmentCalls.incrementAndGet()
            }
            override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome {
                reloadCalls.incrementAndGet()
                return reloadOutcome ?: adjustment.reload(scheduleCsvFile)
            }
        }
        val planning = FlavorRepositories.planningRepository()
        val countedPlanning = object : PlanningRepository {
            override suspend fun generate(request: StudyPlanRequest) = planning.generate(request).let { outcome ->
                planningCalls.incrementAndGet()
                val topic = returnedTopic
                if (outcome is PlanOutcome.Success && topic != null) {
                    val target = outcome.response.schedules.first { it.scheduleId.startsWith("study-") }.scheduleId
                    outcome.copy(response = outcome.response.copy(schedules = outcome.response.schedules.map {
                        if (it.scheduleId == target) it.copy(topic = topic) else it
                    }))
                } else outcome
            }
        }
        val store = AtomicFilePlanStore.open(storeContext, storageScope)
        val failableStore = object : LocalPlanStore by store {
            override suspend fun save(snapshot: PlanSnapshot): Boolean {
                saveCalls.incrementAndGet()
                holdSaves.get()?.await()
                return !failSaves.get() && store.save(snapshot)
            }
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlanFormViewModel(
                questionRepository = object : com.swpp.licenseplanner.data.QuestionRepository {
                    override suspend fun load(certificationId: String) = FlavorRepositories.questionRepository().load(certificationId).also {
                        questionCalls.incrementAndGet()
                    }
                },
                planningRepository = countedPlanning,
                adjustmentRepository = countedAdjustment,
                today = today,
                planStore = failableStore,
                calendarExportRepository = UnavailableCalendarExport,
                calendarReadRepository = UnavailableCalendarRead,
                availabilityProfileRepository = FlavorRepositories.availabilityProfileRepository(),
            ) as T
        }
        rule.runOnIdle {
            owner = nextOwner
            current.value = ViewModelProvider(nextOwner, factory)[PlanFormViewModel::class.java]
        }
    }

    private fun disposeState() {
        val job = current.value?.viewModelScope?.coroutineContext?.get(Job)
        rule.runOnIdle { current.value = null }
        rule.waitForIdle()
        rule.runOnIdle { owner?.viewModelStore?.clear(); owner = null }
        runBlocking { job?.join() }
    }

    fun awaitSaved() {
        rule.waitUntil("The test-owned plan store becomes ready", 10_000) {
            viewModel.storage.value == PlanStorage.Ready
        }
        rule.waitForIdle()
    }

    /** A new store/codec read supplements on-screen assertions without priming app state. */
    fun snapshot(): PlanSnapshot = runBlocking {
        val loaded = AtomicFilePlanStore.open(storeContext, storageScope).load()
        check(loaded is PlanStoreLoad.Loaded) { "Test-owned snapshot could not be read: $loaded" }
        loaded.snapshot
    }

    fun seedDraft(draft: com.swpp.licenseplanner.domain.CreationDraft) = runBlocking {
        val store = AtomicFilePlanStore.open(storeContext, storageScope)
        val loaded = store.load() as PlanStoreLoad.Loaded
        check(store.save(loaded.snapshot.copy(creationDraft = draft)))
    }

    fun seedSnapshot(snapshot: PlanSnapshot) = runBlocking {
        val store = AtomicFilePlanStore.open(storeContext, storageScope)
        check(store.load() is PlanStoreLoad.Loaded)
        check(store.save(snapshot))
        check((store.load() as PlanStoreLoad.Loaded).snapshot == snapshot)
    }

    fun capture(label: String) {
        val output = diagnostics.apply { check(mkdirs() || isDirectory) }
        File(output, "$label.txt").writeText(rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(output, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    override fun close() {
        try {
            disposeState()
        } finally {
            rule.runOnUiThread {
                rule.activity.resources.updateConfiguration(oldConfiguration, rule.activity.resources.displayMetrics)
                Locale.setDefault(oldLocale)
                TimeZone.setDefault(oldTimeZone)
            }
            assertTrue("Only the test-owned plan directory is removed", directory.deleteRecursively())
            assertTrue("Test-owned plan directory is gone", !directory.exists())
        }
    }
}
