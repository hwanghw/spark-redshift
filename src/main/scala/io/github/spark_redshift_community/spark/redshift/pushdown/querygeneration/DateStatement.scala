package io.github.spark_redshift_community.spark.redshift.pushdown.querygeneration

import io.github.spark_redshift_community.spark.redshift.pushdown.{ConstantString, RedshiftSQLStatement}
import org.apache.spark.sql.catalyst.expressions.{AddMonths, Attribute, DateAdd, DateSub, Expression, TruncTimestamp}

/** Extractor for boolean expressions (return true or false). */
private[querygeneration] object DateStatement {
  val REDSHIFT_DATEADD = "DATEADD"

  def unapply(
    expAttr: (Expression, Seq[Attribute])
  ): Option[RedshiftSQLStatement] = {
    val expr = expAttr._1
    val fields = expAttr._2

    Option(expr match {
      case DateAdd(startDate, days) =>
        ConstantString(REDSHIFT_DATEADD) +
          blockStatement(
            ConstantString("day,") +
              convertStatement(days, fields) + "," +
              convertStatement(startDate, fields)
          )

      // it is pushdown by DATEADD with negative days
      case DateSub(startDate, days) =>
        ConstantString(REDSHIFT_DATEADD) +
          blockStatement(
            ConstantString("day, (0 - (") +
              convertStatement(days, fields) + ") )," +
              convertStatement(startDate, fields)
          )

      // AddMonths can't be pushdown to redshift because their functionality is different.
      // For example,
      //     SELECT ADD_MONTHS (CAST ( '2015-02-28' AS DATE ) , 1 )
      // On Redshift, the result is "2015-03-31"
      // On spark 3, the result is "2015-03-28"
      case AddMonths(_, _) => null

      case _: TruncTimestamp =>
        ConstantString(expr.prettyName.toUpperCase) +
          blockStatement(convertStatements(fields, expr.children: _*))

      case _ => null
    })
  }
}
