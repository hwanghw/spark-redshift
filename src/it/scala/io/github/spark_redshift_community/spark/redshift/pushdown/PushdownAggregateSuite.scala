package io.github.spark_redshift_community.spark.redshift.pushdown


import org.apache.spark.sql.Row

abstract class PushdownAggregateSuite extends IntegrationPushdownSuiteBase {

  test("Count(1) pushdown") {
    checkAnswer(
      sqlContext.sql("""SELECT COUNT(1) FROM test_table"""),
      Seq(Row(5)))

    checkSqlStatement(
      s"""SELECT ( COUNT ( 1 ) ) AS "SUBQUERY_1_COL_0"
         |FROM ( SELECT * FROM $test_table AS "RS_CONNECTOR_QUERY_ALIAS" )
         |AS "SUBQUERY_0" LIMIT 1
         |""".stripMargin
    )
  }

  test("Count distinct pushdown") {
    checkAnswer(
      sqlContext.sql("""SELECT COUNT(DISTINCT testdouble) FROM test_table"""),
      Seq(Row(3)))

    checkSqlStatement(
      s"""SELECT ( COUNT ( DISTINCT "SUBQUERY_1"."SUBQUERY_1_COL_0" ) ) AS "SUBQUERY_2_COL_0"
         |FROM ( SELECT ( "SUBQUERY_0"."TESTDOUBLE" ) AS "SUBQUERY_1_COL_0" FROM
         |( SELECT * FROM $test_table AS "RS_CONNECTOR_QUERY_ALIAS" ) AS "SUBQUERY_0" )
         |AS "SUBQUERY_1" LIMIT 1
         |""".stripMargin
    )
  }

  test("Count(1) Group By pushdown") {
    checkAnswer(
      sqlContext.sql("""SELECT COUNT(1), testbyte FROM test_table GROUP BY testbyte"""),
      Seq(Row(1, null), Row(2, 0), Row(2, 1)))

    checkSqlStatement(
      s"""SELECT ( COUNT ( 1 ) ) AS "SUBQUERY_2_COL_0" ,
        |( "SUBQUERY_1"."SUBQUERY_1_COL_0" ) AS "SUBQUERY_2_COL_1"
        |FROM ( SELECT ( "SUBQUERY_0"."TESTBYTE" ) AS "SUBQUERY_1_COL_0"
        |FROM ( SELECT * FROM $test_table AS "RS_CONNECTOR_QUERY_ALIAS" ) AS "SUBQUERY_0" )
        |AS "SUBQUERY_1" GROUP BY "SUBQUERY_1"."SUBQUERY_1_COL_0"
        |""".stripMargin
    )
  }

  test("Count(testbyte) Group By pushdown") {
    checkAnswer(
      sqlContext.sql("""SELECT COUNT(testbyte), testbyte FROM test_table GROUP BY testbyte"""),
      Seq(Row(0, null), Row(2, 0), Row(2, 1)))

    checkSqlStatement(
      s"""SELECT ( COUNT ( "SUBQUERY_1"."SUBQUERY_1_COL_0" ) ) AS "SUBQUERY_2_COL_0" ,
         |( "SUBQUERY_1"."SUBQUERY_1_COL_0" ) AS "SUBQUERY_2_COL_1"
         |FROM ( SELECT ( "SUBQUERY_0"."TESTBYTE" ) AS "SUBQUERY_1_COL_0"
         |FROM ( SELECT * FROM $test_table AS "RS_CONNECTOR_QUERY_ALIAS" ) AS "SUBQUERY_0" )
         |AS "SUBQUERY_1" GROUP BY "SUBQUERY_1"."SUBQUERY_1_COL_0"
         |""".stripMargin
    )
  }

  test("Max() Group By pushdown") {
    checkAnswer(
      sqlContext.sql("""SELECT MAX(testfloat), testbyte FROM test_table GROUP BY testbyte"""),
      Seq(Row(null, null), Row(100000f, 0), Row(1f, 1)))

    checkSqlStatement(
      s"""SELECT ( MAX ( "SUBQUERY_1"."SUBQUERY_1_COL_1" ) ) AS "SUBQUERY_2_COL_0" ,
         |( "SUBQUERY_1"."SUBQUERY_1_COL_0" ) AS "SUBQUERY_2_COL_1"
         |FROM ( SELECT ( "SUBQUERY_0"."TESTBYTE" ) AS "SUBQUERY_1_COL_0" ,
         |( "SUBQUERY_0"."TESTFLOAT" ) AS "SUBQUERY_1_COL_1"
         |FROM ( SELECT * FROM $test_table AS "RS_CONNECTOR_QUERY_ALIAS" ) AS "SUBQUERY_0" )
         |AS "SUBQUERY_1" GROUP BY "SUBQUERY_1"."SUBQUERY_1_COL_0"
         |""".stripMargin
    )
  }

