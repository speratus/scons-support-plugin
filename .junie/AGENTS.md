# AGENTS.md — SCons IntelliJ Platform Plugin

> **Model:** Gemini 3 Flash
> **Target runtime:** IntelliJ Platform SDK (all JetBrains IDEs — IDEA, CLion, PyCharm, etc.)
> **Language:** Kotlin
> **Build system for this plugin:** Gradle with `org.jetbrains.intellij.platform` plugin

---

## 0. Critical constraints (read before every task)

1. **No CLion-specific APIs.** Do not import or reference any class under `com.jetbrains.cidr.*`,
   `com.jetbrains.clion.*`, or `com.jetbrains.objc.*`. The plugin must compile and run on every
   IntelliJ-based IDE. Use only APIs from `com.intellij.*` packages that ship in `intellij-platform`
   (the base platform artifact), not in product-specific JARs.
2. **No hardcoded paths.** Resolve Python and SCons through `PathEnvironmentVariableUtil`,
   user settings, and project-level overrides — in that priority order.
3. **Background threads for all I/O.** Never block the EDT. Use `ProgressManager.getInstance()
   .runProcessWithProgressAsynchronously(...)` or coroutines with `Dispatchers.IO`.
4. **Every generated or mutated file must be logged.** Append a one-line entry to
   `<project>/.idea/scons-plugin.log` for every file written (path, timestamp, reason).
5. **Idempotent shims.** The Python introspection shim injected into `site_scons/site_init.py`
   must be guarded by a sentinel comment so re-running does not duplicate it.
6. **SCons dry-run must never trigger a build.** Always pass `-n` / `--dry-run` when
   introspecting. Never omit this flag in any subprocess call that reads project structure.
7. **`compile_commands.json` generation must not build the project.** Use only
   `--dry-run` + compiler-flag extraction from shim output. See § v0.3 for the exact approach.

---

## 1. Repository layout

```
SCons-Support/
├── .junie/
│   └── AGENTS.md                          ← this file
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── src/
│   main/
│   ├── kotlin/space/luchuktech/sconssupport/
│   │   ├── SConsBundle.kt             ← message bundle
│   │   ├── SConsIcons.kt              ← icon references
│   │   ├── settings/
│   │   │   ├── SConsProjectSettings.kt
│   │   │   ├── SConsSettingsConfigurable.kt
│   │   │   └── SConsSettingsComponent.kt
│   │   ├── model/
│   │   │   ├── SConsTarget.kt         ← data class
│   │   │   ├── SConsOption.kt         ← data class
│   │   │   └── SConsProjectModel.kt   ← aggregates targets + options
│   │   ├── introspection/
│   │   │   ├── SConsShimInstaller.kt  ← writes site_scons shim
│   │   │   ├── SConsRunner.kt         ← low-level subprocess wrapper
│   │   │   └── SConsModelBuilder.kt   ← parses shim JSON → SConsProjectModel
│   │   ├── watcher/
│   │   │   └── SConstructFileListener.kt
│   │   ├── toolwindow/
│   │   │   ├── SConsToolWindowFactory.kt
│   │   │   ├── SConsToolWindowPanel.kt
│   │   │   └── SConsRunPanel.kt
│   │   └── compiledb/
│   │       └── CompileCommandsGenerator.kt
│   └── resources/
│       ├── META-INF/plugin.xml
│       ├── messages/SConsBundle.properties
│       └── shims/
│           └── scons_introspect.py    ← bundled shim template
│   test/
│   └── kotlin/space/luchuktech/sconssupport/
│       ├── SConsModelBuilderTest.kt
│       ├── ShimInstallerTest.kt
│       └── CompileCommandsGeneratorTest.kt
```

---

## 2. `plugin.xml` skeleton

The plugin must declare **no** product-specific module dependencies.

```xml
<idea-plugin>
  <id>space.luchuktech.sconssupport</id>
  <name>SCons Build System Support</name>
  <vendor>Andrew Luchuk</vendor>

  <!-- Base platform only — no com.intellij.modules.clion or similar -->
  <depends>com.intellij.modules.platform</depends>

  <extensions defaultExtensionNs="com.intellij">

    <!-- Settings -->
    <projectConfigurable
        instance="com.example.scons.settings.SConsSettingsConfigurable"
        id="com.example.scons.settings"
        displayName="SCons"/>

    <projectService
        serviceImplementation="com.example.scons.settings.SConsProjectSettings"/>

    <!-- Tool window -->
    <toolWindow
        id="SCons"
        anchor="bottom"
        factoryClass="com.example.scons.toolwindow.SConsToolWindowFactory"
        icon="/icons/scons.svg"/>

    <!-- File watcher (added in v0.2) -->
    <editorNotificationProvider
        implementation="com.example.scons.watcher.SConstructFileListener"/>

  </extensions>

  <applicationListeners>
    <!-- v0.2: bulk file change listener registered here -->
  </applicationListeners>
</idea-plugin>
```

