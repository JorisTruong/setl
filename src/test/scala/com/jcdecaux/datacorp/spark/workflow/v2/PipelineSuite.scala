package com.jcdecaux.datacorp.spark.workflow.v2

import com.jcdecaux.datacorp.spark.SparkSessionBuilder
import com.jcdecaux.datacorp.spark.annotation.Delivery
import com.jcdecaux.datacorp.spark.internal.Deliverable
import com.jcdecaux.datacorp.spark.transformation.Factory
import org.apache.spark.sql.{Dataset, SparkSession}
import org.scalatest.FunSuite

import scala.reflect.runtime.{universe => ru}

case class Product1(x: String)

case class Product2(x: String, y: String)

case class Container[T](content: T)

case class Container2[T](content: T)

class ProductFactory extends Factory[Product1] {

  @Delivery
  var id: String = _
  var output: Product1 = _

  override def read(): ProductFactory.this.type = this
  override def process(): ProductFactory.this.type = {
    output = Product1(id)
    this
  }

  override def write(): ProductFactory.this.type = this

  override def get(): Product1 = output
}


class Product2Factory extends Factory[Product2] {

  var output: Product2 = _

  override def read(): this.type = this
  override def process(): this.type = {
    output = Product2("a", "b")
    this
  }

  override def write(): this.type = this
  override def get(): Product2 = output
}


class ContainerFactory extends Factory[Container[Product1]] {

  @Delivery
  var product1: Product1 = _
  var output: Container[Product1] = _

  override def read(): ContainerFactory.this.type = this
  override def process(): ContainerFactory.this.type = {
    output = Container(product1)
    this
  }
  override def write(): ContainerFactory.this.type = this

  override def get(): Container[Product1] = output
}


class Container2Factory extends Factory[Container2[Product2]] {

  var p2: Product2 = _
  var output: Container2[Product2] = _

  @Delivery
  def setProduct(v: Product2): this.type = {
    this.p2 = v
    this
  }

  override def read(): this.type = this
  override def process(): this.type = {
    output = Container2(p2)
    this
  }
  override def write(): this.type = this
  override def get(): Container2[Product2] = output
}

class DatasetFactory(spark: SparkSession) extends Factory[Dataset[Product1]] {

  import spark.implicits._

  @Delivery
  var p1: Product1 = _

  var output: Dataset[Product1] = _

  override def read(): DatasetFactory.this.type = this

  override def process(): DatasetFactory.this.type = {
    output = Seq(p1, Product1("pd1")).toDS
    this
  }

  override def write(): DatasetFactory.this.type = this

  override def get(): Dataset[Product1] = output
}

class DatasetFactory2(spark: SparkSession) extends Factory[Dataset[Product2]] {

  @Delivery
  var ds: Dataset[Product1] = _

  @Delivery
  var ds2: Dataset[Product2] = _

  var output: Dataset[Product2] = _

  override def read(): DatasetFactory2.this.type = this

  override def process(): DatasetFactory2.this.type = {
    import spark.implicits._
    output = ds.join(ds2, Seq("x")).as[Product2]
    this
  }

  override def write(): DatasetFactory2.this.type = {
    this
  }

  override def get(): Dataset[Product2] = {
    output
  }
}

class PipelineSuite extends FunSuite {

  test("Test pipeline") {

    val f1 = new ProductFactory
    val f2 = new Product2Factory
    val f3 = new ContainerFactory
    val f4 = new Container2Factory

    val pipeline = new Pipeline

    val stage1 = new Stage().addFactory(f1).addFactory(f2)
    val stage2 = new Stage().addFactory(f3)
    val stage3 = new Stage().addFactory(f4)

    pipeline
      .setInput(new Deliverable[String]("id_of_product1"))
      .addStage(stage1)
      .addStage(stage2)
      .addStage(stage3)
      .run()

    pipeline.dispatchManagers.deliveries.foreach(x => println(x.get))
    assert(pipeline.dispatchManagers.deliveries.length === 5)
    println(pipeline.getOutput(ru.typeOf[Container2[Product2]]).head.get)
    assert(pipeline.getOutput(ru.typeOf[Container2[Product2]]).head.get == Container2(Product2("a", "b")))

  }

  test("Test Dataset pipeline") {
    val spark = new SparkSessionBuilder("dev").setEnv("dev").getOrCreate()
    import spark.implicits._

    val ds2: Dataset[Product2] = Seq(
      Product2("id_of_product1", "c2"),
      Product2("pd1", "c2")
    ).toDS

    val f1 = new ProductFactory
    val f2 = new DatasetFactory(spark)
    val f3 = new DatasetFactory2(spark)
    val pipeline = new Pipeline

    val stage0 = new Stage().addFactory(f1)
    val stage1 = new Stage().addFactory(f2)
    val stage2 = new Stage().addFactory(f3)

    pipeline
      .setInput(new Deliverable[String]("wrong_id_of_product1"))
      .setInput[String]("id_of_product1", f1)
      .setInput(ds2)
      .addStage(stage0)
      .addStage(stage1)
      .addStage(stage2)
      .run()

    f3.get().show()
    assert(f3.get().count() === 2)
    assert(f3.get().filter($"x" === "pd1").count() === 1)
    assert(f3.get().filter($"x" === "id_of_product1").count() === 1)
    assert(f3.get().filter($"x" === "id_of_product1").collect().head === Product2("id_of_product1", "c2"))

  }
}
