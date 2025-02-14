/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.linker.frontend.optimizer

import language.higherKinds

import scala.annotation.{switch, tailrec}

import scala.collection.mutable

import org.scalajs.ir._
import Definitions.isConstructorName
import Trees._
import Types._

import org.scalajs.logging._

import org.scalajs.linker._
import org.scalajs.linker.backend.emitter.LongImpl
import org.scalajs.linker.standard._
import org.scalajs.linker.CollectionsCompat.MutableMapCompatOps

/** Incremental optimizer.
 *
 *  An incremental optimizer optimizes a [[standard.LinkingUnit LinkingUnit]]
 *  in an incremental way.
 *
 *  It maintains state between runs to do a minimal amount of work on every
 *  run, based on detecting what parts of the program must be re-optimized,
 *  and keeping optimized results from previous runs for the rest.
 *
 *  @param semantics Required Scala.js Semantics
 *  @param esLevel ECMAScript level
 */
abstract class GenIncOptimizer private[optimizer] (config: CommonPhaseConfig) {

  import GenIncOptimizer._

  val symbolRequirements: SymbolRequirement = {
    val factory = SymbolRequirement.factory("optimizer")
    import factory._

    callMethods(LongImpl.RuntimeLongClass, LongImpl.AllIntrinsicMethods.toList) ++
    instantiateClass("jl_NullPointerException", "init___")
  }

  private[optimizer] val CollOps: AbsCollOps

  private var logger: Logger = _

  /** Are we in batch mode? I.e., are we running from scratch?
   *  Various parts of the algorithm can be skipped entirely when running in
   *  batch mode.
   */
  private var batchMode: Boolean = false

  private var objectClass: Class = _
  private val classes = CollOps.emptyMap[String, Class]

  private val staticLikes =
    CollOps.emptyParMap[String, Array[StaticLikeNamespace]]

  private[optimizer] def getInterface(encodedName: String): InterfaceType

  /** Schedule a method for processing in the PROCESS PASS */
  private[optimizer] def scheduleMethod(method: MethodImpl): Unit

  private[optimizer] def newMethodImpl(owner: MethodContainer,
      encodedName: String): MethodImpl

  private def withLogger[A](logger: Logger)(body: => A): A = {
    assert(this.logger == null)
    this.logger = logger
    try body
    finally this.logger = null
  }

  /** Update the incremental analyzer with a new run. */
  def update(unit: LinkingUnit, logger: Logger): LinkingUnit = {
    withLogger(logger) {
      batchMode = objectClass == null
      logger.debug(s"Optimizer: Batch mode: $batchMode")

      logger.time("Optimizer: Incremental part") {
        /* UPDATE PASS */
        updateAndTagEverything(unit.classDefs)
      }

      logger.time("Optimizer: Optimizer part") {
        /* PROCESS PASS */
        processAllTaggedMethods()
      }

      val newLinkedClasses = for (linkedClass <- unit.classDefs) yield {
        val encodedName = linkedClass.encodedName
        val staticLikeContainers = CollOps.forceGet(staticLikes, encodedName)

        val publicContainer = classes.get(encodedName).getOrElse {
          /* For interfaces, we need to look at default methods.
           * For other kinds of classes, the public namespace is necessarily
           * empty.
           */
          val container = staticLikeContainers(MemberNamespace.Public.ordinal)
          assert(
              linkedClass.kind == ClassKind.Interface || container.methods.isEmpty,
              linkedClass.encodedName -> linkedClass.kind)
          container
        }

        val newMethods = for (m <- linkedClass.methods) yield {
          val namespace = m.value.flags.namespace
          val container =
            if (namespace == MemberNamespace.Public) publicContainer
            else staticLikeContainers(namespace.ordinal)
          container.methods(m.value.encodedName).optimizedMethodDef
        }

        linkedClass.optimized(methods = newMethods)
      }

      new LinkingUnit(unit.coreSpec, newLinkedClasses, unit.moduleInitializers)
    }
  }

