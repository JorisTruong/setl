package com.jcdecaux.setl.internal

import org.apache.spark.sql.DataFrameReader

trait HasReader { Connector =>

  protected val reader: DataFrameReader

}
