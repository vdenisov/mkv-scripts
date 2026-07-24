package org.plukh.mkvtool.cli

import org.plukh.mkvtool.BuildInfo
import picocli.CommandLine.IVersionProvider

/** Feeds `--version` from the build-generated [BuildInfo], the single version source. */
class MkvtoolVersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("mkvtool ${BuildInfo.VERSION}")
}
