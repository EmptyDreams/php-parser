package top.kmar.php.exceptions

open class PhpSyntaxException : RuntimeException {

    val startIndex: Int
    val endIndex: Int

    constructor(startIndex: Int, endIndex: Int) : super(
        handleMessage("", startIndex, endIndex)
    ) {
        this.startIndex = startIndex
        this.endIndex = endIndex
    }

    constructor(message: String, startIndex: Int, endIndex: Int) : super(
        handleMessage(message, startIndex, endIndex)
    ) {
        this.startIndex = startIndex
        this.endIndex = endIndex
    }

    constructor(message: String, caseBy: Throwable, startIndex: Int, endIndex: Int) : super(
        handleMessage(
            message,
            startIndex,
            endIndex
        ), caseBy
    ) {
        this.startIndex = startIndex
        this.endIndex = endIndex
    }

    companion object {

        @JvmStatic
        private fun handleMessage(message: String, startIndex: Int, endIndex: Int): String {
            if (message.isNotEmpty()) {
                if (endIndex > 0) return "Php code [$startIndex, $endIndex) contains syntax errors: $message"
                return "Php code [$startIndex, ...] contains syntax errors: $message"
            } else {
                if (endIndex > 0) return "Php code [$startIndex, $endIndex) contains syntax errors"
                return "Php code [$startIndex, ...] contains syntax errors"
            }
        }

    }

}