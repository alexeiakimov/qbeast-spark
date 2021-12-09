/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.utils

import io.qbeast.core.model._
import io.qbeast.spark.internal.expressions.QbeastMurmur3Hash
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{
  And,
  BinaryComparison,
  EqualTo,
  Expression,
  GreaterThanOrEqual,
  LessThan,
  Literal,
  SubqueryExpression
}
import org.apache.spark.sql.types._

/**
 * Object with utility methods to work with Spark expressions.
 */
object QbeastExpressionUtils {

  lazy val spark = SparkSession.active
  lazy val nameEquality = spark.sessionState.analyzer.resolver

  private def hasQbeastColumnReference(expr: Expression, indexedColumns: Seq[String]): Boolean = {
    expr.references.forall { r =>
      indexedColumns.exists(nameEquality(r.name, _))
    }
  }

  private def isQbeastWeightExpression(expression: Expression): Boolean = {
    expression match {
      case BinaryComparison(_: QbeastMurmur3Hash, _) => true
      case _ => false
    }
  }

  private def isQbeastExpression(expression: Expression, indexedColumns: Seq[String]): Boolean =
    isQbeastWeightExpression(expression) || hasQbeastColumnReference(expression, indexedColumns)

  /**
   * Extracts the data filters from the query that can be used by qbeast
   * @param dataFilters filters passed to the relation
   * @param revision the revision of the index
   * @return sequence of filters involving qbeast format
   */
  def extractDataFilters(
      dataFilters: Seq[Expression],
      revision: Revision): (Seq[Expression], Seq[Expression]) = {
    dataFilters.partition(expression =>
      isQbeastExpression(
        expression,
        revision.columnTransformers.map(_.columnName)) && !SubqueryExpression
        .hasSubquery(expression))
  }

  /**
   * Split conjuntive predicates from an Expression into different values for the sequence
   * @param condition the Expression to analyze
   * @return the sequence of all predicates
   */

  private def splitConjunctivePredicates(condition: Expression): Seq[Expression] = {
    condition match {
      case And(cond1, cond2) =>
        splitConjunctivePredicates(cond1) ++ splitConjunctivePredicates(cond2)
      case other => other :: Nil
    }
  }

  private def hasColumnReference(expr: Expression, columnName: String): Boolean = {
    expr.references.forall(r => nameEquality(r.name, columnName))
  }

  /**
   * Extracts the space of the query
   * @param dataFilters the filters passed by the spark engine
   * @param revision the characteristics of the index
   * @return
   */

  def extractQuerySpace(dataFilters: Seq[Expression], revision: Revision): QuerySpace = {

    // Split conjunctive predicates
    val filters = dataFilters.flatMap(filter => splitConjunctivePredicates(filter))

    val indexedColumns = revision.columnTransformers.map(_.columnName)
    val transformations = revision.transformations

    val fromTo =
      indexedColumns.zip(transformations).map { case (columnName, t) =>
        // Get the filters related to the column
        val columnFilters = filters.filter(hasColumnReference(_, columnName))

        // Get the coordinates of the column in the filters,
        // if not found, use the overall coordinates
        val from = columnFilters
          .collectFirst {
            case GreaterThanOrEqual(_, Literal(value, _)) => t.transform(value)
            case EqualTo(_, Literal(value, _)) => t.transform(value)
          }
          .getOrElse(0.0)

        val to = columnFilters
          .collectFirst { case LessThan(_, Literal(value, _)) => t.transform(value) }
          .getOrElse(1.0)

        (from, to)
      }

    val from = Point(fromTo.map(_._1).toVector)
    val to = Point(fromTo.map(_._2).toVector)

    QuerySpaceFromTo(from, to)
  }

  /**
   * Extracts the sampling weight range of the query
   * @param dataFilters the filters passed by the spark engine
   * @return the upper and lower weight bounds (default: Weight.MinValue, Weight.MaxValue)
   */
  def extractWeightRange(dataFilters: Seq[Expression]): WeightRange = {

    val weightFilters = dataFilters
      .flatMap(filter => splitConjunctivePredicates(filter))
      .filter(isQbeastWeightExpression)

    val min = weightFilters
      .collectFirst { case GreaterThanOrEqual(_, Literal(m, IntegerType)) =>
        m.asInstanceOf[Int]
      }
      .getOrElse(Int.MinValue)

    val max = weightFilters
      .collectFirst { case LessThan(_, Literal(m, IntegerType)) =>
        m.asInstanceOf[Int]
      }
      .getOrElse(Int.MaxValue)

    WeightRange(Weight(min), Weight(max))
  }

}