  /** Incremental part: update state and detect what needs to be re-optimized.
   *  UPDATE PASS ONLY. (This IS the update pass).
   */
  private def updateAndTagEverything(linkedClasses: List[LinkedClass]): Unit = {
    val neededStaticLikes = CollOps.emptyParMap[String, LinkedClass]
    val neededClasses = CollOps.emptyParMap[String, LinkedClass]
    for (linkedClass <- linkedClasses) {
      // Update the list of ancestors for all linked classes
      getInterface(linkedClass.encodedName).ancestors = linkedClass.ancestors

      CollOps.put(neededStaticLikes, linkedClass.encodedName, linkedClass)

      if (linkedClass.hasInstances &&
          (linkedClass.kind.isClass || linkedClass.kind == ClassKind.HijackedClass)) {
        CollOps.put(neededClasses, linkedClass.encodedName, linkedClass)
      }
    }

    /* Remove deleted static-like stuff, and update existing ones.
     * We don't even have to notify callers in case of additions or removals
     * because callers have got to be invalidated by themselves.
     * Only changed methods need to trigger notifications.
     *
     * Non-batch mode only.
     */
    assert(!batchMode || CollOps.isEmpty(staticLikes))
    if (!batchMode) {
      CollOps.retain(staticLikes) { (encodedName, staticLikeNamespaces) =>
        CollOps.remove(neededStaticLikes, encodedName).fold {
          /* Deleted static-like context. Mark all its methods as deleted, and
           * remove it from known static-like contexts.
           */
          for (staticLikeNamespace <- staticLikeNamespaces)
            staticLikeNamespace.methods.values.foreach(_.delete())
          false
        } { linkedClass =>
          /* Existing static-like context. Update it. */
          for (staticLikeNamespace <- staticLikeNamespaces) {
            val (_, changed, _) = staticLikeNamespace.updateWith(linkedClass)
            for (method <- changed) {
              staticLikeNamespace.myInterface.tagStaticCallersOf(
                  staticLikeNamespace.namespace.ordinal, method)
            }
          }
          true
        }
      }
    }

    /* Add new static-like stuff.
     * Easy, we don't have to notify anyone.
     */
    CollOps.valuesForeach(neededStaticLikes) { linkedClass =>
      val namespaces = Array.tabulate(MemberNamespace.Count) { ord =>
        new StaticLikeNamespace(linkedClass.encodedName,
            MemberNamespace.fromOrdinal(ord))
      }
      CollOps.put(staticLikes, linkedClass.encodedName, namespaces)
      namespaces.foreach(_.updateWith(linkedClass))
    }

    if (!batchMode) {
      /* Class removals:
       * * If a class is deleted or moved, delete its entire subtree (because
       *   all its descendants must also be deleted or moved).
       * * If an existing class was instantiated but is no more, notify callers
       *   of its methods.
       *
       * Non-batch mode only.
       */
      val objectClassStillExists =
        objectClass.walkClassesForDeletions(CollOps.get(neededClasses, _))
      assert(objectClassStillExists, "Uh oh, java.lang.Object was deleted!")

      /* Class changes:
       * * Delete removed methods, update existing ones, add new ones
       * * Update the list of ancestors
       * * Class newly instantiated
       *
       * Non-batch mode only.
       */
      objectClass.walkForChanges(
          CollOps.remove(neededClasses, _).get, Set.empty)
    }

    /* Class additions:
     * * Add new classes (including those that have moved from elsewhere).
     * In batch mode, we avoid doing notifications.
     */

    // Group children by (immediate) parent
    val newChildrenByParent = CollOps.emptyAccMap[String, LinkedClass]

    CollOps.valuesForeach(neededClasses) { linkedClass =>
      linkedClass.superClass.fold {
        assert(batchMode, "Trying to add java.lang.Object in incremental mode")
        objectClass = new Class(None, linkedClass.encodedName)
        classes += linkedClass.encodedName -> objectClass
        objectClass.setupAfterCreation(linkedClass)
      } { superClassName =>
        CollOps.acc(newChildrenByParent, superClassName.name, linkedClass)
      }
    }

    val getNewChildren =
      (name: String) => CollOps.getAcc(newChildrenByParent, name)

    // Walk the tree to add children
    if (batchMode) {
      objectClass.walkForAdditions(getNewChildren)
    } else {
      val existingParents =
        CollOps.parFlatMapKeys(newChildrenByParent)(classes.get)
      CollOps.foreach(existingParents) { parent =>
        parent.walkForAdditions(getNewChildren)
      }
    }

  }

  /** Optimizer part: process all methods that need reoptimizing.
   *  PROCESS PASS ONLY. (This IS the process pass).
   */
  private[optimizer] def processAllTaggedMethods(): Unit