  test("Min() Group By pushdown") {
    checkAnswer(
      sqlContext.sql("""SELECT MIN(testfloat), testbyte FROM test_table GROUP BY testbyte"""),
      Seq(Row(null, null), Row(-1f, 0), Row(0f, 1)))

    checkSqlStatement(
      s"""SELECT ( MIN ( "SUBQUERY_1"."SUBQUERY_1_COL_1" ) ) AS "SUBQUERY_2_COL_0" ,
         |( "SUBQUERY_1"."SUBQUERY_1_COL_0" ) AS "SUBQUERY_2_COL_1"
         |FROM ( SELECT ( "SUBQUERY_0"."TESTBYTE" ) AS "SUBQUERY_1_COL_0" ,
         |( "SUBQUERY_0"."TESTFLOAT" ) AS "SUBQUERY_1_COL_1"
         |FROM ( SELECT * FROM $test_table AS "RS_CONNECTOR_QUERY_ALIAS" ) AS "SUBQUERY_0" )
         |AS "SUBQUERY_1" GROUP BY "SUBQUERY_1"."SUBQUERY_1_COL_0"
         |""".stripMargin
    )
  }


  test("Avg() Group By pushdown") {
    checkAnswer(
      sqlContext.sql("""SELECT AVG(testint), testbyte FROM test_table GROUP BY testbyte"""),
      Seq(Row(null, null), Row(4141214f, 0), Row(42f, 1)))

    checkSqlStatement(
      s"""SELECT ( AVG ( "SUBQUERY_1"."SUBQUERY_1_COL_1" ) ) AS "SUBQUERY_2_COL_0" ,
         |( "SUBQUERY_1"."SUBQUERY_1_COL_0" ) AS "SUBQUERY_2_COL_1"
         |FROM ( SELECT ( "SUBQUERY_0"."TESTBYTE" ) AS "SUBQUERY_1_COL_0" ,
         |( "SUBQUERY_0"."TESTINT" ) AS "SUBQUERY_1_COL_1"
         |FROM ( SELECT * FROM $test_table AS "RS_CONNECTOR_QUERY_ALIAS" ) AS "SUBQUERY_0" )
         |AS "SUBQUERY_1" GROUP BY "SUBQUERY_1"."SUBQUERY_1_COL_0"
         |""".stripMargin
    )
  }
  test("Sum() Group By pushdown") {
    checkAnswer(
      sqlContext.sql("""SELECT SUM(testint), testbyte FROM test_table GROUP BY testbyte"""),
      Seq(Row(null, null), Row(4141214, 0), Row(84, 1)))

    checkSqlStatement(
      s"""SELECT ( SUM ( "SUBQUERY_1"."SUBQUERY_1_COL_1" ) ) AS "SUBQUERY_2_COL_0" ,
         |( "SUBQUERY_1"."SUBQUERY_1_COL_0" ) AS "SUBQUERY_2_COL_1"
         |FROM ( SELECT ( "SUBQUERY_0"."TESTBYTE" ) AS "SUBQUERY_1_COL_0" ,
         |( "SUBQUERY_0"."TESTINT" ) AS "SUBQUERY_1_COL_1"
         |FROM ( SELECT * FROM $test_table AS "RS_CONNECTOR_QUERY_ALIAS" ) AS "SUBQUERY_0" )
         |AS "SUBQUERY_1" GROUP BY "SUBQUERY_1"."SUBQUERY_1_COL_0"
         |""".stripMargin
    )
  }

  test("test pushdown MakeDecimal function") {
    checkAnswer(
      sqlContext.sql(
        """SELECT SUM(cast(testint AS DECIMAL(5, 2))),
          |testbyte FROM test_table WHERE testbyte>0
          |GROUP BY testbyte""".stripMargin),
      Seq(Row(84f, 1)))


    checkSqlStatement(
      s"""SELECT ( CAST ( ( SUM ( (
         |CAST ( "SUBQUERY_2"."SUBQUERY_2_COL_1" AS DECIMAL(5, 2) ) * POW(10, 2 ) ) ) / POW(10, 2 ) )
         |AS DECIMAL( 15 , 2 ) ) ) AS "SUBQUERY_3_COL_0" ,
         |( "SUBQUERY_2"."SUBQUERY_2_COL_0" ) AS "SUBQUERY_3_COL_1"
         |FROM ( SELECT ( "SUBQUERY_1"."TESTBYTE" ) AS "SUBQUERY_2_COL_0" ,
         |( "SUBQUERY_1"."TESTINT" ) AS "SUBQUERY_2_COL_1" FROM ( SELECT * FROM
         |( SELECT * FROM $test_table AS "RS_CONNECTOR_QUERY_ALIAS" ) AS "SUBQUERY_0"
         |WHERE ( ( "SUBQUERY_0"."TESTBYTE" IS NOT NULL )
         |AND ( "SUBQUERY_0"."TESTBYTE" > 0 ) ) ) AS "SUBQUERY_1" )
         |AS "SUBQUERY_2" GROUP BY "SUBQUERY_2"."SUBQUERY_2_COL_0"
         |""".stripMargin
    )
  }
}

class DefaultPushdownAggregateSuite extends PushdownAggregateSuite {
  override protected val s3format: String = "DEFAULT"
}

class ParquetPushdownAggregateSuite extends PushdownAggregateSuite {
  override protected val s3format: String = "PARQUET"
}

