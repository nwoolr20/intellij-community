// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.completion.checkers

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.components.KtExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.signatures.KtCallableSignature
import org.jetbrains.kotlin.analysis.api.symbols.KtCallableSymbol

internal fun interface ExtensionApplicabilityChecker {
    context(KtAnalysisSession)
    fun checkApplicability(symbol: KtCallableSymbol): KtExtensionApplicabilityResult
}

internal data class ApplicableExtension(
    val signature: KtCallableSignature<*>,
    val applicabilityResult: KtExtensionApplicabilityResult.Applicable,
)