  private[optimizer] def logProcessingMethods(count: Int): Unit =
    logger.debug(s"Optimizer: Optimizing $count methods.")

  /** Base class for [[GenIncOptimizer.Class]] and
   *  [[GenIncOptimizer.StaticLikeNamespace]].
   */
  private[optimizer] abstract class MethodContainer(val encodedName: String,
      val namespace: MemberNamespace) {

    def thisType: Type =
      if (namespace.isStatic) NoType
      else ClassType(encodedName)

    val myInterface = getInterface(encodedName)

    val methods = mutable.Map.empty[String, MethodImpl]

    def optimizedDefs: List[Versioned[MethodDef]] = {
      (for {
        method <- methods.values
        if !method.deleted
      } yield {
        method.optimizedMethodDef
      }).toList
    }

    /** UPDATE PASS ONLY. Global concurrency safe but not on same instance */
    def updateWith(linkedClass: LinkedClass):
        (Set[String], Set[String], Set[String]) = {

      val addedMethods = Set.newBuilder[String]
      val changedMethods = Set.newBuilder[String]
      val deletedMethods = Set.newBuilder[String]

      val applicableNamespaceOrdinal = this match {
        case _: StaticLikeNamespace
            if namespace == MemberNamespace.Public &&
                (linkedClass.kind.isClass || linkedClass.kind == ClassKind.HijackedClass) =>
          /* The public non-static namespace for a class is always empty,
           * because its would-be content must be handled by the `Class`
           * instead.
           */
          -1 // different from all `ordinal` values of legit namespaces
        case _ =>
          namespace.ordinal
      }
      val linkedMethodDefs = linkedClass.methods.withFilter {
        _.value.flags.namespace.ordinal == applicableNamespaceOrdinal
      }

      val newMethodNames = linkedMethodDefs.map(_.value.encodedName).toSet
      val methodSetChanged = methods.keySet != newMethodNames
      if (methodSetChanged) {
        // Remove deleted methods
        methods.filterInPlace { (methodName, method) =>
          if (newMethodNames.contains(methodName)) {
            true
          } else {
            deletedMethods += methodName
            method.delete()
            false
          }
        }
      }

      this match {
        case cls: Class =>
          cls.isModuleClass = linkedClass.kind == ClassKind.ModuleClass
          cls.fields = linkedClass.fields
        case _          =>
      }

      for (linkedMethodDef <- linkedMethodDefs) {
        val methodName = linkedMethodDef.value.encodedName

        methods.get(methodName).fold {
          addedMethods += methodName
          val method = newMethodImpl(this, methodName)
          method.updateWith(linkedMethodDef)
          methods(methodName) = method
          method
        } { method =>
          if (method.updateWith(linkedMethodDef))
            changedMethods += methodName
          method
        }
      }

      (addedMethods.result(), changedMethods.result(), deletedMethods.result())
    }

    def lookupMethod(methodName: String): Option[MethodImpl]

    override def toString(): String =
      namespace.prefixString + encodedName
  }

