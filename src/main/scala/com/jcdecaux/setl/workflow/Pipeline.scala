package com.jcdecaux.setl.workflow

import com.jcdecaux.setl.BenchmarkResult
import com.jcdecaux.setl.annotation.InterfaceStability
import com.jcdecaux.setl.internal._
import com.jcdecaux.setl.transformation.{Deliverable, Factory}
import com.jcdecaux.setl.util.{ExpectedDeliverable, ReflectUtils}

import scala.reflect.ClassTag
import scala.reflect.runtime.{universe => ru}

/**
 * Pipeline is a complete data transformation workflow.
 */
@InterfaceStability.Evolving
class Pipeline extends Logging
  with HasRegistry[Stage]
  with HasDescription
  with Identifiable
  with HasBenchmark
  with HasDiagram {

  private[this] var _stageCounter: Int = 0
  private[this] var _executionPlan: DAG = _
  private[this] val _deliverableDispatcher: DeliverableDispatcher = new DeliverableDispatcher
  private[this] val _pipelineInspector: PipelineInspector = new PipelineInspector(this)
  private[this] var _pipelineOptimizer: Option[PipelineOptimizer] = None
  private[this] var _benchmarkResult: Array[BenchmarkResult] = Array.empty

  /** Return all the stages of this pipeline */
  def stages: List[Stage] = this.getRegistry.values.toList

  /**
   * Get the inspector of this pipeline
   *
   * @return
   */
  def pipelineInspector: PipelineInspector = this._pipelineInspector

  /**
   * Get the deliverable dispatcher of this pipeline
   *
   * @return
   */
  def deliverableDispatcher: DeliverableDispatcher = this._deliverableDispatcher

  /**
   * Boolean indicating if the pipeline will be optimized
   *
   * @return
   */
  def optimization: Boolean = _pipelineOptimizer match {
    case Some(_) => true
    case _ => false
  }

  /**
   * Optimise execution with an optimizer
   *
   * @param optimizer an implementation of pipeline optimizer
   * @return this pipeline
   */
  def optimization(optimizer: PipelineOptimizer): this.type = {
    this._pipelineOptimizer = Option(optimizer)
    this
  }

  /**
   * Set to true to allow auto optimization of this pipeline. The default SimplePipelineOptimizer will be used.
   *
   * @param boolean true to allow optimization, otherwise false
   * @return this pipeline
   */
  def optimization(boolean: Boolean): this.type = {
    if (boolean) {
      optimization(new SimplePipelineOptimizer())
    } else {
      optimization(null)
    }
    this
  }

  /**
   * Put the Deliverable to the input pool of the pipeline
   *
   * @param deliverable Deliverable object
   * @return
   */
  def setInput(deliverable: Deliverable[_]): this.type = {
    _deliverableDispatcher.addDeliverable(deliverable)
    this
  }

  /**
   * Set input of the pipeline
   *
   * @param payload    object to be delivered
   * @param consumer   consumer of this payload
   * @param deliveryId id of this delivery
   * @tparam T type of payload
   * @return
   */
  def setInput[T: ru.TypeTag](payload: T,
                              consumer: Seq[Class[_ <: Factory[_]]],
                              deliveryId: String): this.type = {
    val deliverable = new Deliverable[T](payload).setDeliveryId(deliveryId).setConsumers(consumer)
    setInput(deliverable)
  }

  /**
   * Set input of the pipeline
   *
   * @param payload    object to be delivered
   * @param consumer   consumer of this payload
   * @param deliveryId id of this delivery
   * @param consumers  other consumers of the payload
   * @tparam T type of payload
   * @return
   */
  def setInput[T: ru.TypeTag](payload: T,
                              deliveryId: String,
                              consumer: Class[_ <: Factory[_]],
                              consumers: Class[_ <: Factory[_]]*): this.type = {
    setInput(payload, consumer +: consumers, deliveryId)
  }

  /**
   * Set input of the pipeline
   *
   * @param payload   object to be delivered
   * @param consumer  consumer of this payload
   * @param consumers other consumers of the payload
   * @tparam T type of payload
   * @return
   */
  def setInput[T: ru.TypeTag](payload: T,
                              consumer: Class[_ <: Factory[_]],
                              consumers: Class[_ <: Factory[_]]*): this.type = {
    setInput(payload, consumer +: consumers, Deliverable.DEFAULT_ID)
  }

  /**
   * Set input of the pipeline
   *
   * @param payload  object to be delivered
   * @param consumer consumer of this payload
   * @tparam T type of payload
   * @return
   */
  def setInput[T: ru.TypeTag](payload: T,
                              consumer: Class[_ <: Factory[_]]): this.type = {
    setInput[T](payload, List(consumer), Deliverable.DEFAULT_ID)
  }

  /**
   * Set input of the pipeline
   *
   * @param payload    object to be delivered
   * @param consumer   consumer of this payload
   * @param deliveryId id of this delivery
   * @tparam T type of payload
   * @return
   */
  def setInput[T: ru.TypeTag](payload: T,
                              consumer: Class[_ <: Factory[_]],
                              deliveryId: String): this.type = {
    setInput[T](payload, List(consumer), deliveryId)
  }

  /**
   * Set input of the pipeline
   *
   * @param payload object to be delivered
   * @tparam T type of payload
   * @return
   */
  def setInput[T: ru.TypeTag](payload: T): this.type = {
    setInput[T](payload, Seq.empty, Deliverable.DEFAULT_ID)
  }

  /**
   * Set input of the pipeline
   *
   * @param payload    object to be delivered
   * @param deliveryId id of this delivery
   * @tparam T type of payload
   * @return
   */
  def setInput[T: ru.TypeTag](payload: T,
                              deliveryId: String): this.type = {
    setInput[T](payload, Seq.empty, deliveryId)
  }

  /**
   * Add a new stage containing the given factory
   *
   * @param factory Factory to be executed
   * @return
   */
  def addStage(factory: Factory[_]): this.type = addStage(new Stage().addFactory(factory))

  /**
   * Add a new stage containing the given factory
   *
   * @param factory         Factory to be executed
   * @param constructorArgs Arguments of the primary constructor of the factory
   * @throws IllegalArgumentException this will be thrown if arguments don't match
   * @return
   */
  @throws[IllegalArgumentException]("Exception will be thrown if the length of constructor arguments are not correct")
  def addStage(factory: Class[_ <: Factory[_]], constructorArgs: Any*): this.type = {
    val _constructorArgs: Seq[Object] = constructorArgs.map(boxPrimitiveType)
    addStage(new Stage().addFactory(factory, _constructorArgs: _*))
  }

  /**
   * Add a new stage containing the given factory
   *
   * @param constructorArgs Arguments of the primary constructor of the factory
   * @param persistence     if set to true, then the `write` method of the factory will be called to persist the output.
   * @tparam T Class of the Factory
   * @throws IllegalArgumentException this will be thrown if arguments don't match
   * @return
   */
  @throws[IllegalArgumentException]("Exception will be thrown if the length of constructor arguments are not correct")
  def addStage[T <: Factory[_] : ClassTag](constructorArgs: Array[Any] = Array.empty,
                                           persistence: Boolean = true): this.type = {
    val _constructorArgs: Array[Object] = constructorArgs.map(boxPrimitiveType)
    addStage(new Stage().addFactory[T](_constructorArgs, persistence))
  }

  private[this] def boxPrimitiveType(t: Any): Object = {
    t match {
      case bool: Boolean => java.lang.Boolean.valueOf(bool)
      case byte: Byte => java.lang.Byte.valueOf(byte)
      case char: Char => java.lang.Character.valueOf(char)
      case short: Short => java.lang.Short.valueOf(short)
      case int: Int => java.lang.Integer.valueOf(int)
      case long: Long => java.lang.Long.valueOf(long)
      case float: Float => java.lang.Float.valueOf(float)
      case double: Double => java.lang.Double.valueOf(double)
      case o => o.asInstanceOf[AnyRef]
    }
  }

  /**
   * Add a new stage to the pipeline
   *
   * @param stage stage object
   * @return
   */
  def addStage(stage: Stage): this.type = {
    logDebug(s"Add stage ${_stageCounter}")

    resetEndStage()
    registerNewItem(stage.setStageId(_stageCounter))
    _stageCounter += 1

    this
  }

  def getStage(id: Int): Option[Stage] = this.stages.find(s => s.stageId == id)

  /** Mark the last stage as a NON-end stage */
  private[this] def resetEndStage(): Unit = this.lastRegisteredItem match {
    case Some(stage) => stage.end = false
    case _ =>
  }

  /** Describe the pipeline */
  override def describe(): this.type = {
    inspectPipeline()
    _executionPlan.describe()
    this
  }

  /** Find all the Deliverables that are set as Input in the Pipeline */
  private[this] def getPipelineDeliverables(deliverableDispatcher: DeliverableDispatcher): Set[ExpectedDeliverable] = {
    deliverableDispatcher
      .getRegistry
      .values
      .flatMap(v =>
        if (v.consumer.nonEmpty) {
          v.consumer.map(
            consumer =>
              ExpectedDeliverable(
                deliverableType = ReflectUtils.getPrettyName(v.runtimeType),
                deliveryId = v.deliveryId,
                producer = v.producer,
                consumer = consumer
              )
          )
        } else {
          Seq(ExpectedDeliverable(
            deliverableType = ReflectUtils.getPrettyName(v.runtimeType),
            deliveryId = v.deliveryId,
            producer = v.producer,
            consumer = null
          ))
        }
      )
      .toSet
  }

  /** Find middle factories output that can be used as a deliverable */
  private[this] def getStagesMiddleOutputs(stages: List[Stage]): Set[ExpectedDeliverable] = {
    stages
      .dropRight(1)
      .flatMap(s => s.factories)
      .flatMap(factory =>
        if (factory.consumers.nonEmpty) {
          factory.consumers.map(
            consumer =>
              ExpectedDeliverable(
                deliverableType = ReflectUtils.getPrettyName(factory.deliveryType()),
                deliveryId = factory.deliveryId,
                producer = factory.getClass,
                consumer = consumer
              )
          )
        } else {
          Seq(ExpectedDeliverable(
            deliverableType = ReflectUtils.getPrettyName(factory.deliveryType()),
            deliveryId = factory.deliveryId,
            producer = factory.getClass,
            consumer = null
          ))
        }
      )
      .toSet
  }

  /** Find all needed deliverables */
  private[this] def getNeededDeliverables(stages: List[Stage]): Set[ExpectedDeliverable] = {
    stages
      .flatMap(s => s.createNodes())
      .flatMap(node => node.input)
      .filter(input => !input.optional)
      .map(input => {
        if (input.autoLoad) {
          ExpectedDeliverable(
            deliverableType = ReflectUtils.getPrettyName(input.runtimeType).replaceAll("Dataset", "SparkRepository"),
            deliveryId = input.deliveryId,
            producer = input.producer,
            consumer = input.consumer
          )
        } else {
          ExpectedDeliverable(
            deliverableType = ReflectUtils.getPrettyName(input.runtimeType),
            deliveryId = input.deliveryId,
            producer = input.producer,
            consumer = input.consumer
          )
        }
      })
      .toSet
  }

  /** Compare available deliverables and needed deliverables */
  private[this] def compareDeliverables(availableDeliverables: Set[ExpectedDeliverable], neededDeliverables: Set[ExpectedDeliverable]): Unit = {
    neededDeliverables.foreach(
      needed => {
        var potentialDeliverables: Set[ExpectedDeliverable] = Set()
        // Producer is not set in a @Deliverable (neededDeliverable)
        if (ReflectUtils.getPrettyName(needed.producer) == classOf[External].getSimpleName) {
          potentialDeliverables = availableDeliverables.filter(available => {
            available.deliverableType == needed.deliverableType && available.deliveryId == needed.deliveryId
          })
        } else {
          // Producer is set in a @Deliverable (neededDeliverable)
          potentialDeliverables = availableDeliverables.filter(available => {
            available.deliverableType == needed.deliverableType && available.deliveryId == needed.deliveryId && available.producer == needed.producer
          })
        }
        // If there is no available deliverable with the correct consumer, we need to find one without consumer
        // If there is, no requirement is needed
        if (!potentialDeliverables.exists(p => needed.consumer == p.consumer)) {
          require(potentialDeliverables.exists(p => p.consumer == null), s"Deliverable of type ${needed.deliverableType} with deliveryId ${if (needed.deliveryId == "") "null" else needed.deliveryId} produced by ${ReflectUtils.getPrettyName(needed.producer)} is expected in ${ReflectUtils.getPrettyName(needed.consumer)}.")
        }
      }
    )
  }

  /** Execute the pipeline */
  def run(): this.type = {
    inspectPipeline()

    // Find all deliverables in Pipeline
    val pipelineDeliverables = getPipelineDeliverables(_deliverableDispatcher)
    // Find middle factories output that can be used as a deliverable
    val stagesOutput = getStagesMiddleOutputs(stages)
    // Find all needed deliverables
    val neededDeliverables = getNeededDeliverables(stages)

    compareDeliverables(pipelineDeliverables ++ stagesOutput, neededDeliverables)

    _benchmarkResult = stages
      .flatMap {
        stage =>
          // Describe current stage
          stage.describe()

          // dispatch only if deliverable pool is not empty
          if (_deliverableDispatcher.getRegistry.nonEmpty) {
            stage.factories.foreach(_deliverableDispatcher.dispatch)
          }

          // Disable benchmark if benchmark is false
          this.benchmark match {
            case Some(boo) =>
              if (!boo) {
                logDebug("Disable benchmark")
                stage.benchmark(false)
              }
            case _ =>
          }
          if (!this.benchmark.getOrElse(false)) {
            stage.benchmark(false)
          }

          // run the stage
          stage.run()
          stage.factories.foreach(_deliverableDispatcher.collectDeliverable)

          // retrieve benchmark result
          stage.getBenchmarkResult

      }.toArray

    // Show benchmark message
    this.getBenchmarkResult.foreach(println)

    this
  }

  /** Inspect the current pipeline if it has not been inspected */
  private[this] def inspectPipeline(): Unit = if (!_pipelineInspector.inspected) forceInspectPipeline()

  /** Force the inspection of the current pipeline */
  private[this] def forceInspectPipeline(): Unit = {
    _pipelineInspector.inspect()

    _executionPlan = _pipelineOptimizer match {
      case Some(optimiser) =>
        optimiser.setExecutionPlan(_pipelineInspector.getDataFlowGraph)
        val newStages = optimiser.optimize(stages)
        this.resetStages(newStages)
        optimiser.getOptimizedExecutionPlan

      case _ => _pipelineInspector.getDataFlowGraph
    }

    _deliverableDispatcher.setDataFlowGraph(_executionPlan)
  }

  /** Clear the current stages and replace it with the given stages */
  private[this] def resetStages(stages: Array[Stage]): Unit = {
    super.clearRegistry()
    super.registerNewItems(stages)
  }

  /**
   * Get the output of the last factory of the last stage
   *
   * @return an object. it has to be convert to the according type manually.
   */
  def getLastOutput: Any = {
    this.lastRegisteredItem.get.factories.last.get()
  }

  /**
   * Get the output of a specific Factory
   *
   * @param cls class of the Factory
   * @return
   */
  def getOutput[A](cls: Class[_ <: Factory[_]]): A = {
    val factory = stages.flatMap(s => s.factories).find(f => f.getClass == cls)

    factory match {
      case Some(x) => x.get().asInstanceOf[A]
      case _ => throw new NoSuchElementException(s"There isn't any class ${cls.getCanonicalName}")
    }
  }

  /**
   * Get the Deliverable of the given runtime Type
   *
   * @param t runtime type of the Deliverable's payload
   * @return
   */
  def getDeliverable(t: ru.Type): Array[Deliverable[_]] = _deliverableDispatcher.findDeliverableByType(t)

  /** Get the aggregated benchmark result. */
  override def getBenchmarkResult: Array[BenchmarkResult] = _benchmarkResult

  /** Generate the diagram */
  override def toDiagram: String = this._executionPlan.toDiagram

  /** Display the diagram */
  override def showDiagram(): Unit = this._executionPlan.showDiagram()

  /** Get the diagram ID */
  override def diagramId: String = throw new NotImplementedError("Pipeline doesn't have diagram id")
}