---

## 3. Phase v0.1 — Foundation: shim, dry-run, tool window skeleton

### Goal
Running **Sync** in the tool window executes the SCons introspection shim in dry-run mode,
parses the JSON output, and populates the tool window with discovered targets and
`ARGUMENTS`-style options. No build is ever triggered.

---

### 3.1 Bundled Python shim (`src/main/resources/shims/scons_introspect.py`)

This script is the single source of truth for target and option discovery. It is copied by
`SConsShimInstaller` into the project's `site_scons/site_init.py` between sentinel comments.

**The shim must:**
- Monkey-patch `Environment.__init__` to intercept `ARGUMENTS` and `Variables` objects
  before any builder method is called.
- Register an `AddPostAction`-style finalizer using `atexit` that, after SCons has finished
  reading all SConscript files (but before it builds anything — guaranteed by `--dry-run`),
  dumps a JSON document to `<build_dir>/.scons_introspect.json`.
- Enumerate every `Program`, `Library`, `SharedLibrary`, `StaticLibrary`, `Object`,
  `Command`, and `Alias` call, recording `name`, `sources` (list), `CCFLAGS`, `CPPPATH`,
  `CPPDEFINES`, `LINKFLAGS`, `CC`, `CXX`, `SHCC`, `SHCXX` from the construction environment
  at the moment of the call.
- Enumerate `Variables` / `BoolVariable` / `EnumVariable` / `PathVariable` entries,
  recording `key`, `help`, `default`, and (for enums) `allowed_values`.
- Never raise an exception that would abort SCons's own exit; wrap all shim code in
  `try/except Exception`.

**Output schema** (write to stdout as well as the JSON file so the plugin can read it
without knowing the build directory):

```jsonc
{
  "schema_version": 1,
  "targets": [
    {
      "name": "hello",           // alias or output file stem
      "type": "Program",         // Program | Library | SharedLibrary | StaticLibrary | Alias | Command | Object
      "sources": ["src/hello.c"],
      "env": {
        "CC":  "gcc",
        "CXX": "g++",
        "CCFLAGS": ["-O2", "-Wall"],
        "CPPPATH": ["include/"],
        "CPPDEFINES": ["NDEBUG"],
        "LINKFLAGS": []
      }
    }
  ],
  "options": [
    {
      "key": "debug",
      "help": "Build with debug symbols",
      "default": "0",
      "type": "bool"            // bool | enum | path | string
    },
    {
      "key": "variant",
      "help": "Build variant",
      "default": "release",
      "type": "enum",
      "allowed_values": ["debug", "release", "profile"]
    }
  ]
}
```

---

### 3.2 `SConsShimInstaller`

```kotlin
// Pseudocode — implement fully in Kotlin

object SConsShimInstaller {
    private const val SENTINEL_BEGIN = "# >>>>> scons-plugin introspect begin <<<<<"
    private const val SENTINEL_END   = "# >>>>> scons-plugin introspect end <<<<<"

    /**
     * Copies the bundled shim into <projectRoot>/site_scons/site_init.py,
     * guarded by sentinel comments so it is idempotent.
     * Must be called on a background thread.
     */
    fun install(projectRoot: Path) {
        val shimText = loadResource("/shims/scons_introspect.py")
        val target = projectRoot.resolve("site_scons/site_init.py")
        target.parent.createDirectories()

        val existing = if (target.exists()) target.readText() else ""
        if (existing.contains(SENTINEL_BEGIN)) return   // already installed

        val block = "\n$SENTINEL_BEGIN\n$shimText\n$SENTINEL_END\n"
        target.appendText(block)
        log(projectRoot, "Installed introspection shim into $target")
    }

    /** Removes shim block if present (used by uninstall / user opt-out). */
    fun uninstall(projectRoot: Path) { /* strip lines between sentinels */ }
}
```

---

### 3.3 `SConsRunner`