  /** Class in the class hierarchy (not an interface).
   *  A class may be a module class.
   *  A class knows its superclass and the interfaces it implements. It also
   *  maintains a list of its direct subclasses, so that the instances of
   *  [[Class]] form a tree of the class hierarchy.
   */
  private[optimizer] class Class(val superClass: Option[Class],
      _encodedName: String)
      extends MethodContainer(_encodedName, MemberNamespace.Public) {

    if (encodedName == Definitions.ObjectClass) {
      assert(superClass.isEmpty)
      assert(objectClass == null)
    } else {
      assert(superClass.isDefined)
    }

    /** Parent chain from this to Object. */
    val parentChain: List[Class] =
      this :: superClass.fold[List[Class]](Nil)(_.parentChain)

    /** Reverse parent chain from Object to this. */
    val reverseParentChain: List[Class] =
      parentChain.reverse

    var interfaces: Set[InterfaceType] = Set.empty
    var subclasses: CollOps.ParIterable[Class] = CollOps.emptyParIterable
    var isInstantiated: Boolean = false

    var isModuleClass: Boolean = false
    var hasElidableModuleAccessor: Boolean = false

    var fields: List[FieldDef] = Nil
    var isInlineable: Boolean = false
    var tryNewInlineable: Option[RecordValue] = None

    override def toString(): String =
      encodedName

    /** Walk the class hierarchy tree for deletions.
     *  This includes "deleting" classes that were previously instantiated but
     *  are no more.
     *  UPDATE PASS ONLY. Not concurrency safe on same instance.
     */
    def walkClassesForDeletions(
        getLinkedClassIfNeeded: String => Option[LinkedClass]): Boolean = {
      def sameSuperClass(linkedClass: LinkedClass): Boolean =
        superClass.map(_.encodedName) == linkedClass.superClass.map(_.name)

      getLinkedClassIfNeeded(encodedName) match {
        case Some(linkedClass) if sameSuperClass(linkedClass) =>
          // Class still exists. Recurse.
          subclasses = CollOps.filter(subclasses)(
              _.walkClassesForDeletions(getLinkedClassIfNeeded))
          if (isInstantiated && !linkedClass.hasInstances)
            notInstantiatedAnymore()
          true
        case _ =>
          // Class does not exist or has been moved. Delete the entire subtree.
          deleteSubtree()
          false
      }
    }

    /** Delete this class and all its subclasses. UPDATE PASS ONLY. */
    def deleteSubtree(): Unit = {
      delete()
      CollOps.foreach(subclasses)(_.deleteSubtree())
    }

    /** UPDATE PASS ONLY. */
    private def delete(): Unit = {
      if (isInstantiated)
        notInstantiatedAnymore()
      for (method <- methods.values)
        method.delete()
      classes -= encodedName
      /* Note: no need to tag methods that call *statically* one of the methods
       * of the deleted classes, since they've got to be invalidated by
       * themselves.
       */
    }

    /** UPDATE PASS ONLY. */
    def notInstantiatedAnymore(): Unit = {
      assert(isInstantiated)
      isInstantiated = false
      for (intf <- interfaces) {
        intf.removeInstantiatedSubclass(this)
        for (methodName <- allMethods().keys)
          intf.tagDynamicCallersOf(methodName)
      }
    }

    /** UPDATE PASS ONLY. */
    def walkForChanges(getLinkedClass: String => LinkedClass,
        parentMethodAttributeChanges: Set[String]): Unit = {

      val linkedClass = getLinkedClass(encodedName)

      val (addedMethods, changedMethods, deletedMethods) =
        updateWith(linkedClass)

      val oldInterfaces = interfaces
      val newInterfaces = linkedClass.ancestors.map(getInterface).toSet
      interfaces = newInterfaces

      val methodAttributeChanges =
        (parentMethodAttributeChanges -- methods.keys ++
            addedMethods ++ changedMethods ++ deletedMethods)

      // Tag callers with dynamic calls
      val wasInstantiated = isInstantiated
      isInstantiated = linkedClass.hasInstances
      assert(!(wasInstantiated && !isInstantiated),
          "(wasInstantiated && !isInstantiated) should have been handled "+
          "during deletion phase")

      if (isInstantiated) {
        if (wasInstantiated) {
          val existingInterfaces = oldInterfaces.intersect(newInterfaces)
          for {
            intf <- existingInterfaces
            methodName <- methodAttributeChanges
          } {
            intf.tagDynamicCallersOf(methodName)
          }
          if (newInterfaces.size != oldInterfaces.size ||
              newInterfaces.size != existingInterfaces.size) {
            val allMethodNames = allMethods().keys
            for {
              intf <- oldInterfaces ++ newInterfaces -- existingInterfaces
              methodName <- allMethodNames
            } {
              intf.tagDynamicCallersOf(methodName)
            }
          }
        } else {
          val allMethodNames = allMethods().keys
          for (intf <- interfaces) {
            intf.addInstantiatedSubclass(this)
            for (methodName <- allMethodNames)
              intf.tagDynamicCallersOf(methodName)
          }
        }
      }

      // Tag callers with static calls
      for (methodName <- methodAttributeChanges)
        myInterface.tagStaticCallersOf(namespace.ordinal, methodName)

      // Module class specifics
      updateHasElidableModuleAccessor()

      // Inlineable class
      if (updateIsInlineable(linkedClass)) {
        for (method <- methods.values; if isConstructorName(method.encodedName))
          myInterface.tagStaticCallersOf(namespace.ordinal, method.encodedName)
      }

      // Recurse in subclasses
      CollOps.foreach(subclasses) { cls =>
        cls.walkForChanges(getLinkedClass, methodAttributeChanges)
      }
    }

    /** UPDATE PASS ONLY. */
    def walkForAdditions(
        getNewChildren: String => CollOps.ParIterable[LinkedClass]): Unit = {

      val subclassAcc = CollOps.prepAdd(subclasses)

      CollOps.foreach(getNewChildren(encodedName)) { linkedClass =>
        val cls = new Class(Some(this), linkedClass.encodedName)
        CollOps.add(subclassAcc, cls)
        classes += linkedClass.encodedName -> cls
        cls.setupAfterCreation(linkedClass)
        cls.walkForAdditions(getNewChildren)
      }

      subclasses = CollOps.finishAdd(subclassAcc)
    }

    /** UPDATE PASS ONLY. */
    def updateHasElidableModuleAccessor(): Unit = {
      def lookupModuleConstructor: Option[MethodImpl] = {
        CollOps
          .forceGet(staticLikes, encodedName)(MemberNamespace.Constructor.ordinal)
          .methods
          .get("init___")
      }

      hasElidableModuleAccessor =
        isAdHocElidableModuleAccessor(encodedName) ||
        (isModuleClass && lookupModuleConstructor.exists(isElidableModuleConstructor))
    }

    /** UPDATE PASS ONLY. */
    def updateIsInlineable(linkedClass: LinkedClass): Boolean = {
      val oldTryNewInlineable = tryNewInlineable
      isInlineable = linkedClass.optimizerHints.inline

      if (!isInlineable) {
        tryNewInlineable = None
      } else {
        val allFields = reverseParentChain.flatMap(_.fields)
        val (fieldValues, fieldTypes) = (for {
          f @ FieldDef(flags, Ident(name, originalName), tpe) <- allFields
          if !flags.namespace.isStatic
        } yield {
          (zeroOf(tpe)(f.pos),
              RecordType.Field(name, originalName, tpe, flags.isMutable))
        }).unzip
        tryNewInlineable = Some(
            RecordValue(RecordType(fieldTypes), fieldValues)(Position.NoPosition))
      }
      tryNewInlineable != oldTryNewInlineable
    }

    /** UPDATE PASS ONLY. */
    def setupAfterCreation(linkedClass: LinkedClass): Unit = {

      updateWith(linkedClass)
      interfaces = linkedClass.ancestors.map(getInterface).toSet

      isInstantiated = linkedClass.hasInstances

      if (batchMode) {
        if (isInstantiated) {
          /* Only add the class to all its ancestor interfaces */
          for (intf <- interfaces)
            intf.addInstantiatedSubclass(this)
        }
      } else {
        val allMethodNames = allMethods().keys

        if (isInstantiated) {
          /* Add the class to all its ancestor interfaces + notify all callers
           * of any of the methods.
           * TODO: be more selective on methods that are notified: it is not
           * necessary to modify callers of methods defined in a parent class
           * that already existed in the previous run.
           */
          for (intf <- interfaces) {
            intf.addInstantiatedSubclass(this)
            for (methodName <- allMethodNames)
              intf.tagDynamicCallersOf(methodName)
          }
        }

        /* Tag static callers because the class could have been *moved*,
         * not just added.
         */
        for (methodName <- allMethodNames)
          myInterface.tagStaticCallersOf(namespace.ordinal, methodName)
      }

      updateHasElidableModuleAccessor()
      updateIsInlineable(linkedClass)
    }

    /** UPDATE PASS ONLY. */
    private def isElidableModuleConstructor(impl: MethodImpl): Boolean = {
      def isTriviallySideEffectFree(tree: Tree): Boolean = tree match {
        case _:VarRef | _:Literal | _:This | _:Skip => true
        case _                                      => false
      }
      def isElidableStat(tree: Tree): Boolean = tree match {
        case Block(stats)                   => stats.forall(isElidableStat)
        case Assign(Select(This(), _), rhs) => isTriviallySideEffectFree(rhs)

        // Mixin constructor, 2.11
        case ApplyStatic(flags, ClassRef(cls), methodName, List(This()))
            if !flags.isPrivate =>
          val container =
            CollOps.forceGet(staticLikes, cls)(MemberNamespace.PublicStatic.ordinal)
          container.methods(methodName.name).originalDef.body.exists {
            case Skip() => true
            case _      => false
          }

        // Mixin constructor, 2.12+
        case ApplyStatically(flags, This(), ClassRef(cls), methodName, Nil)
            if !flags.isPrivate && !classes.contains(cls) =>
          // Since cls is not in classes, it must be a default method call.
          val container =
            CollOps.forceGet(staticLikes, cls)(MemberNamespace.Public.ordinal)
          container.methods.get(methodName.name) exists { methodDef =>
            methodDef.originalDef.body exists {
              case Skip() => true
              case _      => false
            }
          }

        // Delegation to another constructor (super or in the same class)
        case ApplyStatically(flags, This(), ClassRef(cls), methodName, args)
            if flags.isConstructor =>
          val namespace = MemberNamespace.Constructor.ordinal
          args.forall(isTriviallySideEffectFree) && {
            CollOps
              .forceGet(staticLikes, cls)(namespace)
              .methods
              .get(methodName.name)
              .exists(isElidableModuleConstructor)
          }

        case StoreModule(_, _) => true
        case _                 => isTriviallySideEffectFree(tree)
      }
      impl.originalDef.body.fold {
        throw new AssertionError("Module constructor cannot be abstract")
      } { body =>
        isElidableStat(body)
      }
    }

    /** All the methods of this class, including inherited ones.
     *  It has () so we remember this is an expensive operation.
     *  UPDATE PASS ONLY.
     */
    def allMethods(): scala.collection.Map[String, MethodImpl] = {
      val result = mutable.Map.empty[String, MethodImpl]
      for (parent <- reverseParentChain)
        result ++= parent.methods
      result
    }

    /** BOTH PASSES. */
    @tailrec
    final def lookupMethod(methodName: String): Option[MethodImpl] = {
      methods.get(methodName) match {
        case Some(impl) => Some(impl)
        case none =>
          superClass match {
            case Some(p) => p.lookupMethod(methodName)
            case none    => None
          }
      }
    }
  }

