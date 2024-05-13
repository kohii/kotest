@file:Suppress("unused")

package io.kotest.framework.discovery

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.kotest.core.config.ProjectConfiguration
import io.kotest.core.extensions.DiscoveryExtension
import io.kotest.core.internal.KotestEngineProperties
import io.kotest.core.spec.Spec
import io.kotest.mpp.log
import io.kotest.mpp.syspropOrEnv
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Contains the results of a discovery request scan.
 *
 * @specs these are classes which extend one of the spec types
 * @scripts these are kotlin scripts which may or may not contain tests
 */
data class DiscoveryResult(
   val specs: List<KClass<out Spec>>,
   val scripts: List<KClass<*>>,
   val error: Throwable?, // this error is set if there was an exception during discovery
) {
   companion object {
      fun error(t: Throwable): DiscoveryResult = DiscoveryResult(emptyList(), emptyList(), t)
   }
}

/**
 * Scans for tests as specified by a [DiscoveryRequest].
 *
 * [DiscoveryExtension] `afterScan` functions are applied after the scan is complete to
 * optionally filter the returned classes.
 */
class Discovery(
   private val discoveryExtensions: List<DiscoveryExtension> = emptyList(),
   private val configuration: ProjectConfiguration,
) {

   private val requests = ConcurrentHashMap<DiscoveryRequest, DiscoveryResult>()

   // filter functions
   //private val isScript: (KClass<*>) -> Boolean = { ScriptTemplateWithArgs::class.java.isAssignableFrom(it.java) }
   private val isSpecSubclassKt: (KClass<*>) -> Boolean = { Spec::class.java.isAssignableFrom(it.java) }
   private val isSpecSubclass: (Class<*>) -> Boolean = { Spec::class.java.isAssignableFrom(it) }
   private val isAbstract: (KClass<*>) -> Boolean = { it.isAbstract }
   private val cachedSpecsFromClassPaths: List<KClass<out Spec>> by lazy { specsFromClassGraph() }

   /**
    * Returns a function that applies all the [DiscoveryFilter]s to a given class.
    * The class must pass all the filters to be included.
    */
   private fun filterFn(filters: List<DiscoveryFilter>): (KClass<out Spec>) -> Boolean = { kclass ->
      filters.isEmpty() || filters.all { it.test(kclass) }
   }

   /**
    * Returns a function that applies all the [DiscoverySelector]s to a given class.
    * The class must pass any one selector to be included.
    */
   private fun selectorFn(selectors: List<DiscoverySelector>): (KClass<out Spec>) -> Boolean = { kclass ->
      selectors.isEmpty() || selectors.any { it.test(kclass) }
   }

   fun discover(request: DiscoveryRequest): DiscoveryResult =
      requests.getOrPut(request) { doDiscovery(request).getOrElse { DiscoveryResult.error(it) } }

   /**
    * Loads a class reference from a [ClassInfo].
    *
    * @param init false to avoid initializing the class
    */
   private fun ClassInfo.load(init: Boolean): KClass<out Any> =
      Class.forName(name, init, this::class.java.classLoader).kotlin

   private fun doDiscovery(request: DiscoveryRequest): Result<DiscoveryResult> = runCatching {

      val specsSelected = request.specsIfCompletelySpecifiedOrNull()
         ?: cachedSpecsFromClassPaths
            .asSequence()
            .filter(isSpecSubclassKt)
            .filterNot(isAbstract)
            .filter(selectorFn(request.selectors))
            .toList()

      log { "[Discovery] Selected ${specsSelected.size} specs" }

      val specsAfterInitialFiltering = specsSelected.filter(filterFn(request.filters))

      log { "[Discovery] ${specsAfterInitialFiltering.size} specs remain after initial filtering" }

      log { "[Discovery] Further filtering classes via discovery extensions [$discoveryExtensions]" }

      val specsAfterExtensionFiltering = discoveryExtensions
         .fold(specsAfterInitialFiltering) { cl, ext -> ext.afterScan(cl) }
         .sortedBy { it.simpleName }

      log { "[Discovery] ${specsAfterExtensionFiltering.size} specs remain after extension filtering" }

      DiscoveryResult(specsAfterExtensionFiltering, emptyList(), null)
   }

   /**
    * Returns the request's [Spec]s if they are completely specified and a classpath scan is avoidable, null otherwise.
    *
    * Specs are completely specified if the request consists of class discovery selectors only.
    */
   private fun DiscoveryRequest.specsIfCompletelySpecifiedOrNull(): List<KClass<out Spec>>? {
      if (selectors.isEmpty() || !selectors.all { it is DiscoverySelector.ClassDiscoverySelector })
         return null

      log { "[Discovery] Collecting specs via class discovery selectors..." }
      val start = System.currentTimeMillis()

      // first filter down to spec instances only, then load the full class
      val specs = selectors
         .asSequence()
         .filterIsInstance<DiscoverySelector.ClassDiscoverySelector>()
         .map { Class.forName(it.className, false, this::class.java.classLoader) }
         .filter(isSpecSubclass)
         .map { Class.forName(it.name).kotlin }
         .filterIsInstance<KClass<out Spec>>()
         .filterNot(isAbstract)
         .toList()

      log {
         val duration = System.currentTimeMillis() - start
         "[Discovery] Collecting specs via class discovery selectors completed in ${duration}ms," +
            " found ${specs.size} specs"
      }

      return specs
   }

   /**
    * Returns a list of [Spec] classes detected using classgraph in the list of
    * locations specified by the uris param.
    */
   private fun specsFromClassGraph(): List<KClass<out Spec>> {
      log { "[Discovery] Starting classgraph scan for specs..." }

      val start = System.currentTimeMillis()
      val specs = classgraph().scan().use { scanResult ->
         scanResult
            .getSubclasses(Spec::class.java.name)
            .map { Class.forName(it.name).kotlin }
            .filterIsInstance<KClass<out Spec>>()
      }

      log {
         val duration = System.currentTimeMillis() - start
         "[Discovery] Completed classgraph scan for specs in ${duration}ms, found ${specs.size} specs"
      }

      return specs
   }

   private fun classgraph(): ClassGraph {

      val cg = ClassGraph()
         .enableClassInfo()
         .enableExternalClasses()
         .ignoreClassVisibility()

      if (configuration.disableTestNestedJarScanning) {
         log { "Nested jar scanning is disabled" }
         cg.disableNestedJarScanning()
         cg.disableModuleScanning()
      }

      // do not change this to use reject as it will break clients using older versions of classgraph
      return cg.blacklistPackages(
         "java.*",
         "javax.*",
         "sun.*",
         "com.sun.*",
         "kotlin.*",
         "kotlinx.*",
         "androidx.*",
         "org.jetbrains.kotlin.*",
         "org.junit.*"
      ).apply {
         if (syspropOrEnv(KotestEngineProperties.disableJarDiscovery) == "true") {
            disableJarScanning()
         }
      }
   }
}
