package com.gvart.parleyroom.document

object DocumentFixtures {
    /** One valid block of each of the 13 types, with solutions. */
    val allBlocks: String by lazy {
        DocumentFixtures::class.java.classLoader.getResource("documents/all-blocks.json")!!.readText()
    }
}
