/**
 * Output seam — reified [OutputEvent]s produced by command logic and turned into output by a
 * [Renderer]. [TextRenderer] renders them as text; a machine-readable (JSON) renderer can consume the
 * same events. Command logic never writes to a stream or colors anything directly.
 */
package org.plukh.mkvtool.out
