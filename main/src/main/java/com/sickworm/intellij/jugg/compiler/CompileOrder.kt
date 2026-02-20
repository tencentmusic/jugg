package com.sickworm.intellij.jugg.compiler

/**
 * CompileOrder defines order ranges used to place compiler stages in the incremental pipeline.
 */
object CompileOrder {
    const val NO_ORDER = ICompiler.NO_ORDER

    private const val FIRST = 1

    private const val ASSET_START = 1000
    private const val ASSET = 1100
    private const val ASSET_END = 1200

    private const val RES_START = 2000
    private const val RES = 2100
    private const val RES_END = 2200

    private const val SOURCE_START = 3000
    private const val SOURCE = 3100
    private const val SOURCE_END = 3200

    private const val MINIFY_START = 4000
    private const val MINIFY = 4100
    private const val MINIFY_END = 4200

    private const val DEX_START = 5000
    private const val DEX = 5100
    private const val DEX_END = 5200

    private const val LAST = 10000

    val atFirst = FIRST + 1 until ASSET_START

    val beforeAsset = ASSET_START until ASSET
    val afterAsset = ASSET + 1 until ASSET_END

    val beforeRes = RES_START until RES
    val afterRes = RES + 1 until RES_END

    val beforeSource = SOURCE_START until SOURCE
    val afterSource = SOURCE + 1 until SOURCE_END

    val beforeMinify = MINIFY_START until MINIFY
    val afterMinify = MINIFY + 1 until MINIFY_END

    val beforeDex = DEX_START until DEX
    val afterDex = DEX + 1 until DEX_END

    val atLast = DEX_END until LAST

    val noOrder = NO_ORDER until NO_ORDER
}