  /** Namespace for static members of a class. */
  private[optimizer] class StaticLikeNamespace(encodedName: String,
      namespace: MemberNamespace)
      extends MethodContainer(encodedName, namespace) {

    /** BOTH PASSES. */
    final def lookupMethod(methodName: String): Option[MethodImpl] =
      methods.get(methodName)
  }

  /** Thing from which a [[MethodImpl]] can unregister itself from. */
  private[optimizer] trait Unregisterable {
    /** UPDATE PASS ONLY. */
    def unregisterDependee(dependee: MethodImpl): Unit
  }

  /** Type of a class or interface.
   *  Types are created on demand when a method is called on a given
   *  [[org.scalajs.ir.Types.ClassType ClassType]].
   *
   *  Fully concurrency safe unless otherwise noted.
   */
  private[optimizer] abstract class InterfaceType(
      val encodedName: String) extends Unregisterable {

    override def toString(): String =
      s"intf $encodedName"

    /** PROCESS PASS ONLY. Concurrency safe except with
     *  [[addInstantiatedSubclass]] and [[removeInstantiatedSubclass]]
     */
    def instantiatedSubclasses: Iterable[Class]

    /** UPDATE PASS ONLY. Concurrency safe except with
     *  [[instantiatedSubclasses]]
     */
    def addInstantiatedSubclass(x: Class): Unit

    /** UPDATE PASS ONLY. Concurrency safe except with
     *  [[instantiatedSubclasses]]
     */
    def removeInstantiatedSubclass(x: Class): Unit

    /** PROCESS PASS ONLY. Concurrency safe except with [[ancestors_=]] */
    def ancestors: List[String]

    /** UPDATE PASS ONLY. Not concurrency safe. */
    def ancestors_=(v: List[String]): Unit

    /** PROCESS PASS ONLY. Concurrency safe except with [[ancestors_=]]. */
    def registerAskAncestors(asker: MethodImpl): Unit

    /** Register a dynamic-caller of an instance method.
     *  PROCESS PASS ONLY.
     */
    def registerDynamicCaller(methodName: String, caller: MethodImpl): Unit

    /** Register a static-caller of an instance method.
     *  PROCESS PASS ONLY.
     */
    // Using the ordinal works around a bug of Scala 2.10
    def registerStaticCaller(namespaceOrdinal: Int, methodName: String,
        caller: MethodImpl): Unit

    /** Tag the dynamic-callers of an instance method.
     *  UPDATE PASS ONLY.
     */
    def tagDynamicCallersOf(methodName: String): Unit

    /** Tag the static-callers of an instance method.
     *  UPDATE PASS ONLY.
     */
    // Using the ordinal works around a bug of Scala 2.10
    def tagStaticCallersOf(namespaceOrdinal: Int,
        methodName: String): Unit
  }

