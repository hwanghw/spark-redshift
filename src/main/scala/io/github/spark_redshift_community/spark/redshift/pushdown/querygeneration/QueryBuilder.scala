package io.github.spark_redshift_community.spark.redshift.pushdown.querygeneration

import io.github.spark_redshift_community.spark.redshift.pushdown.RedshiftSQLStatement
import io.github.spark_redshift_community.spark.redshift.{RedshiftFailMessage, RedshiftPushdownException, RedshiftPushdownUnsupportedException, RedshiftRelation}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, Expression, NamedExpression}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.types.{StructField, StructType}
import org.slf4j.LoggerFactory

import java.io.{PrintWriter, StringWriter}
import scala.reflect.ClassTag

/** This class takes a Spark LogicalPlan and attempts to generate
  * a query for Redshift using tryBuild(). Here we use lazy instantiation
  * to avoid building the query from the get-go without tryBuild().
  * TODO: Is laziness actually helpful?
  */
private[querygeneration] class QueryBuilder(plan: LogicalPlan) {
  import QueryBuilder.convertProjections

  private val LOG = LoggerFactory.getLogger(getClass)

  /**
    * Indicate whether any Redshift tables are involved in a query plan.
    */
  private var foundRedshiftRelation = false

  /** This iterator automatically increments every time it is used,
    * and is for aliasing subqueries.
    */
  private final val alias = Iterator.from(0).map(n => s"SUBQUERY_$n")

  /** RDD of [InternalRow] to be used by RedshiftPlan. */
  lazy val rdd: RDD[InternalRow] = toRDD[InternalRow]

  /** When referenced, attempts to translate the Spark plan to a SQL query that can be executed
    * by Redshift. It will be null if this fails.
    */
  lazy val tryBuild: Option[QueryBuilder] =
    if (treeRoot == null) None else Some(this)

  lazy val statement: RedshiftSQLStatement = {
    checkTree()
    treeRoot.getStatement()
  }

  /** Fetch the output attributes for Spark. */
  lazy val getOutput: Seq[Attribute] = {
    checkTree()
    treeRoot.output
  }

  /** Finds the SourceQuery in this given tree. */
  private lazy val source = {
    checkTree()
    treeRoot
      .find {
        case q: SourceQuery => q
      }
      .getOrElse(
        throw new RedshiftPushdownException(
          "Something went wrong: a query tree was generated with no " +
            "Redshift SourceQuery found."
        )
      )
  }

  /** Top level query generated by the QueryGenerator. Lazily computed. */
  private[redshift] lazy val treeRoot: RedshiftQuery = {
    try {
      log.debug("Begin query generation.")
      generateQueries(plan).get
    } catch {
      // It isn't necessary a problem even if there are
      // any redshift tables in the query plan - e.g. join with non-redshift table
      case e: RedshiftPushdownUnsupportedException => {
        if (foundRedshiftRelation) {
          LOG.warn(s"""Unsupported pushdown: ${e.unsupportedOperation} - ${e.details}""")

          if(LOG.isDebugEnabled()) {
            LOG.debug(plan.toString(), e)
          }
        }
        null
      }
      case e @ (_: MatchError | _: NoSuchElementException) => {
        if (foundRedshiftRelation) {
          val stringWriter = new StringWriter
          e.printStackTrace(new PrintWriter(stringWriter))
          LOG.error(plan.toString(), e)
        }
      }
      null
    }
  }

  private def toRDD[T: ClassTag]: RDD[T] = {

    val schema = StructType(
      getOutput
        .map(attr => StructField(attr.name, attr.dataType, attr.nullable))
    )

    source.relation.buildScanFromSQL[T](statement, Some(schema))
  }

  private def checkTree(): Unit = {
    if (treeRoot == null) {
      throw new RedshiftPushdownException(
        "QueryBuilder's tree accessed without generation."
      )
    }
  }

  /** Attempts to generate the query from the LogicalPlan. The queries are constructed from
    * the bottom up, but the validation of supported nodes for translation happens on the way down.
    *
    * @param plan The LogicalPlan to be processed.
    * @return An object of type Option[RedshiftQuery], which is None if the plan contains an
    *         unsupported node type.
    */
  private def generateQueries(plan: LogicalPlan): Option[RedshiftQuery] = {
    plan match {
      case l @ LogicalRelation(sfRelation: RedshiftRelation, _, _, _) =>
        foundRedshiftRelation = true
        Some(SourceQuery(sfRelation, l.output, alias.next))

      case UnaryOp(child) =>
        generateQueries(child) map { subQuery =>
          plan match {
            case Filter(condition, _) =>
              FilterQuery(Seq(condition), subQuery, alias.next)
            case Project(fields, _) =>
              ProjectQuery(fields, subQuery, alias.next)
            case Aggregate(groups, fields, _) =>
              AggregateQuery(fields, groups, subQuery, alias.next)
            case Limit(limitExpr, Sort(orderExpr, true, _)) =>
              SortLimitQuery(Some(limitExpr), orderExpr, subQuery, alias.next)
            case Limit(limitExpr, _) =>
              SortLimitQuery(Some(limitExpr), Seq.empty, subQuery, alias.next)

            case Sort(orderExpr, true, Limit(limitExpr, _)) =>
              SortLimitQuery(Some(limitExpr), orderExpr, subQuery, alias.next)
            case Sort(orderExpr, true, _) =>
              SortLimitQuery(None, orderExpr, subQuery, alias.next)

            case Window(windowExpressions, _, _, _) =>
              WindowQuery(
                windowExpressions,
                subQuery,
                alias.next,
                if (plan.output.isEmpty) None else Some(plan.output)
              )

            case _ => subQuery
          }
        }

      case BinaryOp(left, right) =>
        generateQueries(left).flatMap { l =>
          generateQueries(right) map { r =>
            plan match {
              case Join(_, _, joinType, condition, _) =>
                joinType match {
                  case Inner | LeftOuter | RightOuter | FullOuter =>
                    JoinQuery(l, r, condition, joinType, alias.next)
                  case LeftSemi =>
                    LeftSemiJoinQuery(l, r, condition, isAntiJoin = false, alias)
                  case LeftAnti =>
                    LeftSemiJoinQuery(l, r, condition, isAntiJoin = true, alias)
                  case Cross => throw new RedshiftPushdownUnsupportedException(
                    RedshiftFailMessage.FAIL_PUSHDOWN_UNSUPPORTED_JOIN,
                    joinType.sql,
                    joinType.getClass.getName,
                    true)
                  case _ => throw new MatchError
                }
            }
          }
        }

      // From Spark 3.1, Union has 3 parameters
      case Union(children, byName, allowMissingCol) =>
        // Don't support Union by Name. For details about what's UNION by Name,
        // refer to the comment and example at Spark function: DataSet.unionByName()
        if (byName || allowMissingCol) {
          // This exception is not a real issue. It will be caught in
          // QueryBuilder.treeRoot
          throw new RedshiftPushdownUnsupportedException(
            RedshiftFailMessage.FAIL_PUSHDOWN_UNSUPPORTED_UNION,
            s"${plan.nodeName} with byName=$byName allowMissingCol=$allowMissingCol",
            plan.getClass.getName,
            false
          )
        } else {
          Some(UnionQuery(children, alias.next))
        }

      case _ =>
        // This exception is not a real issue. It will be caught in
        // QueryBuilder.treeRoot.
        throw new RedshiftPushdownUnsupportedException(
          RedshiftFailMessage.FAIL_PUSHDOWN_GENERATE_QUERY,
          plan.nodeName,
          plan.getClass.getName,
          false
        )
    }
  }
}

/** QueryBuilder object that serves as an external interface for building queries.
  * Right now, this is merely a wrapper around the QueryBuilder class.
  */
private[redshift] object QueryBuilder {

  final def convertProjections(projections: Seq[Expression],
                               output: Seq[Attribute]): Seq[NamedExpression] = {
    projections zip output map { expr =>
      expr._1 match {
        case e: NamedExpression => e
        case _ => Alias(expr._1, expr._2.name)(expr._2.exprId)
      }
    }
  }

  def getRDDFromPlan(
    plan: LogicalPlan
  ): Option[(Seq[Attribute], RDD[InternalRow])] = {
    val qb = new QueryBuilder(plan)

    qb.tryBuild.map { executedBuilder =>
      (executedBuilder.getOutput, executedBuilder.rdd)
    }
  }
}
