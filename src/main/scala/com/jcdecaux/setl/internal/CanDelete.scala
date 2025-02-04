package com.jcdecaux.setl.internal

import com.jcdecaux.setl.storage.connector.Connector

/**
 * Connectors that inherit CanDelete should be able to delete records for a given query string
 */
trait CanDelete {
  self: Connector =>

  /**
   * Delete rows according to the query
   *
   * @param query a query string
   */
  def delete(query: String): Unit

}
