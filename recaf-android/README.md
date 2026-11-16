# Recaf for Android

Recaf's analysis core running on Android, packaged as an APK, with an agent as the main way of using it.
You open a JAR, class file, DEX file or APK (including the APK of an app installed on the device), and
either ask Claude to dissect it or browse and edit it yourself. The same tools the agent uses are served
to outside clients through a built-in MCP server.

| Module | What it is |
| --- | --- |
| `../recaf-agent` | Plain Java, shared by desktop and Android: the tool set, the Claude agent, the MCP server, the script sandbox and a command line entry point. |
| `core` | `recaf-core` built as an Android library. The desktop sources are reused, not forked. |
| `app` | The application: agent chat, workspace browser, class viewer and editor, tool forms, findings, MCP and settings screens. |

## Building

Requirements: JDK 22 or newer, and the Android SDK with platforms 36 and 37 and build-tools installed.
This build has its own Gradle wrapper (the Android Gradle plugin needs a newer Gradle than the desktop
build uses), so run it from this directory:

```
cd recaf-android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app needs Android 15 (API 35) or newer. The APK is large, a little over 110 MB, because nothing is
minified: Recaf finds its services and the decompilers find their classes reflectively.

Tests:

```
./gradlew :core:testDebugUnitTest            # the Android build of the core and tools, on the host JVM
./gradlew :core:connectedDebugAndroidTest    # the same checks on a device or emulator, under ART
cd .. && ./gradlew :recaf-agent:test         # the full tool, agent and MCP suite on desktop
```

## Using the app

**Open something.** From the welcome screen pick a file, choose an installed app, or share/open a file
with Recaf from another app. Supported inputs are whatever Recaf imports: JAR, WAR, class, DEX, APK, ZIP
and so on.

**Ask the agent.** Put an Anthropic API key in Settings (it is encrypted with the Android Keystore), then
type a question or start one of the ready-made tasks: full analysis, quick triage, secret hunt,
deobfuscation, Android audit. The agent's tool calls are shown inline and can be expanded, findings it
records are collected on their own screen, and changes it wants to make wait for your approval unless you
switch that off. A run can be stopped at any time and continued afterwards. It keeps going while the app is in
the background, with a notification that can stop it.

Agent settings:

- *Model.* Defaults to `claude-opus-5-5`.
- *Effort.* How thoroughly the agent works. Defaults to medium.
- *Changes.* Ask before each change (default), allow changes, or read-only. Recording findings never
  needs approval.
- *Retry on another model.* On by default. When a model's safety filters decline a request, which
  happens with reverse engineering questions, the API answers with another model instead. The chat says
  when that happened.
- *API endpoint.* Empty by default, which means Anthropic's API. Set it when requests go through a gateway.

**Do it by hand.** The browser lists packages, classes and files with search. A class opens decompiled
(CFR, Procyon or Vineflower), as bytecode, or as a structure outline; the bytecode view can be edited and
reassembled. The Tools screen offers every tool as a form, so anything the agent can do, you can do
without it. Export writes the modified JAR or APK wherever you choose.

## Tools

45 tools, the same for the in-app agent, the Tools screen and MCP clients:

| Area | Tools |
| --- | --- |
| Workspace | `workspace_info` `open_workspace` `list_classes` `list_packages` `list_files` `read_file` `get_android_manifest` `list_modified` |
| Reading code | `get_class_info` `decompile_class` `decompile_method` `disassemble_class` `disassemble_method` `get_inheritance` `class_diff` |
| Searching | `search_strings` `search_numbers` `search_references` `search_declarations` `search_instructions` `search_files` |
| Call graph and triage | `get_callers` `get_callees` `find_entry_points` `detect_anti_reversal` |
| Editing | `assemble_class` `assemble_method` `put_file` `delete_class` `delete_file` `undo_change` `export_workspace` `set_comment` `get_comments` |
| Renaming | `rename_class` `rename_member` `apply_mappings` `export_mappings` |
| Deobfuscation | `list_transformers` `apply_transformers` |
| Scripting | `run_script` `script_help` |
| Findings | `record_finding` `list_findings` `remove_finding` |

Scripts are JavaScript, run by Rhino in interpreted mode with a time limit and a class allow-list. A
script gets a `recaf` object for reading and replacing classes and files and for calling any other tool,
which makes bulk work (decrypting every string, patching a pattern across all classes) one call.
`script_help` describes the API.

## MCP server

Start it from the MCP screen. It speaks Streamable HTTP at `http://127.0.0.1:8765/mcp` and requires the
bearer token shown on that screen. The screen also gives the ready-made command for Claude Code:

```
adb forward tcp:8765 tcp:8765
claude mcp add --transport http recaf http://127.0.0.1:8765/mcp --header "Authorization: Bearer <token>"
```

By default only clients on the device (or through `adb forward`) can connect. Accepting connections from
the network is a separate switch, and the server refuses to bind to the network without a token. Besides
the tools, the server offers the ready-made analysis tasks as MCP prompts. A foreground service keeps it
alive while the app is in the background.

## On desktop

`recaf-agent` does not need Android. From the repository root:

```
./gradlew :recaf-agent:run --args="app.jar --analyze"            # needs ANTHROPIC_API_KEY
./gradlew :recaf-agent:run --args="app.apk --analyze android-audit"
./gradlew :recaf-agent:run --args="app.jar --ask 'What does it do on startup?'"
./gradlew :recaf-agent:run --args="app.jar --mcp --port 8765"
```

`--help` lists the options. The agent reads only, unless `--allow-changes` is passed.

## How the port works

`core` does not copy Recaf's sources. At build time it syncs `recaf-core/src/main/java` and adapts it:

- **Removed** (`desktopOnlySources` in `core/source-patches.gradle`): what cannot exist on Android, such
  as attaching to running JVMs, the `javac` integration and the plugin class loading.
- **Replaced** (`core/src/main/java`, same path as the original): `Bootstrap`, logging, and a handful of
  services that need a different implementation.
- **Patched** (`core/source-patches.gradle`): small textual changes for APIs Android lacks. A patch that
  no longer applies fails the build, so drift in the desktop sources is noticed.
- **Retargeted dependencies**: a few libraries are rewritten with ASM at build time, for example to keep
  the decompilers from looking for a `jrt:` file system.

What stands in for the missing pieces:

- *Dependency injection.* Weld generates classes at runtime, which ART cannot load. `LiteContainer`
  implements the part of CDI that Recaf uses, by reflection, from a bean index written at build time.
- *`java.lang.foreign`.* A small shim over `ByteBuffer` for the ZIP reader.
- *The runtime's own classes.* Android has no class files for its platform classes, so the classes that
  decompilers and the assembler ask about are synthesized from reflection.

## Limitations

- No Java source compilation, so no editing by recompiling decompiled Java and no Java scripts. Editing
  is done through the assembler, scripting through JavaScript.
- No plugins, no attaching to processes, and no source-level (AST) services.
- Renaming is refused for most DEX content: Recaf's DEX rewriter fails on methods with unnamed
  parameters. Renaming in JARs works. Transformers and `detect_anti_reversal` are JVM-only.
- Exported APKs are not signed. Only the base APK of an installed split app is opened.
- A conversation lives as long as the app process.
- Large APKs take a while to import on a phone, and much longer on an emulator without hardware
  acceleration.
