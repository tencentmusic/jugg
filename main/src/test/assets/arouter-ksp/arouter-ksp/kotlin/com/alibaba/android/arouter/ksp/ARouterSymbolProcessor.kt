package com.alibaba.android.arouter.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.validate

/**
 * Main KSP processor for ARouter.
 * Collects @Route, @Autowired, @Interceptor symbols and delegates to collectors for code generation.
 */
class ARouterSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val moduleName: String by lazy {
        val name = options[Consts.KEY_MODULE_NAME]
        if (name.isNullOrEmpty()) {
            logger.error(Consts.NO_MODULE_NAME_TIPS)
            throw RuntimeException("ARouter::Compiler >>> No module name, for more information, look at gradle log.")
        }
        name.replace(Regex("[^0-9a-zA-Z_]+"), "")
    }

    private val routeCollector = RouteCollector(codeGenerator, logger)
    private val autowiredCollector = AutowiredCollector(codeGenerator, logger)
    private val interceptorCollector = InterceptorCollector(codeGenerator, logger)

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        logger.info(">>> ARouter KSP processor start, module: $moduleName <<<")

        val deferred = mutableListOf<KSAnnotated>()

        // Collect @Route annotated classes
        val routeSymbols = resolver.getSymbolsWithAnnotation(Consts.ANNOTATION_TYPE_ROUTE)
        val validRoutes = mutableListOf<KSClassDeclaration>()
        routeSymbols.forEach { symbol ->
            if (symbol is KSClassDeclaration && symbol.validate()) {
                validRoutes.add(symbol)
            } else {
                deferred.add(symbol)
            }
        }

        // Collect @Autowired annotated fields
        val autowiredSymbols = resolver.getSymbolsWithAnnotation(Consts.ANNOTATION_TYPE_AUTOWIRED)
        val validAutowired = mutableListOf<KSPropertyDeclaration>()
        autowiredSymbols.forEach { symbol ->
            if (symbol is KSPropertyDeclaration && symbol.validate()) {
                validAutowired.add(symbol)
            } else {
                deferred.add(symbol)
            }
        }

        // Collect @Interceptor annotated classes
        val interceptorSymbols = resolver.getSymbolsWithAnnotation(Consts.ANNOTATION_TYPE_INTERCEPTOR)
        val validInterceptors = mutableListOf<KSClassDeclaration>()
        interceptorSymbols.forEach { symbol ->
            if (symbol is KSClassDeclaration && symbol.validate()) {
                validInterceptors.add(symbol)
            } else {
                deferred.add(symbol)
            }
        }

        // Process routes
        if (validRoutes.isNotEmpty()) {
            routeCollector.collect(validRoutes)
            routeCollector.generate(moduleName)
        }

        // Process autowired
        if (validAutowired.isNotEmpty()) {
            autowiredCollector.collect(validAutowired)
            autowiredCollector.generate()
        }

        // Process interceptors
        if (validInterceptors.isNotEmpty()) {
            interceptorCollector.collect(validInterceptors)
            interceptorCollector.generate(moduleName)
        }

        logger.info(">>> ARouter KSP processor done. <<<")
        return deferred
    }
}
