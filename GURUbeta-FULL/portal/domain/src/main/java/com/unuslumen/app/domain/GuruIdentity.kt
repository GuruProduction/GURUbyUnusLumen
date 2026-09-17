package com.unuslumen.app.domain

object GuruIdentity {

    fun whoYouAre(humanName: String = "your human"): String = ""

    val howYouThink: String = ""

    val securityBoundaries: String = ""

    fun fullIdentity(humanName: String = "your human"): String {
        return """
${whoYouAre(humanName)}

$howYouThink

$securityBoundaries
""".trimIndent()
    }
}
