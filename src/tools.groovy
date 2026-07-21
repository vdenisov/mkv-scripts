// tools.groovy — shared non-output helpers for the scripts in src/.
//
// Loaded the same way as output.groovy: evaluate(new File(scriptDir,
// 'tools.groovy')) with scriptDir resolved from the calling script's own
// location, never the CWD. See output.groovy for why the file is loaded
// explicitly instead of relying on Groovy's sibling-class resolution.
//
// No @Grab and no imports. The last expression is the exported closure.

// Locate an MKVToolNix executable: try PATH first, then the Windows default
// install location. Throws when neither works — the callers cannot do anything
// useful without the tool.
{ String name ->
    try {
        def probe = [name, '--version'].execute()
        probe.waitFor()
        if (probe.exitValue() == 0) return name
    } catch (ignored) {}
    if (System.getProperty('os.name').toLowerCase().contains('win')) {
        def path = "C:\\Program Files\\MKVToolNix\\${name}.exe"
        if (new File(path).exists()) return path
    }
    throw new RuntimeException("'$name' not found on PATH or in default install location. Install MKVToolNix.")
}
