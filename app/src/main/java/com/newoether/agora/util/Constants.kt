package com.newoether.agora.util

object Constants {
    const val TOOL_MSG_PREFIX = "tool_"
    const val RESULT_MSG_PREFIX = "result_"
    const val TOOL_CALL_ID_PREFIX = "call_"

    /** Max characters per embedded text chunk */
    const val MAX_EMBEDDING_TEXT_LENGTH = 8000
    /** Max characters stored per embedding chunk for display */
    const val MAX_CHUNK_TEXT_LENGTH = 500
    /** Max file content to read from user-attached text files */
    const val MAX_FILE_CONTENT_READ_LENGTH = 500_000
    /** Max characters to fetch from a web page */
    const val MAX_WEB_FETCH_HTML_LENGTH = 80_000
    /** Max characters per tool result (prevents CursorWindow 2MB overflow) */
    const val MAX_TOOL_RESULT_LENGTH = 100_000
    /** Placeholder model ID used as StateFlow/DataStore cold-start fallback and
     *  template preview sample. NOT the real default model — it is overwritten
     *  as soon as the user selects a model or DataStore loads the persisted value. */
    const val EXAMPLE_MODEL_ID = "gemini-1.5-flash"
}