```kotlin
data class RunResult(val stdout: String, val stderr: String, val exitCode: Int)

object SConsRunner {
    /**
     * Runs SCons with --dry-run plus any extra args.
     * Resolves the Python interpreter from SConsProjectSettings, then falls
     * back to PATH.  Never omits -n.
     */
    fun dryRun(
        projectRoot: Path,
        settings: SConsProjectSettings,
        extraArgs: List<String> = emptyList(),
        indicator: ProgressIndicator
    ): RunResult {
        val python = settings.pythonPath.ifBlank { "python3" }
        val scons  = settings.sconsPath.ifBlank  { "scons"   }

        // Always include -n. Fail loudly (assertion) if caller passes --no-dry-run.
        require(extraArgs.none { it == "--no-dry-run" }) {
            "SCons must never be invoked without --dry-run during introspection"
        }

        val cmd = listOf(python, "-m", "SCons") + listOf("-n", "-Q", "--silent") + extraArgs
        // Alternative if scons is on PATH: listOf(scons, "-n", "-Q", "--silent") + extraArgs

        val process = ProcessBuilder(cmd)
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
            .start()

        // Stream stdout/stderr while checking indicator.isCanceled
        // Return RunResult with captured output
    }
}
```

---

### 3.4 `SConsModelBuilder`

Parses the JSON written to stdout by the shim and returns `SConsProjectModel`.

```kotlin
object SConsModelBuilder {
    fun parse(json: String): SConsProjectModel {
        // Use kotlinx.serialization or Gson (already on platform classpath)
        // Validate schema_version == 1
        // Map targets → List<SConsTarget>
        // Map options  → List<SConsOption>
        // Return SConsProjectModel(targets, options)
    }
}
```

---

### 3.5 Tool window — v0.1 layout

```
┌─ SCons ─────────────────────────────────────────────────────┐
│ [▶ Sync]  [▶ Run]  [⚙ Settings]          Status: Ready      │
├─────────────────────────────────────────────────────────────┤
│ Targets                    │ Options                         │
│ ─────────────────────────  │ ───────────────────────────    │
│ ○ hello       (Program)    │ debug=0       [bool  ▼]        │
│ ○ libfoo      (Library)    │ variant=release [enum ▼]       │
│ ○ tests       (Alias)      │ prefix=/usr   [path  …]        │
│                            │                                 │
│                            │ [+ Add custom key=value]        │
└────────────────────────────┴─────────────────────────────────┘
│ Output                                                       │
│ scons -n -Q ...                                              │
│ scons: Reading SConscript files ...                          │
│ scons: done reading SConscript files.                        │
└─────────────────────────────────────────────────────────────┘
```

- **Sync button** — installs the shim, runs dry-run, rebuilds model, refreshes UI.
- **Run button** — runs SCons *without* `-n`, passing selected target and all option
  key=value pairs that differ from their defaults. Uses `GeneralCommandLine` routed through
  the platform's `OSProcessHandler` so output streams to the tool window console.
- **Targets panel** — `JBList` backed by `SConsProjectModel.targets`. Single-selection.
  Passing no target runs the default.
- **Options panel** — `JBTable` with columns Key / Value / Help. Enum options render a
  `ComboBoxTableCellEditor`; bool options render a checkbox; path options render a text
  field with a folder-chooser button.
- **Output panel** — `ConsoleView` obtained via
  `TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole()`.

**Implementation class:** `SConsToolWindowPanel : SimpleToolWindowPanel(vertical = true)`

---

### 3.6 Settings (`SConsProjectSettings`)

Persist via `@State` + `PersistentStateComponent`. Fields:

| Field | Type | Default | Description |
|---|---|---|---|
| `pythonPath` | `String` | `""` | Absolute path to Python interpreter (blank = use PATH) |
| `sconsPath` | `String` | `""` | Absolute path to `scons` script (blank = use PATH) |
| `sconsArgs` | `String` | `""` | Extra flags always appended to every SCons invocation |
| `autoSync` | `Boolean` | `true` | Re-sync when `SConstruct` changes (v0.2) |
| `generateCompileCommands` | `Boolean` | `true` | Auto-generate `compile_commands.json` (v0.3) |
| `compileCommandsOnFirstSync` | `Boolean` | `true` | Generate on first sync rather than waiting for a build |

---

### 3.7 v0.1 acceptance criteria

- [ ] `./gradlew runIde` launches a sandboxed IntelliJ IDEA instance with the plugin loaded.
- [ ] Opening a project containing an `SConstruct` file and clicking **Sync** produces
  `.scons_introspect.json` in the project root with at least the `targets` and `options`
  arrays (may be empty for a trivial SConstruct).