  /** A method implementation.
   *  It must be concrete, and belong either to a [[GenIncOptimizer.Class]] or a
   *  [[GenIncOptimizer.StaticsNamespace]].
   *
   *  A single instance is **not** concurrency safe (unless otherwise noted in
   *  a method comment). However, the global state modifications are
   *  concurrency safe.
   */
  private[optimizer] abstract class MethodImpl(val owner: MethodContainer,
      val encodedName: String)
      extends OptimizerCore.MethodImpl with OptimizerCore.AbstractMethodID
      with Unregisterable {

    private[this] var _deleted: Boolean = false

    var lastInVersion: Option[String] = None
    var lastOutVersion: Int = 0

    var optimizerHints: OptimizerHints = OptimizerHints.empty
    var originalDef: MethodDef = _
    var optimizedMethodDef: Versioned[MethodDef] = _

    def thisType: Type = owner.thisType
    def deleted: Boolean = _deleted

    override def toString(): String =
      s"$owner.$encodedName"

    /** PROCESS PASS ONLY. */
    def registerBodyAsker(asker: MethodImpl): Unit

    /** UPDATE PASS ONLY. */
    def tagBodyAskers(): Unit

    /** PROCESS PASS ONLY. */
    private def registerAskAncestors(intf: InterfaceType): Unit = {
      intf.registerAskAncestors(this)
      registeredTo(intf)
    }

    /** Register that this method is a dynamic-caller of an instance method.
     *  PROCESS PASS ONLY.
     */
    private def registerDynamicCall(intf: InterfaceType,
        methodName: String): Unit = {
      intf.registerDynamicCaller(methodName, this)
      registeredTo(intf)
    }

    /** Register that this method is a static-caller of an instance method.
     *  PROCESS PASS ONLY.
     */
    private def registerStaticCall(intf: InterfaceType,
        namespace: MemberNamespace, methodName: String): Unit = {
      intf.registerStaticCaller(namespace.ordinal, methodName, this)
      registeredTo(intf)
    }

    /** PROCESS PASS ONLY. */
    def registerAskBody(target: MethodImpl): Unit = {
      target.registerBodyAsker(this)
      registeredTo(target)
    }

    /** PROCESS PASS ONLY. */
    protected def registeredTo(intf: Unregisterable): Unit

    /** UPDATE PASS ONLY. */
    protected def unregisterFromEverywhere(): Unit

    /** Return true iff this is the first time this method is called since the
     *  last reset (via [[resetTag]]).
     *  UPDATE PASS ONLY.
     */
    protected def protectTag(): Boolean

    /** PROCESS PASS ONLY. */
    protected def resetTag(): Unit

    /** Returns true if the method's attributes changed.
     *  Attributes are whether it is inlineable, and whether it is a trait
     *  impl forwarder. Basically this is what is declared in
     *  `OptimizerCore.AbstractMethodID`.
     *  In the process, tags all the body askers if the body changes.
     *  UPDATE PASS ONLY. Not concurrency safe on same instance.
     */
    def updateWith(linkedMethod: Versioned[MethodDef]): Boolean = {
      assert(!_deleted, "updateWith() called on a deleted method")

      if (lastInVersion.isDefined && lastInVersion == linkedMethod.version) {
        false
      } else {
        lastInVersion = linkedMethod.version

        val methodDef = linkedMethod.value

        val changed = {
          originalDef == null ||
          (methodDef.hash zip originalDef.hash).forall {
            case (h1, h2) => !Hashers.hashesEqual(h1, h2)
          }
        }

        if (changed) {
          tagBodyAskers()

          val oldAttributes = (inlineable, isForwarder)

          optimizerHints = methodDef.optimizerHints
          originalDef = methodDef
          optimizedMethodDef = null
          updateInlineable()
          tag()

          val newAttributes = (inlineable, isForwarder)
          newAttributes != oldAttributes
        } else {
          false
        }
      }
    }

    /** UPDATE PASS ONLY. Not concurrency safe on same instance. */
    def delete(): Unit = {
      assert(!_deleted, "delete() called twice")
      _deleted = true
      if (protectTag())
        unregisterFromEverywhere()
    }

    /** Concurrency safe with itself and [[delete]] on the same instance
     *
     *  [[tag]] can be called concurrently with [[delete]] when methods in
     *  traits/classes are updated.
     *
     *  UPDATE PASS ONLY.
     */
    def tag(): Unit = if (protectTag()) {
      scheduleMethod(this)
      unregisterFromEverywhere()
    }

    /** PROCESS PASS ONLY. */
    def process(): Unit = if (!_deleted) {
      val optimizedDef = new Optimizer().optimize(thisType, originalDef)
      lastOutVersion += 1
      optimizedMethodDef =
        new Versioned(optimizedDef, Some(lastOutVersion.toString))
      resetTag()
    }

    /** All methods are PROCESS PASS ONLY */
    private class Optimizer extends OptimizerCore(config) {
      type MethodID = MethodImpl

      val myself: MethodImpl.this.type = MethodImpl.this

      protected def getMethodBody(method: MethodID): MethodDef = {
        MethodImpl.this.registerAskBody(method)
        method.originalDef
      }

      /** Look up the targets of a dynamic call to an instance method. */
      protected def dynamicCall(intfName: String,
          methodName: String): List[MethodID] = {
        val intf = getInterface(intfName)
        MethodImpl.this.registerDynamicCall(intf, methodName)
        intf.instantiatedSubclasses.flatMap(_.lookupMethod(methodName)).toList
      }

      /** Look up the target of a static call to an instance method. */
      protected def staticCall(className: String, namespace: MemberNamespace,
          methodName: String): Option[MethodID] = {

        def inStaticsLike =
          CollOps.forceGet(staticLikes, className)(namespace.ordinal)

        val container =
          if (namespace != MemberNamespace.Public) inStaticsLike
          else classes.get(className).getOrElse(inStaticsLike)

        MethodImpl.this.registerStaticCall(container.myInterface, namespace,
            methodName)
        container.lookupMethod(methodName)
      }

      protected def getAncestorsOf(intfName: String): List[String] = {
        val intf = getInterface(intfName)
        registerAskAncestors(intf)
        intf.ancestors
      }

      protected def hasElidableModuleAccessor(moduleClassName: String): Boolean =
        classes(moduleClassName).hasElidableModuleAccessor

      protected def tryNewInlineableClass(className: String): Option[RecordValue] =
        classes(className).tryNewInlineable
    }
  }

}

