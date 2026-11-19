package com.jemcik.jemrec.recordings

import androidx.core.content.FileProvider

/**
 * The content URIs Share and Play hand out for a file in app storage
 * (res/xml/file_paths.xml). A subclass of its own, as FileProvider's
 * reference asks: "It is possible to use FileProvider directly instead of
 * extending it. However, this is not reliable and will causes crashes on
 * some devices." Declared as FileProvider itself until 1.2; the authority
 * is the same, so a link handed out before still opens.
 */
class RecordingsFileProvider : FileProvider()