- [ ] The tool window lists all discovered targets and options.
- [ ] Selecting a target and clicking **Run** invokes SCons without `-n` and streams output.
- [ ] The plugin loads without error in CLion, PyCharm, GoLand, and IntelliJ IDEA
  (verified by running against each in the Gradle sandbox; no `NoClassDefFoundError`).
- [ ] No EDT violations (verified with `ApplicationManager.getApplication().assertIsDispatchThread()`
  guards and IJ's threading assertions in tests).

---

## 4. Phase v0.2 — File watching and auto-resync

### Goal
When the user saves `SConstruct`, any `SConscript`, or any `.py` file under `site_scons/`,
the plugin schedules a background resync and refreshes the tool window automatically.
A banner is shown while the resync is in progress.

---

### 4.1 `SConstructFileListener`

Register as a `BulkFileListener` via the message bus, not as an `EditorNotificationProvider`
(the v0.1 skeleton placeholder was a simplification).

```kotlin
class SConstructFileListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (!SConsProjectSettings.getInstance(project).autoSync) return

        val relevant = events.any { event ->
            val path = event.path
            path.endsWith("SConstruct") ||
            path.endsWith("SConscript") ||
            (path.contains("site_scons") && path.endsWith(".py"))
        }

        if (relevant) {
            // Debounce: cancel any pending resync task, schedule a new one in 500 ms
            SConsResyncService.getInstance(project).scheduleResync()
        }
    }
}
```

Register in `plugin.xml`:
```xml
<listener class="com.example.scons.watcher.SConstructFileListener"
          topic="com.intellij.openapi.vfs.newvfs.BulkFileListener"/>
```

---

### 4.2 `SConsResyncService`

A project-level light service that owns a single `ScheduledFuture` for debouncing:

```kotlin
@Service(Service.Level.PROJECT)
class SConsResyncService(private val project: Project) {
    private val executor = AppExecutorUtil.getAppScheduledExecutorService()
    private var pending: ScheduledFuture<*>? = null

    fun scheduleResync(delayMs: Long = 500) {
        pending?.cancel(false)
        pending = executor.schedule({
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "SCons: syncing…") {
                override fun run(indicator: ProgressIndicator) {
                    SConsModelBuilder.sync(project, indicator)   // installs shim + dry run + updates model
                    updateToolWindowOnEdt(project)
                }
            })
        }, delayMs, TimeUnit.MILLISECONDS)
    }
}
```

---

### 4.3 Tool window changes for v0.2

- Add a thin **"Syncing…"** banner (`EditorNotificationPanel` style) that appears at the top
  of the tool window while a resync is running, dismissed automatically on completion.
- Add a **last synced** timestamp label in the status bar area (bottom of the tool window).
- After resync, if the set of available options changes (new key added or key removed),
  show a balloon notification:
  `"SCons options changed: added [debug], removed [legacy_mode]. Tool window updated."`

---

### 4.4 v0.2 acceptance criteria

- [ ] Editing and saving `SConstruct` triggers a resync within ≤1 s (debounce + background
  task latency).
- [ ] The "Syncing…" banner appears during the resync and disappears on completion.
- [ ] If a new `Variables()` entry is added to `SConstruct`, it appears in the Options panel
  after the auto-resync without any manual user action.
- [ ] `autoSync = false` in settings completely disables the listener (verified by toggling
  the setting and saving `SConstruct` — no resync must occur).

---

## 5. Phase v0.3 — `compile_commands.json` generation (no build required)

### Goal
After a successful Sync, the plugin generates a valid `compile_commands.json` in the project
root using **only dry-run output** — the project is never compiled. The file is kept up to
date on each resync. IDEs that support compilation databases (CLion, others via plugin) can
use this file for code intelligence without any additional user action.

---

### 5.1 How to extract compile commands without building

SCons, when run with `-n`, prints every command it *would* execute to stdout, including
compiler invocations like:

```
gcc -o build/hello.o -c src/hello.c -I include/ -O2 -Wall -DNDEBUG
```

The plugin captures this output and transforms each compiler line into a
`compile_commands.json` entry. This is safe because `-n` guarantees no file system mutation
by SCons itself.

**Extend `SConsRunner.dryRun`** to capture the full stdout. Pass no extra `-Q`/`--silent`
flags when generating compile commands (those flags suppress the command lines we need).

---

### 5.2 `CompileCommandsGenerator`

```kotlin
object CompileCommandsGenerator {

    private val COMPILER_PATTERN = Regex(
        """(?:^|\s)((?:gcc|g\+\+|clang|clang\+\+|cc|c\+\+|cl\.exe)(?:\S*))\s+(.+)""",
        RegexOption.MULTILINE
    )

    /**
     * Parses dry-run stdout, extracts compiler invocations,
     * and writes compile_commands.json to projectRoot.
     * Must be called on a background thread.
     */
    fun generate(projectRoot: Path, dryRunOutput: String) {
        val entries = mutableListOf<CompileCommandEntry>()

        for (match in COMPILER_PATTERN.findAll(dryRunOutput)) {
            val compiler = match.groupValues[1]
            val args     = match.groupValues[2]

            // Extract the source file: last non-flag argument before -o, or the argument
            // after -c, depending on compiler style.
            val sourceFile = extractSourceFile(args) ?: continue
            val outputFile = extractOutputFile(args)

            entries += CompileCommandEntry(
                directory = projectRoot.toString(),
                command   = "$compiler $args",
                file      = projectRoot.resolve(sourceFile).normalize().toString(),
                output    = outputFile?.let { projectRoot.resolve(it).normalize().toString() }
            )
        }

        if (entries.isEmpty()) return   // nothing to write; don't clobber existing file

        val json = buildString {
            appendLine("[")
            entries.forEachIndexed { i, e ->
                appendLine("  {")
                appendLine("""    "directory": ${e.directory.jsonQuote()},""")
                appendLine("""    "command": ${e.command.jsonQuote()},""")
                appendLine("""    "file": ${e.file.jsonQuote()}""")
                if (i < entries.lastIndex) appendLine("  },") else appendLine("  }")
            }
            appendLine("]")
        }

        val outFile = projectRoot.resolve("compile_commands.json")
        outFile.writeText(json)
        log(projectRoot, "Wrote ${entries.size} entries to $outFile")
    }

    // Helper: find the input source file in a compiler argument string.
    // Returns a relative or absolute path string, or null if not found.
    private fun extractSourceFile(args: String): String? { /* parse -c <file> pattern */ }
    private fun extractOutputFile(args: String): String? { /* parse -o <file> pattern */ }
}

data class CompileCommandEntry(
    val directory: String,
    val command:   String,
    val file:      String,
    val output:    String?
)
```

---

### 5.3 Integration into the sync pipeline

In `SConsModelBuilder.sync(project, indicator)`:

```
1. Install shim           → SConsShimInstaller.install(projectRoot)
2. Run dry-run (verbose)  → SConsRunner.dryRun(projectRoot, settings, indicator = indicator)
3. Parse JSON             → SConsModelBuilder.parse(result.stdout)   // from shim on stdout
4. Update model           → project.putUserData(SCONS_MODEL_KEY, model)
5. Generate compiledb     → if (settings.generateCompileCommands)
                               CompileCommandsGenerator.generate(projectRoot, result.stdout)
6. Refresh VFS            → VirtualFileManager.getInstance().asyncRefresh(...)
```

Step 2 must run SCons **without** `--silent` / `-Q` when `generateCompileCommands` is true,
so that compiler command lines appear in stdout. The shim JSON is written to the JSON file
and also echoed to stdout by the shim's `atexit` handler; `CompileCommandsGenerator` ignores
non-compiler lines (they won't match `COMPILER_PATTERN`).

---

### 5.4 First-sync behaviour

When `compileCommandsOnFirstSync` is `true` (default):

- `compile_commands.json` is generated as part of the very first **Sync** action.
- The project is never built. Only `-n` dry-run is used.
- If `compile_commands.json` did not exist before, a balloon notification is shown:
  `"compile_commands.json generated (N entries). Open in IDE? [Open]"`
- The `[Open]` action calls `OpenFileAction` on the file (informational only; the real
  benefit comes from IDEs that auto-detect the file, such as CLion).

---

### 5.5 Keeping `compile_commands.json` fresh

- On every auto-resync triggered by v0.2's file watcher, regenerate `compile_commands.json`
  if `generateCompileCommands` is `true`.
- Do **not** regenerate if the dry-run output is byte-for-byte identical to the previous
  run (cache the SHA-256 of the last stdout in the service state).

---

### 5.6 v0.3 acceptance criteria

- [ ] After clicking **Sync** on a project with at least one C/C++ or C source file,
  `compile_commands.json` exists in the project root with at least one entry.
- [ ] The project is never compiled: no `.o`, `.a`, or executable files are created.
- [ ] Each entry in `compile_commands.json` has valid `"directory"`, `"command"`, and
  `"file"` fields, with `"file"` resolving to an existing file on disk.
- [ ] Editing `SConstruct` to add a new source file, saving, and waiting ≤2 s results in
  `compile_commands.json` being updated to include the new file.
- [ ] Setting `generateCompileCommands = false` in settings prevents the file from being
  written or updated.
- [ ] The plugin still loads and functions normally in IDEs that do not use
  `compile_commands.json` (IDEA, PyCharm, GoLand) — no errors in the log.

---

## 6. Cross-cutting implementation rules

### Threading model

| Operation | Thread |
|---|---|
| All `ProcessBuilder` / SCons subprocess calls | Background (`ProgressManager` task or `AppExecutorUtil`) |
| Parsing JSON / building model | Background |
| Writing `compile_commands.json` | Background |
| All Swing / UI updates | EDT via `ApplicationManager.getApplication().invokeLater { }` |
| VFS refresh | Background, then EDT callback for notification |

### Error handling

- If the shim install fails (permissions), show an error balloon with a "Show details" link
  that opens the plugin log. Fall back to running SCons without the shim (targets and options
  will be empty, but Run still works).
- If SCons exits non-zero during dry-run, display stderr in the Output panel in red and set
  status to "Sync failed". Do not clear the previous model.
- If JSON parsing fails, log the raw stdout to the plugin log and report
  `"Introspection parse error — check scons-plugin.log"` in the status bar.

### Logging

All structured log entries go to `<projectRoot>/.idea/scons-plugin.log` in the format:

```
2025-06-20T14:23:01Z [INFO ] Installed introspection shim into /home/user/myproject/site_scons/site_init.py
2025-06-20T14:23:02Z [INFO ] Dry-run complete: 3 targets, 2 options discovered
2025-06-20T14:23:02Z [INFO ] Wrote 12 entries to /home/user/myproject/compile_commands.json
2025-06-20T14:23:15Z [WARN ] compile_commands.json unchanged (SHA-256 match), skipping write
```

Use `java.time.Instant.now()` for timestamps. Never use `System.out.println` in production
code paths.

### Testing strategy

- **Unit tests** for `SConsModelBuilder.parse()` using fixture JSON files in
  `src/test/resources/fixtures/`.
- **Unit tests** for `CompileCommandsGenerator` using fixture dry-run stdout strings
  covering: gcc, g++, clang, clang++ invocations; lines with `-c`; lines without `-c`
  (link step — should be skipped); Windows `cl.exe` style.
- **Unit tests** for `SConsShimInstaller` verifying idempotency (running `install()` twice
  produces exactly one copy of the sentinel block).
- **Integration tests** (marked `@Slow`) that spin up a real SCons process against a minimal
  `SConstruct` fixture in `src/test/resources/projects/hello_c/`.

---

## 7. Gemini 3 Flash working guidelines

When implementing tasks from this file, follow these conventions:

1. **One file per response.** When creating a new Kotlin file, output only that file plus
   the minimal `plugin.xml` diff required to register it. Do not regenerate unchanged files.
2. **Always show the full file, never diffs** for files under 200 lines. For files over
   200 lines, show diffs with 10-line context.
3. **Compile check.** After generating any Kotlin file, mentally verify that all imported
   symbols exist in `com.intellij.*` base platform (not product-specific JARs). Flag any
   uncertain import with `// TODO: verify API availability in platform baseline`.
4. **Phase gating.** Do not implement v0.2 code until v0.1 acceptance criteria are all
   checked off. Do not implement v0.3 code until v0.2 acceptance criteria are all checked off.
5. **Shim is Python 3.6+.** The bundled `scons_introspect.py` must not use f-strings
   with `=` (3.8+), walrus operator (3.8+), or `match` (3.10+). Use `.format()` for
   string formatting to maximise compatibility.
6. **Ask before adding dependencies.** If an implementation would benefit from a library
   not already on the IntelliJ platform classpath (e.g. Jackson, Arrow), ask before adding
   it to `build.gradle.kts`. Prefer `kotlinx.serialization` (bundled with Kotlin plugin)
   or the platform's `JsonParser` utility.
7. **Mark all TODOs.** Any stub or placeholder must be marked `// TODO(v0.X): description`
   matching the phase in which it is to be completed.