object GenIncOptimizer {

  private val isAdHocElidableModuleAccessor =
    Set("s_Predef$")

  private[optimizer] trait AbsCollOps {
    type Map[K, V] <: mutable.Map[K, V]
    type ParMap[K, V] <: AnyRef
    type AccMap[K, V] <: AnyRef
    type ParIterable[V] <: AnyRef
    type Addable[V] <: AnyRef

    def emptyAccMap[K, V]: AccMap[K, V]
    def emptyMap[K, V]: Map[K, V]
    def emptyParMap[K, V]: ParMap[K, V]
    def emptyParIterable[V]: ParIterable[V]

    // Operations on ParMap
    def isEmpty[K, V](map: ParMap[K, V]): Boolean
    def forceGet[K, V](map: ParMap[K, V], k: K): V
    def get[K, V](map: ParMap[K, V], k: K): Option[V]
    def put[K, V](map: ParMap[K, V], k: K, v: V): Unit
    def remove[K, V](map: ParMap[K, V], k: K): Option[V]
    def retain[K, V](map: ParMap[K, V])(p: (K, V) => Boolean): Unit
    def valuesForeach[K, V, U](map: ParMap[K, V])(f: V => U): Unit

    // Operations on AccMap
    def acc[K, V](map: AccMap[K, V], k: K, v: V): Unit
    def getAcc[K, V](map: AccMap[K, V], k: K): ParIterable[V]
    def parFlatMapKeys[A, B](map: AccMap[A, _])(
        f: A => Option[B]): ParIterable[B]

    // Operations on ParIterable
    def prepAdd[V](it: ParIterable[V]): Addable[V]
    def add[V](addable: Addable[V], v: V): Unit
    def finishAdd[V](addable: Addable[V]): ParIterable[V]
    def foreach[V, U](it: ParIterable[V])(f: V => U): Unit
    def filter[V](it: ParIterable[V])(f: V => Boolean): ParIterable[V]

  }

}
