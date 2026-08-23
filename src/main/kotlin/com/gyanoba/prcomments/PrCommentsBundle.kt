package com.gyanoba.prcomments

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

private const val BUNDLE = "messages.PrCommentsBundle"

/** Single access point for every user-visible string in the plugin (see guardrail §14.5). */
object PrCommentsBundle {
    private val instance = DynamicBundle(PrCommentsBundle::class.java, BUNDLE)

    fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any?): @Nls String =
        instance.getMessage(key, *params)

    fun lazyMessage(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any?): Supplier<@Nls String> =
        instance.getLazyMessage(key, *params)
}
