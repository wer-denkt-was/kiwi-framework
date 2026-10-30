package de.kiwi

import de.kiwi.gradle.findLibrary

plugins {
    antlr
}

dependencies {
    antlr(findLibrary("antlr4-antlr"))
    compileOnly(findLibrary("antlr4-antlr"))
    implementation(findLibrary("antlr4-runtime"))
}
